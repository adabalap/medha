package com.adabala.medha.connect

/**
 * The rules for Medha's capture log: what it keeps, and how much it hands
 * over at a time.
 *
 * WHAT THE LOG IS FOR
 *
 * Medha can see the phone's notification stream and has no memory to reason
 * about it. Quicksilver has a corpus, embeddings and correlation, and
 * cannot see the stream at all. The brief was the only thing passing
 * between them, and a brief row is a rendered sentence — by the time it
 * exists the observation has been flattened into display text with no
 * identity, so the side with the brain receives three unrelated strings
 * where there was one event.
 *
 * The capture log is the other direction: raw observations, with ids, that
 * the agent can pull and interpret. Medha stops guessing; it reports.
 *
 * WHAT THIS CHANGES ABOUT PRIVACY, STATED PLAINLY
 *
 * Notification title and text are ALREADY persisted — the brief holds them
 * in preferences, and any client with the brief capability can read them
 * back. So this is not a new kind of exposure. It is a larger one: the
 * brief keeps what survived the filter and a 20-item cap for a day, and
 * this keeps everything that survived the filter for [RETAIN_DAYS].
 *
 * Two things follow, and both are enforced rather than intended:
 *
 *   - Captures pass through the SAME NotificationFilter as the brief, so
 *     the one-time-passcode guarantee is not weakened by this route. A
 *     notification that never became a brief row never becomes a capture
 *     either.
 *   - The log is bounded in both directions, by age and by row count. An
 *     unbounded record of everything that ever notified you is not a
 *     feature, and "it has a TTL" is only true if something enforces it.
 *
 * Pure: no Android, no SQL, no clock. The harness runs it.
 */
object CapturePolicy {

    /** How long an observation is worth keeping. */
    const val RETAIN_DAYS = 14
    const val RETAIN_MS = RETAIN_DAYS * 24 * 60 * 60 * 1000L

    /**
     * A hard ceiling independent of age.
     *
     * A busy phone can produce thousands of notifications in a day, and a
     * retention window alone would let the log grow without any bound the
     * device can feel. Whichever limit bites first wins.
     */
    const val MAX_ROWS = 5000

    /** Rows per page when the caller does not say. */
    const val PAGE_DEFAULT = 200

    /**
     * The most rows one request may take.
     *
     * The agent is on the same loopback and is not an adversary, but a
     * cursor walk with no ceiling turns one bad `?limit=` into Medha
     * materialising the whole log into a JSON array in a foreground
     * service's heap.
     */
    const val PAGE_MAX = 1000

    /** Clamps a requested page size; null or nonsense gives the default. */
    fun pageSize(requested: Int?): Int = when {
        requested == null || requested <= 0 -> PAGE_DEFAULT
        requested > PAGE_MAX -> PAGE_MAX
        else -> requested
    }

    /**
     * A cursor from a caller, floored at zero.
     *
     * Negative means "from the beginning" rather than an error: the cursor
     * is an opaque number to the client, and refusing a request because it
     * arithmetic'd its way to -1 helps nobody.
     */
    fun cursor(requested: Long?): Long = if (requested == null || requested < 0) 0L else requested

    /** Observations at or before this instant have aged out. */
    fun cutoff(now: Long): Long = now - RETAIN_MS

    /**
     * How many rows to drop to get back under [MAX_ROWS].
     *
     * Zero when there is room. Deliberately returns the whole overage
     * rather than trimming one row per insert: a log that prunes one at a
     * time never catches up with a burst.
     */
    fun overBy(rowCount: Int): Int = if (rowCount > MAX_ROWS) rowCount - MAX_ROWS else 0

    /**
     * The cursor to hand back, given the last row of this page and the head
     * of the log.
     *
     * Here rather than inline in the route because this is the arithmetic
     * that silently loses data when it is wrong, and the route is in a file
     * nothing short of Gradle can compile. A dropped row from a cursor
     * off-by-one produces no error anywhere: the agent simply never learns
     * about one notification.
     *
     * [lastId] null means the page was empty, and then the answer is [head]
     * rather than the caller's own `since`. The reasoning is worth writing
     * down because the obvious alternative — "keep what you had" — is what
     * makes a client ask forever:
     *
     * The query is `id > since LIMIT n` with n >= 1, so an empty page means
     * no row has an id above `since`, which means `head <= since`. Normally
     * they are equal and this returns the caller's own position unchanged.
     * They differ only after the log was cleared on revocation, where head
     * drops to 0 while ids keep climbing from `sqlite_sequence` — and there
     * handing back 0 is right: everything the old cursor pointed past has
     * been deleted, and a walk from 0 returns exactly the rows that exist.
     */
    fun nextCursor(lastId: Long?, head: Long): Long = lastId ?: head

    /**
     * Whether this page was cut short, so the caller should come straight
     * back rather than wait for its next poll.
     *
     * Not a total count: that needs a second query and is stale the moment
     * it is computed. This is derivable from what the caller already has.
     *
     * WHY `returned >= limit` IS IN HERE. It looks redundant, and for
     * correctness it is: the query is `id > since ORDER BY id ASC LIMIT n`,
     * so a page shorter than `n` has exhausted the matching rows and its
     * last id already equals the head. Dropping the term passes every
     * correctness test in the suite — a mutation probe confirmed it.
     *
     * It is here for termination under load. `more` means "there is a known
     * backlog", not "something may have arrived since". Without the term,
     * `lastId < head` is true whenever one notification landed between the
     * two queries, which on a busy phone is most of the time — and a client
     * that polls while `more` is true would be driven into a tight loop by
     * ordinary notification traffic. With it, `more` is bounded by the
     * backlog: it says drain faster, never poll faster. A row that arrives
     * in that window is picked up on the client's own next poll, which is
     * what the client's schedule is for.
     *
     * Both concurrency directions stay safe either way. An insert between
     * the two queries leaves `head` above the page's last id; a prune can
     * leave it BELOW, and `false` is then correct too, because there is
     * nothing above this page left to fetch.
     */
    fun hasMore(returned: Int, limit: Int, lastId: Long?, head: Long): Boolean =
        lastId != null && returned >= limit && lastId < head
}
