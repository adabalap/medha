package com.adabala.medha.connect

/**
 * The rules for Medha's save outbox: what the user explicitly asked to keep,
 * held until Quicksilver has taken it.
 *
 * WHY THIS IS NOT THE CAPTURE LOG
 *
 * They look alike from the outside — append-only, cursored, pulled by the
 * agent — and they are not the same kind of thing, which shows up in exactly
 * one place: what happens when nobody drains them.
 *
 * A capture is an OBSERVATION. Medha noticed something; nobody asked for it.
 * If the agent is off for three weeks the observation ages out and that is
 * correct, because an un-corroborated notification from three weeks ago is
 * not worth the row it sits in.
 *
 * A save is an INSTRUCTION. Somebody read a thing on their brief and pressed
 * a button to say "keep this". Aging that out is losing work the person did,
 * and they would have no way of knowing: the capture is long gone from the
 * shade and the note never appeared. So the normal way a row leaves this
 * table is that Quicksilver acknowledges it, not that it gets old. The age
 * limit here is a backstop against a permanently-absent agent, set far past
 * any outage a person would sit through, and [MAX_ROWS] is low because a
 * human pressing a button cannot produce thousands of these.
 *
 * WHAT "VERBATIM" MEANS AND WHERE IT STOPS
 *
 * The text is stored exactly as it arrived — no stripping of "Posted by
 * WhatsApp", no re-titling, no splitting into items. That is the whole
 * point: the decision about what this becomes is made later, in Quicksilver,
 * by a person, and a decision made on a rewritten copy is made on evidence
 * that has already been edited.
 *
 * Verbatim still needs a ceiling, because the alternative is that one
 * pathological notification decides how big the database is. Oversized text
 * is cut at [MAX_TEXT] and the row records that it was, so the agent can say
 * so rather than presenting a truncation as the whole thing.
 *
 * Pure: no Android, no SQL, no clock. The harness runs it.
 */
object SavePolicy {

    /**
     * The backstop, not the plan.
     *
     * A row normally leaves because the agent acknowledged it. This is only
     * for the case where Quicksilver never comes back at all, and it is long
     * on purpose: a fortnight would quietly eat a save made the day before a
     * holiday.
     */
    const val RETAIN_DAYS = 90
    const val RETAIN_MS = RETAIN_DAYS * 24 * 60 * 60 * 1000L

    /**
     * A ceiling independent of age.
     *
     * Two orders of magnitude below the capture log's, because this fills at
     * the speed of a thumb. If this limit is ever reached, something is
     * wrong — either the agent has not drained in months or something is
     * pressing Save that is not a person — and dropping the oldest is the
     * least bad answer available to a policy object.
     */
    const val MAX_ROWS = 500

    /** Rows per page when the caller does not say. */
    const val PAGE_DEFAULT = 100

    /**
     * The most rows one request may take. Lower than the capture log's: the
     * whole table is smaller than one of its pages.
     */
    const val PAGE_MAX = 500

    /** A title longer than this is a bug upstream, not a title. */
    const val MAX_TITLE = 200

    /** The verbatim ceiling. Generous enough for any real notification. */
    const val MAX_TEXT = 8000

    /** Clamps a requested page size; null or nonsense gives the default. */
    fun pageSize(requested: Int?): Int = when {
        requested == null || requested <= 0 -> PAGE_DEFAULT
        requested > PAGE_MAX -> PAGE_MAX
        else -> requested
    }

    /**
     * Cursor handling, the next cursor and the backlog flag are the same
     * arithmetic as the capture log's, so they are taken from there rather
     * than written again.
     *
     * This is a deliberate dependency. That code carries a long argument
     * about why an empty page hands back the head, and why `hasMore` keeps a
     * term that is redundant for correctness but is what stops a client
     * spinning under load. A second copy of that reasoning is a second copy
     * to get wrong, and the failure it produces — a silently skipped row —
     * is invisible from both ends.
     */
    fun cursor(requested: Long?): Long = CapturePolicy.cursor(requested)

    fun nextCursor(lastId: Long?, head: Long): Long = CapturePolicy.nextCursor(lastId, head)

    fun hasMore(returned: Int, limit: Int, lastId: Long?, head: Long): Boolean =
        CapturePolicy.hasMore(returned, limit, lastId, head)

    /** Saves at or before this instant have aged out. */
    fun cutoff(now: Long): Long = now - RETAIN_MS

    /** How many rows to drop to get back under [MAX_ROWS]. Zero when there is room. */
    fun overBy(rowCount: Int): Int = if (rowCount > MAX_ROWS) rowCount - MAX_ROWS else 0

    /** What a field is cut to. Null in, empty out — storage columns are NOT NULL. */
    fun clipTitle(title: String?): String = clip(title, MAX_TITLE)

    fun clipText(text: String?): String = clip(text, MAX_TEXT)

    private fun clip(s: String?, max: Int): String {
        val v = s ?: return ""
        return if (v.length <= max) v else v.substring(0, max)
    }

    /** Whether storing this pair loses anything, so the row can admit it. */
    fun wasTruncated(title: String?, text: String?): Boolean =
        (title?.length ?: 0) > MAX_TITLE || (text?.length ?: 0) > MAX_TEXT

    /**
     * A row worth storing at all.
     *
     * Everything blank is the one case worth refusing: it comes from a brief
     * line that was rendered entirely from fields this payload does not
     * carry, and saving it produces a note with nothing in it that the
     * person then has to work out how to delete.
     */
    fun isSavable(title: String?, text: String?): Boolean =
        !(title.isNullOrBlank() && text.isNullOrBlank())

    /**
     * The identity of a save, for refusing an accidental second one.
     *
     * The problem being solved is narrow: a double tap, a retried request,
     * or the same row saved again before the agent has drained it. It is NOT
     * "this text may only ever be saved once" — a weekly grocery list is the
     * same words every week and each one is a real save. [postedAt] is what
     * separates those, so it is part of the key.
     *
     * FNV-1a over the composite, 64 bits as hex. A collision would silently
     * drop a save, which is why the whole composite goes in rather than a
     * prefix, and why the width is 64 and not 32: at [MAX_ROWS] rows the
     * chance of any pair colliding is about 500^2 / 2^65, which is far below
     * the rate at which the phone itself loses writes.
     *
     * The separator is a unit separator rather than a space or a pipe,
     * because those appear in notification text and a key that can be forged
     * by its own content is not a key: "a|b" plus "c" would otherwise match
     * "a" plus "b|c".
     */
    fun fingerprint(origin: String?, title: String?, text: String?, postedAt: Long): String {
        val composite = buildString {
            append(origin ?: ""); append(SEP)
            append(title ?: ""); append(SEP)
            append(text ?: ""); append(SEP)
            append(postedAt)
        }
        var h = 0xcbf29ce484222325uL
        for (ch in composite) {
            h = h xor ch.code.toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16).padStart(16, '0')
    }

    private const val SEP = '\u001F'

    /**
     * Whether a brief line should offer Save at all.
     *
     * Only lines Medha read off the notification shade. Everything else on
     * the brief was POSTED by a client, which means it already lives in that
     * client's own system: "Get the groceries today" arrived from Quicksilver
     * as a task it already holds, and saving it would send Quicksilver a copy
     * of its own row. The button would work, produce a duplicate, and teach
     * the person that Save makes clutter.
     *
     * A notification is different in kind — WhatsApp will not keep it for
     * you, and the shade forgets it the moment it is swiped. That is what
     * Save is for.
     */
    fun offersSave(clientId: String): Boolean =
        clientId == com.adabala.medha.notify.BriefPolicy.INFERRED_CLIENT_ID

    /** Exactly what goes into a row of the outbox. */
    data class Row(
        val postedAt: Long,
        val origin: String,
        val title: String,
        val text: String,
        val truncated: Boolean,
        val fp: String
    )

    /**
     * The row to store for a brief line, or null when there is nothing worth
     * keeping.
     *
     * The fingerprint is computed from the CLIPPED fields, and that is the
     * reason this function exists rather than the caller doing it inline. The
     * route serves `fp` recomputed from what is stored; if the write keyed
     * the original text and the read keyed the clipped text, every truncated
     * save would be stored under one identity and announced under another,
     * and the consumer's idempotence would be keyed on a value that matches
     * nothing. Building both from the same clipped strings makes that
     * impossible rather than merely unlikely.
     *
     * [postedAt] null falls back to [now]. A line with no post time is one
     * the brief cannot age either, and the save instant is the honest
     * substitute: it still separates this week's save from next week's,
     * which is the property the key exists for.
     */
    fun row(origin: String?, title: String?, text: String?, postedAt: Long?, now: Long): Row? {
        if (!isSavable(title, text)) return null
        val o = origin ?: ""
        val t = clipTitle(title)
        val x = clipText(text)
        val p = postedAt ?: now
        return Row(p, o, t, x, wasTruncated(title, text), fingerprint(o, t, x, p))
    }
}
