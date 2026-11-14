package com.adabala.medha.notify

/**
 * Whether Medha should interrupt you, and what it would say.
 *
 * Distinct from `POST /notify`, which is an app asking Medha to post
 * something on its behalf. This is Medha speaking for itself, about the
 * brief, which is the only thing it owns an opinion on.
 *
 * WHY THIS IS A PURE FUNCTION
 *
 * Deciding to interrupt someone is the easiest thing in an app to get
 * wrong, and the wrongness is invisible in code review: a nudge that fires
 * twice, or at 3am, or every time a widget redraws, reads as perfectly
 * reasonable logic until it is on a real phone at 3am. All of it lives here
 * and all of it has tests.
 *
 * THE RULES, AND WHY EACH ONE EXISTS
 *
 * - Only for OVERDUE. A thing due next week does not justify a sound. If
 *   the brief has nothing overdue there is no nudge, however full it is.
 * - Never more than once per [DEFAULT_GAP_MS]. Without this, every write to
 *   the brief would be a notification, and a digest that pings on every
 *   update is just the notifications it was supposed to replace.
 * - Never during quiet hours. An overdue bill at 2am is still overdue at
 *   8am, and nothing on a brief is worth a phone lighting up at night.
 * - Nothing when the count has not grown. Being told "3 overdue" twice is
 *   how people learn to swipe a notification away without reading it.
 */
object BriefNudge {

    /** Twelve hours: at most a morning and an evening. */
    const val DEFAULT_GAP_MS = 12 * 60 * 60 * 1000L

    /** 22:00. Local, from the offset the caller passes in. */
    const val QUIET_FROM_HOUR = 22

    /** 07:00. */
    const val QUIET_UNTIL_HOUR = 7

    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    /**
     * What the user would be told.
     *
     * [overdue] is the count. [worst] is the oldest overdue item's title,
     * so the notification can name one thing instead of only counting:
     * "3 overdue" is a statistic, "ICICI credit card, 13d over" is a
     * reminder.
     */
    data class Nudge(val overdue: Int, val worst: String, val worstMark: String)

    /** The state a caller has to remember between nudges. */
    data class Memory(val lastAt: Long, val lastCount: Int)

    val NEVER = Memory(0L, 0)

    /** True inside quiet hours, local time. */
    fun quiet(now: Long, offsetMs: Long): Boolean {
        val local = now + offsetMs
        val hour = ((local % DAY + DAY) % DAY / HOUR).toInt()
        // The window wraps midnight, so this is an OR rather than a range.
        return hour >= QUIET_FROM_HOUR || hour < QUIET_UNTIL_HOUR
    }

    /**
     * The nudge to post, or null.
     *
     * Everything needed to decide is a parameter: no clock, no preferences,
     * no Android. A caller that wants a nudge at a particular moment in a
     * particular timezone with a particular history can ask for exactly
     * that, which is what makes the rules checkable.
     */
    fun nudge(
        sections: List<BriefPolicy.Section>,
        now: Long,
        offsetMs: Long,
        memory: Memory,
        gapMs: Long = DEFAULT_GAP_MS
    ): Nudge? {
        if (quiet(now, offsetMs)) return null

        val overdue = BriefPolicy.overdueCount(sections, now, offsetMs)
        if (overdue == 0) return null

        // Within the gap, only a GROWING problem earns an interruption.
        // Something new going overdue is news; the same three still being
        // overdue is not.
        val withinGap = now - memory.lastAt < gapMs
        if (withinGap && overdue <= memory.lastCount) return null

        // The oldest overdue item: the one that has been ignored longest is
        // the one worth naming.
        val worst = BriefPolicy
            .rows(sections, now, BriefPolicy.MAX_SECTIONS * BriefPolicy.MAX_ITEMS_PER_SECTION, offsetMs)
            .firstOrNull { BriefPolicy.bandOf(it.dueAt, now, offsetMs) == BriefPolicy.Band.OVERDUE }
            ?: return null

        return Nudge(
            overdue = overdue,
            worst = worst.title,
            worstMark = BriefPolicy.relative(worst.dueAt, now, offsetMs).orEmpty()
        )
    }
}
