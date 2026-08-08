package com.adabala.medha.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Text embedding backend.
 *
 * ## Why this is not `(String) -> FloatArray`
 *
 * The previous hook was a single function used for both sides of retrieval.
 * That cannot express a modern retrieval model. EmbeddingGemma is *asymmetric*:
 * it expects a different instruction prefix for a query than for a document,
 *
 *     query:    "task: search result | query: <text>"
 *     document: "title: <title|none> | text: <text>"
 *
 * and applying the wrong one (or neither) silently degrades recall rather than
 * throwing. Silent quality loss is the worst failure mode for a retrieval
 * system, so the asymmetry is encoded in the type instead of left to a comment.
 *
 * ## Contract
 *
 * - [embedQuery] and [embedDocument] MUST return vectors of length [dimensions].
 * - Returned vectors MUST be L2-normalised, so downstream similarity is a plain
 *   dot product rather than a full cosine.
 * - [id] identifies the model *and* its output dimensionality. It is persisted
 *   next to every stored vector. Vectors produced by a different [id] are never
 *   compared against the current model's — see MedhaDatabase.vectorCandidates.
 *   Mixing embedding spaces produces confident nonsense, which is far worse
 *   than having no vectors at all.
 */
interface Embedder {

    /** Stable identity of the embedding space, e.g. "embeddinggemma-300m@768". */
    val id: String

    /** Output vector length. */
    val dimensions: Int

    /** Longest input in tokens; text beyond this is truncated by the model. */
    val maxTokens: Int

    /** True once the underlying model is loaded and usable. */
    val isReady: Boolean

    suspend fun embedQuery(text: String): FloatArray?

    suspend fun embedDocument(text: String, title: String? = null): FloatArray?

    /** Releases native resources. Safe to call repeatedly. */
    fun close() {}

    companion object {
        // Verified against the model card and the sentence-transformers config.
        // Do not "tidy" the spacing: the trailing space after "query:" and
        // "text:" is part of the documented prefix.
        const val QUERY_PREFIX = "task: search result | query: "

        fun documentPrefix(title: String?): String =
            "title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: "

        /**
         * L2-normalise in place and return. A zero vector is returned unchanged
         * rather than producing NaNs.
         */
        fun normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x
            val n = sqrt(sum)
            if (n <= 1e-12) return v
            for (i in v.indices) v[i] = (v[i] / n).toFloat()
            return v
        }

        /** Dot product. Correct as cosine only for normalised inputs. */
        fun dot(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var s = 0.0
            for (i in a.indices) s += a[i].toDouble() * b[i]
            return s
        }

        /**
         * float32 little-endian BLOB codec.
         *
         * Replaces the previous comma-separated-string storage. A 768-dim
         * vector is 3 KB as a BLOB versus roughly 9 KB of text that also had to
         * be split and parsed on every single query. Little-endian is chosen to
         * match the format used by the AI Edge RAG SDK's SQLite vector store,
         * so the two are byte-compatible.
         */
        fun encode(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) bb.putFloat(x)
            return bb.array()
        }

        fun decode(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(b.size / 4)
            for (i in out.indices) out[i] = bb.float
            return out
        }
    }
}

/**
 * Placeholder used when no embedding model is configured. Retrieval falls back
 * to lexical mode, which is exactly the behaviour Medha shipped with — so an
 * absent embedder degrades the service rather than breaking it.
 */
object NoEmbedder : Embedder {
    override val id = "none"
    override val dimensions = 0
    override val maxTokens = 0
    override val isReady = false
    override suspend fun embedQuery(text: String): FloatArray? = null
    override suspend fun embedDocument(text: String, title: String?): FloatArray? = null
}
