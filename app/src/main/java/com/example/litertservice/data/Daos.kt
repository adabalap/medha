package com.example.litertservice.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findBySession(sessionId: String): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :ts, title = COALESCE(:title, title) WHERE id = :id")
    suspend fun touch(id: Long, ts: Long = System.currentTimeMillis(), title: String? = null)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ConversationEntity>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun recentForConversation(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface DocumentDao {
    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Insert
    suspend fun insertChunk(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunks WHERE collection = :collection")
    suspend fun chunksInCollection(collection: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE collection = :collection AND embedding IS NOT NULL")
    suspend fun embeddedChunks(collection: String): List<ChunkEntity>

    @Query("DELETE FROM documents WHERE collection = :collection")
    suspend fun clearCollectionDocs(collection: String)

    @Query("DELETE FROM chunks WHERE collection = :collection")
    suspend fun clearCollectionChunks(collection: String)

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun docCount(): Int

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun chunkCount(): Int
}
