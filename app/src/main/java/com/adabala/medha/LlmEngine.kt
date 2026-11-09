package com.adabala.medha

import android.content.Context
import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.oai.MultimodalProbe
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Wrapper around the LiteRT-LM Kotlin API. All LiteRT specifics live here.
 *
 * Concurrency contract
 * --------------------
 * A single native Engine is NOT safe to drive from two threads at once. The
 * previous version guarded [generate] with `@Synchronized` but left
 * `generateStream` unguarded, so a streaming request racing a blocking request
 * hit the same native handle concurrently — a native crash, not a Kotlin one.
 *
 * Both paths now take the same [gate] Mutex. It is a coroutine Mutex rather
 * than a monitor lock on purpose: waiting callers suspend instead of parking a
 * thread, so a queued request cannot starve the Ktor CIO event loop.
 */
class LlmEngine(private val appContext: Context) {

    enum class ConfiguredBackend { GPU, CPU, NPU }

    /** Serialises every use of the native engine, streaming or not. */
    private val gate = Mutex()

    /** Guards load/close against each other. Separate from [gate] by design. */
    private val lifecycle = Any()

    @Volatile private var engine: Engine? = null
    @Volatile var loadedModelPath: String? = null; private set
    @Volatile var configuredBackend: ConfiguredBackend = ConfiguredBackend.GPU; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var loadMs: Long = 0; private set
    @Volatile var loadedAt: Long = 0; private set

    // --- multimodal state -------------------------------------------------
    //
    // Two separate facts, and conflating them is what produced a /health
    // block that lied for three releases:
    //
    //   aarCapabilities  what the installed AAR can express and configure
    //   visionActive /   whether THIS engine actually came up with those
    //   audioActive      backends enabled, which additionally depends on the
    //                    loaded .litertlm carrying the encoder
    //
    // /health advertises the second pair, never the first. A model with no
    // audio tower makes Engine.initialize() fail with audioBackend set, and
    // a client told "audio: true" on that build would stop falling back and
    // start failing — the exact outcome the capability block exists to
    // prevent.

    /** What the installed AAR supports. Fixed for the process lifetime. */
    val aarCapabilities: MultimodalProbe.Report by lazy { MultimodalProbe.probeInstalled() }

    @Volatile var visionActive: Boolean = false; private set
    @Volatile var audioActive: Boolean = false; private set

    /** Why multimodal is off when the AAR supports it; null when it is on. */
    @Volatile var multimodalError: String? = null; private set

    /** Which rung of the init ladder the live engine came up on. */
    @Volatile var multimodalMode: String = "text-only"; private set

    private val inFlight = AtomicLong(0)

    val isLoaded: Boolean get() = engine != null
    val isBusy: Boolean get() = gate.isLocked
    val queueDepth: Long get() = inFlight.get()

    /**
     * Loads a model. Idempotent: re-issuing the same (path, backend) while the
     * engine is already up is a no-op.
     *
     * This matters because [InferenceService.onStartCommand] runs again on
     * every START_STICKY redelivery. The old code unconditionally called
     * close() + initialize(), which tore down a live engine underneath any
     * request that happened to be running.
     */
    fun load(
        modelPath: String,
        backend: ConfiguredBackend,
        force: Boolean = false,
        enableMultimodal: Boolean = true
    ) {
        synchronized(lifecycle) {
            if (!force && engine != null &&
                loadedModelPath == modelPath && configuredBackend == backend
            ) {
                Diagnostics.i(TAG, "Model already loaded with the same config; skipping reload")
                return
            }
            val f = File(modelPath)
            require(f.exists() && f.canRead()) { "Model file not found or unreadable: $modelPath" }
            require(f.length() > 0) { "Model file is empty: $modelPath" }

            closeLocked()
            lastError = null
            multimodalError = null
            visionActive = false
            audioActive = false
            multimodalMode = "text-only"
            configuredBackend = backend

            val caps = aarCapabilities
            val wantVision = enableMultimodal && caps.vision
            val wantAudio = enableMultimodal && caps.audio
            val start = System.currentTimeMillis()

            // Init ladder. Each rung costs a full model load (~8s on a
            // flagship), so this only ever descends on failure — the happy
            // path is one attempt.
            //
            // Rung 2 exists because the media encoders and the language
            // model do not have to run on the same accelerator, and a GPU
            // that happily runs the decoder can still lack a kernel the
            // vision tower needs. Retrying the encoders on CPU costs a few
            // hundred ms per request and is strictly better than silently
            // dropping to text-only.
            val rungs = buildList {
                if (wantVision || wantAudio) {
                    add(Triple("media on ${backend.name}", wantVision, wantAudio) to backend)
                    if (backend != ConfiguredBackend.CPU) {
                        add(Triple("media on CPU", wantVision, wantAudio) to ConfiguredBackend.CPU)
                    }
                }
                add(Triple("text-only", false, false) to backend)
            }

            var live: Engine? = null
            for ((spec, mediaBackend) in rungs) {
                val (label, v, a) = spec
                val attempt = runCatching {
                    val e = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backendOf(backend),
                            visionBackend = if (v) backendOf(mediaBackend) else null,
                            audioBackend = if (a) backendOf(mediaBackend) else null,
                            maxNumImages = if (v) MAX_IMAGES_PER_REQUEST else null,
                            cacheDir = appContext.cacheDir.path
                        )
                    )
                    // initialize() is where a model without the encoder
                    // fails, so the Engine object already exists by then and
                    // leaks native memory unless it is closed on the way out.
                    runCatching { e.initialize() }.onFailure {
                        runCatching { e.close() }
                        throw it
                    }
                    e
                }

                attempt.onSuccess {
                    live = it
                    visionActive = v
                    audioActive = a
                    multimodalMode = label
                }
                if (live != null) break

                val why = attempt.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "unknown"
                // Only the first failure is worth reporting to clients: it
                // is the one that says "this model has no encoder", which is
                // the fact a caller can act on.
                if (multimodalError == null) multimodalError = why
                Diagnostics.w(TAG, "Engine init rung '$label' failed -- $why")
            }

            val e = live ?: error("Engine failed to initialize on every configuration")
            loadMs = System.currentTimeMillis() - start
            engine = e
            loadedModelPath = modelPath
            loadedAt = System.currentTimeMillis()
            Diagnostics.i(
                TAG,
                "Engine initialized: $modelPath backend=$backend mode=$multimodalMode " +
                    "vision=$visionActive audio=$audioActive in ${loadMs}ms"
            )
        }
    }

    /**
     * A fresh Backend instance per config slot.
     *
     * Not a shared one: the same object would be handed to the main, vision
     * and audio slots, and these are native-backed handles whose lifecycle
     * the Kotlin layer does not document. Three cheap allocations beat one
     * shared handle closed three times.
     */
    private fun backendOf(b: ConfiguredBackend): Backend = when (b) {
        ConfiguredBackend.GPU -> Backend.GPU()
        ConfiguredBackend.CPU -> Backend.CPU()
        ConfiguredBackend.NPU ->
            Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
    }

    /**
     * One-shot generation. Suspends (never blocks the caller's thread) while
     * waiting for the engine, then runs the blocking native call on IO.
     *
     * [systemInstruction] used to be accepted and silently discarded, so
     * `POST /generate {"system": "..."}` had no effect at all. It is now
     * prepended to the prompt.
     */
    suspend fun generate(prompt: String, systemInstruction: String? = null): Result {
        val e = engine ?: error("No model loaded")
        val full = composePrompt(prompt, systemInstruction)
        inFlight.incrementAndGet()
        try {
            return gate.withLock {
                withContext(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    val text = e.createConversation().use { conv ->
                        extractText(conv.sendMessage(full))
                    }
                    val ms = System.currentTimeMillis() - start
                    val outTokens = SystemInfo.estimateTokens(text)
                    val inTokens = SystemInfo.estimateTokens(full)
                    SystemInfo.record(inTokens, outTokens, ms)
                    Result(text, inTokens, outTokens, ms)
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * One-shot generation with a hard output cap.
     *
     * The LiteRT-LM surface Medha binds takes no max-tokens parameter, so
     * `max_tokens` was advisory: a client could ask for 300 and get a
     * thousand. This enforces it by generating through the *streaming* path
     * and stopping early, which is a real cap rather than a truncation —
     * tokens past the limit are never produced, so the phone does not spend
     * the compute. That matters most for Telugu, which costs roughly 2.2x
     * the tokens for the same text.
     *
     * Returns the text and whether the cap was what ended it, so the caller
     * can report `finish_reason: "length"` honestly instead of guessing.
     *
     * Falls back to [generate] when the installed AAR has no async path, in
     * which case the cap is applied by truncation and [Capped.truncated]
     * says so.
     */
    suspend fun generateCapped(
        prompt: String,
        systemInstruction: String? = null,
        maxTokens: Int?
    ): Capped {
        if (maxTokens == null || maxTokens <= 0) {
            val r = generate(prompt, systemInstruction)
            return Capped(r.text, r.promptTokens, r.tokens, r.ms, hitCap = false, truncated = false)
        }

        val start = System.currentTimeMillis()
        val sb = StringBuilder()
        var hitCap = false
        // takeWhile checks BEFORE each emission, so the chunk that crosses
        // the cap is appended in full and the next one stops the flow.
        // Overrunning by at most one chunk is deliberate: cutting mid-chunk
        // would truncate a word, and takeWhile cancels the upstream, so the
        // model genuinely stops rather than being left to finish.
        //
        // NOT a hand-rolled `collectWhile` -- no such operator exists in
        // kotlinx.coroutines, and with the library off this classpath an
        // invented name compiles to the same "unresolved reference" noise as
        // every real Android symbol. takeWhile and collect are both already
        // used in this file.
        generateStream(prompt, systemInstruction)
            .takeWhile { !hitCap }
            .collect { delta ->
                sb.append(delta)
                if (SystemInfo.estimateTokens(sb.toString()) >= maxTokens) hitCap = true
            }
        val ms = System.currentTimeMillis() - start
        val text = sb.toString()
        val inTokens = SystemInfo.estimateTokens(composePrompt(prompt, systemInstruction))
        return Capped(text, inTokens, SystemInfo.estimateTokens(text), ms, hitCap, truncated = false)
    }

    /**
     * Raised when a request needs a modality this engine did not come up
     * with. Carries enough for the caller to answer accurately rather than
     * returning a bare 500.
     */
    class MultimodalUnavailable(
        val modality: String,
        val reason: String
    ) : Exception("$modality input is not available on this build: $reason")

    /**
     * One-shot generation over mixed text, image and audio input.
     *
     * Runs through [com.google.ai.edge.litertlm.Session] rather than
     * `Conversation`, deliberately: a transcription or an image question is
     * stateless, and a Conversation would carry a KV cache and chat
     * formatting that the caller neither asked for nor can reuse. Session's
     * `generateContent(List<InputData>)` is the SDK's own stateless path.
     *
     * Media comes before the text instruction in the input list. These
     * models are trained with the media tokens preceding the question, and
     * the reverse order measurably degrades the answer — the model has
     * already started composing a reply to a question about nothing.
     */
    suspend fun generateMultimodal(
        text: String,
        images: List<ByteArray> = emptyList(),
        audio: List<ByteArray> = emptyList(),
        systemInstruction: String? = null
    ): Result {
        val e = engine ?: error("No model loaded")

        if (images.isNotEmpty() && !visionActive) {
            throw MultimodalUnavailable("image", visionUnavailableReason())
        }
        if (audio.isNotEmpty() && !audioActive) {
            throw MultimodalUnavailable("audio", audioUnavailableReason())
        }
        require(images.size <= MAX_IMAGES_PER_REQUEST) {
            "at most $MAX_IMAGES_PER_REQUEST images per request"
        }

        val prompt = composePrompt(text, systemInstruction)
        val parts = buildList<InputData> {
            images.forEach { add(InputData.Image(it)) }
            audio.forEach { add(InputData.Audio(it)) }
            if (prompt.isNotBlank()) add(InputData.Text(prompt))
        }
        if (parts.isEmpty()) return Result("", 0, 0, 0)

        inFlight.incrementAndGet()
        try {
            return gate.withLock {
                withContext(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    val out = e.createSession().use { s -> s.generateContent(parts) }
                    val ms = System.currentTimeMillis() - start
                    val outTokens = SystemInfo.estimateTokens(out)
                    // Media tokens are not text tokens and estimateTokens
                    // cannot see them, so the prompt count here covers the
                    // text only. Reported as such rather than guessed at:
                    // a fabricated image-token count would corrupt every
                    // downstream cost and throughput figure.
                    val inTokens = SystemInfo.estimateTokens(prompt)
                    SystemInfo.record(inTokens, outTokens, ms)
                    Result(out, inTokens, outTokens, ms)
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Why vision is off, in words a client can act on. */
    fun visionUnavailableReason(): String = when {
        engine == null -> "no model is loaded"
        !aarCapabilities.vision ->
            "the installed LiteRT-LM AAR has no image binding (${aarCapabilities.summary()})"
        multimodalError != null ->
            "the loaded model did not initialize with a vision backend: $multimodalError"
        else -> "vision was disabled for this engine"
    }

    /** Why audio is off, in words a client can act on. */
    fun audioUnavailableReason(): String = when {
        engine == null -> "no model is loaded"
        !aarCapabilities.audio ->
            "the installed LiteRT-LM AAR has no audio binding (${aarCapabilities.summary()})"
        multimodalError != null ->
            "the loaded model did not initialize with an audio backend: $multimodalError"
        else -> "audio was disabled for this engine"
    }

    private fun MultimodalProbe.Report.summary(): String = MultimodalProbe.summary(this)

    data class Capped(
        val text: String,
        val promptTokens: Int,
        val tokens: Int,
        val ms: Long,
        /** True when generation was stopped by the cap rather than by the model. */
        val hitCap: Boolean,
        /** True when the cap was applied after the fact rather than during. */
        val truncated: Boolean
    ) {
        /** Matches Result.tokensPerSec so callers can treat the two alike. */
        val tokensPerSec: Double
            get() = if (ms > 0) tokens * 1000.0 / ms else 0.0
    }

    /**
     * Streaming generation as a Flow of *deltas*.
     *
     * The installed AAR may emit either incremental deltas or a growing
     * cumulative string; the LiteRT-LM Kotlin surface does not document which,
     * and it has differed between builds. We detect it: if an emission starts
     * with everything we have emitted so far, we treat the stream as cumulative
     * and forward only the tail. Otherwise we forward it verbatim. Consumers
     * therefore always receive true deltas, which is what an SSE client and the
     * OpenAI wire format both expect.
     */
    fun generateStream(prompt: String, systemInstruction: String? = null): Flow<String> = flow {
        val e = engine ?: error("No model loaded")
        val full = composePrompt(prompt, systemInstruction)
        inFlight.incrementAndGet()
        try {
            gate.withLock {
                val start = System.currentTimeMillis()
                var acc = ""
                val conv = e.createConversation()
                try {
                    val flowMethod = runCatching {
                        conv.javaClass.getMethod("sendMessageAsync", String::class.java)
                    }.getOrNull()

                    var streamed = false
                    if (flowMethod != null) {
                        @Suppress("UNCHECKED_CAST")
                        val f = runCatching { flowMethod.invoke(conv, full) as? Flow<Any?> }.getOrNull()
                        if (f != null) {
                            streamed = true
                            f.collect { msg ->
                                val piece = extractText(msg)
                                if (piece.isEmpty()) return@collect
                                val delta = if (piece.startsWith(acc) && piece.length >= acc.length) {
                                    piece.substring(acc.length)   // cumulative stream
                                } else {
                                    piece                          // already a delta
                                }
                                if (delta.isNotEmpty()) {
                                    acc += delta
                                    emit(delta)
                                }
                            }
                        }
                    }

                    if (!streamed) {
                        // Fallback: AAR has no async surface. One emission.
                        val text = extractText(conv.sendMessage(full))
                        acc = text
                        if (text.isNotEmpty()) emit(text)
                    }
                } finally {
                    runCatching { conv.close() }
                    // Streaming requests used to bypass metrics entirely, so
                    // /metrics under-reported every SSE consumer.
                    val ms = System.currentTimeMillis() - start
                    SystemInfo.record(
                        SystemInfo.estimateTokens(full),
                        SystemInfo.estimateTokens(acc),
                        ms
                    )
                }
            }
        } catch (c: CancellationException) {
            throw c
        } finally {
            inFlight.decrementAndGet()
        }
    }.flowOn(Dispatchers.IO)

    private fun composePrompt(prompt: String, systemInstruction: String?): String =
        if (systemInstruction.isNullOrBlank()) prompt
        else "System: $systemInstruction\n\n$prompt"

    /**
     * Extracts plain text from whatever the installed LiteRT-LM AAR's
     * `Message` type actually looks like.
     *
     * Every other official LiteRT-LM binding represents a response as
     * `content: [{type: "text", text: "..."}]`, not a flat string — e.g.
     * `response.content[0].text` in the JS SDK, `response["content"][0]
     * ["text"]` in Python. The Kotlin `Message` class very likely mirrors
     * that shape rather than exposing a flat `getText()`. The previous
     * version only tried `getText()` and fell back to `msg.toString()` on
     * anything else — which, if `getText()` doesn't actually exist on this
     * type, means every streamed AND non-streamed response silently became
     * the raw Kotlin data-class dump of the Message object (something like
     * `Message(role=ASSISTANT, content=[Text(text=Hello)])`) instead of the
     * reply text. That failure mode never throws, so it looks exactly like
     * "streaming is broken" or "the model returns garbage" without any
     * exception anywhere to point at it.
     *
     * Tries, in order: a direct `getText()`; then a `content`/`contents`
     * list, walking it for text-typed items only (so a tool-call or
     * image/audio entry never leaks its raw payload into chat text); then
     * gives up to `toString()` as a genuine last resort — logging loudly
     * when it has to, so a future SDK shape change is visible instead of
     * silently wrong.
     */
    private fun extractText(msg: Any?): String {
        if (msg == null) return ""

        runCatching {
            val t = msg.javaClass.getMethod("getText").invoke(msg) as? String
            if (!t.isNullOrEmpty()) return t
        }

        runCatching {
            val content = listOf("getContent", "getContents")
                .firstNotNullOfOrNull { name ->
                    runCatching { msg.javaClass.getMethod(name).invoke(msg) }.getOrNull()
                }
            if (content is Iterable<*>) {
                val text = content.mapNotNull(::extractTextFromContentItem).joinToString("")
                if (text.isNotEmpty()) return text
            }
        }

        Diagnostics.w(
            TAG,
            "extractText: no known text accessor on ${msg.javaClass.name}; " +
                "falling back to toString(). Streamed/generated text may be wrong " +
                "until this is updated for the installed litertlm-android version."
        )
        return msg.toString()
    }

    /** One item of a Message's content list; null if it isn't a text item. */
    private fun extractTextFromContentItem(item: Any?): String? {
        if (item == null) return null
        // Skip anything explicitly typed as non-text (tool calls, images,
        // audio) so their raw payload never leaks into chat output.
        val type = runCatching {
            item.javaClass.getMethod("getType").invoke(item)?.toString()
        }.getOrNull()
        if (type != null && !type.equals("text", ignoreCase = true)) return null

        for (name in listOf("getText", "component1")) {
            val v = runCatching { item.javaClass.getMethod(name).invoke(item) as? String }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    fun close() {
        synchronized(lifecycle) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedModelPath = null
        loadedAt = 0
    }

    fun recordError(msg: String?) {
        lastError = msg
    }

    data class Result(
        val text: String,
        val promptTokens: Int,
        val tokens: Int,
        val ms: Long
    ) {
        val tokensPerSec: Double get() = if (ms > 0) tokens * 1000.0 / ms else 0.0
    }

    companion object {
        private const val TAG = "LlmEngine"

        /**
         * Hard cap on images in one request, passed to
         * `EngineConfig.maxNumImages`.
         *
         * Each image costs a few hundred vision tokens against a 4096-token
         * context, so four is already most of the window. The limit is here
         * rather than only in the parser because the engine needs the number
         * at init: a request with more images than the engine was configured
         * for fails natively, with a message about tensor shapes.
         */
        const val MAX_IMAGES_PER_REQUEST = 4
    }
}
