package com.adabala.medha.notify

/**
 * Letting someone remove a line from their own brief.
 *
 * WHY THIS IS NOT JUST "DELETE THE ITEM"
 *
 * Clients own their section and re-post it whole. hg_agent sends five items,
 * Medha stores five items, and the next sync sends five again — so deleting
 * one from the store removes it for about thirty seconds. A dismissal has to
 * be remembered separately from the content and applied every time the brief
 * is read, or the gesture silently does nothing, which is worse than not
 * offering it.
 *
 * WHAT A DISMISSAL IS KEYED ON
 *
 * The client, the title, and the DUE DATE together. The due date is the part
 * that matters: dismissing November's electricity bill must not also dismiss
 * December's, and those differ by nothing except when they are due. An
 * undated item falls back to client and title, which is the best identity
 * available for something with no other distinguishing mark.
 *
 * A re-post with an updated countdown keeps the same dueAt, so it stays
 * dismissed. A genuinely new instance has a new dueAt and comes back. That
 * is the behaviour people expect without being able to describe it.
 *
 * AND WHY THEY EXPIRE
 *
 * [TTL_MS], so the list cannot grow forever on a device nobody prunes, and
 * so an undated item dismissed once is not banished for the life of the
 * install. Thirty days is longer than any sensible brief item lives.
 */
object BriefDismiss {

    /** Thirty days. */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * The most dismissals kept. Oldest are dropped first.
     *
     * Bounded because this is a SharedPreferences string that is read on
     * every brief render; unbounded growth here would be a slow leak that
     * only ever shows up as the home screen getting gradually slower.
     */
    const val MAX = 200

    /** A dismissal: what, and when it happened. */
    data class Mark(val key: String, val at: Long)

    /**
     * Identity, length-prefixed.
     *
     * The obvious version — join the three with a separator character —
     * collides: client "a" with title "x<sep>y" produces exactly the same
     * string as client "a<sep>x" with title "y", so one client could dismiss
     * another's item by choosing a title. A test caught it. Writing each
     * part's length first makes the encoding unambiguous whatever the parts
     * contain.
     *
     * Control characters are flattened to spaces as well, because the key
     * is stored one-per-line and a title containing a newline would
     * otherwise corrupt every mark after it.
     */
    fun key(clientId: String, title: String, dueAt: Long?): String {
        val c = clean(clientId)
        val t = clean(title)
        return "${c.length}:$c|${t.length}:$t|${dueAt?.toString() ?: ""}"
    }

    private fun clean(s: String): String =
        s.trim().lowercase().map { if (it.code < 0x20) ' ' else it }.joinToString("")

    fun key(clientId: String, item: BriefPolicy.Item): String =
        key(clientId, item.title, item.dueAt)

    /** Drops expired marks and trims to [MAX], newest kept. */
    fun prune(marks: List<Mark>, now: Long, ttlMs: Long = TTL_MS): List<Mark> =
        marks.asSequence()
            .filter { now - it.at in 0 until ttlMs }
            .sortedByDescending { it.at }
            .distinctBy { it.key }
            .take(MAX)
            .toList()

    /** Adds [keys], replacing any earlier mark for the same key. */
    fun add(marks: List<Mark>, keys: Collection<String>, now: Long): List<Mark> =
        prune(keys.map { Mark(it, now) } + marks, now)

    /**
     * Every key in [sections], for "dismiss all of these".
     *
     * Takes a filter so the caller decides what a group IS — one band, one
     * client, everything — without this file needing to know about bands.
     */
    fun keysOf(
        sections: List<BriefPolicy.Section>,
        where: (BriefPolicy.Section, BriefPolicy.Item) -> Boolean = { _, _ -> true }
    ): List<String> = sections.flatMap { s ->
        s.items.filter { where(s, it) }.map { key(s.clientId, it) }
    }

    /**
     * The brief with dismissed items removed.
     *
     * A section emptied by dismissal is dropped entirely rather than left as
     * a heading with nothing under it.
     */
    fun apply(
        sections: List<BriefPolicy.Section>,
        marks: List<Mark>,
        now: Long,
        ttlMs: Long = TTL_MS
    ): List<BriefPolicy.Section> {
        if (marks.isEmpty()) return sections
        val live = prune(marks, now, ttlMs).mapTo(HashSet()) { it.key }
        if (live.isEmpty()) return sections
        return sections.mapNotNull { s ->
            val kept = s.items.filterNot { key(s.clientId, it) in live }
            when {
                kept.isEmpty() -> null
                kept.size == s.items.size -> s
                else -> s.copy(items = kept)
            }
        }
    }

    // ---------------------------------------------------------------- storage
    //
    // One line per mark, "at<TAB>key". Hand-rolled rather than JSON because
    // this is read on every brief render including every widget redraw, and
    // because it keeps the whole file compilable by the test harness with no
    // org.json stub. Malformed lines are skipped rather than throwing: a
    // corrupt preference must not be able to make the brief unreadable.

    fun encode(marks: List<Mark>): String =
        marks.joinToString("\n") { "${it.at}\t${it.key}" }

    fun decode(raw: String?): List<Mark> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val k = line.substring(tab + 1)
            if (k.isEmpty()) null else Mark(k, at)
        }.toList()
    }
}
