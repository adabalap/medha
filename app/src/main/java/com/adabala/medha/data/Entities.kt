package com.adabala.medha.data

/**
 * Plain data classes. Persistence is hand-written SQL in [MedhaDatabase].
 *
 * DELIBERATELY NO ROOM ANNOTATIONS. Room would drag in KSP, whose version is
 * hard-pinned to the Kotlin version; that coupling is the single most common
 * cause of CI breakage on this project. See docs/ARCHITECTURE-DB.md.
 */

data class ConversationEntity(
    val id: Long = 0,
    val sessionId: String,
    val title: String? = null,
    val systemInstruction: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class MessageEntity(
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class DocumentEntity(
    val id: Long = 0,
    val collection: String,
    val title: String? = null,
    val source: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A retrievable chunk.
 *
 * [embedding] is a float32 little-endian BLOB, not a comma-separated string:
 * 3 KB instead of ~9 KB for a 768-dim vector, with no parse cost per query.
 *
 * [embeddingModel] records which embedding space produced the vector. Vectors
 * from a different space must never be compared against the current model's —
 * the arithmetic succeeds and the results are meaningless, which is the single
 * nastiest failure mode in a RAG system.
 */
data class ChunkEntity(
    val id: Long = 0,
    val documentId: Long,
    val collection: String,
    val text: String,
    val embedding: ByteArray? = null,
    val embeddingModel: String? = null,
    val embeddingDim: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    // Explicit because the class holds a ByteArray, where the generated
    // equals/hashCode would compare references and quietly misbehave.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is ChunkEntity && id == other.id && text == other.text)

    override fun hashCode(): Int = (id * 31 + text.hashCode()).toInt()
}

/** Summary row for listing sessions in a consumer PWA. */
data class ConversationSummary(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val updatedAt: Long
)

/** Summary row for listing RAG collections. */
data class CollectionSummary(
    val collection: String,
    val documents: Int,
    val chunks: Int,
    val embedded: Int = 0
)
