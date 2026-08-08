package com.adabala.medha.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Medha's long-term memory. Plain SQLite, hand-written SQL.
 *
 * Why not Room: Room requires an annotation processor (KSP), whose version is
 * hard-pinned to the Kotlin compiler version. Every Kotlin bump then becomes a
 * CI break. This store is small enough that hand-written SQL is cheaper to own.
 *
 * Schema v2:
 *   conversations(id, sessionId UNIQUE, title, systemInstruction, createdAt, updatedAt)
 *   messages(id, conversationId -> conversations.id CASCADE, role, content, createdAt)
 *   documents(id, collection, title, source, createdAt)
 *   chunks(id, documentId -> documents.id CASCADE, collection, text, embedding, createdAt)
 *   chunks_fts  FTS4 virtual table, docid == chunks.id, kept in sync on write
 *
 * v1 -> v2 is a real migration: it adds the FTS index and backfills it. It does
 * NOT drop user data. (v1 dropped every table on upgrade, silently destroying
 * all stored conversations on any schema bump.)
 */
class MedhaDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    @Volatile private var ftsAvailable: Boolean = true

    /** False if this device's SQLite build refused to create the FTS4 table. */
    val hasFullTextIndex: Boolean get() = ftsAvailable

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // WAL lets the dashboard poll /metrics while a RAG ingest is writing.
        runCatching { db.enableWriteAheadLogging() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT UNIQUE NOT NULL,
                title TEXT,
                systemInstruction TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversationId INTEGER NOT NULL
                    REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conversationId, id)")

        db.execSQL(
            """
            CREATE TABLE documents(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection TEXT NOT NULL,
                title TEXT,
                source TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_doc_coll ON documents(collection)")

        db.execSQL(
            """
            CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                documentId INTEGER NOT NULL
                    REFERENCES documents(id) ON DELETE CASCADE,
                collection TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding BLOB,
                embeddingModel TEXT,
                embeddingDim INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_chunk_coll ON chunks(collection)")
        db.execSQL("CREATE INDEX idx_chunk_emb ON chunks(collection, embeddingModel)")
        db.execSQL("CREATE INDEX idx_chunk_doc ON chunks(documentId)")

        createFts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Additive migrations only. Never drop user memory.
        if (oldV < 2) {
            createFts(db)
            if (ftsAvailable) {
                runCatching {
                    db.execSQL("INSERT INTO chunks_fts(docid, text) SELECT id, text FROM chunks")
                }
            }
        }
        if (oldV < 3) {
            // v2 stored embeddings as a comma-separated TEXT column and had no
            // record of which model produced them. Rather than guess at the
            // provenance of existing vectors, the column is rebuilt empty and
            // the chunks are left for POST /rag/reindex to re-embed. Chunk TEXT
            // is preserved, so nothing the user ingested is lost -- only the
            // vectors, which were unusable without a known embedding space.
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE chunks RENAME TO chunks_v2")
                db.execSQL(
                    """
                    CREATE TABLE chunks(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        documentId INTEGER NOT NULL
                            REFERENCES documents(id) ON DELETE CASCADE,
                        collection TEXT NOT NULL,
                        text TEXT NOT NULL,
                        embedding BLOB,
                        embeddingModel TEXT,
                        embeddingDim INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO chunks(id, documentId, collection, text, createdAt)
                    SELECT id, documentId, collection, text, createdAt FROM chunks_v2
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE chunks_v2")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_coll ON chunks(collection)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_doc ON chunks(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_emb ON chunks(collection, embeddingModel)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Tolerate a downgraded APK rather than crashing on open.
    }

    /**
     * FTS4, not FTS5: FTS4 is present in Android's bundled SQLite on every API
     * level this app supports (minSdk 27). FTS5 availability is less consistent
     * across OEM builds, and a MATCH against a missing module fails at query
     * time, not build time.
     */
    private fun createFts(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(text, tokenize=porter)"
            )
            ftsAvailable = true
        }.onFailure { ftsAvailable = false }
    }

    // ============================ conversations ============================

    fun findConversation(sessionId: String): ConversationEntity? =
        readableDatabase.rawQuery("$CONV_COLS WHERE sessionId=? LIMIT 1", arrayOf(sessionId))
            .use { c -> if (c.moveToFirst()) c.toConversation() else null }

    fun insertConversation(sessionId: String, systemInstruction: String?): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("sessionId", sessionId)
            put("systemInstruction", systemInstruction)
            put("createdAt", now)
            put("updatedAt", now)
        }
        // CONFLICT_IGNORE: two PWAs racing on the same sessionId must not crash.
        return writableDatabase.insertWithOnConflict(
            "conversations", null, v, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun touchConversation(id: Long, title: String?) {
        val v = ContentValues().apply {
            put("updatedAt", System.currentTimeMillis())
            if (title != null) put("title", title)
        }
        writableDatabase.update("conversations", v, "id=?", arrayOf(id.toString()))
    }

    fun listConversations(limit: Int = 100, offset: Int = 0): List<ConversationSummary> {
        val out = ArrayList<ConversationSummary>()
        readableDatabase.rawQuery(
            """
            SELECT c.sessionId, c.title, COUNT(m.id), c.updatedAt
            FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id
            GROUP BY c.id ORDER BY c.updatedAt DESC LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ConversationSummary(
                        c.getString(0), c.getStringOrNull(1), c.getInt(2), c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    /** Deletes the conversation and (via CASCADE) all of its messages. */
    fun deleteConversation(sessionId: String): Boolean =
        writableDatabase.delete("conversations", "sessionId=?", arrayOf(sessionId)) > 0

    fun countConversations(): Int = simpleCount("conversations")

    // ============================== messages ==============================

    fun insertMessage(conversationId: Long, role: String, content: String): Long {
        val v = ContentValues().apply {
            put("conversationId", conversationId)
            put("role", role)
            put("content", content)
            put("createdAt", System.currentTimeMillis())
        }
        return writableDatabase.insert("messages", null, v)
    }

    /** Writes the user turn and the assistant turn atomically. */
    fun insertTurn(conversationId: Long, userText: String, assistantText: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertMessage(conversationId, "user", userText)
            insertMessage(conversationId, "assistant", assistantText)
            touchConversation(conversationId, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Most recent [limit] messages, returned oldest-first (ready for prompting). */
    fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity> {
        val out = ArrayList<MessageEntity>()
        readableDatabase.rawQuery(
            "$MSG_COLS WHERE conversationId=? ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.toMessage()) }
        out.reverse()
        return out
    }

    fun clearMessages(conversationId: Long) {
        writableDatabase.delete("messages", "conversationId=?", arrayOf(conversationId.toString()))
    }

    fun countMessages(): Int = simpleCount("messages")

    // ========================= documents / chunks =========================

    fun insertDocument(collection: String, title: String?, source: String?): Long {
        val v = ContentValues().apply {
            put("collection", collection)
            put("title", title)
            put("source", source)
            put("createdAt", System.currentTimeMillis())
        }
        return writableDatabase.insert("documents", null, v)
    }

    /**
     * Inserts all chunks of a document in one transaction, keeping FTS in sync.
     * [chunks] is a list of (text, encodedEmbedding-or-null).
     */
    fun insertChunks(
        documentId: Long,
        collection: String,
        chunks: List<ChunkInsert>
    ): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (row in chunks) {
                val text = row.text
                val v = ContentValues().apply {
                    put("documentId", documentId)
                    put("collection", collection)
                    put("text", text)
                    put("embedding", row.embedding)
                    put("embeddingModel", row.embeddingModel)
                    put("embeddingDim", row.embeddingDim)
                    put("createdAt", System.currentTimeMillis())
                }
                val id = db.insert("chunks", null, v)
                if (id > 0) {
                    n++
                    if (ftsAvailable) {
                        val fv = ContentValues().apply {
                            put("docid", id)
                            put("text", text)
                        }
                        runCatching { db.insert("chunks_fts", null, fv) }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /**
     * Full-text shortlist. Returns at most [limit] candidate chunks whose text
     * matches any of [terms]. Callers re-rank in Kotlin.
     *
     * This is the memory fix: the previous implementation loaded EVERY chunk in
     * the collection into a List before scoring, which is an OOM waiting to
     * happen once a PWA ingests a real corpus.
     */
    fun searchChunks(collection: String, terms: List<String>, limit: Int): List<ChunkEntity> {
        if (!ftsAvailable || terms.isEmpty()) return emptyList()
        // Sanitise: FTS MATCH has its own grammar, so only pass bare tokens.
        val safe = terms.mapNotNull { t ->
            val cleaned = t.filter { ch -> ch.isLetterOrDigit() }
            if (cleaned.length > 2) cleaned else null
        }.distinct()
        if (safe.isEmpty()) return emptyList()
        val match = safe.joinToString(" OR ")
        return runCatching {
            queryChunks(
                """
                SELECT c.id, c.documentId, c.collection, c.text, c.embedding, c.createdAt
                FROM chunks_fts f JOIN chunks c ON c.id = f.docid
                WHERE chunks_fts MATCH ? AND c.collection = ? LIMIT ?
                """.trimIndent(),
                arrayOf(match, collection, limit.toString())
            )
        }.getOrDefault(emptyList())
    }

    /** Bounded fallback scan for when FTS is unavailable or matched nothing. */
    fun chunksInCollection(collection: String, limit: Int = MAX_SCAN): List<ChunkEntity> =
        queryChunks(
            "$CHUNK_COLS WHERE collection=? ORDER BY id DESC LIMIT ?",
            arrayOf(collection, limit.toString())
        )

    /**
     * Chunks carrying a vector from *this exact* embedding space.
     *
     * The `embeddingModel = ?` predicate is the important part. Without it, a
     * model swap or a Matryoshka dimension change would leave old vectors in
     * the table that still decode fine and still produce a similarity score --
     * a plausible-looking number computed in the wrong space. Scoping the query
     * to the active model means stale vectors are simply invisible until
     * reindexed.
     */
    fun vectorCandidates(
        collection: String,
        embeddingModel: String,
        limit: Int = MAX_SCAN
    ): List<ChunkEntity> = queryChunks(
        "$CHUNK_COLS WHERE collection=? AND embeddingModel=? AND embedding IS NOT NULL " +
            "ORDER BY id DESC LIMIT ?",
        arrayOf(collection, embeddingModel, limit.toString())
    )

    /** Chunks in [collection] that still need a vector for [embeddingModel]. */
    fun chunksNeedingEmbedding(
        collection: String?,
        embeddingModel: String,
        limit: Int
    ): List<ChunkEntity> {
        val where = if (collection == null) "" else "collection=? AND "
        val args = if (collection == null) {
            arrayOf(embeddingModel, limit.toString())
        } else {
            arrayOf(collection, embeddingModel, limit.toString())
        }
        return queryChunks(
            "$CHUNK_COLS WHERE $where(embeddingModel IS NULL OR embeddingModel<>?) ORDER BY id LIMIT ?",
            args
        )
    }

    /** Attaches a freshly computed vector to an existing chunk. */
    fun updateChunkEmbedding(
        chunkId: Long,
        embedding: ByteArray,
        embeddingModel: String,
        embeddingDim: Int
    ) {
        val v = ContentValues().apply {
            put("embedding", embedding)
            put("embeddingModel", embeddingModel)
            put("embeddingDim", embeddingDim)
        }
        writableDatabase.update("chunks", v, "id=?", arrayOf(chunkId.toString()))
    }

    fun countEmbedded(embeddingModel: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chunks WHERE embeddingModel=?", arrayOf(embeddingModel)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun listCollections(): List<CollectionSummary> {
        val out = ArrayList<CollectionSummary>()
        readableDatabase.rawQuery(
            """
            SELECT d.collection, COUNT(DISTINCT d.id), COUNT(c.id),
                   SUM(CASE WHEN c.embedding IS NOT NULL THEN 1 ELSE 0 END)
            FROM documents d LEFT JOIN chunks c ON c.documentId = d.id
            GROUP BY d.collection ORDER BY d.collection
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(CollectionSummary(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3)))
            }
        }
        return out
    }

    fun deleteCollection(collection: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (ftsAvailable) {
                runCatching {
                    db.execSQL(
                        "DELETE FROM chunks_fts WHERE docid IN (SELECT id FROM chunks WHERE collection=?)",
                        arrayOf<Any>(collection)
                    )
                }
            }
            val n = db.delete("documents", "collection=?", arrayOf(collection))
            db.delete("chunks", "collection=?", arrayOf(collection))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    fun countDocuments(): Int = simpleCount("documents")
    fun countChunks(): Int = simpleCount("chunks")

    /** Approximate on-disk size, for the /system panel. */
    fun sizeBytes(context: Context): Long =
        runCatching { context.getDatabasePath(DB_NAME).length() }.getOrDefault(0L)

    // =============================== helpers ===============================

    private fun queryChunks(sql: String, args: Array<String>): List<ChunkEntity> {
        val out = ArrayList<ChunkEntity>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(c.toChunk()) }
        return out
    }

    private fun simpleCount(table: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toConversation() = ConversationEntity(
        id = getLong(0),
        sessionId = getString(1),
        title = getStringOrNull(2),
        systemInstruction = getStringOrNull(3),
        createdAt = getLong(4),
        updatedAt = getLong(5)
    )

    private fun Cursor.toMessage() = MessageEntity(
        id = getLong(0),
        conversationId = getLong(1),
        role = getString(2),
        content = getString(3),
        createdAt = getLong(4)
    )

    private fun Cursor.toChunk() = ChunkEntity(
        id = getLong(0),
        documentId = getLong(1),
        collection = getString(2),
        text = getString(3),
        embedding = if (isNull(4)) null else getBlob(4),
        embeddingModel = getStringOrNull(5),
        embeddingDim = getInt(6),
        createdAt = getLong(7)
    )

    /** One row to insert, with an optional pre-computed vector. */
    data class ChunkInsert(
        val text: String,
        val embedding: ByteArray? = null,
        val embeddingModel: String? = null,
        val embeddingDim: Int = 0
    )

    companion object {
        private const val DB_NAME = "medha.db"
        private const val DB_VERSION = 3

        /** Hard cap on rows pulled into memory by any single fallback scan. */
        const val MAX_SCAN = 2000

        private const val CONV_COLS =
            "SELECT id,sessionId,title,systemInstruction,createdAt,updatedAt FROM conversations"
        private const val MSG_COLS =
            "SELECT id,conversationId,role,content,createdAt FROM messages"
        private const val CHUNK_COLS =
            "SELECT id,documentId,collection,text,embedding,embeddingModel,embeddingDim,createdAt FROM chunks"

        @Volatile private var INSTANCE: MedhaDatabase? = null

        fun get(context: Context): MedhaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MedhaDatabase(context).also { INSTANCE = it }
            }
    }
}
