package com.example.litertservice.rag

import com.example.litertservice.data.ChunkEntity
import com.example.litertservice.data.MedhaDatabase
import kotlin.math.sqrt

/**
 * Retrieval layer for RAG.
 *  - VECTOR mode when chunks carry embeddings (cosine similarity).
 *  - KEYWORD mode otherwise (TF overlap), so RAG works before an embedding
 *    model is wired in.
 * embedder is pluggable: supply text -> FloatArray to enable vector mode.
 */
class Retriever(
    private val db: MedhaDatabase,
    private val embedder: (suspend (String) -> FloatArray?)? = null
) {
    data class Hit(val text: String, val score: Double)

    suspend fun ingest(collection: String, title: String?, source: String?, text: String) {
        val docId = db.insertDocument(collection, title, source)
        for (chunk in chunk(text)) {
            val emb = embedder?.invoke(chunk)
            db.insertChunk(docId, collection, chunk, emb?.let { encode(it) })
        }
    }

    suspend fun retrieve(collection: String, query: String, topK: Int): List<Hit> {
        val queryEmb = embedder?.invoke(query)
        if (queryEmb != null) {
            val embedded = db.embeddedChunks(collection)
            if (embedded.isNotEmpty()) return vectorSearch(embedded, queryEmb, topK)
        }
        return keywordSearch(db.chunksInCollection(collection), query, topK)
    }

    private fun vectorSearch(chunks: List<ChunkEntity>, q: FloatArray, topK: Int): List<Hit> =
        chunks.mapNotNull { c -> c.embedding?.let { Hit(c.text, cosine(q, decode(it))) } }
            .sortedByDescending { it.score }.take(topK)

    private fun keywordSearch(chunks: List<ChunkEntity>, query: String, topK: Int): List<Hit> {
        val qTerms = tokenize(query)
        if (qTerms.isEmpty()) return emptyList()
        return chunks.map { c ->
            val terms = tokenize(c.text)
            val overlap = qTerms.count { terms.contains(it) }.toDouble()
            val score = if (terms.isEmpty()) 0.0 else overlap / sqrt(terms.size.toDouble())
            Hit(c.text, score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(topK)
    }

    private fun tokenize(s: String) =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    private fun chunk(text: String, target: Int = 600): List<String> {
        val paras = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in paras) {
            if (sb.length + p.length > target && sb.isNotEmpty()) { out.add(sb.toString().trim()); sb.clear() }
            sb.append(p).append("\n\n")
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.ifEmpty { listOf(text.trim()) }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        val d = sqrt(na) * sqrt(nb); return if (d == 0.0) 0.0 else dot / d
    }

    private fun encode(v: FloatArray) = v.joinToString(",")
    private fun decode(s: String) = s.split(",").map { it.toFloat() }.toFloatArray()
}
