package com.adabala.medha.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Per-consumer credentials.
 *
 * ## The problem this replaces
 *
 * Medha shipped with one token that granted everything. Any PWA holding it
 * could read every session and every RAG collection — so an SMS organizer's
 * message archive was fully readable by any other app you wrote, and by
 * anything that scraped the token from the bundled UI. On a device where the
 * whole point is that private data never leaves, that is the wrong default.
 *
 * ## Model
 *
 *     token  ->  Client { id, namespace, capabilities }
 *
 * A client may only touch sessions and collections under its own [namespace].
 * `sms-organizer` gets `sms:*`; `compose` gets `compose:*`; neither can see the
 * other. Enforcement is server-side on every request — the namespace is derived
 * from the token, never taken from the request body, so a client cannot ask for
 * someone else's prefix.
 *
 * The admin client (created on first run, shown in the app UI) keeps full
 * access so the dashboard and the bundled demo keep working.
 */
class ClientRegistry private constructor(private val prefs: SharedPreferences) {

    data class Client(
        val id: String,
        val name: String,
        val namespace: String,
        val capabilities: Set<String>,
        val token: String,
        val createdAt: Long
    ) {
        val isAdmin: Boolean get() = capabilities.contains(Cap.ADMIN)

        /** True if [key] belongs to this client's namespace. */
        fun owns(key: String): Boolean =
            isAdmin || key == namespace || key.startsWith("$namespace:")

        /** Namespaces a bare key so clients cannot collide or peek. */
        fun scope(key: String): String =
            if (isAdmin || key.startsWith("$namespace:")) key else "$namespace:$key"

        fun can(cap: String): Boolean = isAdmin || capabilities.contains(cap)
    }

    /** Capability strings. Coarse on purpose — fine-grained scopes nobody sets correctly are theatre. */
    object Cap {
        const val ADMIN = "admin"
        const val GENERATE = "generate"   // /generate, /chat, /v1/chat/completions
        const val MEMORY = "memory"       // /sessions
        const val RAG = "rag"             // /rag/*, /v1/embeddings
        const val STORE = "store"         // /store/* key-value
        const val SMS_READ = "sms.read"
        const val SMS_SEND = "sms.send"
        const val NOTIFY = "notify"       // post notifications / Live Updates

        val DEFAULT = setOf(GENERATE, MEMORY, RAG, STORE)
        val ALL = setOf(ADMIN, GENERATE, MEMORY, RAG, STORE, SMS_READ, SMS_SEND, NOTIFY)
    }

    @Volatile private var cache: Map<String, Client> = emptyMap()

    init {
        reload()
        if (cache.isEmpty()) bootstrapAdmin()
    }

    fun resolve(token: String): Client? = cache[token]

    fun all(): List<Client> = cache.values.sortedBy { it.createdAt }

    fun admin(): Client? = cache.values.firstOrNull { it.isAdmin }

    fun create(
        id: String,
        name: String,
        namespace: String,
        capabilities: Set<String>
    ): Client {
        require(id.isNotBlank() && ID_RE.matches(id)) {
            "client id must match ${ID_RE.pattern}"
        }
        require(namespace.isNotBlank() && ID_RE.matches(namespace)) {
            "namespace must match ${ID_RE.pattern}"
        }
        require(cache.values.none { it.id == id }) { "client '$id' already exists" }

        val c = Client(
            id = id,
            name = name.ifBlank { id },
            namespace = namespace,
            capabilities = capabilities.filter { it in Cap.ALL }.toSet(),
            token = newToken(),
            createdAt = System.currentTimeMillis()
        )
        persist(cache.values + c)
        return c
    }

    fun revoke(id: String): Boolean {
        val target = cache.values.firstOrNull { it.id == id } ?: return false
        // Refuse to remove the last admin: doing so locks the owner out of
        // their own service with no recovery short of clearing app data.
        if (target.isAdmin && cache.values.count { it.isAdmin } <= 1) return false
        persist(cache.values.filter { it.id != id })
        return true
    }

    /** Rotates one client's token, leaving every other client working. */
    fun rotate(id: String): Client? {
        val target = cache.values.firstOrNull { it.id == id } ?: return null
        val fresh = target.copy(token = newToken())
        persist(cache.values.filter { it.id != id } + fresh)
        return fresh
    }

    // ------------------------------ storage ------------------------------

    private fun bootstrapAdmin() {
        // Migrate the legacy single token if one exists, so upgrading does not
        // silently invalidate a token the user already pasted into a client.
        val legacy = prefs.getString(LEGACY_TOKEN_KEY, null)?.takeIf { it.length >= 16 }
        val c = Client(
            id = "admin",
            name = "Medha app",
            namespace = "admin",
            capabilities = Cap.ALL,
            token = legacy ?: newToken(),
            createdAt = System.currentTimeMillis()
        )
        persist(listOf(c))
    }

    private fun persist(clients: Collection<Client>) {
        val arr = JSONArray()
        clients.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("namespace", c.namespace)
                put("capabilities", JSONArray(c.capabilities.toList()))
                put("token", c.token)
                put("createdAt", c.createdAt)
            })
        }
        prefs.edit().putString(CLIENTS_KEY, arr.toString()).apply()
        cache = clients.associateBy { it.token }
    }

    private fun reload() {
        val raw = prefs.getString(CLIENTS_KEY, null) ?: return
        val out = mutableListOf<Client>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val caps = mutableSetOf<String>()
                val ca = o.optJSONArray("capabilities")
                if (ca != null) for (j in 0 until ca.length()) caps.add(ca.getString(j))
                out.add(
                    Client(
                        id = o.getString("id"),
                        name = o.optString("name", o.getString("id")),
                        namespace = o.optString("namespace", o.getString("id")),
                        capabilities = caps,
                        token = o.getString("token"),
                        createdAt = o.optLong("createdAt", 0)
                    )
                )
            }
        }
        cache = out.associateBy { it.token }
    }

    companion object {
        private const val CLIENTS_KEY = "clients_v1"
        private const val LEGACY_TOKEN_KEY = "api_token"
        private val ID_RE = Regex("[a-z0-9][a-z0-9_-]{1,31}")

        fun newToken(): String {
            val b = ByteArray(20)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        @Volatile private var INSTANCE: ClientRegistry? = null

        fun get(context: Context): ClientRegistry =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClientRegistry(
                    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                ).also { INSTANCE = it }
            }
    }
}
