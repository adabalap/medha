package com.adabala.medha.notify

/**
 * When the brief will next look different, so the widget can be redrawn then.
 *
 * WHY THIS EXISTS
 *
 * The widget's whole premise is urgency at a glance, and its urgency was
 * frozen at the moment some app last posted. `updatePeriodMillis` is 0, no
 * alarm existed, and nothing else asked for a redraw -- so a row that said
 * "in 2h" at noon still said "in 2h" at six, in the same colour, and the
 * header's overdue count was wrong along with it.
 *
 * The banding itself was never broken. [BriefPolicy.bandOf] and
 * [BriefPolicy.relative] both take `now` and were always correct for the
 * instant they ran. They simply never ran again.
 *
 * WHY THIS IS SMALLER THAN IT SOUNDS
 *
 * Every time-dependent thing the widget draws goes through
 * [BriefPolicy.dayDelta], which floors both the due date and now to whole
 * LOCAL days. The band, the "in 6d" mark, the "today"/"tomorrow" wording --
 * all of them change at local midnight and at no other moment. There is no
 * per-item schedule to build and no minute ticking to follow.
 *
 * That leaves exactly two things that can change the drawing without anyone
 * posting:
 *
 *   1. local midnight, if any item carries a due date at all, and
 *   2. a section reaching its expiresAt and being pruned away.
 *
 * So the answer is the earlier of those, and null when neither applies --
 * an empty brief, or one where nothing is dated and nothing expires soon,
 * needs no alarm and should not arm one.
 *
 * Pure: no Android, no clock of its own. `now` and the UTC offset are
 * passed in, which is what lets the harness run it.
 */
object BriefClock {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * The next local midnight strictly after [now].
     *
     * [offsetMs] is the device's UTC offset, the same value
     * [BriefPolicy.deviceOffset] supplies to banding, so midnight here is
     * the midnight the bands flip at rather than UTC's.
     *
     * At exactly midnight this returns the NEXT one, a full day later: the
     * flip for that instant has already happened, and returning `now` would
     * arm an alarm for a moment that has passed.
     */
    fun nextLocalMidnight(now: Long, offsetMs: Long): Long {
        val local = now + offsetMs
        // Non-negative remainder, so pre-epoch instants floor the same way
        // BriefPolicy.floorDay does rather than toward zero.
        val sinceMidnight = ((local % DAY_MS) + DAY_MS) % DAY_MS
        return now + (DAY_MS - sinceMidnight)
    }

    /**
     * When the widget's drawing next changes on its own, or null if it does
     * not.
     *
     * Null is a real answer and not a failure: it means no alarm is needed,
     * and arming a daily one anyway would cost a wakeup a day to redraw a
     * picture that cannot have changed.
     */
    fun nextChangeAt(
        sections: List<BriefPolicy.Section>,
        now: Long,
        offsetMs: Long
    ): Long? {
        val live = BriefPolicy.prune(sections, now)
        if (live.isEmpty()) return null

        val candidates = mutableListOf<Long>()

        // Midnight only matters if something is dated. A brief of undated
        // notes reads identically tomorrow.
        if (live.any { s -> s.items.any { it.dueAt != null } }) {
            candidates += nextLocalMidnight(now, offsetMs)
        }

        // A section vanishing changes the drawing as surely as a band flip.
        live.mapNotNullTo(candidates) { s -> s.expiresAt.takeIf { it > now } }

        return candidates.minOrNull()
    }
}
