package com.example.litertservice.data

// Plain data classes (no Room annotations). Persistence handled by MedhaDatabase.

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
