package com.adabala.medha.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.adabala.medha.connect.CapturePolicy
import com.adabala.medha.connect.SavePolicy

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
class MedhaDatabase private constructor(context: Context, dbName: String) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

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
        createStore(db)
        createCaptures(db)
        createSaves(db)
    }

    /**
     * The capture log: raw notification observations, with a cursor.
     *
     * AUTOINCREMENT is load-bearing and not decoration. Without it SQLite
     * reuses the highest rowid after a delete, so pruning the oldest rows
     * would hand later inserts ids a client has already seen — and a
     * caller polling `?since=` would skip every one of them, silently and
     * forever. AUTOINCREMENT costs a row in sqlite_sequence and guarantees
     * the cursor only ever moves forward.
     */
    private fun createCaptures(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS captures(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                pkg TEXT NOT NULL,
                label TEXT NOT NULL,
                category TEXT,
                title TEXT NOT NULL,
                text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_captures_at ON captures(at)")
    }

    /**
     * The save outbox: things a person pressed Save on, waiting for
     * Quicksilver to take them.
     *
     * AUTOINCREMENT for the same reason as the capture log, and one more
     * besides: rows here are deleted on acknowledgement rather than by age,
     * so the table is emptied routinely. Reused ids after an empty would let
     * an ack for an old id delete a save that arrived afterwards.
     *
     * `fp` is UNIQUE, and that index IS the idempotence. The alternative --
     * checking for an existing row before inserting -- is a race with
     * itself: two taps a few milliseconds apart both read "absent" and both
     * insert. Letting SQLite refuse the second one is the only version that
     * is actually atomic.
     *
     * `truncated` records that the text was cut, so the agent can say so
     * instead of presenting part of a notification as the whole of it.
     */
    private fun createSaves(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saves(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                postedAt INTEGER NOT NULL,
                origin TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                truncated INTEGER NOT NULL DEFAULT 0,
                fp TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_saves_fp ON saves(fp)")
    }

    private fun createStore(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS kv(
                scopedKey TEXT PRIMARY KEY,
                clientId TEXT NOT NULL,
                value TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kv_client ON kv(clientId, scopedKey)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Additive migrations only. Never drop user memory.
        if (oldV < 6) {
            createSaves(db)
        }
        if (oldV < 5) {
            createCaptures(db)
        }
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
        if (oldV < 4) createStore(db)
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

    // ============================ key-value store ============================
    //
    // Generic per-client persistence. PWAs cannot rely on IndexedDB: browser
    // storage is quota-limited and EVICTABLE, so Android may silently drop it
    // under memory pressure. Derived data a PWA paid inference time to produce
    // must survive that, so it lives here instead.
    //
    // Keys arrive already namespaced by the caller (client.scope(key)), so
    // isolation is enforced before this layer ever sees them.

    fun kvPut(scopedKey: String, clientId: String, value: String) {
        val v = ContentValues().apply {
            put("scopedKey", scopedKey)
            put("clientId", clientId)
            put("value", value)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("kv", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Bulk upsert in one transaction — a 500-message batch is one commit. */
    fun kvPutAll(clientId: String, entries: List<Pair<String, String>>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for ((k, value) in entries) {
                kvPut(k, clientId, value)
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun kvGet(scopedKey: String): String? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE scopedKey=? LIMIT 1", arrayOf(scopedKey))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun kvDelete(scopedKey: String): Boolean =
        writableDatabase.delete("kv", "scopedKey=?", arrayOf(scopedKey)) > 0

    // ---------------------------------------------------------------- captures

    /** One observed notification. [id] is the cursor value. */
    data class Capture(
        val id: Long,
        val at: Long,
        val pkg: String,
        val label: String,
        val category: String?,
        val title: String,
        val text: String
    )

    /**
     * Records an observation and enforces both retention bounds.
     *
     * Pruning here rather than on a timer: an insert is the only moment the
     * log can grow, so it is the only moment it can need trimming, and a
     * separate schedule is one more thing that can quietly stop running.
     */
    fun captureAdd(
        at: Long,
        pkg: String,
        label: String,
        category: String?,
        title: String,
        text: String
    ): Long {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("at", at)
            put("pkg", pkg)
            put("label", label)
            put("category", category)
            put("title", title)
            put("text", text)
        }
        val id = db.insert("captures", null, v)
        prune(db, at)
        return id
    }

    private fun prune(db: SQLiteDatabase, now: Long) {
        db.delete("captures", "at < ?", arrayOf(CapturePolicy.cutoff(now).toString()))
        val count = simpleCount("captures")
        val over = CapturePolicy.overBy(count)
        if (over > 0) {
            // By id, not by `at`: ids are monotonic and `at` comes from the
            // posting app's clock, which can go backwards.
            db.execSQL(
                "DELETE FROM captures WHERE id IN " +
                    "(SELECT id FROM captures ORDER BY id ASC LIMIT ?)",
                arrayOf<Any>(over)
            )
        }
    }

    /** Observations after [since], oldest first, at most [limit]. */
    fun capturesSince(since: Long, limit: Int): List<Capture> =
        readableDatabase.rawQuery(
            "SELECT id, at, pkg, label, category, title, text FROM captures " +
                "WHERE id > ? ORDER BY id ASC LIMIT ?",
            arrayOf(since.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Capture(
                            c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                            if (c.isNull(4)) null else c.getString(4),
                            c.getString(5), c.getString(6)
                        )
                    )
                }
            }
        }

    /**
     * The highest id in the log, or 0 when it is empty.
     *
     * A caller that has consumed everything needs a cursor to resume from,
     * and the last row of an empty page cannot supply one.
     */
    fun capturesHead(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM captures", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * Empties the log, returning how many rows went.
     *
     * Called when no client holds the capture capability, which is the only
     * thing that justifies the log existing. Revocation has to delete what
     * was already collected, not merely stop collection: otherwise turning
     * it off leaves a fortnight of notification history on the device,
     * readable in full by whatever is granted the capability next.
     *
     * Not `DROP TABLE`: the schema stays so a later grant does not need a
     * migration, and the ids keep climbing from where they were, so a stale
     * cursor held by a revoked client cannot be replayed against new rows.
     */
    fun capturesClear(): Int = writableDatabase.delete("captures", null, null)

    // ------------------------------------------------------------------ saves

    /** One thing a person asked to keep. [id] is the cursor and the ack key. */
    data class Save(
        val id: Long,
        val at: Long,
        val postedAt: Long,
        val origin: String,
        val title: String,
        val text: String,
        val truncated: Boolean
    )

    /**
     * Records a save. Returns the new row id, or -1 when this exact save is
     * already waiting.
     *
     * CONFLICT_IGNORE rather than a read-then-write: two taps in the same
     * handful of milliseconds would both see an empty table. -1 is not an
     * error here, it is the answer to "did that do anything" -- the caller
     * uses it to say "Saved" or "Already saved" instead of claiming a second
     * save happened.
     */
    fun saveAdd(
        at: Long,
        postedAt: Long,
        origin: String,
        title: String,
        text: String,
        truncated: Boolean,
        fp: String
    ): Long {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("at", at)
            put("postedAt", postedAt)
            put("origin", origin)
            put("title", title)
            put("text", text)
            put("truncated", if (truncated) 1 else 0)
            put("fp", fp)
        }
        val id = db.insertWithOnConflict("saves", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) pruneSaves(db, at)
        return id
    }

    private fun pruneSaves(db: SQLiteDatabase, now: Long) {
        db.delete("saves", "at < ?", arrayOf(SavePolicy.cutoff(now).toString()))
        val over = SavePolicy.overBy(simpleCount("saves"))
        if (over > 0) {
            db.execSQL(
                "DELETE FROM saves WHERE id IN (SELECT id FROM saves ORDER BY id ASC LIMIT ?)",
                arrayOf<Any>(over)
            )
        }
    }

    /** Pending saves after [since], oldest first, at most [limit]. */
    fun savesSince(since: Long, limit: Int): List<Save> =
        readableDatabase.rawQuery(
            "SELECT id, at, postedAt, origin, title, text, truncated FROM saves " +
                "WHERE id > ? ORDER BY id ASC LIMIT ?",
            arrayOf(since.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Save(
                            c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3),
                            c.getString(4), c.getString(5), c.getInt(6) != 0
                        )
                    )
                }
            }
        }

    fun savesHead(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM saves", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun savesPending(): Int = simpleCount("saves")

    /**
     * Drops everything up to and including [through], once Quicksilver says
     * it has them.
     *
     * This is why delivery can be at-least-once without duplicating
     * anything: if the agent creates the notes and then dies before acking,
     * it reads the same rows again next time, and its own insert is keyed on
     * the save's fingerprint so the second attempt lands on the row it
     * already made. The guarantee lives on the consumer side, where it can
     * actually be enforced.
     */
    fun savesAck(through: Long): Int =
        writableDatabase.delete("saves", "id <= ?", arrayOf(through.toString()))

    fun kvDeleteByClient(clientId: String): Int =
        writableDatabase.delete("kv", "clientId=?", arrayOf(clientId))

    /** Keys under [prefix], newest first. [prefix] must already be scoped. */
    fun kvList(prefix: String, limit: Int, offset: Int): List<Triple<String, String, Long>> {
        val out = ArrayList<Triple<String, String, Long>>()
        readableDatabase.rawQuery(
            "SELECT scopedKey,value,updatedAt FROM kv WHERE scopedKey LIKE ? ESCAPE '\\' " +
                "ORDER BY updatedAt DESC LIMIT ? OFFSET ?",
            arrayOf(escapeLike(prefix) + "%", limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    fun kvCount(prefix: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM kv WHERE scopedKey LIKE ? ESCAPE '\\'",
            arrayOf(escapeLike(prefix) + "%")
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * LIKE treats % and _ as wildcards. Without escaping, a client could pass a
     * prefix of "%" and enumerate every other client's keys.
     */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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
        private const val DB_VERSION = 6

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
                INSTANCE ?: MedhaDatabase(context, DB_NAME).also { INSTANCE = it }
            }

        /**
         * Test-only construction path that bypasses the process-wide
         * [INSTANCE] cache. Instrumented tests exercise real on-disk SQLite
         * upgrade behavior (see `MedhaDatabaseMigrationTest`), and each test
         * needs its own independently-named database file rather than
         * sharing whatever instance [get] has already cached in that test
         * process — otherwise the second test to run would silently reuse
         * the first test's already-upgraded database instead of exercising
         * its own upgrade path.
         */
        @androidx.annotation.VisibleForTesting
        fun forTesting(context: Context, dbName: String): MedhaDatabase =
            MedhaDatabase(context, dbName)
    }
}
