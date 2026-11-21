package com.adabala.medha.notify

/**
 * What earns a place in the brief, and what is merely a list.
 *
 * THE RULE
 *
 * An item belongs in the brief if ignoring it TODAY has a consequence.
 * Operationally: two disqualifiers, then either of two ways in.
 *
 * Disqualified outright, whatever else is true:
 *
 *   - the client has said it is settled (done, cancelled), or
 *   - its own words announce a cancellation.
 *
 * Otherwise it reaches the agenda if EITHER:
 *
 *   - a person committed to it ([BriefPolicy.Item.committed]), or
 *   - it carries a time that was DECLARED rather than guessed out of
 *     prose.
 *
 * Everything else is an open item — real, worth keeping, worth finding,
 * but not something a two-second glance at a home screen has to carry.
 *
 * HOW THE RULE GOT HERE, BECAUSE IT HAS BEEN WRONG TWICE
 *
 * v0.19.1 required only a time. On one real evening the agenda held five
 * rows: three were emails that merely mentioned a time, one was a MEETING
 * CANCELLATION drawn at the cancelled meeting's hour, and one was a
 * genuine obligation. The guessed-date and cancellation conditions came
 * from that.
 *
 * v0.19.2 therefore required a time, declared. That was wrong in the
 * other direction, and it took a one-line report to see it: "pick up the
 * dry cleaning" has no date, never will, and is exactly what the brief is
 * for. The date rule kept it off the home screen permanently.
 *
 * So "dated" was never the real test. It was standing in for a question
 * it could not answer: did a PERSON decide this needs doing? The 17
 * undated items that started all this — a shopping list, a webinar
 * invitation, "Thanq thanq" — were not junk because they lacked dates.
 * They were junk because an app had mentioned them and nobody had
 * committed to them. [BriefPolicy.Item.committed] asks that directly, and
 * the date rule stays as the fallback for everything nobody vouched for.
 *
 * WHY A RULE AT ALL
 *
 * Because without one the brief had no definition, and a surface with no
 * definition cannot be relied on. The state that prompted this was 19 items
 * of which 17 were undated: a ₹2,50,000 loan instalment and a 17:30 meeting
 * sharing a scroll with a shopping list, a webinar invitation, a Teams
 * "sent a message", and a WhatsApp "Thanq thanq". Nothing was broken. Every
 * one of those had passed the filter, because the filter only ever asked
 * whether a notification was junk, never whether it was an OBLIGATION.
 *
 * The cost of that is not clutter, it is the glance. A brief you skim is a
 * brief you miss the instalment in.
 *
 * WHAT THIS DELIBERATELY IS NOT
 *
 * It is not a cap. Nothing is discarded and nothing is hidden: [OPEN] items
 * keep their own surface, in full. A day with fifteen meetings shows
 * fifteen, because that is what the day is.
 *
 * It is also not a judgement about importance. "Dated" is a proxy for
 * consequence, chosen because it is the one signal that is objective. A
 * model may later demote a dated item it can see is noise; it may not
 * promote an undated one by deciding it feels urgent.
 *
 * Pure: no Android, no clock. The harness runs it.
 */
object BriefTriage {

    enum class Lane {
        /** Has a time. Draws on the widget and leads the full brief. */
        AGENDA,

        /** No time. Kept, listed and searchable; never on the widget. */
        OPEN
    }

    /**
     * Words that mean an obligation has STOPPED existing.
     *
     * "Canceled: DACH Knowledge hour" sat on the agenda at 21:30 as
     * though it were happening. A cancellation is the absence of an
     * appointment, and putting it where an appointment goes is worse than
     * noise — it is wrong information in the one place that is supposed
     * to be reliable.
     *
     * Matched at the START of the title or body, which is where mail and
     * calendar clients put it. Deliberately not matched anywhere in the
     * text: "the 4pm is not cancelled" would otherwise disappear, and
     * silently dropping a real meeting is a far worse failure than
     * showing a cancelled one.
     */
    private val CANCELLED = Regex(
        """^\s*(?:re:\s*|fwd:\s*)*(cancell?ed|declined|withdrawn)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isCancellation(title: String, text: String): Boolean =
        CANCELLED.containsMatchIn(title) || CANCELLED.containsMatchIn(text)

    /**
     * Four conditions, all required: it has a time, the time was declared
     * rather than guessed, the client has not said it is settled, and its
     * own words do not announce a cancellation.
     */
    fun laneOf(item: BriefPolicy.Item): Lane = lane(
        item.dueAt, item.dueInferred, item.settled, item.committed, item.title, item.text
    )

    fun laneOf(row: BriefPolicy.Row): Lane = lane(
        row.dueAt, row.dueInferred, row.settled, row.committed, row.title, row.text
    )

    /**
     * [settled] is the client asserting that the obligation no longer
     * stands — `state` of `done` or `cancelled` on the v2 protocol. It can
     * only demote, never promote, which is why it sits beside the title
     * rule rather than replacing it.
     *
     * A client sending `state: "open"` therefore does NOT rescue an item
     * whose title begins "Cancelled:". The two mistakes cost different
     * amounts: a wrongly demoted item is still on the full brief one tap
     * away, while a wrongly promoted one is how a cancelled meeting came to
     * sit on the home screen at the cancelled meeting's hour. Where the two
     * signals disagree, the quieter answer wins, and `GET /widget/content`
     * echoes the lane so the client can see it happened.
     */
    private fun lane(
        dueAt: Long?,
        inferred: Boolean,
        settled: Boolean,
        committed: Boolean,
        title: String,
        text: String
    ): Lane {
        // Two disqualifiers, checked first because nothing overrides them.
        // A settled obligation has stopped existing; a title that announces
        // a cancellation is the fallback for clients that cannot say so.
        if (settled || isCancellation(title, text)) return Lane.OPEN

        // Then the two ways in. EITHER is enough, and that is the change:
        // the rule used to require a date, which kept "pick up the dry
        // cleaning" off the home screen forever while letting any app that
        // mentioned a time onto it.
        //
        //   - committed: a person decided this needs doing. A date is not
        //     what makes it matter, and most of a person's real list has
        //     no date at all.
        //   - dated AND declared: nobody committed to it here, so it needs
        //     the one objective signal instead — and the date has to have
        //     been DECLARED, not scraped out of prose, or v0.19.1 happens
        //     again.
        val dated = dueAt != null && !inferred
        return if (committed || dated) Lane.AGENDA else Lane.OPEN
    }

    /**
     * The sections restricted to one lane, dropping any left with no items.
     *
     * Returning sections rather than items keeps every downstream caller —
     * ordering, attribution, the overdue count — working on the shape it
     * already understands, so the lane split does not leak through the rest
     * of the pipeline.
     *
     * An empty section is removed rather than kept empty: `prune` and the
     * contributor count both treat a section's existence as "this app has
     * something to say", and a client with only undated items has nothing
     * to say to the agenda.
     */
    fun only(sections: List<BriefPolicy.Section>, lane: Lane): List<BriefPolicy.Section> =
        sections.mapNotNull { s ->
            val kept = s.items.filter { laneOf(it) == lane }
            if (kept.isEmpty()) null else s.copy(items = kept)
        }

    /** How many items sit in [lane], across every section. */
    fun count(sections: List<BriefPolicy.Section>, lane: Lane): Int =
        sections.sumOf { s -> s.items.count { laneOf(it) == lane } }
}
