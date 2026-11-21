package com.adabala.medha.notify

/**
 * Turning what a client sent into what the brief stores.
 *
 * WHY THIS IS ITS OWN FILE
 *
 * `PUT /widget/content` now accepts two shapes: the v1 rendered rows and the
 * v2 obligations. Which one arrived, and what to do with each field of it, is
 * branching logic — and it sits in LocalServer.kt, which nothing short of
 * Gradle can compile. Every decision in this project that reached the home
 * screen from an uncompilable file has eventually been wrong in a way nobody
 * could have executed a test against: the dueAt that was stored and ignored
 * for two releases, the request codes that collided, the triage rule that
 * promoted four emails.
 *
 * So the decisions live here, where the harness runs them, and the route does
 * nothing but call them and write the result.
 *
 * Pure: no Android, no Ktor, no serialization. The harness runs it.
 */
object BriefIntake {

    /**
     * Which protocol a request is speaking.
     *
     * [NEITHER] is deliberately not treated as an empty post. A client that
     * sends `{}` has either got the field name wrong or lost its payload, and
     * silently taking its section off the user's home screen is the wrong
     * answer to both. `{"items": []}` is how you withdraw, and that is a
     * different thing a client has to actually mean.
     *
     * [BOTH] is a 400 rather than a merge or a precedence rule. A request
     * carrying rendered rows AND obligations disagrees with itself about which
     * protocol it speaks; picking one for it would make whichever choice was
     * made load-bearing for a client that never intended either.
     */
    enum class Shape { V1, V2, NEITHER, BOTH }

    fun shapeOf(hasItems: Boolean, hasObligations: Boolean): Shape = when {
        hasItems && hasObligations -> Shape.BOTH
        hasObligations -> Shape.V2
        hasItems -> Shape.V1
        else -> Shape.NEITHER
    }

    /**
     * A client's obligation id, or null.
     *
     * Trimmed, capped, and blank-to-null, so `""` and `"   "` mean "I did not
     * send one" rather than becoming an id that every untitled item shares —
     * which would make one dismissal remove all of them, since the key would
     * be identical.
     */
    fun idOrNull(raw: String?, maxLength: Int = BriefJson.MAX_ID_LENGTH): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)

    /**
     * A positive instant, or null.
     *
     * Zero is a real instant — 1 Jan 1970 — and a client that means "no date"
     * sends it far more often than one that means the epoch. An item stored
     * with dueAt 0 renders as decades overdue and sorts above everything the
     * user actually has to do today.
     */
    fun dueAtOrNull(at: Long?): Long? = at?.takeIf { it > 0L }

    /** How far ahead of Medha's clock a `since` may be before it is refused. */
    const val SINCE_SKEW_MS = 5 * 60 * 1000L

    /**
     * When an obligation began, if the client's value can be believed.
     *
     * The brief's right-hand mark on an undated row used to be the age of the
     * SECTION — when the client last posted — which is one value for every
     * row it sends. A real brief showed "40m" on thirteen rows in a column,
     * a forty-eight-day-old task among them. `since` lets the mark say how
     * long the thing has been waiting, which is the only age a person reads.
     *
     * Refused when it is not positive (as [dueAtOrNull], 0 is "no value" far
     * more often than it is 1970) and when it is in the future beyond a few
     * minutes of clock skew: a task cannot have been waiting for a negative
     * time, and drawing "just now" for something that has not begun would
     * be quietly wrong.
     */
    fun sinceOrNull(at: Long?, now: Long): Long? =
        at?.takeIf { it > 0L && it <= now + SINCE_SKEW_MS }

    /**
     * The result of choosing a tap target from the v2 `links` array.
     *
     * [dropped] is reported back to the client rather than swallowed. v0.9.0
     * echoed a request's own size while silently rendering three of five
     * items, which left a client no way to see the loss; the same mistake
     * with links would leave it believing a tap goes somewhere it does not.
     */
    data class Links(val chosen: String?, val dropped: Int)

    /**
     * The first link the brief is willing to open, and a count of the rest.
     *
     * First rather than best, because order is the client's statement of
     * preference and Medha has no basis for a better one.
     *
     * Unsafe entries are skipped rather than failing the item: a brief row
     * with no tap target is a smaller loss than no row at all, which is the
     * same reasoning [BriefJson.isSafeLink] is applied with everywhere else.
     * They count as dropped, so a client sending only `quicksilver://note/1`
     * sees `linksDropped: 1` and can work out why nothing opens.
     */
    fun chooseLink(links: List<String>): Links {
        var chosen: String? = null
        var dropped = 0
        links.forEach { raw ->
            val safe = BriefJson.safeLinkOrNull(raw)
            if (safe != null && chosen == null) chosen = safe else dropped++
        }
        return Links(chosen, dropped)
    }

    /**
     * Whether a title and text pair is within the brief's limits.
     *
     * Returns the offending field's name, or null when both fit. A name
     * rather than a boolean because the 400 has to say which one — "a title
     * exceeds 80 characters" is actionable and "bad request" is not.
     */
    fun tooLong(
        title: String,
        text: String,
        maxTitle: Int,
        maxText: Int
    ): String? = when {
        title.length > maxTitle -> "title"
        text.length > maxText -> "text"
        else -> null
    }
}
