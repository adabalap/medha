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
        val kind: String? = null,
        /**
         * The app this came from, as a person would name it: "Outlook",
         * "WhatsApp", "Microsoft Teams".
         *
         * Only the notification listener sets it, and that is deliberate.
         * Every notification-sourced item arrives under one section, so
         * without a per-ITEM source the whole stream is attributable only
         * to the literal string "notifications" — which is how a shopping
         * list from your wife and a Teams announcement ended up looking
         * like they came from the same place.
         *
         * PUT /brief does NOT accept it. A client that could set its own
         * source could label itself "Outlook" and borrow the trust of an
         * app it is not. A posting client is named by its registration
         * instead, which Medha controls.
         */
        val source: String? = null,
        /**
         * True when [dueAt] was GUESSED from prose rather than declared.
         *
         * This is the field the brief was missing, and its absence made
         * the triage rule do the opposite of its job. "Dated" is an
         * objective proxy for consequence only when the date was
         * DECLARED — a client setting dueAt, a calendar entry's start
         * time. When it is scraped out of free text by a regex it is the
         * least reliable signal in the system, and the rule handed it
         * control of the home screen.
         *
         * One real evening of it: four of the five rows on the agenda
         * were emails. "RE: Upcoming Traveler Information" was due
         * tomorrow. "Re: Delayed employees for 11:30 on 17/11/2026" was
         * due tomorrow, from a date already past, because the parser
         * found "11:30" and rolled it forward. None of them was an
         * obligation at all.
         *
         * Inferred dates still sort and still persist; they simply may
         * not promote. [BriefTriage] is where that is enforced.
         *
         * Default false, so an item that predates this field reads as
         * declared. Client-posted dates genuinely are, and listener items
         * are rewritten with the flag on the next notification — within
         * one TTL at worst.
         */
        val dueInferred: Boolean = false,
        /**
         * The client's own stable identity for this obligation.
         *
         * Opaque to Medha: it is never parsed, compared across clients, or
         * shown. It exists so the two things that need to recognise an
         * item again can do so without reading its words:
         *
         *   - a dismissal, which until now keyed on (client, title, dueAt)
         *     and was therefore forgotten the moment a title was reworded.
         *     An agent that rewrites "Pay ICICI card" as "ICICI card
         *     payment" resurrected something the user had put away.
         *   - a reschedule, which is the same obligation at a new time and
         *     used to be indistinguishable from a new one.
         *
         * Null for anything posted without it, and the old identity still
         * applies there — see [BriefDismiss.key], which keeps the previous
         * encoding byte for byte so no existing dismissal is invalidated.
         */
        val id: String? = null,
        /**
         * Whether the obligation still stands: one of [STATES], or null
         * meaning the client did not say.
         *
         * This is the client asserting something it KNOWS, replacing a
         * guess Medha was making from the words. [BriefTriage] read
         * "Canceled:" off the front of a title, which works for a mail
         * client's subject line and not at all for an agent that has the
         * thread and can see the meeting was reinstated.
         *
         * Only in the demoting direction. `cancelled` and `done` take an
         * item off the agenda outright; `open` does NOT override the title
         * rule, because the two failures are not symmetrical — a wrongly
         * demoted item is still there, one tap away on the full brief,
         * while a wrongly promoted one is what put a cancelled meeting on
         * the home screen at the cancelled meeting's hour. The lane Medha
         * actually concluded is echoed back on `GET /widget/content`, so a
         * client can see where it disagreed rather than wonder.
         */
        val state: String? = null,
        /**
         * Whether a person committed to this, as opposed to an app having
         * merely mentioned it.
         *
         * This replaces "has a date" as the test for belonging on the
         * agenda, and the replacement matters because the old test was a
         * proxy for the wrong thing.
         *
         * The rule shipped in v0.19.2 required a date. It was written
         * against a screen holding 19 items of which 17 were undated — a
         * shopping list, a webinar invitation, a WhatsApp "Thanq thanq" —
         * and "dated" was chosen because it was the one objective signal
         * available. But the thing those 17 had in common was not that
         * they lacked a date. It was that NOBODY HAD COMMITTED TO THEM:
         * an app had mentioned them, and an app mentioning something is
         * not an obligation.
         *
         * Meanwhile "pick up the dry cleaning" has no date, never will,
         * and is exactly the sort of thing the brief exists for. Under the
         * date rule it could never reach the home screen. That was the
         * report that prompted this, and it is the same defect as
         * v0.19.1's in the opposite direction: a proxy standing in for
         * consequence and getting it wrong.
         *
         * Defaults TRUE, and the default is the whole design:
         *
         *  - A client posting to your brief is asserting that this needs
         *    you. That assertion is the commitment, so every client-posted
         *    item qualifies without any client having to change.
         *  - The notification listener sets it FALSE
         *    ([BriefPolicy.INFERRED_CLIENT_ID]'s items), because it is
         *    reporting what the phone said rather than what you decided.
         *    That is precisely the stream that flooded the agenda.
         *
         * It cannot promote something settled or cancelled, and it does
         * not make a guessed date trustworthy — see [BriefTriage].
         */
        val committed: Boolean = true,
        /**
         * When this obligation began, if the client said: a task's creation,
         * a follow-up's first nudge. Drives the age shown on an undated row,
         * which otherwise could only be the age of the client's last post.
         * Null for everything Medha read off the shade, whose own post time
         * is already the right answer.
         */
        val since: Long? = null
     ) {
        /** True when the client has said this obligation no longer stands. */
        val settled: Boolean get() = state == STATE_DONE || state == STATE_CANCELLED
     }

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
        val kind: String? = null,
        /** [Item.source]: the app, when one item of a section knows. */
        val source: String? = null,
        /** [Item.dueInferred]: the date is a guess, not a commitment. */
        val dueInferred: Boolean = false,
        /** [Item.id]: the client's stable identity, for dismissals. */
        val id: String? = null,
        /** [Item.state]: what the client says about whether it still stands. */
        val state: String? = null,
        /** [Item.committed]: a person committed to this; an app did not. */
        val committed: Boolean = true,
        /** [Item.since]: when the obligation began, if the client said. */
        val since: Long? = null
    ) {
        /** True when the client has said this obligation no longer stands. */
        val settled: Boolean get() = state == STATE_DONE || state == STATE_CANCELLED
    }

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

            // Source breaks ties INSIDE a band; it never crosses one.
            //
            // A paired app DECLARES an item: it chose the title, the due
            // date, the kind, the link. The notification listener INFERS
            // one by parsing a notification written for something else
            // entirely. Declared beats inferred when the two are equally
            // urgent, which is what this ordering says.
            //
            // What it deliberately does NOT say is that inferred items rank
            // below everything. An overdue bill Medha read out of a bank
            // notification outranks an undated note a paired app posted,
            // and sorting by source first would bury it under the pile the
            // brief exists to cut through. Urgency first, then confidence.
            //
            // sortedBy is stable, so the recency order prune() established
            // survives inside each group.
            val ordered = queues.sortedBy { (s, _) ->
                if (s.clientId == INFERRED_CLIENT_ID) 1 else 0
            }

            if (band != null) {
                // A DATED band is an agenda, so it reads in time order.
                //
                // Round-robin across clients exists so one noisy app cannot
                // crowd the others out, and that is the right answer when
                // items are unranked. It is the wrong answer here: a day
                // whose rows are interleaved by which app posted them is a
                // filing cabinet, not a schedule, and the 09:00 stand-up
                // can sit below the 18:00 review because a different app
                // happened to write it.
                //
                // sortedBy is stable and `ordered` is already declared-
                // before-inferred within section recency, so two items at
                // the same instant keep that order.
                val flat = ordered.flatMap { (s, items) -> items.map { s to it } }
                    .sortedBy { (_, item) -> item.dueAt ?: Long.MAX_VALUE }
                for ((s, item) in flat) {
                    out += row(s, item)
                    if (out.size == maxRows) return out
                }
            } else {
                // UNDATED items have no time to sort by, so fairness is all
                // there is: one from each client, then a second from each.
                val deepest = ordered.maxOf { (_, items) -> items.size }
                for (depth in 0 until deepest) {
                    for ((s, items) in ordered) {
                        val item = items.getOrNull(depth) ?: continue
                        out += row(s, item)
                        if (out.size == maxRows) return out
                    }
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
     * NO LONGER DRAWN BY ANYTHING, and kept as pure arithmetic rather than
     * deleted because the tests around it also pin [pageCount]'s wrapping.
     * v0.13.0 stopped the widget rotating: a brief is read in a two-second
     * glance, so a page that moves means the row you came to check may not
     * be on screen when you look. [BriefRender] takes the first N rows of an
     * urgency-ordered list instead, and names what is under the fold.
     *
     * If you are here because you want paging back, the answer is almost
     * certainly a bigger widget: there are three sizes now.
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
    private fun row(s: Section, item: Item) = Row(
        title = item.title,
        text = item.text,
        clientId = s.clientId,
        updatedAt = s.updatedAt,
        link = item.link,
        dueAt = item.dueAt,
        kind = item.kind,
        source = item.source,
        dueInferred = item.dueInferred,
        id = item.id,
        state = item.state,
        committed = item.committed,
        since = item.since
    )

    /**
     * The wall-clock time of [dueAt], but only when it carries one.
     *
     * "today" is the right mark for a bill. It is useless for a meeting:
     * the Milestone Program slot arrived with "17:30 - 18:00" in its body,
     * dueAtFrom read the time correctly, and the row still showed "today"
     * because every mark went through the day-granular [relative]. The time
     * was captured and then thrown away at the last step.
     *
     * Null when the due instant lands exactly on local midnight, which is
     * what a date-only obligation looks like once it is a Long. Drawing
     * "00:00" on a bill due Saturday would invent a precision nobody
     * supplied.
     */
    fun clockAt(dueAt: Long?, now: Long, offsetMs: Long = 0L): String? {
        if (dueAt == null) return null
        if (dayDelta(dueAt, now, offsetMs) != 0) return null
        val local = dueAt + offsetMs
        val sinceMidnight = ((local % DAY_MS) + DAY_MS) % DAY_MS
        if (sinceMidnight == 0L) return null
        val h = sinceMidnight / (60 * 60 * 1000L)
        val m = (sinceMidnight / (60 * 1000L)) % 60
        return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
    }

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

    /** The obligation still stands. */
    const val STATE_OPEN = "open"

    /** It was met. Kept briefly, off the agenda — "paid" is worth seeing once. */
    const val STATE_DONE = "done"

    /** It stopped existing. Off the agenda; see [Item.state]. */
    const val STATE_CANCELLED = "cancelled"

    val STATES = setOf(STATE_OPEN, STATE_DONE, STATE_CANCELLED)

    /**
     * Normalises a caller's `state`, or null if it is not one Medha knows.
     *
     * Unknown values become null — "the client did not say" — rather than an
     * error, for the same reason an unknown `kind` does: a vocabulary Medha
     * will extend later must not break a client written against it today.
     * The cost is bounded, because null means the title rule still applies.
     *
     * `cancelled` and `canceled` both arrive in the wild, from the same
     * split that made [BriefTriage]'s regex accept `cancell?ed`. Both map to
     * [STATE_CANCELLED] rather than one of them silently meaning nothing.
     */
    fun stateOrNull(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s == "canceled") return STATE_CANCELLED
        return s.takeIf { it in STATES }
    }

    /** The provenance values `due.provenance` accepts on the wire. */
    const val PROVENANCE_DECLARED = "declared"
    const val PROVENANCE_INFERRED = "inferred"

    /**
     * Whether a wire `provenance` means the date was guessed.
     *
     * Defaults to declared for anything unrecognised, INCLUDING null, which
     * matches how [Item.dueInferred] already behaves and keeps a v1 client's
     * dates trusted. The asymmetry is deliberate: a client that went to the
     * trouble of sending a date in a structured field is asserting it, and
     * the one case that burned us was Medha scraping dates out of prose by
     * itself — which this field exists to let a client admit to.
     */
    fun inferredFrom(provenance: String?): Boolean =
        provenance?.trim()?.lowercase() == PROVENANCE_INFERRED

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

    /**
     * The section Medha writes for itself, from the phone's notifications.
     *
     * Every other section belongs to an app that paired and posted on
     * purpose. This one is Medha reading over the user's shoulder, so its
     * items are inferred rather than declared, and rows() ranks them last
     * among equals. Lives here rather than in the listener because it is a
     * fact about the brief's ordering, and the harness has to be able to
     * exercise it without an Android classpath.
     */
    const val INFERRED_CLIENT_ID = "notifications"

    const val MAX_SECTIONS = 8

    /**
     * Items one client may post.
     *
     * Was 5, which was sized for the WIDGET and wrong for the screen behind
     * it. PUT /brief rejects anything over this, so a client with six things
     * to say could not say them -- and hg_agent did the only thing left,
     * posting five rows where the fifth was a synthetic "+2 more · Open
     * Quicksilver". The full brief then listed a pointer to another app
     * instead of the two items it existed to show.
     *
     * Nothing downstream needed 5. The widget sizes carry their own
     * capacities (BriefRender.ROWS_*, at most 7) and fold the remainder; the
     * full screen takes EVERYTHING and scrolls. The cap only ever decided
     * what a client was allowed to SEND.
     */
    const val MAX_ITEMS_PER_SECTION = 20

    /**
     * Formerly the rows one page of the widget drew. Nothing pages any
     * more; the sizes carry their own capacities in `BriefRender`. Kept
     * because `page()` is kept. The old note read: keep this and the layout
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
