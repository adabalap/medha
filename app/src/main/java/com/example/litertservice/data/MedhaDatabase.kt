package com.example.litertservice.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite store for Medha's long-term memory. Deliberately no Room/KSP —
 * hand-written SQL keeps the build free of annotation-processor version coupling.
 *
 * Tables:
 *   conversations(id, sessionId UNIQUE, title, systemInstruction, createdAt, updatedAt)
 *   messages(id, conversationId, role, content, createdAt)
 *   documents(id, collection, title, source, createdAt)
 *   chunks(id, documentId, collection, text, embedding, createdAt)
 */
class MedhaDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "medha.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT UNIQUE NOT NULL,
                title TEXT,
                systemInstruction TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("""
            CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversationId INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conversationId)")
        db.execSQL("""
            CREATE TABLE documents(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection TEXT NOT NULL,
                title TEXT,
                source TEXT,
                createdAt INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("""
            CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                documentId INTEGER NOT NULL,
                collection TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding TEXT,
                createdAt INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("CREATE INDEX idx_chunk_coll ON chunks(collection)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS documents")
        db.execSQL("DROP TABLE IF EXISTS chunks")
        onCreate(db)
    }

    // ---------- conversations ----------
    fun findConversation(sessionId: String): ConversationEntity? =
        readableDatabase.rawQuery(
            "SELECT id,sessionId,title,systemInstruction,createdAt,updatedAt FROM conversations WHERE sessionId=? LIMIT 1",
            arrayOf(sessionId)
        ).use { c ->
            if (c.moveToFirst()) ConversationEntity(
                id = c.getLong(0), sessionId = c.getString(1),
                title = c.getStringOrNull(2), systemInstruction = c.getStringOrNull(3),
                createdAt = c.getLong(4), updatedAt = c.getLong(5)
            ) else null
        }

    fun insertConversation(sessionId: String, systemInstruction: String?): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("sessionId", sessionId); put("systemInstruction", systemInstruction)
            put("createdAt", now); put("updatedAt", now)
        }
        return writableDatabase.insert("conversations", null, v)
    }

    fun touchConversation(id: Long, title: String?) {
        val v = ContentValues().apply {
            put("updatedAt", System.currentTimeMillis())
            if (title != null) put("title", title)
        }
        writableDatabase.update("conversations", v, "id=?", arrayOf(id.toString()))
    }

    fun countConversations(): Int = simpleCount("conversations")

    // ---------- messages ----------
    fun insertMessage(conversationId: Long, role: String, content: String): Long {
        val v = ContentValues().apply {
            put("conversationId", conversationId); put("role", role)
            put("content", content); put("createdAt", System.currentTimeMillis())
        }
        return writableDatabase.insert("messages", null, v)
    }

    fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity> {
        val out = ArrayList<MessageEntity>()
        readableDatabase.rawQuery(
            "SELECT id,conversationId,role,content,createdAt FROM messages WHERE conversationId=? ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                MessageEntity(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
            )
        }
        return out
    }

    fun clearMessages(conversationId: Long) {
        writableDatabase.delete("messages", "conversationId=?", arrayOf(conversationId.toString()))
    }

    fun countMessages(): Int = simpleCount("messages")

    // ---------- documents / chunks ----------
    fun insertDocument(collection: String, title: String?, source: String?): Long {
        val v = ContentValues().apply {
            put("collection", collection); put("title", title); put("source", source)
            put("createdAt", System.currentTimeMillis())
        }
        return writableDatabase.insert("documents", null, v)
    }

    fun insertChunk(documentId: Long, collection: String, text: String, embedding: String?): Long {
        val v = ContentValues().apply {
            put("documentId", documentId); put("collection", collection)
            put("text", text); put("embedding", embedding)
            put("createdAt", System.currentTimeMillis())
        }
        return writableDatabase.insert("chunks", null, v)
    }

    fun chunksInCollection(collection: String): List<ChunkEntity> =
        queryChunks("SELECT id,documentId,collection,text,embedding,createdAt FROM chunks WHERE collection=?", arrayOf(collection))

    fun embeddedChunks(collection: String): List<ChunkEntity> =
        queryChunks("SELECT id,documentId,collection,text,embedding,createdAt FROM chunks WHERE collection=? AND embedding IS NOT NULL", arrayOf(collection))

    fun countDocuments(): Int = simpleCount("documents")
    fun countChunks(): Int = simpleCount("chunks")

    // ---------- helpers ----------
    private fun queryChunks(sql: String, args: Array<String>): List<ChunkEntity> {
        val out = ArrayList<ChunkEntity>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(
                ChunkEntity(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getStringOrNull(4), c.getLong(5))
            )
        }
        return out
    }

    private fun simpleCount(table: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? =
        if (isNull(i)) null else getString(i)

    companion object {
        @Volatile private var INSTANCE: MedhaDatabase? = null
        fun get(context: Context): MedhaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MedhaDatabase(context).also { INSTANCE = it }
            }
    }
}
