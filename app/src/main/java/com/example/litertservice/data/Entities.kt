package com.example.litertservice.data

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

data class ChunkEntity(
    val id: Long = 0,
    val documentId: Long,
    val collection: String,
    val text: String,
    val embedding: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

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
    val chunks: Int
)
