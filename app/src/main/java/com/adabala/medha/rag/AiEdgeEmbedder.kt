package com.adabala.medha.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma / Gecko backed by the Google AI Edge RAG SDK.
 *
 * ## Why this uses reflection
 *
 * The SDK is reached through reflection rather than a compile-time import, and
 * that is a deliberate trade, not laziness:
 *
 *  - `com.google.ai.edge.localagents:localagents-rag` pulls in
 *    `com.google.mediapipe:tasks-genai`, which ships its own copies of the
 *    LiteRT native libraries. Medha already links `litertlm-android`, which
 *    ships them too. Two AARs contributing `libLiteRt.so` is a packaging
 *    conflict at best and a native symbol clash at runtime at worst.
 *  - Making it a hard dependency would mean every build carries that risk even
 *    for users who never enable embeddings.
 *
 * So: the dependency is commented out in build.gradle.kts by default. Add it,
 * drop the model files on the device, and this class lights up. Leave it out
 * and [isReady] stays false, RAG runs in lexical mode, and nothing else in the
 * app changes. Enabling it is one uncommented line plus a `pickFirst` packaging
 * rule; both are documented in build.gradle.kts.
 *
 * ## Model files
 *
 * Place under the app's files dir, `models/embed/`:
 *
 *   embeddinggemma-300m_seq256_f32.tflite   (or a Gecko_256_f32.tflite)
 *   sentencepiece.model                     tokenizer
 *
 * Sequence length must be >= the token length of a chunk. Medha chunks to ~600
 * characters, roughly 150 tokens, so the 256-token variants are sufficient and
 * are meaningfully faster and smaller than the 512/1024 ones.
 *
 * ## Memory
 *
 * EmbeddingGemma-300M in fp32 is well over a gigabyte. Alongside a resident
 * 2B-class generation model that will OOM most mid-range phones. Use a
 * quantized variant, and expect to trade some retrieval quality for it.
 */
class AiEdgeEmbedder private constructor(
    private val delegate: Any,
    override val id: String,
    override val dimensions: Int,
    override val maxTokens: Int
) : Embedder {

    /** The native embedder is not documented as thread-safe. Serialise it. */
    private val gate = Mutex()

    @Volatile private var closed = false

    override val isReady: Boolean get() = !closed

    override suspend fun embedQuery(text: String): FloatArray? =
        embed(Embedder.QUERY_PREFIX + text.trim())

    override suspend fun embedDocument(text: String, title: String?): FloatArray? =
        embed(Embedder.documentPrefix(title) + text.trim())

    private suspend fun embed(prefixed: String): FloatArray? {
        if (closed) return null
        return gate.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val raw = invokeEmbed(delegate, prefixed) ?: return@runCatching null
                    if (raw.size != dimensions) {
                        Log.w(TAG, "embedder returned ${raw.size} dims, expected $dimensions")
                        return@runCatching null
                    }
                    // Normalise here rather than trusting the backend: the
                    // Embedder contract promises unit vectors so that callers
                    // can use a dot product, and not every variant normalises.
                    Embedder.normalize(raw)
                }.onFailure { Log.w(TAG, "embed failed", it) }.getOrNull()
            }
        }
    }

    override fun close() {
        closed = true
        runCatching {
            delegate.javaClass.getMethod("close").invoke(delegate)
        }
    }

    companion object {
        private const val TAG = "AiEdgeEmbedder"

        const val MODEL_DIR = "models/embed"

        private val EMBED_METHODS = listOf(
            "getEmbeddings", "embed", "getEmbedding", "embedText"
        )

        private const val SDK_CLASS =
            "com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel"

        /**
         * Calls the SDK's embedding entry point. The RAG SDK has revised this
         * surface between releases, so several known shapes are tried and the
         * first that yields a float array wins. If none match, embeddings
         * stay disabled rather than the app crashing.
         *
         * Static (not an instance method) so [createOrNull] can use it for a
         * startup probe before an [AiEdgeEmbedder] — and therefore its
         * [dimensions] — exists yet.
         */
        private fun invokeEmbed(target: Any, text: String): FloatArray? {
            for (name in EMBED_METHODS) {
                val m = runCatching { target.javaClass.getMethod(name, String::class.java) }
                    .getOrNull() ?: continue
                val result = runCatching { m.invoke(target, text) }.getOrNull() ?: continue
                toFloatArray(result)?.let { return it }
            }
            return null
        }

        private fun toFloatArray(v: Any?): FloatArray? = when (v) {
            null -> null
            is FloatArray -> v
            is DoubleArray -> FloatArray(v.size) { v[it].toFloat() }
            is List<*> -> {
                val nums = v.filterIsInstance<Number>()
                if (nums.size == v.size && nums.isNotEmpty()) {
                    FloatArray(nums.size) { nums[it].toFloat() }
                } else {
                    // Some versions wrap the vector in a result object.
                    v.firstOrNull()?.let { toFloatArray(it) }
                }
            }
            else -> runCatching {
                for (g in listOf("getEmbedding", "getValues", "getVector", "embedding")) {
                    val m = runCatching { v.javaClass.getMethod(g) }.getOrNull() ?: continue
                    toFloatArray(m.invoke(v))?.let { return@runCatching it }
                }
                null
            }.getOrNull()
        }

        /**
         * Attempts to construct the embedder. Returns [NoEmbedder] on any
         * failure — a missing dependency, a missing model file, or an SDK whose
         * constructor shape has changed. Embeddings are an enhancement; they
         * must never be able to stop Medha from serving.
         */
        fun createOrNull(context: Context): Embedder {
            val dir = File(context.filesDir, MODEL_DIR)
            val model = dir.listFiles { f -> f.name.endsWith(".tflite") }?.firstOrNull()
            val tokenizer = File(dir, "sentencepiece.model")

            if (model == null || !tokenizer.exists()) {
                Log.i(TAG, "No embedding model in ${dir.path}; RAG stays lexical")
                return NoEmbedder
            }

            val cls = runCatching { Class.forName(SDK_CLASS) }.getOrNull()
            if (cls == null) {
                Log.i(
                    TAG,
                    "AI Edge RAG SDK not on the classpath; uncomment localagents-rag " +
                        "in build.gradle.kts to enable vector retrieval"
                )
                return NoEmbedder
            }

            val seq = SEQ_RE.find(model.name)?.groupValues?.get(1)?.toIntOrNull() ?: 256

            val delegate = runCatching {
                // Known constructor shape: (modelPath, tokenizerPath, useGpu).
                cls.getConstructor(
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType
                ).newInstance(model.absolutePath, tokenizer.absolutePath, false)
            }.recoverCatching {
                cls.getConstructor(String::class.java, String::class.java)
                    .newInstance(model.absolutePath, tokenizer.absolutePath)
            }.onFailure {
                Log.w(TAG, "Could not construct ${cls.name}; RAG stays lexical", it)
            }.getOrNull() ?: return NoEmbedder

            // Dimensionality is measured, not guessed from the filename.
            //
            // Both EmbeddingGemma and Gecko ship Matryoshka-truncated variants
            // (768/512/256/128 dims) with no filename convention every
            // checkpoint follows, so a static guess is a coin flip on anything
            // but the default export. Guessing wrong does not throw — every
            // future embed() call would fail the dimension check in [embed],
            // return null, and silently pin RAG in lexical mode forever. One
            // real probe at startup turns that into ground truth: either the
            // model actually produces vectors and we record their true length,
            // or it doesn't and embeddings stay off, loudly, right here.
            val probe = runCatching {
                runBlocking { invokeEmbed(delegate, Embedder.QUERY_PREFIX + "medha") }
            }.getOrNull()

            if (probe == null || probe.isEmpty()) {
                Log.w(
                    TAG,
                    "Constructed ${cls.name} but a startup probe embed produced no output; " +
                        "RAG stays lexical"
                )
                runCatching { delegate.javaClass.getMethod("close").invoke(delegate) }
                return NoEmbedder
            }

            val dims = probe.size
            val id = "${model.nameWithoutExtension}@$dims"
            Log.i(TAG, "Embedder ready: $id (seq=$seq, measured via startup probe)")
            return AiEdgeEmbedder(delegate, id, dims, seq)
        }

        private val SEQ_RE = Regex("""(?:seq|_)(\d{3,4})""")
    }
}
