package com.example.litertservice

import com.example.litertservice.data.ConversationEntity
import com.example.litertservice.data.MedhaDatabase
import com.example.litertservice.data.MessageEntity

/**
 * Multi-turn memory on top of SQLite. A consumer PWA passes a stable sessionId;
 * Medha keeps the full thread and rebuilds context each turn (bounded to the
 * most recent N messages to protect the KV cache / RAM).
 */
class MemoryRepository(private val db: MedhaDatabase) {

    suspend fun getOrCreate(sessionId: String, systemInstruction: String?): ConversationEntity {
        db.conversationDao().findBySession(sessionId)?.let { return it }
        val id = db.conversationDao().insert(
            ConversationEntity(sessionId = sessionId, systemInstruction = systemInstruction)
        )
        return db.conversationDao().findBySession(sessionId)!!.copy(id = id)
    }

    suspend fun appendMessage(conversationId: Long, role: String, content: String) {
        db.messageDao().insert(MessageEntity(conversationId = conversationId, role = role, content = content))
        db.conversationDao().touch(conversationId)
    }

    suspend fun history(conversationId: Long, maxMessages: Int = 20): List<MessageEntity> {
        val recent = db.messageDao().recentForConversation(conversationId, maxMessages)
        return recent.sortedBy { it.id }
    }

    /** Flatten history + optional retrieved context into a single prompt. */
    fun buildPrompt(
        systemInstruction: String?,
        history: List<MessageEntity>,
        userMessage: String,
        retrievedContext: List<String> = emptyList()
    ): String = buildString {
        systemInstruction?.let { append("System: ").append(it).append("\n\n") }
        if (retrievedContext.isNotEmpty()) {
            append("Relevant context:\n")
            retrievedContext.forEachIndexed { i, c -> append("[${i + 1}] ").append(c).append("\n") }
            append("\n")
        }
        history.forEach { m -> append(m.role.replaceFirstChar { it.uppercase() }).append(": ").append(m.content).append("\n") }
        append("User: ").append(userMessage).append("\n")
        append("Assistant:")
    }

    suspend fun clear(sessionId: String) {
        db.conversationDao().findBySession(sessionId)?.let {
            db.messageDao().clearConversation(it.id)
        }
    }

    suspend fun stats(): Map<String, Int> = mapOf(
        "conversations" to db.conversationDao().count(),
        "messages" to db.messageDao().count(),
        "documents" to db.documentDao().docCount(),
        "chunks" to db.documentDao().chunkCount()
    )
}
