package com.adabala.medha.net

/**
 * Parsing and comparison of HTTP `Origin` header values.
 *
 * Pure Kotlin on purpose: no Android, no Ktor. The whole access decision for
 * browser clients turns on comparing two origin strings, and a bug here is a
 * cross-origin hole rather than a cosmetic glitch, so this has to be testable
 * without a device. See tools/tests/WebOriginTest.kt.
 *
 * ## What an origin is, and what it is not
 *
 * An origin is exactly `scheme://host[:port]`. It carries no path, no query,
 * no fragment, no userinfo, and no trailing slash. Browsers send it in that
 * form, but code that *stores* an origin usually got it from a human typing a
 * URL, so [canonical] accepts a URL and reduces it.
 *
 * Two rules here are load-bearing:
 *
 *  - **`Origin: null` is rejected.** Sandboxed iframes, `file://` pages and
 *    some redirect chains all send the literal string `null`. Treating it as
 *    an origin would let any sandboxed frame on any site inherit a pairing.
 *    It is a string, not an absent header, and it must never match anything.
 *
 *  - **The default port is dropped.** `https://x.com` and `https://x.com:443`
 *    are the same origin, and a browser sends the former. Storing the latter
 *    and comparing literally would silently never match.
 */
object WebOrigin {

    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")

    /** Ports that are implied by the scheme and therefore omitted. */
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ws" to 80, "wss" to 443)

    /**
     * Reduces [raw] to a canonical origin, or returns null if it is not one.
     *
     * Accepts a full URL and discards everything after the authority, so a
     * user who pastes `https://notes.example.com/app/?x=1` gets the origin
     * their browser will actually send.
     */
    fun canonical(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // The literal "null" origin. Never matchable. See the class doc.
        if (s.equals("null", ignoreCase = true)) return null

        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = s.substring(0, schemeEnd).lowercase()
        if (!SCHEME_RE.matches(scheme)) return null

        var rest = s.substring(schemeEnd + 3)
        if (rest.isEmpty()) return null

        // Cut path / query / fragment.
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        if (rest.isEmpty()) return null

        // Reject userinfo outright rather than stripping it. A string with
        // credentials in it is not something a browser ever sends as an
        // Origin, so its presence means the caller is confused and the safe
        // answer is "not an origin".
        if (rest.contains('@')) return null

        val (hostRaw, portRaw) = splitHostPort(rest) ?: return null
        val host = hostRaw.lowercase()
        if (host.isEmpty()) return null
        if (host.any { it.isWhitespace() }) return null

        val port = when {
            portRaw == null -> null
            else -> portRaw.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }

        val effective = if (port != null && port == DEFAULT_PORTS[scheme]) null else port
        return if (effective == null) "$scheme://$host" else "$scheme://$host:$effective"
    }

    /**
     * Splits an authority into host and port, handling bracketed IPv6.
     * Returns null if the shape is not a valid authority.
     */
    private fun splitHostPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            return when {
                tail.isEmpty() -> host to null
                tail.startsWith(":") -> host to tail.substring(1)
                else -> null
            }
        }
        // An unbracketed colon is a port separator; more than one means the
        // caller passed a bare IPv6 address, which is not a valid authority.
        val colons = authority.count { it == ':' }
        return when (colons) {
            0 -> authority to null
            1 -> {
                val i = authority.indexOf(':')
                authority.substring(0, i) to authority.substring(i + 1)
            }
            else -> null
        }
    }

    /**
     * True if [origin] names this device.
     *
     * Only exact loopback literals count. A hostname that merely *resolves* to
     * 127.0.0.1 does not, because the browser sends the name it was given and
     * an attacker controls DNS for names they own — "localtest.me" and friends
     * resolve to loopback and are registered to someone else.
     */
    fun isLoopback(origin: String?): Boolean {
        val c = canonical(origin) ?: return false
        val host = hostOf(c) ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    /** The host portion of an already-canonical origin, or null. */
    fun hostOf(canonicalOrigin: String): String? {
        val i = canonicalOrigin.indexOf("://")
        if (i < 0) return null
        val rest = canonicalOrigin.substring(i + 3)
        return splitHostPort(rest)?.first
    }

    /**
     * Reduces a hostname to a client id.
     *
     * Must satisfy ClientRegistry's `[a-z0-9][a-z0-9_-]{1,31}` — note that
     * pattern's minimum length is **two**, so a one-character host has to be
     * padded rather than passed through. ClientRegistry.create() `require`s
     * the pattern, so anything this returns that does not match surfaces as a
     * pairing failure blaming the id instead of the host, which is a
     * genuinely baffling error to debug from a phone.
     */
    fun slugForHost(host: String): String {
        val s = host.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(32)
            .trim('-')
        return when {
            s.isEmpty() -> FALLBACK_ID
            s.length < 2 -> s + "-app"
            else -> s
        }
    }

    const val FALLBACK_ID = "web-app"

    /** The id pattern slugForHost must satisfy. Mirrors ClientRegistry.ID_RE. */
    val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{1,31}")

    /**
     * True if [origin] is served over a channel a browser treats as secure.
     *
     * Loopback counts even over plain http: per the Secure Contexts spec both
     * `http://127.0.0.1` and `http://localhost` are "potentially trustworthy",
     * which is the reason an https:// page is allowed to fetch Medha at all
     * instead of being blocked as mixed content.
     */
    fun isPotentiallyTrustworthy(origin: String?): Boolean {
        val c = canonical(origin) ?: return false
        return c.startsWith("https://") || c.startsWith("wss://") || isLoopback(c)
    }
}
