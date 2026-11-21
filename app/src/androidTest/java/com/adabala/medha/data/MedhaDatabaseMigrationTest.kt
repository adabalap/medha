package com.adabala.medha.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the two paths [docs/PRODUCTION-READINESS.md] called out as the
 * highest-consequence untested code in the app: the v1 -> v4 on-disk SQLite
 * upgrade, and FTS4 staying in sync with the `chunks` table across insert and
 * delete. Neither can be verified without Android's bundled SQLite — that's
 * why this is `androidTest`, not a unit test.
 *
 * Run: ./gradlew connectedCoreDebugAndroidTest --tests "*MedhaDatabaseMigrationTest*"
 * (needs a connected device or a running emulator)
 *
 * NOT executed as part of the sandboxed review that produced this file: that
 * sandbox has no Android SDK, no emulator, and no device — a real SQLite
 * upgrade cannot be faked. Every assertion below was worked through by hand
 * against MedhaDatabase's actual onUpgrade implementation, cross-checked
 * against the exact old schema implied by its own migration comments. Treat
 * a first green run of this file, not this comment, as the real
 * confirmation — this is the same honesty this repo already applies to
 * InferenceSchedulerConcurrencyTest, for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class MedhaDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "medha_migration_test.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /**
     * Hand-builds exactly the v1 on-disk schema: no `chunks_fts`, no `kv`
     * table, embeddings stored as a TEXT column (not BLOB, no model/dim
     * tracking), matching what MedhaDatabase's class doc and its v2->v3
     * migration comment both say v1 actually looked like. `user_version` is
     * set to 1 so the real [MedhaDatabase] (declaring version 4) opens this
     * file via onUpgrade(db, 1, 4) exactly as it would for a real user's
     * years-old install, rather than treating it as a fresh install.
     */
    private fun buildV1Database(): Long {
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
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
                conversationId INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
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
        db.execSQL(
            """
            CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                documentId INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                collection TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "INSERT INTO conversations(sessionId,title,systemInstruction,createdAt,updatedAt) " +
                "VALUES ('sess-1','Old chat',NULL,1000,1000)"
        )
        val conversationId = db.rawQuery("SELECT id FROM conversations WHERE sessionId='sess-1'", null)
            .use { it.moveToFirst(); it.getLong(0) }
        db.execSQL(
            "INSERT INTO messages(conversationId,role,content,createdAt) VALUES (?, 'user', 'hello from v1', 1000)",
            arrayOf(conversationId)
        )
        db.execSQL(
            "INSERT INTO messages(conversationId,role,content,createdAt) VALUES (?, 'assistant', 'hi there', 1001)",
            arrayOf(conversationId)
        )

        db.execSQL(
            "INSERT INTO documents(collection,title,source,createdAt) VALUES ('notes','My Doc','manual',1000)"
        )
        val documentId = db.rawQuery("SELECT id FROM documents WHERE collection='notes'", null)
            .use { it.moveToFirst(); it.getLong(0) }
        // One chunk carries a v1-era embedding (comma-separated TEXT, no
        // known model) -- exactly the case the v2->v3 migration comment
        // describes discarding rather than guessing at.
        db.execSQL(
            "INSERT INTO chunks(documentId,collection,text,embedding,createdAt) " +
                "VALUES (?, 'notes', 'the quick brown fox jumps over the lazy dog', '0.1,0.2,0.3', 1000)",
            arrayOf(documentId)
        )
        db.execSQL(
            "INSERT INTO chunks(documentId,collection,text,embedding,createdAt) " +
                "VALUES (?, 'notes', 'a second unrelated sentence about spreadsheets', NULL, 1001)",
            arrayOf(documentId)
        )

        db.version = 1
        db.close()
        return conversationId
    }

    @Test
    fun upgrade_v1_to_v4_preserves_conversations_and_messages() {
        buildV1Database()

        val upgraded = MedhaDatabase.forTesting(context, dbName)
        assertEquals(1, upgraded.countConversations())
        assertEquals(2, upgraded.countMessages())

        val convo = upgraded.findConversation("sess-1")
        assertTrue("conversation from the v1 file must survive the upgrade", convo != null)
        assertEquals("Old chat", convo!!.title)

        val messages = upgraded.recentMessages(convo.id, 10)
        assertEquals(2, messages.size)
        assertEquals("hello from v1", messages[0].content)
        assertEquals("hi there", messages[1].content)
    }

    @Test
    fun upgrade_v1_to_v4_preserves_chunk_text_but_clears_unattributed_embeddings() {
        buildV1Database()
        val upgraded = MedhaDatabase.forTesting(context, dbName)

        assertEquals(
            "chunk rows themselves must not be dropped by the upgrade",
            2,
            upgraded.countChunks()
        )

        val chunks = upgraded.chunksInCollection("notes")
        assertEquals(2, chunks.size)
        val texts = chunks.map { it.text }.toSet()
        assertTrue(
            "chunk TEXT must be byte-for-byte preserved",
            texts.contains("the quick brown fox jumps over the lazy dog") &&
                texts.contains("a second unrelated sentence about spreadsheets")
        )

        // The v1 embedding was in an unknown, untracked space. Rather than
        // silently treating it as valid, the migration must discard it so a
        // stale vector never gets scored as if it were current.
        chunks.forEach { chunk ->
            assertNull(
                "a v1-era embedding with no known model must be cleared, not carried forward",
                chunk.embeddingModel
            )
            assertEquals(0, chunk.embeddingDim)
        }
    }

    @Test
    fun upgrade_v1_to_v4_backfills_fts_so_search_works_without_a_manual_reindex() {
        buildV1Database()
        val upgraded = MedhaDatabase.forTesting(context, dbName)

        assertTrue(
            "this device's SQLite must support FTS4 for the rest of this assertion to be meaningful",
            upgraded.hasFullTextIndex
        )

        val hits = upgraded.searchChunks("notes", listOf("spreadsheets"), limit = 10)
        assertEquals(
            "a chunk that existed before the upgrade must be findable via FTS " +
                "immediately after it, with no manual reindex step",
            1,
            hits.size
        )
        assertTrue(hits[0].text.contains("spreadsheets"))

        val noHits = upgraded.searchChunks("notes", listOf("nonexistentterm"), limit = 10)
        assertTrue(noHits.isEmpty())
    }

    @Test
    fun upgrade_v1_to_v4_creates_kv_table() {
        buildV1Database()
        val upgraded = MedhaDatabase.forTesting(context, dbName)

        // v1 had no kv table at all. If oldV<4 -> createStore(db) didn't run,
        // this throws instead of returning 0/null -- that distinction matters:
        // a missing table is a broken upgrade, not empty data.
        upgraded.kvPut("client-a:pref", "client-a", "1")
        assertEquals("1", upgraded.kvGet("client-a:pref"))
    }

    @Test
    fun insert_chunks_keeps_fts_in_sync() {
        val db = MedhaDatabase.forTesting(context, dbName)
        val docId = db.insertDocument("coll", "Doc", null)
        db.insertChunks(
            docId, "coll",
            listOf(
                MedhaDatabase.ChunkInsert(text = "alpha beta gamma"),
                MedhaDatabase.ChunkInsert(text = "delta epsilon zeta")
            )
        )

        assertEquals(1, db.searchChunks("coll", listOf("alpha"), 10).size)
        assertEquals(1, db.searchChunks("coll", listOf("epsilon"), 10).size)
        assertEquals(0, db.searchChunks("coll", listOf("omega"), 10).size)
    }

    @Test
    fun delete_collection_removes_matching_fts_rows_too() {
        val db = MedhaDatabase.forTesting(context, dbName)
        val docId = db.insertDocument("to-delete", "Doc", null)
        db.insertChunks(
            docId, "to-delete",
            listOf(MedhaDatabase.ChunkInsert(text = "unique searchable phrase"))
        )
        assertEquals(1, db.searchChunks("to-delete", listOf("searchable"), 10).size)

        db.deleteCollection("to-delete")

        assertEquals(0, db.countChunks())
        assertEquals(
            "an orphaned chunks_fts row for a deleted collection must not still match",
            0,
            db.searchChunks("to-delete", listOf("searchable"), 10).size
        )
    }
}
