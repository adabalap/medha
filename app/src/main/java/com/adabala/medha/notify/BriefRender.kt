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
        /** null for an undated item. Chooses the bar colour, nothing else. */
        val band: BriefPolicy.Band?,
        /** One of [BriefPolicy.KINDS], or null. Chooses the row icon. */
        val kind: String?,
        val clientId: String
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
        mark = BriefPolicy.relative(row.dueAt, now, offsetMs)
            ?: BriefPolicy.age(row.updatedAt, now),
        band = BriefPolicy.bandOf(row.dueAt, now, offsetMs),
        kind = row.kind,
        clientId = row.clientId
    )

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
