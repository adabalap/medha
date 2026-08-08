package com.adabala.medha.connectors

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Read/write access to the device SMS store, exposed so PWAs can use it.
 *
 * A browser page cannot reach SMS at all: Android gates it behind `READ_SMS`,
 * which only a native app can hold. Putting the connector here means one APK
 * carries the sensitive permission instead of every app you build.
 *
 * ## Design decisions worth keeping
 *
 * - **Cursor pagination on timestamps, never page numbers.** New messages
 *   arrive mid-scan and shift every offset, which silently duplicates or skips
 *   rows during a backlog pass. `before`/`since` are stable.
 * - **Bodies are never copied into Medha's database.** The system provider is
 *   already the source of truth; a second copy is another thing to secure and
 *   to keep in sync. Clients store derived labels against message IDs in
 *   `/store`.
 * - **Read and send are separate capabilities.** Most consumers need neither
 *   or only read.
 * - **Contact names are resolved on demand and cached in memory only.** The
 *   address is the stable key; the display name is a convenience.
 */
class SmsConnector(private val appContext: Context) {

    data class Conversation(
        val threadId: Long,
        val address: String,
        val displayName: String?,
        val snippet: String,
        val messageCount: Int,
        val unreadCount: Int,
        val lastAt: Long
    )

    data class Message(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val read: Boolean,
        val inbound: Boolean
    )

    data class Status(
        val supported: Boolean,
        val canRead: Boolean,
        val canSend: Boolean,
        val isDefaultSmsApp: Boolean,
        val totalMessages: Int
    )

    private val nameCache = HashMap<String, String?>()

    // ------------------------------ status ------------------------------

    fun status(): Status {
        val canRead = has(Manifest.permission.READ_SMS)
        return Status(
            supported = smsSupported,
            canRead = canRead,
            canSend = has(Manifest.permission.SEND_SMS),
            isDefaultSmsApp = isDefaultSmsApp(),
            totalMessages = if (canRead) count() else -1
        )
    }

    fun canRead(): Boolean = has(Manifest.permission.READ_SMS)

    /**
     * True only if the permission is BOTH declared in the manifest and granted.
     *
     * In the "core" flavour the SMS permissions are not declared at all, so
     * checkSelfPermission returns DENIED permanently and a request would fail
     * silently. [isDeclared] lets the UI say "this build has no SMS support"
     * instead of showing a Grant button that can never succeed.
     */
    private fun has(p: String) =
        ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED

    fun isDeclared(permission: String): Boolean = runCatching {
        val pi = appContext.packageManager.getPackageInfo(
            appContext.packageName, PackageManager.GET_PERMISSIONS
        )
        pi.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    /** False on the core flavour: no SMS permissions in the manifest. */
    val smsSupported: Boolean get() = isDeclared(Manifest.permission.READ_SMS)

    private fun isDefaultSmsApp(): Boolean = runCatching {
        Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
    }.getOrDefault(false)

    private fun count(): Int = runCatching {
        resolver().query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, null)
            ?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun resolver(): ContentResolver = appContext.contentResolver

    // --------------------------- conversations ---------------------------

    fun conversations(limit: Int = 50, offset: Int = 0): List<Conversation> {
        if (!canRead()) return emptyList()
        val out = ArrayList<Conversation>()
        runCatching {
            // Telephony.Threads exposes per-thread aggregates directly, which
            // avoids scanning every message just to build a thread list.
            resolver().query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                null, null, null, "date DESC"
            )?.use { c ->
                var seen = 0
                while (c.moveToNext()) {
                    if (seen++ < offset) continue
                    if (out.size >= limit) break
                    val threadId = c.getLongOrZero("_id")
                    val recipientIds = c.getStringOr("recipient_ids", "")
                    val address = addressForThread(threadId, recipientIds)
                    out.add(
                        Conversation(
                            threadId = threadId,
                            address = address,
                            displayName = contactName(address),
                            snippet = c.getStringOr("snippet", ""),
                            messageCount = c.getIntOrZero("message_count"),
                            unreadCount = unreadCount(threadId),
                            lastAt = c.getLongOrZero("date")
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "conversations query failed", it) }
        return out
    }

    private fun addressForThread(threadId: Long, recipientIds: String): String {
        // The conversations view returns recipient ids, not numbers. Reading one
        // message from the thread is the cheapest reliable resolution.
        return runCatching {
            resolver().query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: recipientIds
    }

    private fun unreadCount(threadId: Long): Int = runCatching {
        resolver().query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id"),
            "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0",
            arrayOf(threadId.toString()), null
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    // ----------------------------- messages -----------------------------

    /**
     * Cursor-paginated messages.
     *
     * [before] and [since] are epoch millis. Page through a backlog by passing
     * the oldest `date` you received back as [before]; the window is stable
     * even while new messages arrive.
     */
    fun messages(
        threadId: Long? = null,
        since: Long? = null,
        before: Long? = null,
        unreadOnly: Boolean = false,
        limit: Int = 100
    ): List<Message> {
        if (!canRead()) return emptyList()
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        threadId?.let { where.append(" AND ${Telephony.Sms.THREAD_ID}=?"); args.add(it.toString()) }
        since?.let { where.append(" AND ${Telephony.Sms.DATE}>?"); args.add(it.toString()) }
        before?.let { where.append(" AND ${Telephony.Sms.DATE}<?"); args.add(it.toString()) }
        if (unreadOnly) where.append(" AND ${Telephony.Sms.READ}=0")

        val out = ArrayList<Message>()
        runCatching {
            resolver().query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE
                ),
                where.toString(), args.toTypedArray(),
                "${Telephony.Sms.DATE} DESC LIMIT ${limit.coerceIn(1, 500)}"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Message(
                            id = c.getLong(0),
                            threadId = c.getLong(1),
                            address = c.getString(2) ?: "",
                            body = c.getString(3) ?: "",
                            date = c.getLong(4),
                            read = c.getInt(5) == 1,
                            inbound = c.getInt(6) == Telephony.Sms.MESSAGE_TYPE_INBOX
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "messages query failed", it) }
        return out
    }

    fun message(id: Long): Message? {
        if (!canRead()) return null
        return runCatching {
            resolver().query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE
                ),
                "${Telephony.Sms._ID}=?", arrayOf(id.toString()), null
            )?.use { c ->
                if (!c.moveToFirst()) null else Message(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "",
                    c.getLong(4), c.getInt(5) == 1, c.getInt(6) == Telephony.Sms.MESSAGE_TYPE_INBOX
                )
            }
        }.getOrNull()
    }

    // ------------------------------ contacts ------------------------------

    fun contactName(address: String): String? {
        if (address.isBlank()) return null
        nameCache[address]?.let { return it }
        if (nameCache.containsKey(address)) return null
        if (!has(Manifest.permission.READ_CONTACTS)) {
            nameCache[address] = null
            return null
        }
        val name = runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
            )
            resolver().query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        nameCache[address] = name
        return name
    }

    // ------------------------------- writes -------------------------------

    fun markRead(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return runCatching {
            val v = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            val placeholders = ids.joinToString(",") { "?" }
            resolver().update(
                Telephony.Sms.CONTENT_URI, v,
                "${Telephony.Sms._ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray()
            )
        }.getOrDefault(0)
    }

    fun markThreadRead(threadId: Long): Int = runCatching {
        val v = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        resolver().update(
            Telephony.Sms.CONTENT_URI, v,
            "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())
        )
    }.getOrDefault(0)

    /**
     * Sends an SMS. Long bodies are divided by the platform — a naive
     * `sendTextMessage` silently truncates anything over one segment.
     */
    fun send(address: String, body: String): Result<Unit> {
        if (!has(Manifest.permission.SEND_SMS)) {
            return Result.failure(SecurityException("SEND_SMS permission not granted"))
        }
        if (address.isBlank() || body.isBlank()) {
            return Result.failure(IllegalArgumentException("address and body are required"))
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val sm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sm.divideMessage(body)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                sm.sendTextMessage(address, null, body, null, null)
            }
        }
    }

    // ------------------------------- events -------------------------------

    /**
     * Emits whenever the SMS store changes, so a PWA can hold one SSE
     * connection instead of polling a content provider over HTTP.
     *
     * A ContentObserver is used rather than an SMS_RECEIVED broadcast because
     * the broadcast requires being the default SMS app on modern Android, while
     * observing the provider only needs READ_SMS.
     */
    fun changes(): Flow<Long> = callbackFlow {
        if (!canRead()) {
            close(); return@callbackFlow
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(System.currentTimeMillis())
            }
        }
        resolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        awaitClose { runCatching { resolver().unregisterContentObserver(observer) } }
    }

    // ------------------------------ helpers ------------------------------

    private fun android.database.Cursor.getLongOrZero(col: String): Long {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getLong(i) else 0L
    }

    private fun android.database.Cursor.getIntOrZero(col: String): Int {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getInt(i) else 0
    }

    private fun android.database.Cursor.getStringOr(col: String, def: String): String {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getString(i) ?: def else def
    }

    companion object {
        private const val TAG = "SmsConnector"

        val READ_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
        )
    }
}
