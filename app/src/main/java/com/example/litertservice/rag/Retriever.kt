package com.example.litertservice.rag

import com.example.litertservice.data.ChunkEntity
import com.example.litertservice.data.MedhaDatabase
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Retrieval layer for RAG.
 *
 *  - VECTOR mode when chunks carry embeddings (cosine similarity).
 *  - LEXICAL mode otherwise, so RAG is useful before an embedding model exists.
 *
 * Lexical mode is now a two-stage pipeline: SQLite FTS4 shortlists candidates
 * inside the database, then we re-rank that bounded set in Kotlin with IDF
 * weighting. The previous implementation pulled every chunk in the collection
 * into a List and scored it in memory, which is fine for a demo corpus and an
 * OOM on a real one.
 *
 * [embedder] is pluggable: supply text -> FloatArray to enable vector mode
 * (e.g. EmbeddingGemma via LiteRT). Nothing else has to change.
 */
class Retriever(
    private val db: MedhaDatabase,
    private val embedder: (suspend (String) -> FloatArray?)? = null
) {

    data class Hit(val text: String, val score: Double, val mode: String)

    suspend fun ingest(
        collection: String,
        title: String?,
        source: String?,
        text: String
    ): Int {
        require(collection.isNotBlank()) { "collection must not be blank" }
        require(text.isNotBlank()) { "text must not be blank" }

        val docId = db.insertDocument(collection, title, source)
        val pieces = chunk(text)
        val rows = pieces.map { piece ->
            val emb = runCatching { embedder?.invoke(piece) }.getOrNull()
            piece to emb?.let { encode(it) }
        }
        // One transaction for the whole document: a 200-chunk ingest was
        // previously 200 separate commits, each with its own fsync.
        return db.insertChunks(docId, collection, rows)
    }

    suspend fun retrieve(collection: String, query: String, topK: Int): List<Hit> {
        if (query.isBlank() || topK <= 0) return emptyList()
        val k = topK.coerceAtMost(MAX_TOP_K)

        val queryEmb = runCatching { embedder?.invoke(query) }.getOrNull()
        if (queryEmb != null) {
            val embedded = db.embeddedChunks(collection)
            if (embedded.isNotEmpty()) return vectorSearch(embedded, queryEmb, k)
        }

        val terms = tokenize(query).toList()
        if (terms.isEmpty()) return emptyList()

        // Stage 1: bounded candidate set from the FTS index.
        var candidates = db.searchChunks(collection, terms, SHORTLIST)
        // Stage 2 fallback: no FTS on this device, or nothing matched.
        if (candidates.isEmpty()) candidates = db.chunksInCollection(collection)
        if (candidates.isEmpty()) return emptyList()

        return lexicalRank(candidates, terms.toSet(), k)
    }

    private fun vectorSearch(chunks: List<ChunkEntity>, q: FloatArray, topK: Int): List<Hit> =
        chunks.mapNotNull { c ->
            val decoded = runCatching { decode(c.embedding!!) }.getOrNull() ?: return@mapNotNull null
            Hit(c.text, cosine(q, decoded), "vector")
        }.sortedByDescending { it.score }.take(topK)

    /**
     * IDF-weighted overlap with length normalisation. Rare query terms count for
     * more than common ones, which plain term-count overlap got wrong: a chunk
     * repeating a stopword-ish token outranked one containing the actual
     * distinctive keyword.
     */
    private fun lexicalRank(
        chunks: List<ChunkEntity>,
        queryTerms: Set<String>,
        topK: Int
    ): List<Hit> {
        val tokenised = chunks.map { it to tokenize(it.text) }
        val n = tokenised.size.toDouble()

        val docFreq = HashMap<String, Int>()
        for (term in queryTerms) {
            docFreq[term] = tokenised.count { it.second.contains(term) }
        }

        return tokenised.mapNotNull { (chunkEntity, terms) ->
            if (terms.isEmpty()) return@mapNotNull null
            var score = 0.0
            for (term in queryTerms) {
                if (!terms.contains(term)) continue
                val df = docFreq[term] ?: 0
                if (df == 0) continue
                score += ln(1.0 + n / df)
            }
            if (score <= 0.0) null
            else Hit(chunkEntity.text, score / sqrt(terms.size.toDouble()), "lexical")
        }.sortedByDescending { it.score }.take(topK)
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .split(TOKEN_SPLIT)
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

    /**
     * Paragraph-aware chunking with overlap. The overlap is the important part:
     * without it, a fact that straddles a chunk boundary becomes unretrievable
     * because neither chunk contains the whole statement.
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
            // A single oversized paragraph gets hard-split rather than emitted whole.
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

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0.0) 0.0 else dot / d
    }

    private fun encode(v: FloatArray) = v.joinToString(",")

    private fun decode(s: String) = s.split(",").map { it.trim().toFloat() }.toFloatArray()

    companion object {
        private const val TARGET_CHARS = 600
        private const val OVERLAP_CHARS = 100
        private const val SHORTLIST = 200
        private const val MAX_TOP_K = 20

        private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
        private val PARA_SPLIT = Regex("\n\\s*\n")

        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his",
            "how", "its", "new", "now", "old", "see", "two", "way", "who", "did",
            "yes", "his", "from", "they", "this", "that", "with", "have", "what",
            "were", "when", "your", "said", "there", "their", "which", "about"
        )
    }
}
