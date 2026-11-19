package com.adabala.medha.notify

/**
 * What earns a place in the brief, and what is merely a list.
 *
 * THE RULE
 *
 * An item belongs in the brief if ignoring it TODAY has a consequence.
 * Operationally that is four conditions, all required:
 *
 *   1. it has a time,
 *   2. the time was DECLARED rather than guessed out of prose,
 *   3. the client has not said the obligation is settled, and
 *   4. its own words do not announce a cancellation.
 *
 * Everything else is an open item — real, worth keeping, worth finding,
 * but not something a two-second glance at a home screen has to carry.
 *
 * Conditions 2 and 4 were added after the first version shipped with only
 * condition 1, which was not enough and failed in the most damaging
 * direction. See [BriefPolicy.Item.dueInferred] and [isCancellation]: on
 * one real evening the agenda held five rows, of which three were emails
 * that merely mentioned a time, one was a MEETING CANCELLATION drawn at
 * the cancelled meeting's hour, and one was a genuine obligation.
 *
 * Condition 3 is the v0.20.0 protocol letting a client say what it knows
 * instead of leaving Medha to read it off a subject line. Condition 4 is
 * that same judgement made badly, kept as the fallback for every client
 * that cannot make it. Both only demote — see [BriefPolicy.Item.state].
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
        item.dueAt, item.dueInferred, item.settled, item.title, item.text
    )

    fun laneOf(row: BriefPolicy.Row): Lane = lane(
        row.dueAt, row.dueInferred, row.settled, row.title, row.text
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
        title: String,
        text: String
    ): Lane =
        if (dueAt != null && !inferred && !settled && !isCancellation(title, text)) {
            Lane.AGENDA
        } else {
            Lane.OPEN
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
