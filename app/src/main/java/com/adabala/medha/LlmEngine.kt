package com.adabala.medha

import android.content.Context
import com.adabala.medha.diag.Diagnostics
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    fun load(modelPath: String, backend: ConfiguredBackend, force: Boolean = false) {
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
            configuredBackend = backend

            val be = when (backend) {
                ConfiguredBackend.GPU -> Backend.GPU()
                ConfiguredBackend.CPU -> Backend.CPU()
                ConfiguredBackend.NPU ->
                    Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
            }
            val config = EngineConfig(
                modelPath = modelPath,
                backend = be,
                cacheDir = appContext.cacheDir.path
            )
            val start = System.currentTimeMillis()
            val e = Engine(config)
            e.initialize()
            loadMs = System.currentTimeMillis() - start
            engine = e
            loadedModelPath = modelPath
            loadedAt = System.currentTimeMillis()
            Diagnostics.i(TAG, "Engine initialized: $modelPath backend=$backend in ${loadMs}ms")
        }
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
    }
}
