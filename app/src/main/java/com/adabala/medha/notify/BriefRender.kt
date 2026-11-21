package com.adabala.medha.notify

/**
 * What a brief widget should draw, decided with no Android in sight.
 *
 * WHY THIS FILE EXISTS
 *
 * MedhaWidgetProvider cannot be compiled by anything short of Gradle: it
 * needs RemoteViews, Context and an Android classpath, so neither the
 * pure-logic harness nor any checker in tools/ can execute a line of it. For
 * two releases that meant the widget's actual decisions — which rows survive
 * the cut, what the countdown says, what the footer claims is hidden — were
 * the only part of the brief with no tests at all, and it showed: `dueAt`
 * was accepted, stored and echoed back for two releases while the widget
 * quietly ignored it.
 *
 * So the decisions live here, where kotlinc can run them, and the provider
 * is left as a binder: take a [Face], push the strings and resource ids into
 * RemoteViews, stop. Nothing in this file may import Android, and nothing
 * here may resolve a string or colour resource — a decision that needs
 * `R.anything` is a decision that belongs in the provider.
 *
 * That is also why [Fold] is a type rather than a sentence. "2 more ·
 * nothing else due this week" has a number and a plural in it and belongs in
 * strings.xml; what is testable is *which* of the three statements is true,
 * and that is what this returns.
 */
object BriefRender {

    /**
     * Row slots in each layout.
     *
     * These are layout facts and the layouts must agree with them, which is
     * why tools/check_widget_wiring.py counts the row ids in each XML and
     * compares. A layout with four slots and a capacity of three silently
     * drops an item, which is the v0.9.0 bug (five items posted against a
     * three-row layout, two discarded in silence) waiting to happen again.
     *
     * 2x2 shows one line on purpose. At that size a second line is two
     * truncated titles instead of one readable one, and the whole point of
     * the small size is "what is next", not "what is there".
     *
     * These are counts that FIT, measured against a real 4x2 on a One UI
     * home screen, not counts that look right on a design board. v0.13.0 put
     * eight here for the 4x4 and three rows plus a footer into a 4x2 that
     * holds about 180dp; the footer came out sliced in half. The layouts now
     * also give the row area the flexible height, so if a denser launcher
     * grid squeezes one of these the ROW clips -- which reads as "more
     * below" -- and the footer, which is load-bearing, never does.
     */
    const val ROWS_2X2 = 1
    const val ROWS_4X2 = 3
    const val ROWS_4X4 = 7

    /**
     * The most rows the store can hold: every section full.
     *
     * Used instead of [BriefPolicy.MAX_ROWS] when flattening, so that the
     * fold counts what is really hidden rather than what the paging budget
     * happened to retain. Telling someone "2 more" when nine are hidden is
     * the same class of lie as a stale countdown.
     */
    private const val EVERYTHING = BriefPolicy.MAX_SECTIONS * BriefPolicy.MAX_ITEMS_PER_SECTION

    /** One drawn line. */
    data class Line(
        val title: String,
        val text: String,
        /**
         * The right-hand mark: "10d over", "in 6d", "today" — or, when the
         * item carries no dueAt, how long ago its app wrote, which is then
         * the only freshness signal available.
         */
        val mark: String,
        /** null for an undated item. Chooses the pill colour, nothing else. */
        val band: BriefPolicy.Band?,
        /**
         * The raw instant, carried alongside the derived [band] and [mark].
         *
         * Needed because a dismissal's identity includes the due date — so
         * dismissing November's bill does not also dismiss December's — and
         * the screen dismisses from a Line rather than going back to the
         * raw sections to find the item again.
         */
        val dueAt: Long?,
        /** One of [BriefPolicy.KINDS], or null. Chooses the row icon. */
        val kind: String?,
        val clientId: String,
        /**
         * The app a notification came from, when one did.
         *
         * Attribution is resolved where a Context exists: `source` when it
         * is set, otherwise the posting client's registered NAME, and only
         * as a last resort [clientId]. The literal strings "notifications"
         * and "admin" are registry ids and were never meant to be read by
         * a person.
         */
        val source: String? = null,
        /** [BriefPolicy.Item.dueInferred]: the date is a guess. */
        val dueInferred: Boolean = false,
        /**
         * Carried so the full-brief screen can open a row without going back
         * to the raw sections and re-deriving the order. Already filtered to
         * http(s) on write and on read; the screen filters once more before
         * it starts an activity, because that is the line that actually acts
         * on it.
         */
        val link: String?,
        /**
         * [BriefPolicy.Item.id]: the client's own identity for this
         * obligation, carried for exactly one reason — the full-brief
         * screen dismisses from a Line, and a dismissal's key prefers the
         * id when there is one.
         *
         * Without it that screen would compute the title-based key while
         * [BriefDismiss.apply] computed the id-based one, and tapping
         * Dismiss on a v2 item would appear to work and change nothing.
         */
        val id: String? = null,
        /**
         * [BriefPolicy.Row.updatedAt]: when the posting app last wrote this.
         *
         * Carried for Save, and for one property of it. A save's identity has
         * to tell "pressed Save twice" apart from "saved the same grocery
         * list again next week", and for an undated row this instant is the
         * only thing that does: the words are identical every week, and
         * [dueAt] is null. Without it either the double tap duplicates or
         * the second week's list is silently refused.
         */
        val postedAt: Long? = null
    )

    /**
     * What is below the fold, as a claim rather than a sentence.
     *
     * [Overdue] should be rare and is not impossible: overdue sorts first,
     * so it only appears when more things are overdue than the widget has
     * room for. That is exactly when the user most needs to be told.
     */
    sealed class Fold {
        data class Overdue(val more: Int, val overdue: Int) : Fold()
        data class Soon(val more: Int, val soon: Int) : Fold()
        data class Clear(val more: Int) : Fold()
    }

    /** Everything one widget needs in order to draw itself. */
    data class Face(
        val lines: List<Line>,
        /** Overdue across the whole brief, drawn or not. Feeds the header badge. */
        val overdue: Int,
        /** null when nothing is hidden. */
        val fold: Fold?,
        /** Apps contributing. Below 2, per-line attribution is noise. */
        val contributors: Int,
        val total: Int
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    fun face(
        sections: List<BriefPolicy.Section>,
        now: Long,
        offsetMs: Long,
        capacity: Int
    ): Face {
        val ordered = BriefPolicy.rows(sections, now, EVERYTHING, offsetMs)
        val shown = ordered.take(capacity)
        return Face(
            lines = shown.map { line(it, now, offsetMs) },
            overdue = BriefPolicy.overdueCount(sections, now, offsetMs),
            fold = fold(ordered.drop(capacity), now, offsetMs),
            contributors = BriefPolicy.prune(sections, now).size,
            total = ordered.size
        )
    }

    fun line(row: BriefPolicy.Row, now: Long, offsetMs: Long): Line = Line(
        title = row.title,
        text = row.text,
        // Derived here, every render, rather than taken from the client. A
        // string posted as "overdue 10 days" is right when written and wrong
        // a few hours later; this cannot go stale on a home screen.
        // A clock time first when the item has one and it is today.
        // "today" is the right mark for a bill and useless for a meeting:
        // the 17:30 slot arrived with its time parsed correctly and still
        // drew "today", because every mark went through the day-granular
        // relative(). The time was captured and discarded at the last step.
        // A GUESSED date is not drawn at all.
        //
        // An inferred date cannot promote an item (BriefTriage), but it
        // was still being printed in the mark column, so an email would
        // sit in open items confidently labelled "tomorrow". A guess
        // rendered in the same type as a commitment is still a lie; the
        // age is the honest thing to show, and it is what an undated row
        // shows anyway. The guess is kept in the data for later use.
        mark = (if (row.dueInferred) null else BriefPolicy.clockAt(row.dueAt, now, offsetMs))
            ?: (if (row.dueInferred) null else BriefPolicy.relative(row.dueAt, now, offsetMs))
            ?: BriefPolicy.age(row.updatedAt, now),
        band = BriefPolicy.bandOf(row.dueAt, now, offsetMs),
        dueAt = row.dueAt,
        kind = row.kind,
        clientId = row.clientId,
        source = row.source,
        dueInferred = row.dueInferred,
        link = row.link,
        id = row.id,
        postedAt = row.updatedAt
    )

    /**
     * The key a line is bucketed under on the full-brief screen: the APP it
     * came from, falling back to the client that posted it.
     *
     * [Line.source] and not [Line.clientId], because every notification-
     * sourced row shares the single client "notifications" while actually
     * coming from WhatsApp, Teams and Outlook. Bucketing by client would
     * put all of them in one pile labelled with a registry id nobody was
     * ever meant to read, which is the opposite of the point.
     */
    fun originKey(line: Line): String =
        line.source?.takeIf { it.isNotBlank() } ?: line.clientId

    /**
     * Lines grouped by where they came from, biggest bucket first.
     *
     * For the open-items part of the full brief, where the question is
     * "what is all this and can I clear it in one go" rather than "what do
     * I have to do". Biggest first because the bucket worth dismissing
     * wholesale is almost always the noisiest one, and making the user hunt
     * for it defeats the feature.
     *
     * Ties keep the order the lines were already in, which is the ranked
     * order — so two equal buckets appear in the order their most urgent
     * line did, not alphabetically.
     *
     * The AGENDA is deliberately not grouped this way anywhere. Splitting
     * what needs doing by which app mentioned it turns a list of
     * obligations back into an inbox: a ₹2,50,000 instalment and a 17:30
     * meeting belong next to each other because they are both today, not
     * in separate piles because one arrived as a bank message and the other
     * from Teams.
     */
    fun byOrigin(lines: List<Line>): List<Pair<String, List<Line>>> {
        if (lines.isEmpty()) return emptyList()
        val groups = LinkedHashMap<String, MutableList<Line>>()
        lines.forEach { groups.getOrPut(originKey(it)) { mutableListOf() }.add(it) }
        // sortedByDescending is stable, so equal sizes keep insertion order
        // and insertion order is the ranked order.
        return groups.entries.sortedByDescending { it.value.size }.map { it.key to it.value.toList() }
    }

    /**
     * Names the fold so a list that never moves is still safe to trust.
     *
     * This is the whole argument against rotating the widget. A fixed top
     * few rows are only honest if the user can tell that nothing urgent is
     * hiding underneath them — so the footer reports the count AND the worst
     * band still hidden, rather than a page number that says nothing about
     * what is on the other page.
     */
    fun fold(hidden: List<BriefPolicy.Row>, now: Long, offsetMs: Long): Fold? {
        if (hidden.isEmpty()) return null
        val bands = hidden.map { BriefPolicy.bandOf(it.dueAt, now, offsetMs) }
        val overdue = bands.count { it == BriefPolicy.Band.OVERDUE }
        if (overdue > 0) return Fold.Overdue(hidden.size, overdue)
        val soon = bands.count { it == BriefPolicy.Band.SOON }
        if (soon > 0) return Fold.Soon(hidden.size, soon)
        return Fold.Clear(hidden.size)
    }
}
