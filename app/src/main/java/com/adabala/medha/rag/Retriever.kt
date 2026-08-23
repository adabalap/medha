package com.adabala.medha.rag

import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.data.ChunkEntity
import com.adabala.medha.data.MedhaDatabase
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Retrieval for RAG.
 *
 * Three modes, chosen automatically:
 *
 *  - **hybrid**  — dense vectors AND lexical FTS, fused. The default whenever an
 *                  embedder is loaded and the collection has vectors.
 *  - **vector**  — dense only, when lexical finds nothing.
 *  - **lexical** — FTS4 shortlist re-ranked by IDF. The fallback when no
 *                  embedder is configured, which is exactly how Medha behaved
 *                  before embeddings existed.
 *
 * ## Why hybrid rather than "upgrade to vector"
 *
 * Dense retrieval is strong on paraphrase and weak on *exact tokens*. An
 * embedding of "order AX-99213" and one of "order AX-99871" sit almost on top
 * of each other, because the model encodes "an order reference" and not the
 * digits. For an SMS organizer — OTP codes, tracking numbers, account digits,
 * short names — that is the common case, not an edge case. Lexical search is
 * exact but brittle to phrasing; dense is robust to phrasing but blurry on
 * identifiers. Fusing them recovers both.
 *
 * Fusion is Reciprocal Rank Fusion: each list contributes 1/(K + rank). RRF is
 * used rather than a weighted score blend because cosine similarities and IDF
 * scores live on incomparable scales, and any fixed weighting between them is a
 * magic number that silently stops being right when the corpus changes. RRF
 * only reads *ranks*, so it needs no calibration.
 */
class Retriever(
    private val db: MedhaDatabase,
    private val embedder: Embedder = NoEmbedder
) {

    data class Hit(
        val text: String,
        val score: Double,
        val mode: String,
        val chunkId: Long = 0
    )

    val embeddingId: String get() = embedder.id
    val vectorEnabled: Boolean get() = embedder.isReady

    // ------------------------------ ingest ------------------------------

    suspend fun ingest(
        collection: String,
        title: String?,
        source: String?,
        text: String
    ): IngestResult {
        require(collection.isNotBlank()) { "collection must not be blank" }
        require(text.isNotBlank()) { "text must not be blank" }

        val docId = db.insertDocument(collection, title, source)
        val pieces = chunk(text)
        var embedded = 0

        val rows = pieces.map { piece ->
            // Document side gets the "title: ... | text: ..." prefix. Applying
            // the query prefix here instead would cost recall silently.
            val vec = if (embedder.isReady) {
                runCatching { embedder.embedDocument(piece, title) }
                    .onFailure { Diagnostics.w(TAG, "embedDocument failed; storing chunk without a vector", it) }
                    .getOrNull()
            } else null

            if (vec != null && vec.size == embedder.dimensions) {
                embedded++
                MedhaDatabase.ChunkInsert(
                    text = piece,
                    embedding = Embedder.encode(vec),
                    embeddingModel = embedder.id,
                    embeddingDim = embedder.dimensions
                )
            } else {
                // A failed embedding must not lose the chunk. It stays
                // lexically searchable and POST /rag/reindex can fill it later.
                MedhaDatabase.ChunkInsert(text = piece)
            }
        }

        val n = db.insertChunks(docId, collection, rows)
        return IngestResult(chunks = n, embedded = embedded)
    }

    data class IngestResult(val chunks: Int, val embedded: Int)

    /**
     * Embeds chunks that have no vector for the active model. Returns how many
     * were filled. Bounded per call so a huge collection can be worked through
     * incrementally without holding the engine hostage.
     */
    suspend fun reindex(collection: String?, batch: Int = REINDEX_BATCH): ReindexResult {
        if (!embedder.isReady) return ReindexResult(0, 0, "no embedder loaded")
        val pending = db.chunksNeedingEmbedding(collection, embedder.id, batch)
        var done = 0
        for (c in pending) {
            val vec = runCatching { embedder.embedDocument(c.text, null) }.getOrNull()
            if (vec != null && vec.size == embedder.dimensions) {
                db.updateChunkEmbedding(
                    c.id, Embedder.encode(vec), embedder.id, embedder.dimensions
                )
                done++
            }
        }
        val remaining = db.chunksNeedingEmbedding(collection, embedder.id, batch + 1).size
        return ReindexResult(done, remaining, if (remaining > 0) "more remaining" else "complete")
    }

    data class ReindexResult(val embedded: Int, val remaining: Int, val status: String)

    // ----------------------------- retrieve -----------------------------

    suspend fun retrieve(collection: String, query: String, topK: Int): List<Hit> {
        if (query.isBlank() || topK <= 0) return emptyList()
        val k = topK.coerceAtMost(MAX_TOP_K)

        val dense = denseSearch(collection, query, k * OVERFETCH)
        val lexical = lexicalSearch(collection, query, k * OVERFETCH)

        return when {
            dense.isNotEmpty() && lexical.isNotEmpty() -> fuse(dense, lexical, k)
            dense.isNotEmpty() -> dense.take(k)
            else -> lexical.take(k)
        }
    }

    private suspend fun denseSearch(collection: String, query: String, limit: Int): List<Hit> {
        if (!embedder.isReady) return emptyList()
        val q = runCatching { embedder.embedQuery(query) }
            .onFailure { Diagnostics.w(TAG, "embedQuery failed; falling back to lexical", it) }
            .getOrNull() ?: return emptyList()

        // Scoped to this embedding space; stale vectors are invisible.
        val candidates = db.vectorCandidates(collection, embedder.id)
        if (candidates.isEmpty()) return emptyList()

        return candidates.mapNotNull { c ->
            val blob = c.embedding ?: return@mapNotNull null
            if (c.embeddingDim != q.size) return@mapNotNull null
            val v = runCatching { Embedder.decode(blob) }.getOrNull() ?: return@mapNotNull null
            // Both sides are L2-normalised, so a dot product IS the cosine.
            Hit(c.text, Embedder.dot(q, v), "vector", c.id)
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun lexicalSearch(collection: String, query: String, limit: Int): List<Hit> {
        val terms = tokenize(query).toList()
        if (terms.isEmpty()) return emptyList()
        var candidates = db.searchChunks(collection, terms, SHORTLIST)
        if (candidates.isEmpty()) candidates = db.chunksInCollection(collection)
        if (candidates.isEmpty()) return emptyList()
        return lexicalRank(candidates, terms.toSet(), limit)
    }

    /**
     * Reciprocal Rank Fusion. Rank-only, so the two incomparable score scales
     * never have to be reconciled.
     */
    private fun fuse(dense: List<Hit>, lexical: List<Hit>, topK: Int): List<Hit> {
        val scores = HashMap<String, Double>()
        val best = HashMap<String, Hit>()

        fun add(list: List<Hit>) {
            list.forEachIndexed { i, h ->
                val key = h.text
                scores[key] = (scores[key] ?: 0.0) + 1.0 / (RRF_K + i + 1)
                best.putIfAbsent(key, h)
            }
        }
        add(dense)
        add(lexical)

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .mapNotNull { e ->
                best[e.key]?.let { Hit(it.text, e.value, "hybrid", it.chunkId) }
            }
    }

    /**
     * IDF-weighted overlap with length normalisation. Rare query terms count
     * for more than common ones, so a chunk repeating a near-stopword cannot
     * outrank one holding the actual distinctive keyword.
     */
    private fun lexicalRank(
        chunks: List<ChunkEntity>,
        queryTerms: Set<String>,
        topK: Int
    ): List<Hit> {
        val tokenised = chunks.map { it to tokenize(it.text) }
        val n = tokenised.size.toDouble()

        val docFreq = HashMap<String, Int>()
        for (term in queryTerms) docFreq[term] = tokenised.count { it.second.contains(term) }

        return tokenised.mapNotNull { (c, terms) ->
            if (terms.isEmpty()) return@mapNotNull null
            var score = 0.0
            for (term in queryTerms) {
                if (!terms.contains(term)) continue
                val df = docFreq[term] ?: 0
                if (df == 0) continue
                score += ln(1.0 + n / df)
            }
            if (score <= 0.0) null
            else Hit(c.text, score / sqrt(terms.size.toDouble()), "lexical", c.id)
        }.sortedByDescending { it.score }.take(topK)
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .split(TOKEN_SPLIT)
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

    /**
     * Paragraph-aware chunking with overlap. Overlap is the important part:
     * without it a fact straddling a boundary lands whole in neither chunk and
     * becomes unretrievable by either mode.
     *
     * [TARGET_CHARS] is deliberately ~600 characters, roughly 150 tokens. That
     * keeps a chunk comfortably inside a 256-token embedder sequence length,
     * which is the smallest commonly shipped variant. Raising it without also
     * raising the embedder's sequence length means the model silently truncates
     * and embeds only the head of each chunk.
     */
    private fun chunk(
        text: String,
        target: Int = TARGET_CHARS,
        overlap: Int = OVERLAP_CHARS
    ): List<String> {
        val paras = text.split(PARA_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return listOf(text.trim())

        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in paras) {
            if (p.length > target * 2) {
                if (sb.isNotBlank()) {
                    out.add(sb.toString().trim()); sb.setLength(0)
                }
                var i = 0
                while (i < p.length) {
                    val end = minOf(i + target, p.length)
                    out.add(p.substring(i, end).trim())
                    i = if (end == p.length) end else end - overlap
                }
                continue
            }
            if (sb.length + p.length > target && sb.isNotBlank()) {
                val done = sb.toString().trim()
                out.add(done)
                sb.setLength(0)
                if (done.length > overlap) sb.append(done.takeLast(overlap)).append("\n\n")
            }
            sb.append(p).append("\n\n")
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(text.trim()) }
    }

    companion object {
        private const val TAG = "Retriever"
        private const val TARGET_CHARS = 600
        private const val OVERLAP_CHARS = 100
        private const val SHORTLIST = 200
        private const val MAX_TOP_K = 20
        private const val REINDEX_BATCH = 64

        /** Pull this many times topK from each mode before fusing. */
        private const val OVERFETCH = 4

        /**
         * RRF damping. 60 is the value from the original Cormack et al. paper
         * and is not sensitive enough to be worth tuning here: it mainly sets
         * how sharply rank 1 outweighs rank 10.
         */
        private const val RRF_K = 60.0

        private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
        private val PARA_SPLIT = Regex("\n\\s*\n")

        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his",
            "how", "its", "new", "now", "old", "see", "two", "way", "who", "did",
            "yes", "from", "they", "this", "that", "with", "have", "what",
            "were", "when", "your", "said", "there", "their", "which", "about"
        )
    }
}
