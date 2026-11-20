package com.adabala.medha.notify

/**
 * The same obligation, arriving twice.
 *
 * WHY THIS HAS TO LIVE IN MEDHA
 *
 * Every other judgement about the brief belongs to the agent, which has the
 * corpus. This one cannot: a duplicate spans SECTIONS, and a client only
 * ever sees its own. The 17:30 meeting arrives as a reminder the agent took
 * from a note AND as a Teams notification the listener read off the phone,
 * and neither side can see both. Medha holds every section, so Medha is the
 * only thing that can notice.
 *
 * WHAT COUNTS AS THE SAME THING
 *
 * Two items match when their titles share enough distinctive words, and,
 * when both carry a time, those times fall on the same day. Crude on
 * purpose, and deliberately not the embeddings the agent has: this runs on
 * every widget redraw, and the consequence of a match is that a row
 * DISAPPEARS from someone's home screen. A rule you can read in ten seconds
 * is the right kind of rule for that.
 *
 * The bar is set so a miss is the likely failure. A missed duplicate costs
 * one repeated line, which is what happens today. A false match costs an
 * obligation the user never sees, which is the failure the brief exists to
 * prevent.
 *
 * WHICH COPY SURVIVES
 *
 * The better-sourced one, scored in this order:
 *
 *   1. committed over observed — a person decided this; an app mentioned it
 *   2. a declared date over a guessed one
 *   3. having a date at all over having none
 *   4. having a link over having none
 *
 * So the agent's reminder beats the Teams notification about the same
 * meeting, and the surviving row keeps the tap target that actually opens
 * the note. Ties keep the EARLIER section, which is the one that has been
 * contributing longest.
 *
 * Pure: no Android, no clock beyond the offset it is handed. The harness
 * runs it.
 */
object BriefResolve {

    /**
     * How much of the shorter title must be shared.
     *
     * Measured against the smaller set so a four-word reminder is not
     * punished for being matched against a notification that quotes a whole
     * thread.
     */
    const val MIN_OVERLAP = 0.6

    /**
     * And at least this many words, whatever the ratio says.
     *
     * A ratio alone is not identification: a one-word title scores 1.0
     * against anything containing that word, so "Rent" would swallow every
     * row mentioning rent. Two shared words is the floor, which means a
     * one-word title can never be resolved away — the safe failure, since a
     * miss leaves the duplicate visible and a false match hides something.
     */
    const val MIN_SHARED = 2

    /**
     * Words that identify nothing.
     *
     * Mail and calendar clients prepend half of these, and a person's own
     * notes are full of the rest. Leaving them in made unrelated rows look
     * similar and, worse, made genuinely identical rows look weak once the
     * prefixes differed.
     */
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "for", "to", "in", "on", "at",
        "by", "with", "your", "you", "is", "are", "was", "has", "have", "be",
        "been", "this", "that", "it", "its", "re", "fw", "fwd", "fyi",
        "meeting", "invitation", "invite", "invitation:", "reminder", "call",
        "update", "updated", "new", "notice", "alert", "my", "me",
        "cancelled", "canceled", "accepted", "declined", "tentative",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat",
        "sun", "today", "tomorrow", "yesterday", "am", "pm"
    )

    private val WORD = Regex("[a-z0-9]{3,}")

    /** The identifying words of a title, lowercased and stripped. */
    fun tokens(title: String): Set<String> =
        WORD.findAll(title.lowercase()).map { it.value }.filterNot { it in STOP }.toSet()

    /** Share of the smaller set present in both. Zero when either is empty. */
    fun overlap(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0
        else (a intersect b).size / minOf(a.size, b.size).toDouble()

    /**
     * Whether two items are the same real-world obligation.
     *
     * The day check is the half that stops a recurring meeting from
     * swallowing its own future instances: a standup today and the same
     * standup tomorrow share every word, and collapsing them would hide
     * tomorrow's. When only ONE carries a time, the words decide — that is
     * the case this exists for, where the agent has the note and the phone
     * has the notification and only one of them knows when.
     */
    fun same(a: BriefPolicy.Item, b: BriefPolicy.Item, offsetMs: Long): Boolean {
        val ta = tokens(a.title)
        val tb = tokens(b.title)
        // One rule, not two. A pre-check on each title's size reads like a
        // guard and cannot change the answer: an intersection is never
        // larger than the smaller set, so a title with fewer than
        // MIN_SHARED identifying words falls out here anyway. A mutation
        // that deleted half of that pre-check left every test green, which
        // is what a condition that cannot fire looks like from the outside.
        if ((ta intersect tb).size < MIN_SHARED) return false
        if (overlap(ta, tb) < MIN_OVERLAP) return false
        val da = a.dueAt
        val db = b.dueAt
        if (da != null && db != null) return day(da, offsetMs) == day(db, offsetMs)
        return true
    }

    private fun day(at: Long, offsetMs: Long): Long = Math.floorDiv(at + offsetMs, DAY_MS)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * How good a copy this is. Higher wins; see the class comment.
     *
     * Weights are powers of two so the order is strict rather than a blend:
     * no number of links ever outweighs a person having committed to
     * something, which is the one comparison that must never be close.
     */
    fun quality(item: BriefPolicy.Item): Int =
        (if (item.committed) 8 else 0) +
            (if (item.dueAt != null && !item.dueInferred) 4 else 0) +
            (if (item.dueAt != null) 2 else 0) +
            (if (item.link != null) 1 else 0)

    /**
     * The sections with cross-section duplicates removed.
     *
     * Only ACROSS sections. A client repeating itself inside its own section
     * is that client's business and it may well be deliberate — two bills
     * from the same merchant are two bills. Medha's standing is that it sees
     * what no single client can, and it should not use that to start editing
     * what one client said about itself.
     *
     * Sections keep their order and their metadata; a section emptied by
     * resolution is dropped, like everywhere else, so no heading is left
     * with nothing under it.
     */
    fun dedupe(
        sections: List<BriefPolicy.Section>,
        offsetMs: Long = 0L
    ): List<BriefPolicy.Section> {
        // An optimisation, not a rule: a one-section brief has no
        // cross-section pair to find, so the loop below would reach the
        // same answer after building an index for nothing. Removing this
        // line changes no result, which is exactly what it should do.
        if (sections.size < 2) return sections

        // BEST FIRST, and only a SURVIVOR may eliminate anything.
        //
        // The obvious implementation — walk every pair, drop the worse one —
        // is wrong, and wrong in a way that is invisible until you look for
        // it. Matching is not transitive: A can match B, B match C, and A
        // not match C. Pairwise, B loses to A and then goes on to eliminate
        // C, so C vanishes although nothing that survived ever matched it.
        //
        // Worse, whether that happens depends on the order the sections
        // arrive in, which is just whichever client posted first. Three
        // obligations, six orderings, and the first version of this returned
        // two rows for three of them and one row for the other three. A
        // brief that loses a row depending on who wrote to it first is not
        // one anybody can rely on.
        //
        // So: sort every item by quality, best first, and keep an item only
        // when no item ALREADY KEPT, from another section, is the same
        // thing. A dropped item is never in `kept`, so it can never take
        // anything with it, and the sort makes the outcome independent of
        // the order sections happened to be in.
        //
        // Quadratic in the worst case and bounded by MAX_SECTIONS x
        // MAX_ITEMS_PER_SECTION, on a path that runs per redraw.
        data class Ref(val section: Int, val index: Int, val item: BriefPolicy.Item)
        val all = sections.flatMapIndexed { s, sec ->
            sec.items.mapIndexed { i, it -> Ref(s, i, it) }
        }

        // Ties fall to the earlier section, then the earlier item: the copy
        // that has been on the widget longest, rather than whichever the
        // sort happened to reach first.
        val best = all.sortedWith(
            compareByDescending<Ref> { quality(it.item) }
                .thenBy { it.section }
                .thenBy { it.index }
        )

        val kept = ArrayList<Ref>(all.size)
        val dropped = HashSet<Pair<Int, Int>>()
        for (r in best) {
            val covered = kept.any {
                it.section != r.section && same(it.item, r.item, offsetMs)
            }
            if (covered) dropped += r.section to r.index else kept += r
        }
        if (dropped.isEmpty()) return sections

        return sections.mapIndexedNotNull { s, sec ->
            val kept = sec.items.filterIndexed { i, _ -> (s to i) !in dropped }
            if (kept.isEmpty()) null else sec.copy(items = kept)
        }
    }

    /** How many items [dedupe] would remove. For the response and the logs. */
    fun duplicateCount(sections: List<BriefPolicy.Section>, offsetMs: Long = 0L): Int =
        sections.sumOf { it.items.size } - dedupe(sections, offsetMs).sumOf { it.items.size }
}
