package com.adabala.medha.net

/**
 * The cross-origin decision for every request, as a pure function.
 *
 * ## Why this is hand-written instead of `install(CORS)`
 *
 * Two reasons, and the second is the decisive one.
 *
 *  1. The allowed set is **dynamic**. Ktor's CORS plugin is configured once at
 *     install time, but a web app can be paired at any moment and its origin
 *     has to start working without restarting the server.
 *
 *  2. **Private Network Access.** A page on a public origin fetching
 *     `http://127.0.0.1:8001` is, to Chrome, a public-to-private request. It
 *     is blocked unless the preflight response carries
 *     `Access-Control-Allow-Private-Network: true`. Ktor 2.3's CORS plugin
 *     has no notion of that header, so the preflight has to be handled here
 *     regardless — and having two pieces of code racing to set CORS headers
 *     on the same response is worse than having one.
 *
 * ## Policy
 *
 * Allowed origins are:
 *  - the loopback origins Medha itself serves (the bundled demo UI), and
 *  - every origin that has completed pairing.
 *
 * `Access-Control-Allow-Origin` always echoes the single requesting origin and
 * is never `*`. `*` is incompatible with credentials, and more importantly it
 * would let any page on the internet read model output from a device it
 * happened to be able to reach.
 *
 * `Vary: Origin` is emitted on every response that varies by origin, including
 * denied ones, so no cache can serve one origin's allow decision to another.
 *
 * Pure Kotlin, no Ktor types: see tools/tests/CorsPolicyTest.kt.
 */
object CorsPolicy {

    const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
    const val ALLOW_METHODS = "Access-Control-Allow-Methods"
    const val ALLOW_HEADERS = "Access-Control-Allow-Headers"
    const val MAX_AGE = "Access-Control-Max-Age"
    const val ALLOW_PRIVATE_NETWORK = "Access-Control-Allow-Private-Network"
    const val REQUEST_PRIVATE_NETWORK = "Access-Control-Request-Private-Network"
    const val EXPOSE_HEADERS = "Access-Control-Expose-Headers"
    const val VARY = "Vary"

    private const val METHODS = "GET, POST, PUT, DELETE, OPTIONS"
    private const val HEADERS = "Authorization, Content-Type, Accept, X-Medha-Client"

    /**
     * Browsers cap preflight caching anyway (Chrome at 2 hours). Asking for
     * exactly that avoids a preflight on every single call from a PWA without
     * pretending to a longer life than the browser will honour.
     */
    private const val MAX_AGE_SECONDS = 7200

    /**
     * What the server should do with one request.
     *
     * @param headers headers to add to the response, always applied.
     * @param shortCircuitStatus non-null when the request must be answered
     *        immediately with this status and no body — a preflight.
     */
    data class Decision(
        val allowed: Boolean,
        val headers: Map<String, String>,
        val shortCircuitStatus: Int? = null
    ) {
        val isPreflight: Boolean get() = shortCircuitStatus != null
    }

    /**
     * @param originHeader raw `Origin` header, or null when absent. Absent is
     *        normal and fine: curl, a native Android client and a server-side
     *        caller all send no Origin. Those are not browser requests, so
     *        there is no same-origin policy to satisfy and nothing to grant.
     * @param method the request method, uppercase.
     * @param requestsPrivateNetwork whether the preflight carried
     *        [REQUEST_PRIVATE_NETWORK].
     * @param allowedOrigins canonical origins currently permitted.
     */
    fun decide(
        originHeader: String?,
        method: String,
        requestsPrivateNetwork: Boolean,
        allowedOrigins: Set<String>
    ): Decision {
        val isOptions = method.equals("OPTIONS", ignoreCase = true)
        val origin = WebOrigin.canonical(originHeader)

        // No Origin header: not a browser request. Let it through untouched;
        // the bearer token is the only thing standing between a caller and the
        // API, and that check happens elsewhere and is unaffected by CORS.
        if (origin == null) {
            return if (isOptions) {
                Decision(true, mapOf(VARY to "Origin"), shortCircuitStatus = 204)
            } else {
                Decision(true, mapOf(VARY to "Origin"))
            }
        }

        if (origin !in allowedOrigins) {
            // Deny by *omitting* the allow header rather than by rejecting the
            // request. The browser then refuses to hand the response to the
            // page, which is the actual enforcement point. Returning 403 here
            // would additionally break non-browser clients that happen to set
            // an Origin, and would leak which origins are paired.
            return Decision(
                allowed = false,
                headers = mapOf(VARY to "Origin"),
                shortCircuitStatus = if (isOptions) 204 else null
            )
        }

        val h = linkedMapOf(
            VARY to "Origin",
            ALLOW_ORIGIN to origin,
            EXPOSE_HEADERS to "Content-Type"
        )

        if (isOptions) {
            h[ALLOW_METHODS] = METHODS
            h[ALLOW_HEADERS] = HEADERS
            h[MAX_AGE] = MAX_AGE_SECONDS.toString()
            // Only ever on a preflight that asked, and only for an origin
            // already on the allow list. Setting it unconditionally would
            // advertise loopback reachability to pages that have not paired.
            if (requestsPrivateNetwork) h[ALLOW_PRIVATE_NETWORK] = "true"
            return Decision(true, h, shortCircuitStatus = 204)
        }

        return Decision(true, h)
    }

    /**
     * The origins the bundled demo UI is served from. Medha serves the demo
     * itself, so these are same-origin in practice, but a page loaded via
     * `localhost` while the server is bound to `127.0.0.1` is cross-origin to
     * the browser and needs saying out loud.
     */
    fun loopbackOrigins(port: Int): Set<String> = setOf(
        "http://127.0.0.1:$port",
        "http://localhost:$port",
        "http://[::1]:$port"
    )
}
