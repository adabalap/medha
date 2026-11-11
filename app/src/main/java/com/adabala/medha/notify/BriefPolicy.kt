package com.adabala.medha.notify

/**
 * Medha's brief: a digest surface on the home screen that several apps can
 * write to at once.
 *
 * ## Why this exists
 *
 * Samsung's **Now Brief cannot be written to by a third party.** There is no
 * API, no intent and no SDK. The custom cards added in One UI 9 are
 * user-authored — you pick a subject in Settings → Galaxy AI → Now brief,
 * optionally describe what you want, and Samsung's own AI generates the
 * content on a schedule, capped at three cards. Nothing an app can reach.
 *
 * The **Now Bar** is open, via Android 16 Live Updates, and
 * [NotificationHub] targets it. But the Now Bar is for *one ongoing activity
 * with progress*, not a digest of several apps' standing items. So the
 * digest shape has nowhere to go on the platform, and Medha provides its own
 * instead: its home-screen widget, which any paired client can write to.
 *
 * ## What changed in v0.9.1
 *
 * The first version was last-writer-wins: one shared blob, one `clientId`,
 * and whoever wrote most recently replaced everything. With one consumer that
 * is indistinguishable from working. With two it is a bug — each app silently
 * erases the other, and neither can tell why its content vanished.
 *
 * It also accepted five items and rendered three, because
 * `MAX_WIDGET_ITEMS = 5` and the layout had three rows. Items four and five
 * were dropped with no error anywhere.
 *
 * Now each client owns a **section**. Sections are merged at read time,
 * newest first, with expired ones dropped.
 *
 * ## Why expiry, and why at read time
 *
 * A card from Tuesday sitting on the home screen on Friday is worse than no
 * card: the user cannot tell stale from current, so they stop trusting the
 * whole surface. Sections therefore carry an expiry.
 *
 * It is evaluated when the widget is read, not by a timer. A scheduled job to
 * tidy a home-screen widget would mean a wakeup, a worker and a reason for
 * the OS to keep Medha alive, to delete text nobody is looking at. Reading is
 * the only moment the answer matters.
 *
 * Pure Kotlin over plain data types, with no `org.json` and no Android: the
 * JSON layer lives in [NotificationHub] and the rendering in
 * [MedhaWidgetProvider], so this — the part with the actual rules in it — is
 * executable-testable. See tools/tests/BriefPolicyTest.kt.
 */
object BriefPolicy {

    /**
      * One line in the brief.
      *
      * [link] is where tapping the line should go, and is optional. Only the
      * app that posted an item knows what it refers to -- Medha stores a
      * hashed namespace rather than the caller's package, so it cannot
      * launch the posting app and will not guess. With no link, tapping
      * opens a detail sheet instead of doing nothing.
      *
      * [dueAt] is when the item comes due, epoch ms, and is optional.
     *
     * It exists because the alternative is prose. Before v0.12.0 a client
     * sent "overdue 10 days" as [text], which is wrong the moment the clock
     * passes midnight and was observably wrong on a real device: a widget
     * showing a five-hour-old string that still claimed "10 days". A
     * timestamp lets Medha compute the distance at draw time, every time.
     *
     * [kind] is one of [KINDS], and only chooses a glyph. Unknown values
     * are dropped rather than rejected — a client sending a kind Medha has
     * never heard of should still get its item on the screen.
     */
     data class Item(
        val title: String,
        val text: String,
        val link: String? = null,
        val dueAt: Long? = null,
        val kind: String? = null
     )

    /** One client's contribution, replaced wholesale when it writes again. */
    data class Section(
        val clientId: String,
        val updatedAt: Long,
        val expiresAt: Long,
        val items: List<Item>
    )

    /** A row ready to draw, with attribution resolved. */
    data class Row(
        val title: String,
        val text: String,
        val clientId: String,
        val updatedAt: Long,
        val link: String? = null,
        val dueAt: Long? = null,
        val kind: String? = null
    )

    /**
     * Replaces [clientId]'s section with [items].
     *
     * Replace rather than append: a client posting its brief is stating its
     * whole current contribution, not adding to a log. Appending would mean
     * every client needing a delete call to correct itself, and a brief that
     * only ever grows.
     *
     * A write with no items removes the section, so `PUT` with an empty list
     * is a withdrawal and a client needs no second endpoint to clear itself.
     */
    fun put(
        existing: List<Section>,
        clientId: String,
        items: List<Item>,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxSections: Int = MAX_SECTIONS,
        maxItemsPerSection: Int = MAX_ITEMS_PER_SECTION
    ): List<Section> {
        val others = existing.filter { it.clientId != clientId }

        val trimmed = items
            .filter { it.title.isNotBlank() }
            .take(maxItemsPerSection)

        if (trimmed.isEmpty()) return prune(others, now, maxSections)

        val mine = Section(
            clientId = clientId,
            updatedAt = now,
            // Clamped so a client cannot pin content on the home screen
            // indefinitely by asking for a ten-year TTL.
            expiresAt = now + ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS),
            items = trimmed
        )
        return prune(others + mine, now, maxSections)
    }

    /** Drops [clientId]'s section. */
    fun remove(existing: List<Section>, clientId: String, now: Long): List<Section> =
        prune(existing.filter { it.clientId != clientId }, now)

    /**
     * Expired sections out, newest first, bounded.
     *
     * Bounded by *oldest evicted*, which matters: an unbounded map is a slow
     * leak a client could drive, and evicting the newest would mean a brief
     * that freezes once full.
     */
    fun prune(
        sections: List<Section>,
        now: Long,
        maxSections: Int = MAX_SECTIONS
    ): List<Section> =
        sections
            .filter { it.expiresAt > now }
            .sortedByDescending { it.updatedAt }
            .take(maxSections)

    /**
     * Flattens the live sections into rows to draw.
     *
     * Two rules, in this order.
     *
     * URGENCY ACROSS BANDS. Rows come out banded: everything overdue, then
     * everything due within [SOON_DAYS], then everything later, then
     * everything undated. Until v0.12.2 this did not happen, and the
     * consequence was plain — an overdue bill from the second app to post
     * sat underneath a note due in three weeks from the first, because
     * position was decided by who posted rather than by when things are
     * due. A brief whose top line is not its most urgent line is not a
     * brief. This is also what lets the widget show a fixed top few rows
     * honestly: overdue cannot hide below the fold if overdue sorts first.
     *
     * FAIRNESS WITHIN A BAND. Inside each band the original behaviour is
     * untouched: one item per client per pass, not section by section.
     * With section-by-section, one app posting five items fills the whole
     * widget and every other app is invisible — the exact failure the
     * per-client split was meant to fix, reintroduced at the rendering
     * step. Round-robin means each app gets its first line of a band
     * before any app gets its second.
     *
     * Note what banding does NOT do: it cannot starve an app the way a
     * global sort on `dueAt` would, because the round-robin still runs
     * inside every band. One app with five overdue items takes the overdue
     * band, which is correct — five things are actually overdue — but it
     * takes it one row at a time, behind every other app's overdue row.
     *
     * A client that sends no `dueAt` is unaffected: all of its items land
     * in the same undated band, so it gets back exactly the round-robin it
     * got before.
     *
     * [offsetMs] is the device's UTC offset; see [deviceOffset]. It is a
     * parameter rather than a lookup so a test can pin a timezone instead
     * of inheriting the machine's.
     */
    fun rows(
        sections: List<Section>,
        now: Long,
        maxRows: Int = MAX_ROWS,
        offsetMs: Long = 0L
    ): List<Row> {
        val live = prune(sections, now)
        if (live.isEmpty()) return emptyList()

        // `null` last, and `null` is a real key here rather than a synonym
        // for LATER: an item with no dueAt is unranked, not far away, and
        // folding the two together would let an undated note outrank a
        // dated one for no reason the user could see.
        val bands: List<Band?> = listOf(Band.OVERDUE, Band.SOON, Band.LATER, null)

        val out = mutableListOf<Row>()
        for (band in bands) {
            val queues = live
                .map { s -> s to s.items.filter { bandOf(it.dueAt, now, offsetMs) == band } }
                .filter { (_, items) -> items.isNotEmpty() }
            if (queues.isEmpty()) continue

            val deepest = queues.maxOf { (_, items) -> items.size }
            for (depth in 0 until deepest) {
                for ((s, items) in queues) {
                    val item = items.getOrNull(depth) ?: continue
                    out += Row(
                        title = item.title,
                        text = item.text,
                        clientId = s.clientId,
                        updatedAt = s.updatedAt,
                        link = item.link,
                        dueAt = item.dueAt,
                        kind = item.kind
                    )
                    if (out.size == maxRows) return out
                }
            }
        }
        return out
    }

    /**
     * The device's UTC offset at [at], for callers to pass back in as
     * `offsetMs`.
     *
     * Deliberately not called from anywhere inside this file. Every banding
     * function here takes the offset as a parameter so a test can pin a
     * timezone rather than inherit the machine's, and that property is worth
     * more than the convenience of looking it up internally. This exists so
     * that the callers which DO want the real device offset all spell it the
     * same way, instead of each writing its own and drifting apart on DST.
     *
     * Fully qualified on purpose: this file has no import statements, which
     * is what lets the pure-logic harness compile it with nothing but
     * kotlinc. One qualified name is a cheaper price than losing that.
     */
    fun deviceOffset(at: Long): Long =
        java.util.TimeZone.getDefault().getOffset(at).toLong()

    /**
     * One screenful of the widget.
     *
     * @param rows the rows on this page, at most [PAGE_ROWS].
     * @param index zero-based page number.
     * @param pageCount how many pages the brief currently has.
     * @param totalRows every row across every page.
     */
    data class Page(
        val rows: List<Row>,
        val index: Int,
        val pageCount: Int,
        val totalRows: Int
    ) {
        /** True when there is more than one page, i.e. rotation is meaningful. */
        val overflows: Boolean get() = pageCount > 1

        /** "2/3" for the widget's footer. Empty when there is nothing to page. */
        val indicator: String get() = if (overflows) "${index + 1}/$pageCount" else ""
    }

    /** Pages the brief currently has, at least 1 even when empty. */
    fun pageCount(rowCount: Int, perPage: Int = PAGE_ROWS): Int =
        if (rowCount <= 0) 1 else ((rowCount + perPage - 1) / perPage)

    /**
     * The page to draw for a monotonically increasing [tick].
     *
     * Takes a tick rather than a page index so the caller can be a dumb
     * counter that never needs to know how many pages exist — which matters
     * because the brief changes underneath it as clients write and sections
     * expire. Modulo means a tick captured when there were four pages still
     * lands somewhere sensible when there are suddenly two.
     *
     * [tick] may be negative (a counter that wrapped, or a caller that
     * subtracts); the modulo is taken so the index is always in range rather
     * than throwing on a negative list index.
     */
    fun page(
        sections: List<Section>,
        now: Long,
        tick: Long = 0,
        perPage: Int = PAGE_ROWS,
        offsetMs: Long = 0L
    ): Page {
        val all = rows(sections, now, offsetMs = offsetMs)
        val count = pageCount(all.size, perPage)

        // No early return for the empty case: pageCount() floors at 1, so the
        // arithmetic below yields subList(0, 0) and Page([], 0, 1, 0) — exactly
        // what an explicit guard returned. A mutation test showed the guard
        // was indistinguishable from its absence, and a branch no test can
        // tell apart is a branch that rots.
        //
        // floorMod, not %: Kotlin's % keeps the sign of the dividend, so a
        // negative tick would index backwards off the start of the list.
        val index = ((tick % count) + count) % count
        val from = (index * perPage).toInt()
        val to = minOf(from + perPage, all.size)
        return Page(all.subList(from, to), index.toInt(), count, all.size)
    }

    /**
     * "just now" / "12m" / "3h" / "2d" — a width the widget can spare.
     *
     * Absolute times are worse here: the user is glancing at a home screen to
     * judge whether a line is current, and "14:02" forces them to work that
     * out themselves.
     *
     * A negative delta means a section written by a device whose clock has
     * since moved backwards; it reads as "just now" rather than a negative
     * figure.
     */
    /**
     * Which urgency band an item falls in. Drives colour, grouping and the
     * overdue count in the widget header.
     */
    enum class Band { OVERDUE, SOON, LATER }

    /** Days ahead, inclusive, that still count as [Band.SOON]. */
    const val SOON_DAYS = 7

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Whole days from today to [dueAt]'s day: negative in the past, 0 today.
     *
     * **Day granularity, not elapsed milliseconds**, and that is the whole
     * point. A bill due "17 Nov" arrives as midnight on the 17th; by 14:00
     * that day an elapsed-time comparison calls it overdue, which is both
     * wrong and alarming. People think in days, so the arithmetic does too.
     *
     * [offsetMs] is the viewer's UTC offset, because "today" is a local
     * idea — at 02:00 in Hyderabad it is still the previous day in UTC, and
     * every date would read one short. The caller passes the device offset;
     * this file stays free of Android so it can be tested.
     */
    fun dayDelta(dueAt: Long, now: Long, offsetMs: Long = 0L): Int =
        (floorDay(dueAt + offsetMs) - floorDay(now + offsetMs)).toInt()

    /** Floor division into whole days; correct for pre-epoch values too. */
    private fun floorDay(ms: Long): Long {
        val d = ms / DAY_MS
        return if (ms < 0 && d * DAY_MS != ms) d - 1 else d
    }

    /** The band for [dueAt], or null when an item carries no due date. */
    fun bandOf(dueAt: Long?, now: Long, offsetMs: Long = 0L): Band? {
        if (dueAt == null) return null
        val d = dayDelta(dueAt, now, offsetMs)
        return when {
            d < 0 -> Band.OVERDUE
            d <= SOON_DAYS -> Band.SOON
            else -> Band.LATER
        }
    }

    /**
     * The short chip a row shows: `10d over`, `today`, `in 6d`.
     *
     * Computed at draw time from [dueAt], so it cannot go stale the way a
     * client-supplied string does.
     */
    fun relative(dueAt: Long?, now: Long, offsetMs: Long = 0L): String? {
        if (dueAt == null) return null
        return when (val d = dayDelta(dueAt, now, offsetMs)) {
            0 -> "today"
            1 -> "tomorrow"
            -1 -> "yesterday"
            else -> if (d < 0) "${-d}d over" else "in ${d}d"
        }
    }

    /** How many items in [sections] are overdue. The widget header's badge. */
    fun overdueCount(sections: List<Section>, now: Long, offsetMs: Long = 0L): Int =
        sections.sumOf { s ->
            s.items.count { bandOf(it.dueAt, now, offsetMs) == Band.OVERDUE }
        }

    /**
     * Glyphs a client may ask for. Anything else is dropped to null rather
     * than refused: a wrong icon is a smaller failure than a lost item.
     */
    val KINDS = setOf("bill", "date", "event", "alert", "done", "note")

    /** Normalises a caller's `kind`, or null if it is not one Medha draws. */
    fun kindOrNull(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it in KINDS }

    fun age(updatedAt: Long, now: Long): String {
        val ms = now - updatedAt
        if (ms < 60_000) return "just now"
        val minutes = ms / 60_000
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h"
        return "${hours / 24}d"
    }

    /**
     * Twenty-four hours. A brief is a "today" surface — long enough that
     * something posted this morning is still there this evening, short
     * enough that yesterday's clears itself without anyone tidying up.
     */
    const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L

    /** A minute. Below this a write would expire before the widget redrew. */
    const val MIN_TTL_MS = 60 * 1000L

    /** Seven days. The ceiling on pinning something to the home screen. */
    const val MAX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    const val MAX_SECTIONS = 8
    const val MAX_ITEMS_PER_SECTION = 5

    /**
     * Rows the widget layout draws at once. Keep this and `widget_medha.xml`
     * in step: v0.9.0 had the server accepting five items against a
     * three-row layout and dropped two in silence. BriefPolicyTest pins the
     * number and check_resources.py confirms the ids exist.
     */
    const val PAGE_ROWS = 3

    /**
     * Rows the brief will hold in total, across all pages.
     *
     * Larger than [PAGE_ROWS] because the widget rotates: content beyond the
     * first page is reachable rather than discarded, which is the whole point
     * of paging. Still bounded, because this is a glanceable surface and a
     * brief nobody can read to the end of is a list, not a brief.
     */
    const val MAX_ROWS = 12
}
