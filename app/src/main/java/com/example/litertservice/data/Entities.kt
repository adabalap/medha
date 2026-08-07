package com.example.litertservice.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A conversation thread. A PWA (e.g. the SMS organizer, a notes app) owns one or
 * more conversations, identified by its own sessionId string.
 */
@Entity(tableName = "conversations", indices = [Index(value = ["sessionId"], unique = true)])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val title: String? = null,
    val systemInstruction: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A single message within a conversation. role is "user" | "assistant" | "system". */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A document ingested for retrieval (RAG). Its text is split into chunks.
 * collection lets a PWA keep its own namespace (e.g. "sms", "notes").
 */
@Entity(tableName = "documents", indices = [Index(value = ["collection"])])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collection: String,
    val title: String? = null,
    val source: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A retrievable chunk of a document. embedding is a JSON float array when an
 * embedding model is available; null means keyword-only retrieval for this chunk.
 */
@Entity(
    tableName = "chunks",
    indices = [Index(value = ["documentId"]), Index(value = ["collection"])]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val collection: String,
    val text: String,
    val embedding: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
