package com.adabala.medha.notify

/**
 * What earns a place in the brief, and what is merely a list.
 *
 * THE RULE
 *
 * An item belongs in the brief if ignoring it TODAY has a consequence.
 * Operationally that is: it has a time. Everything else is an open item —
 * real, worth keeping, worth finding, but not something a two-second glance
 * at a home screen has to carry.
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

    fun laneOf(item: BriefPolicy.Item): Lane =
        if (item.dueAt != null) Lane.AGENDA else Lane.OPEN

    fun laneOf(row: BriefPolicy.Row): Lane =
        if (row.dueAt != null) Lane.AGENDA else Lane.OPEN

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
