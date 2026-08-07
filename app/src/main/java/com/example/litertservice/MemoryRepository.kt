package com.example.litertservice

import com.example.litertservice.data.ConversationEntity
import com.example.litertservice.data.MedhaDatabase
import com.example.litertservice.data.MessageEntity

/**
 * Multi-turn memory on top of SQLite. A consumer PWA passes a stable sessionId;
 * Medha keeps the full thread and rebuilds bounded context each turn.
 */
class MemoryRepository(private val db: MedhaDatabase) {

    fun getOrCreate(sessionId: String, systemInstruction: String?): ConversationEntity {
        db.findConversation(sessionId)?.let { return it }
        db.insertConversation(sessionId, systemInstruction)
        return db.findConversation(sessionId)!!
    }

    fun appendMessage(conversationId: Long, role: String, content: String) {
        db.insertMessage(conversationId, role, content)
        db.touchConversation(conversationId, null)
    }

    fun history(conversationId: Long, maxMessages: Int = 20): List<MessageEntity> =
        db.recentMessages(conversationId, maxMessages).sortedBy { it.id }

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
        history.forEach { m ->
            append(m.role.replaceFirstChar { it.uppercase() }).append(": ").append(m.content).append("\n")
        }
        append("User: ").append(userMessage).append("\n")
        append("Assistant:")
    }

    fun clear(sessionId: String) {
        db.findConversation(sessionId)?.let { db.clearMessages(it.id) }
    }

    fun stats(): Map<String, Int> = mapOf(
        "conversations" to db.countConversations(),
        "messages" to db.countMessages(),
        "documents" to db.countDocuments(),
        "chunks" to db.countChunks()
    )
}
