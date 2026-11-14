package com.adabala.medha.connect

import com.adabala.medha.notify.BriefPolicy

/**
 * Turns a kept notification into a brief item, and keeps the section tidy.
 *
 * Pure, for the same reason [NotificationFilter] is: the listener service
 * needs Android and cannot be executed by the harness, and "what the home
 * screen says about your meeting" should not be decided by untestable code.
 *
 * WHAT THIS IS AND IS NOT
 *
 * Heuristics, openly. A notification is prose written for a human, and
 * pulling a time out of it with regular expressions works for the phrasings
 * Teams and Outlook actually use and fails on the rest. That is acceptable
 * because failure here is a missing countdown on a row that still appears,
 * never a wrong one: [dueAtFrom] returns null rather than guessing, and a
 * null simply means the row shows how long ago it arrived.
 *
 * The on-device model is the intended replacement for exactly this function
 * and nothing else around it. When it lands it fills the same [BriefPolicy.Item],
 * so everything downstream — ordering, bands, the widget, the screen — is
 * already built and already tested against it.
 */
object NotificationItem {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * Which glyph the row gets.
     *
     * Package first, because an app's identity is a better signal than the
     * category it happened to set: Outlook posts mail and meetings under the
     * same category, and a calendar app posting `event` is already explicit.
     */
    fun kindFor(pkg: String, category: String?): String {
        val p = pkg.lowercase()
        return when {
            category?.lowercase() == "event" -> "event"
            category?.lowercase() == "err" -> "alert"
            p.contains("calendar") -> "date"
            p.contains("teams") || p.contains("zoom") || p.contains("meet") -> "event"
            p.contains("outlook") || p.contains("gm") && p.contains("mail") -> "note"
            p.contains("bank") || p.contains("upi") || p.contains("pay") -> "bill"
            else -> "note"
        }
    }

    /**
     * When the thing happens, from the words, or null.
     *
     * Null is a real answer and the common one. A row with no `dueAt` still
     * appears; it simply sits in the undated band and shows its age instead
     * of a countdown. Inventing a time would put a confident wrong number on
     * a home screen, which is worse than no number at all.
     *
     * [offsetMs] is the device's UTC offset, passed in rather than looked up
     * so a test can pin a timezone — the same discipline as BriefPolicy.
     */
    fun dueAtFrom(text: String, now: Long, offsetMs: Long = 0L): Long? {
        val s = text.lowercase()

        // "starts in 10 minutes", "in 1 hour" -- what a reminder actually
        // says, and the only form that needs no date arithmetic at all.
        Regex("""\bin (\d{1,3}) (min|mins|minute|minutes|hour|hours|hr|hrs|day|days)\b""")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@let
                val unit = when (m.groupValues[2]) {
                    "min", "mins", "minute", "minutes" -> MINUTE
                    "hour", "hours", "hr", "hrs" -> HOUR
                    else -> DAY
                }
                return now + n * unit
            }

        // "at 3:00 pm", "14:30". The colon is required: a bare "1430" is
        // indistinguishable from a passcode, and NotificationFilter has
        // already dropped anything that looks like one.
        val clock = Regex("""\b(\d{1,2}):(\d{2})\s*(am|pm)?""").find(s) ?: return null
        var hour = clock.groupValues[1].toIntOrNull() ?: return null
        val minute = clock.groupValues[2].toIntOrNull() ?: return null
        if (minute > 59) return null
        when (clock.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour > 23) return null

        val localNow = now + offsetMs
        val localMidnight = localNow - (localNow % DAY)
        var at = localMidnight + hour * HOUR + minute * MINUTE - offsetMs

        if (s.contains("tomorrow")) {
            at += DAY
        } else if (!s.contains("today") && at < now - HOUR) {
            // Well past and nobody said today: a reminder for 9:00 arriving
            // at 18:00 is about tomorrow morning. Within the last hour it is
            // far likelier to be the meeting that just started.
            at += DAY
        }
        return at
    }

    fun toItem(p: NotificationFilter.Posted, now: Long, offsetMs: Long = 0L): BriefPolicy.Item {
        val title = p.title.ifBlank { p.text }.trim()
        val text = if (p.title.isBlank()) "" else p.text.trim()
        return BriefPolicy.Item(
            title = title,
            text = text,
            link = null,
            dueAt = dueAtFrom("${p.title} ${p.text}", now, offsetMs),
            kind = BriefPolicy.kindOrNull(kindFor(p.pkg, p.category))
        )
    }

    /**
     * Folds a new item into the section, newest first, without duplicates.
     *
     * Chat and meeting apps repost the same notification as it updates, and
     * four near-identical rows is what the brief looked like before this
     * existed. Identity is the title alone, case-folded: the body of a
     * reminder changes every minute ("in 10 minutes", "in 9 minutes") while
     * it is plainly the same event, so matching on the body would defeat the
     * purpose. The NEW copy wins, because its countdown is the current one.
     */
    fun merge(
        existing: List<BriefPolicy.Item>,
        fresh: BriefPolicy.Item,
        limit: Int = BriefPolicy.MAX_ITEMS_PER_SECTION
    ): List<BriefPolicy.Item> {
        val key = fresh.title.trim().lowercase()
        val kept = existing.filter { it.title.trim().lowercase() != key }
        return (listOf(fresh) + kept).take(limit)
    }
}
