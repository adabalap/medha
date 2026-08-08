package com.adabala.medha

import com.adabala.medha.data.ConversationEntity
import com.adabala.medha.data.ConversationSummary
import com.adabala.medha.data.MedhaDatabase
import com.adabala.medha.data.MessageEntity

/**
 * Multi-turn memory on top of SQLite. A consumer PWA passes a stable sessionId;
 * Medha keeps the full thread and rebuilds a bounded context each turn.
 */
class MemoryRepository(private val db: MedhaDatabase) {

    fun getOrCreate(sessionId: String, systemInstruction: String?): ConversationEntity {
        db.findConversation(sessionId)?.let { return it }
        db.insertConversation(sessionId, systemInstruction)
        // Re-read rather than trusting the insert rowid: with CONFLICT_IGNORE a
        // concurrent creator may have won the race and we want their row.
        return db.findConversation(sessionId)
            ?: error("Failed to create conversation for session $sessionId")
    }

    fun appendMessage(conversationId: Long, role: String, content: String) {
        db.insertMessage(conversationId, role, content)
        db.touchConversation(conversationId, null)
    }

    /** Persists a completed exchange atomically so a crash cannot orphan a turn. */
    fun appendTurn(conversationId: Long, userText: String, assistantText: String) =
        db.insertTurn(conversationId, userText, assistantText)

    fun history(conversationId: Long, maxMessages: Int = MAX_HISTORY_MESSAGES): List<MessageEntity> =
        db.recentMessages(conversationId, maxMessages)

    /**
     * Builds the prompt under a character budget rather than a fixed message
     * count. A fixed count is the wrong bound: twenty short messages and twenty
     * pasted-in emails are the same number, and only one of those fits in a 2B
     * model's context. We keep the most recent turns that fit and drop the
     * oldest, which is what actually protects against context overflow on a
     * mid-range phone.
     */
    fun buildPrompt(
        systemInstruction: String?,
        history: List<MessageEntity>,
        userMessage: String,
        retrievedContext: List<String> = emptyList(),
        charBudget: Int = DEFAULT_CHAR_BUDGET
    ): String {
        val head = StringBuilder()
        systemInstruction?.takeIf { it.isNotBlank() }
            ?.let { head.append("System: ").append(it).append("\n\n") }

        if (retrievedContext.isNotEmpty()) {
            head.append("Relevant context:\n")
            retrievedContext.forEachIndexed { i, c ->
                head.append("[").append(i + 1).append("] ").append(c.trim()).append("\n")
            }
            head.append("\n")
        }

        val tail = "User: $userMessage\nAssistant:"
        var remaining = charBudget - head.length - tail.length

        // Walk newest-first, keeping what fits, then restore chronological order.
        val kept = ArrayList<String>()
        for (m in history.asReversed()) {
            val line = "${m.role.replaceFirstChar { it.uppercase() }}: ${m.content}\n"
            if (line.length > remaining) break
            kept.add(line)
            remaining -= line.length
        }
        kept.reverse()

        return buildString {
            append(head)
            kept.forEach { append(it) }
            append(tail)
        }
    }

    fun clear(sessionId: String) {
        db.findConversation(sessionId)?.let { db.clearMessages(it.id) }
    }

    fun delete(sessionId: String): Boolean = db.deleteConversation(sessionId)

    fun listSessions(limit: Int = 100, offset: Int = 0): List<ConversationSummary> =
        db.listConversations(limit, offset)

    fun messages(sessionId: String, limit: Int = MAX_HISTORY_MESSAGES): List<MessageEntity> =
        db.findConversation(sessionId)?.let { db.recentMessages(it.id, limit) } ?: emptyList()

    fun stats(): Map<String, Int> = mapOf(
        "conversations" to db.countConversations(),
        "messages" to db.countMessages(),
        "documents" to db.countDocuments(),
        "chunks" to db.countChunks()
    )

    companion object {
        const val MAX_HISTORY_MESSAGES = 50

        /**
         * ~6000 chars ≈ 1500 tokens of history, leaving room for the system
         * prompt, retrieved context and the response inside a small model's
         * window. Tune per model if you move off a 2B-class checkpoint.
         */
        const val DEFAULT_CHAR_BUDGET = 6000
    }
}
