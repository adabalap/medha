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
 * could read every session and every RAG collection — so one app's private
 * notes and indexed documents were fully readable by any other app you wrote,
 * and by anything that scraped the token from the bundled UI. On a device where
 * the whole point is that private data never leaves, that is the wrong default.
 *
 * ## Model
 *
 *     token  ->  Client { id, namespace, capabilities }
 *
 * A client may only touch sessions and collections under its own [namespace].
 * `journal` gets `journal:*`; `compose` gets `compose:*`; neither can see the
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
        val createdAt: Long,
        /** How this grant came to exist. One of the [Origin] constants. */
        val origin: String = Origin.MANUAL,
        /**
         * Epoch millis of the last request that authenticated with this token,
         * or 0 if never used. Deliberately coarse — see [touch].
         */
        val lastUsedAt: Long = 0L,
        /**
         * For a client paired from a browser: the canonical web origin it was
         * paired from, e.g. `https://notes.example.com`. Null for native
         * clients, which have no origin and are not restricted by one.
         *
         * Not the same thing as [origin], which is provenance. The unfortunate
         * name collision is kept because [origin] is already persisted in
         * every existing install's SharedPreferences and renaming it would
         * mean a migration for a cosmetic gain.
         *
         * Two things key off this:
         *  - CORS: a browser may only read Medha's replies from this origin.
         *  - Request binding: a request carrying this token but a *different*
         *    Origin header is refused. A token pasted into another site's
         *    page is therefore useless from a browser.
         */
        val webOrigin: String? = null
    ) {
        /** True if this credential belongs to a paired web app. */
        val isWebClient: Boolean get() = webOrigin != null

        val isAdmin: Boolean get() = capabilities.contains(Cap.ADMIN)

        /** True if [key] belongs to this client's namespace. */
        fun owns(key: String): Boolean =
            isAdmin || key == namespace || key.startsWith("$namespace:")

        /** Namespaces a bare key so clients cannot collide or peek. */
        fun scope(key: String): String =
            if (isAdmin || key.startsWith("$namespace:")) key else "$namespace:$key"

        fun can(cap: String): Boolean = isAdmin || capabilities.contains(cap)

        /** Strips the namespace so clients see the keys they actually sent. */
        fun unscope(key: String): String =
            if (!isAdmin && key.startsWith("$namespace:")) key.removePrefix("$namespace:") else key
    }

    /**
     * Where a grant came from. Surfaced in the client list because "an app
     * asked me for this" and "I made this for my own script" warrant different
     * scrutiny when the user is scanning the list deciding what to cut off.
     */
    object Origin {
        /** Created by the user in Medha's own UI. */
        const val MANUAL = "manual"

        /** Granted to another app through the consent flow. */
        const val APP = "app"

        /** The built-in admin client created on first run. */
        const val BOOTSTRAP = "bootstrap"

        /**
         * Paired with a browser-based app through a pairing code. Listed
         * separately from [APP] because the thing on the other end is a web
         * page, not an installed package: it was vouched for by the user
         * typing a code, not by the system reporting a caller.
         */
        const val WEB = "web"
    }

    /** Capability strings. Coarse on purpose — fine-grained scopes nobody sets correctly are theatre. */
    object Cap {
        const val ADMIN = "admin"
        const val GENERATE = "generate"   // /generate, /chat, /v1/chat/completions
        const val MEMORY = "memory"       // /sessions
        const val RAG = "rag"             // /rag/*, /v1/embeddings
        const val STORE = "store"         // /store/* key-value
        const val NOTIFY = "notify"       // post notifications / Live Updates

        val DEFAULT = setOf(GENERATE, MEMORY, RAG, STORE)
        val ALL = setOf(ADMIN, GENERATE, MEMORY, RAG, STORE, NOTIFY)
    }

    @Volatile private var cache: Map<String, Client> = emptyMap()

    /**
     * Serialises every read-modify-write on [cache].
     *
     * This guards a pre-existing race that was previously hard to hit: each
     * mutator reads `cache.values`, derives a new list, and calls
     * [persistLocked], which overwrites the whole set. Two concurrent mutators
     * could therefore drop each other's change. Until now every mutator was
     * UI-driven — a person tapping a button, essentially never concurrent —
     * so it never surfaced. [touch] runs on the request path, which makes
     * "a background request writes a timestamp while the user taps Revoke"
     * an ordinary Tuesday rather than a thought experiment, and losing the
     * revoke in that race would be a security bug.
     */
    private val writeLock = Any()

    init {
        reload()
        if (cache.isEmpty()) bootstrapAdmin()
    }

    fun resolve(token: String): Client? = cache[token]

    /**
     * Records that [token] was just used, at most once per
     * [TOUCH_THROTTLE_MS] per client.
     *
     * The throttle is the whole design. This is called from the auth
     * interceptor, so it runs on *every* authenticated request — and
     * [persist] serialises the entire client list to SharedPreferences. An
     * unthrottled version would add a full JSON encode plus a disk write to
     * the hot path of an inference server, to maintain a timestamp whose only
     * consumer is a human reading "last used 3 days ago". An hour of
     * granularity is far more precision than that decision needs.
     *
     * Returns immediately without locking in the common case, so a request
     * that isn't due for a write pays only a map lookup and a subtraction.
     */
    fun touch(token: String) {
        val now = System.currentTimeMillis()
        val current = cache[token] ?: return
        if (now - current.lastUsedAt < TOUCH_THROTTLE_MS) return
        synchronized(writeLock) {
            // Re-read inside the lock: another request may have won the race
            // and already written, in which case there is nothing to do.
            val fresh = cache[token] ?: return
            if (now - fresh.lastUsedAt < TOUCH_THROTTLE_MS) return
            persistLocked(cache.values.filter { it.token != token } + fresh.copy(lastUsedAt = now))
        }
    }

    /**
     * Every client, oldest first. For the UI's list.
     *
     * Do **not** call this from the request path: it sorts. Use
     * [unordered], which does not.
     */
    fun all(): List<Client> = cache.values.sortedBy { it.createdAt }

    /**
     * Every client in no particular order, with no allocation beyond the
     * view onto the backing map.
     *
     * Exists because token resolution runs on every single authenticated
     * request and used to go through [all], sorting the whole client list by
     * createdAt each time purely to throw the ordering away. Trivial at five
     * clients and pure waste at any number — an inference server should not
     * be sorting anything to answer "is this token valid".
     */
    fun unordered(): Collection<Client> = cache.values

    fun admin(): Client? = cache.values.firstOrNull { it.isAdmin }

    fun create(
        id: String,
        name: String,
        namespace: String,
        capabilities: Set<String>,
        origin: String = Origin.MANUAL,
        webOrigin: String? = null
    ): Client = synchronized(writeLock) {
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
            createdAt = System.currentTimeMillis(),
            origin = origin,
            webOrigin = webOrigin
        )
        persistLocked(cache.values + c)
        return c
    }

    fun revoke(id: String): Boolean = synchronized(writeLock) {
        val target = cache.values.firstOrNull { it.id == id } ?: return false
        // Refuse to remove the last admin: doing so locks the owner out of
        // their own service with no recovery short of clearing app data.
        if (target.isAdmin && cache.values.count { it.isAdmin } <= 1) return false
        persistLocked(cache.values.filter { it.id != id })
        return true
    }

    /**
     * Replaces a client's capability set, keeping its id, namespace and token.
     *
     * Needed because [Cap.NOTIFY] and [Cap.ADMIN] are not part of the default
     * grant -- most consumers should not have them -- but there has to be some
     * way to give them to the one that does. Without this, a client created
     * from the UI could never reach /notify and every call returned 403 with
     * no remedy anywhere in the app.
     */
    fun setCapabilities(id: String, capabilities: Set<String>): Client? = synchronized(writeLock) {
        val target = cache.values.firstOrNull { it.id == id } ?: return null
        // The last admin keeps admin: dropping it locks the owner out of their
        // own service with no recovery short of clearing app data.
        val caps = capabilities.filter { it in Cap.ALL }.toMutableSet()
        if (target.isAdmin && cache.values.count { it.isAdmin } <= 1) caps.add(Cap.ADMIN)
        val updated = target.copy(capabilities = caps)
        persistLocked(cache.values.filter { it.id != id } + updated)
        return updated
    }

    /** Rotates one client's token, leaving every other client working. */
    fun rotate(id: String): Client? = synchronized(writeLock) {
        val target = cache.values.firstOrNull { it.id == id } ?: return null
        val fresh = target.copy(token = newToken())
        persistLocked(cache.values.filter { it.id != id } + fresh)
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
            createdAt = System.currentTimeMillis(),
            origin = Origin.BOOTSTRAP
        )
        persistLocked(listOf(c))
    }

    private fun persistLocked(clients: Collection<Client>) {
        val arr = JSONArray()
        clients.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("namespace", c.namespace)
                put("capabilities", JSONArray(c.capabilities.toList()))
                put("token", c.token)
                put("createdAt", c.createdAt)
                put("origin", c.origin)
                put("lastUsedAt", c.lastUsedAt)
                // OMITTED when null, never written as JSONObject.NULL.
                //
                // org.json's optString(name, fallback) does not return the
                // fallback for a JSON null: opt() hands back the NULL
                // sentinel, which is not Java null, so the value stringifies
                // to the four characters "null". A native client would come
                // back from disk with webOrigin == "null", which is truthy,
                // and the origin-binding check in LocalServer would then
                // reject every request it made that carried any Origin
                // header. Omitting the key makes absent mean absent.
                if (c.webOrigin != null) put("webOrigin", c.webOrigin)
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
                // Pulled out with an explicit type rather than inlined into
                // the constructor call below: an inline `if` whose condition
                // involves an unresolved symbol makes the whole Client(...)
                // call un-inferable, which hides every other type error in
                // it behind generic-inference noise.
                // Blank covers both "key absent" and a value written blank;
                // see persistLocked for why it is never the string "null".
                val storedWebOrigin: String? = o.optString("webOrigin", "").ifBlank { null }
                out.add(
                    Client(
                        id = o.getString("id"),
                        name = o.optString("name", o.getString("id")),
                        namespace = o.optString("namespace", o.getString("id")),
                        capabilities = caps,
                        token = o.getString("token"),
                        createdAt = o.optLong("createdAt", 0),
                        // Grants written before origin existed are, by
                        // definition, ones the user made in the UI: the
                        // consent flow did not exist yet.
                        origin = o.optString("origin", Origin.MANUAL),
                        lastUsedAt = o.optLong("lastUsedAt", 0),
                        webOrigin = storedWebOrigin
                    )
                )
            }
        }
        cache = out.associateBy { it.token }
    }

    /**
     * Every canonical web origin that currently has a paired client.
     *
     * The CORS layer reads this on each request rather than caching it, so a
     * pairing completed thirty seconds ago works without a server restart —
     * and, more importantly, a *revoked* client stops being able to read
     * replies immediately rather than at the next restart.
     */
    fun pairedWebOrigins(): Set<String> =
        cache.values.mapNotNull { it.webOrigin }.toSet()

    /**
     * Creates a credential for a browser-based app, bound to [webOrigin].
     *
     * The id is derived from the origin's host rather than taken from the
     * caller: a web app naming itself is a web app the user cannot
     * distinguish from an impostor, and the host is the one thing about a
     * browser request that is not self-declared.
     */
    fun createForWeb(
        webOrigin: String,
        capabilities: Set<String>
    ): Client = synchronized(writeLock) {
        val host = com.adabala.medha.net.WebOrigin.hostOf(webOrigin)
            ?: throw IllegalArgumentException("not a usable origin: $webOrigin")

        // Reuse the existing grant for an origin instead of accumulating one
        // client per re-pair. Re-pairing is how a user recovers a lost token,
        // and it should rotate that origin's credential, not silently leave
        // the old one live.
        cache.values.firstOrNull { it.webOrigin == webOrigin }?.let { existing ->
            val rotated = existing.copy(
                token = newToken(),
                capabilities = capabilities.filter { it in Cap.ALL }.toSet()
            )
            persistLocked(cache.values.filter { it.id != existing.id } + rotated)
            return rotated
        }

        val base = com.adabala.medha.net.WebOrigin.slugForHost(host)
        var id = base
        var n = 2
        while (cache.values.any { it.id == id }) {
            id = "$base-$n"; n++
        }

        val c = Client(
            id = id,
            name = host,
            namespace = id,
            capabilities = capabilities.filter { it in Cap.ALL }.toSet(),
            token = newToken(),
            createdAt = System.currentTimeMillis(),
            origin = Origin.WEB,
            webOrigin = webOrigin
        )
        persistLocked(cache.values + c)
        return c
    }

    companion object {
        /** See [touch]. One hour is far finer than "last used" needs to be. */
        private const val TOUCH_THROTTLE_MS = 60 * 60 * 1000L


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
