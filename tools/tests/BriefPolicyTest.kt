// Executable tests for Medha's brief — the multi-client home-screen digest
// that exists because Samsung's Now Brief has no third-party API.
//
// Runs with no Android SDK and no Gradle:
//
//     kotlinc app/src/main/java/com/adabala/medha/notify/BriefPolicy.kt \
//             tools/tests/BriefPolicyTest.kt -include-runtime -d /tmp/b.jar
//     java -jar /tmp/b.jar
//
// verify.sh and CI run this automatically.
//
// The three properties worth testing are the three that were broken or absent
// in v0.9.0: one client cannot erase another's content, stale content leaves
// on its own, and one noisy client cannot fill the whole widget.

import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefPolicy.Item

var failures = 0
var checks = 0

fun check(what: String, ok: Boolean) {
    checks++
    if (ok) println("  ok    $what")
    else { println("  FAIL  $what"); failures++ }
}

fun <T> eq(what: String, actual: T, expected: T) {
    checks++
    if (actual == expected) println("  ok    $what")
    else {
        println("  FAIL  $what\n          expected: $expected\n          actual:   $actual")
        failures++
    }
}

const val HOUR = 60 * 60 * 1000L
const val DAY = 24 * HOUR

fun items(vararg titles: String) = titles.map { Item(it, "text for $it") }

/**
 * Titles of one client's section, or null if that client has no section.
 *
 * Returns null rather than throwing so that a regression which DELETES a
 * section is reported as a failed comparison naming the client, instead of
 * killing the suite with NoSuchElementException part-way through.
 */
fun titles(sections: List<BriefPolicy.Section>, clientId: String): List<String>? =
    sections.firstOrNull { it.clientId == clientId }?.items?.map { it.title }

fun main() {
    var now = 1_000_000L

    // ------------------------------------------------- isolation between apps
    println("\nBriefPolicy — clients do not erase each other")
    // This is the whole reason the rewrite happened. v0.9.0 kept one blob, so
    // the second app to write wiped the first and neither could tell why.
    var s = BriefPolicy.put(emptyList(), "journal", items("Note A"), now)
    now += 1000
    s = BriefPolicy.put(s, "builds", items("Build green"), now)
    eq("both sections present", s.size, 2)
    check("journal survived a write by builds", s.any { it.clientId == "journal" })
    check("builds is present", s.any { it.clientId == "builds" })

    println("\nBriefPolicy — a client replaces only its own section")
    now += 1000
    s = BriefPolicy.put(s, "journal", items("Note B"), now)
    eq("still two sections", s.size, 2)
    // firstOrNull, not first {}: a regression to last-writer-wins leaves no
    // "builds" section, and `first {}` would throw NoSuchElementException.
    // The suite would then die with a stack trace instead of naming the
    // invariant that broke -- which is exactly what a mutation test showed.
    eq("journal has its new content",
        titles(s, "journal"), listOf("Note B"))
    eq("builds untouched",
        titles(s, "builds"), listOf("Build green"))

    println("\nBriefPolicy — ordering is newest section first")
    eq("journal wrote last, so it leads", s.firstOrNull()?.clientId, "journal")

    // ------------------------------------------------------------- withdrawal
    println("\nBriefPolicy — withdrawal")
    val removed = BriefPolicy.remove(s, "journal", now)
    eq("journal gone", removed.size, 1)
    eq("builds remains", removed.firstOrNull()?.clientId, "builds")
    // An empty PUT must mean the same thing, so a client needs no second call
    // just to clear itself.
    val emptied = BriefPolicy.put(s, "journal", emptyList(), now)
    eq("PUT with no items withdraws too", emptied.size, 1)
    eq("and leaves the other client", emptied.firstOrNull()?.clientId, "builds")
    check("a blank title is not an item",
        BriefPolicy.put(emptyList(), "x", listOf(Item("   ", "body")), now).isEmpty())

    // ----------------------------------------------------------------- expiry
    println("\nBriefPolicy — expiry")
    var e = BriefPolicy.put(emptyList(), "old", items("Yesterday"), now, ttlMs = DAY)
    e = BriefPolicy.put(e, "fresh", items("Today"), now + DAY, ttlMs = DAY)
    val at = now + DAY + 1000
    eq("the day-old section is gone", BriefPolicy.prune(e, at).map { it.clientId }, listOf("fresh"))
    eq("and contributes no rows", BriefPolicy.rows(e, at).map { it.clientId }, listOf("fresh"))
    check("rows() on an all-expired brief is empty",
        BriefPolicy.rows(e, now + 30 * DAY).isEmpty())

    println("\nBriefPolicy — TTL is clamped")
    // A client must not be able to pin content to the home screen for a year.
    val greedy = BriefPolicy.put(emptyList(), "greedy", items("Forever"), now,
        ttlMs = 365L * DAY).first()
    eq("an absurd TTL is capped at MAX_TTL_MS",
        greedy.expiresAt - now, BriefPolicy.MAX_TTL_MS)
    val hasty = BriefPolicy.put(emptyList(), "hasty", items("Gone"), now, ttlMs = 1L).first()
    eq("a sub-minute TTL is raised to MIN_TTL_MS",
        hasty.expiresAt - now, BriefPolicy.MIN_TTL_MS)
    check("a clamped-up section is actually visible",
        BriefPolicy.rows(listOf(hasty), now + 1000).isNotEmpty())

    // ------------------------------------------------------------------ bounds
    println("\nBriefPolicy — bounds")
    var many = emptyList<BriefPolicy.Section>()
    for (i in 1..20) {
        now += 1000
        many = BriefPolicy.put(many, "client$i", items("Item $i"), now)
    }
    eq("sections capped at MAX_SECTIONS", many.size, BriefPolicy.MAX_SECTIONS)
    check("the OLDEST were evicted, not the newest",
        many.any { it.clientId == "client20" } && many.none { it.clientId == "client1" })

    val greedyItems = BriefPolicy.put(emptyList(), "loud",
        items("a", "b", "c", "d", "e", "f", "g", "h"), now).first()
    eq("items per section capped", greedyItems.items.size, BriefPolicy.MAX_ITEMS_PER_SECTION)

    // ------------------------------------------------- the 5-vs-3 bug, pinned
    println("\nBriefPolicy — row budget matches the layout")
    // v0.9.0 accepted 5 items and had 3 layout rows, silently dropping two.
    // The widget now draws PAGE_ROWS at a time and rotates through the rest,
    // so MAX_ROWS is deliberately larger than what fits on screen.
    eq("PAGE_ROWS is 3, matching widget_medha.xml", BriefPolicy.PAGE_ROWS, 3)
    check("MAX_ROWS exceeds PAGE_ROWS, so overflow is reachable not discarded",
        BriefPolicy.MAX_ROWS > BriefPolicy.PAGE_ROWS)
    val full = BriefPolicy.put(emptyList(), "one", items("a", "b", "c", "d", "e"), now)
    eq("a single client's five items are all retained",
        BriefPolicy.rows(full, now).size, 5)

    println("\nBriefPolicy — one noisy client cannot crowd the others out")
    // Section-by-section flattening would give "loud" all five rows and make
    // "quiet" invisible, which is the per-client isolation bug moved from the
    // store to the renderer.
    var mixed = BriefPolicy.put(emptyList(), "quiet", items("Q1"), now)
    now += 1000
    mixed = BriefPolicy.put(mixed, "loud", items("L1", "L2", "L3", "L4", "L5"), now)
    val rows = BriefPolicy.rows(mixed, now)
    eq("six rows retained across pages", rows.size, 6)
    check("quiet still appears", rows.any { it.clientId == "quiet" })
    eq("every client gets its first line before anyone gets a second",
        rows.take(2).map { it.clientId }.toSet(), setOf("loud", "quiet"))
    eq("rows carry attribution", rows.firstOrNull()?.clientId?.isNotBlank(), true)

    // --------------------------------------------------------------------- age
    println("\nBriefPolicy.age")
    eq("under a minute", BriefPolicy.age(now, now + 30_000), "just now")
    eq("minutes", BriefPolicy.age(now, now + 12 * 60_000), "12m")
    eq("just under an hour", BriefPolicy.age(now, now + 59 * 60_000), "59m")
    eq("hours", BriefPolicy.age(now, now + 3 * HOUR), "3h")
    eq("just under a day", BriefPolicy.age(now, now + 23 * HOUR), "23h")
    eq("days", BriefPolicy.age(now, now + 2 * DAY), "2d")
    // A clock that moved backwards must not render "-5m".
    eq("a backwards clock reads as just now", BriefPolicy.age(now + 5 * 60_000, now), "just now")

    // ------------------------------------------------------------ degenerate
    println("\nBriefPolicy — degenerate input")
    check("rows() on an empty brief is empty", BriefPolicy.rows(emptyList(), now).isEmpty())
    check("prune() on an empty brief is empty", BriefPolicy.prune(emptyList(), now).isEmpty())
    check("remove() of an absent client is harmless",
        BriefPolicy.remove(emptyList(), "nobody", now).isEmpty())
    // Sections whose items are all empty should not produce phantom rows.
    val hollow = listOf(BriefPolicy.Section("hollow", now, now + DAY, emptyList()))
    check("a section with no items draws nothing", BriefPolicy.rows(hollow, now).isEmpty())

    // ------------------------------------------------------------- paging
    println("\nBriefPolicy.pageCount")
    eq("empty brief is still one page", BriefPolicy.pageCount(0), 1)
    eq("1 row, 1 page", BriefPolicy.pageCount(1), 1)
    eq("3 rows fit on one page", BriefPolicy.pageCount(3), 1)
    eq("4 rows need two", BriefPolicy.pageCount(4), 2)
    eq("6 rows need two", BriefPolicy.pageCount(6), 2)
    eq("7 rows need three", BriefPolicy.pageCount(7), 3)
    eq("12 rows need four", BriefPolicy.pageCount(12), 4)
    // A negative count would be a caller bug, but must not produce 0 pages
    // or a negative one, either of which divides by or indexes past nothing.
    eq("a nonsense row count still reports one page", BriefPolicy.pageCount(-5), 1)

    println("\nBriefPolicy.page — no overflow means no rotation")
    var p3 = emptyList<BriefPolicy.Section>()
    now += 1000
    p3 = BriefPolicy.put(p3, "a", items("1", "2", "3"), now)
    val single = BriefPolicy.page(p3, now, tick = 0)
    eq("one page", single.pageCount, 1)
    check("does not overflow", !single.overflows)
    eq("no indicator shown when there is nothing to page", single.indicator, "")
    eq("all three rows on it", single.rows.size, 3)
    // The tick must be inert when there is only one page, otherwise a static
    // brief would appear to flicker on every service tick.
    for (t in 0L..5L) {
        eq("tick $t still shows page 0 when there is one page",
            BriefPolicy.page(p3, now, tick = t).index, 0)
    }

    println("\nBriefPolicy.page — overflow rotates")
    var p7 = emptyList<BriefPolicy.Section>()
    p7 = BriefPolicy.put(p7, "a", items("a1", "a2", "a3", "a4", "a5"), now)
    now += 1000
    p7 = BriefPolicy.put(p7, "b", items("b1", "b2"), now)
    val total = BriefPolicy.rows(p7, now).size
    eq("seven rows in total", total, 7)
    eq("three pages", BriefPolicy.page(p7, now).pageCount, 3)
    check("overflows", BriefPolicy.page(p7, now).overflows)
    eq("indicator reads 1/3 on the first page", BriefPolicy.page(p7, now, 0).indicator, "1/3")
    eq("and 2/3 on the second", BriefPolicy.page(p7, now, 1).indicator, "2/3")
    eq("page 0 holds three rows", BriefPolicy.page(p7, now, 0).rows.size, 3)
    eq("page 1 holds three rows", BriefPolicy.page(p7, now, 1).rows.size, 3)
    // The last page is a remainder and must not be padded or over-read.
    eq("the last page holds only the remainder", BriefPolicy.page(p7, now, 2).rows.size, 1)
    eq("totalRows reports the whole brief, not the page",
        BriefPolicy.page(p7, now, 2).totalRows, 7)

    println("\nBriefPolicy.page — every row is reachable exactly once per cycle")
    // The property that matters: rotating through one full cycle shows the
    // user everything, with nothing repeated and nothing skipped.
    val seen = mutableListOf<String>()
    for (t in 0 until BriefPolicy.page(p7, now).pageCount) {
        seen += BriefPolicy.page(p7, now, t.toLong()).rows.map { it.title }
    }
    eq("one cycle covers every row", seen.size, total)
    eq("with no duplicates", seen.toSet().size, total)
    eq("and in order", seen, BriefPolicy.rows(p7, now).map { it.title })

    println("\nBriefPolicy.page — the tick wraps instead of running off the end")
    eq("tick 3 wraps to page 0", BriefPolicy.page(p7, now, 3).index, 0)
    eq("tick 4 wraps to page 1", BriefPolicy.page(p7, now, 4).index, 1)
    eq("a large tick stays in range", BriefPolicy.page(p7, now, 1_000_000).index, 1)
    // Kotlin's % keeps the dividend's sign, so a negative tick would index
    // backwards off the start of the list and throw.
    eq("a negative tick does not index off the start",
        BriefPolicy.page(p7, now, -1).index, 2)
    eq("a very negative tick is also in range",
        BriefPolicy.page(p7, now, -7).index in 0..2, true)
    check("Long.MIN_VALUE does not overflow into a negative index",
        BriefPolicy.page(p7, now, Long.MIN_VALUE).index >= 0)

    println("\nBriefPolicy.page — a tick held across a shrinking brief stays valid")
    // A counter captured when there were three pages must still land on a
    // real page after sections expire down to one.
    val shrunk = BriefPolicy.put(emptyList(), "a", items("only"), now)
    val held = BriefPolicy.page(shrunk, now, tick = 2)
    eq("stale tick lands on the only page", held.index, 0)
    eq("and still has its row", held.rows.size, 1)

    println("\nBriefPolicy.page — empty brief")
    val none = BriefPolicy.page(emptyList(), now, tick = 5)
    eq("no rows", none.rows.size, 0)
    eq("index 0", none.index, 0)
    check("does not overflow", !none.overflows)
    eq("no indicator", none.indicator, "")
    eq("totalRows zero", none.totalRows, 0)

    println("\nBriefPolicy.page — an all-expired brief pages like an empty one")
    val stale = listOf(BriefPolicy.Section("x", now - 2 * DAY, now - DAY, items("gone")))
    val stalePage = BriefPolicy.page(stale, now, tick = 3)
    eq("no rows", stalePage.rows.size, 0)
    check("does not overflow", !stalePage.overflows)

    // ───────────── dueAt: bands and relative time ─────────────
    //
    // Added in v0.12.0. Before it, a client sent "overdue 10 days" as prose
    // and the widget showed that string for as long as the section lived --
    // observably wrong on a real device, which displayed a five-hour-old
    // "10 days" without flinching. Medha now derives the distance at draw
    // time from a timestamp.

    val IST = 5 * 3600_000L + 30 * 60_000L          // Asia/Kolkata, +05:30
    // 11 Nov 2026, 09:00 UTC = 14:30 IST. Matches the device this was
    // designed against, so the arithmetic is checked where it is used.
    val T = 1778490000000L

    println("\nBriefPolicy.dayDelta — whole days, not elapsed milliseconds")
    eq("same instant is day 0", BriefPolicy.dayDelta(T, T, IST), 0)
    eq("+1 day", BriefPolicy.dayDelta(T + DAY, T, IST), 1)
    eq("-1 day", BriefPolicy.dayDelta(T - DAY, T, IST), -1)
    eq("+10 days", BriefPolicy.dayDelta(T + 10 * DAY, T, IST), 10)

    // THE case the whole design rests on. A bill due "17 Nov" arrives as
    // local midnight on the 17th. At 14:30 that afternoon an elapsed-time
    // comparison calls it overdue -- wrong, and alarming. People think in
    // days, so the arithmetic must too.
    val midnight17 = T + 6 * DAY - (9 * 3600_000L) - IST + DAY  // 00:00 IST, 17 Nov
    eq("midnight on the due day, read that afternoon, is still today",
        BriefPolicy.dayDelta(midnight17, midnight17 + 14 * 3600_000L, IST), 0)
    eq("and is NOT overdue",
        BriefPolicy.bandOf(midnight17, midnight17 + 14 * 3600_000L, IST), BriefPolicy.Band.SOON)

    println("\nBriefPolicy.dayDelta — the offset is what makes 'today' local")
    // 02:00 IST on 12 Nov is still 11 Nov in UTC. Without the offset every
    // date reads one day short for the five and a half hours overnight --
    // exactly when a daily brief regenerates.
    val twoAmIst = T + 11 * 3600_000L + 30 * 60_000L            // 02:00 IST, 12 Nov = 20:30 UTC, 11 Nov
    val noonIst = twoAmIst + 10 * 3600_000L                     // 12:00 IST, 12 Nov = 06:30 UTC, 12 Nov
    // Both instants are 12 Nov in Hyderabad, so this is due TODAY.
    eq("with the offset, a same-local-day due date is 0",
        BriefPolicy.dayDelta(noonIst, twoAmIst, IST), 0)
    // In UTC they land either side of midnight, so the same item would be
    // drawn as "tomorrow" -- wrong, every night between 00:00 and 05:30.
    eq("in UTC the same pair straddles midnight and reads 1",
        BriefPolicy.dayDelta(noonIst, twoAmIst, 0L), 1)

    println("\nBriefPolicy.dayDelta — flooring stays correct before the epoch")
    // Integer division truncates TOWARD ZERO, so -1ms / 86400000 is 0, not
    // -1: without a floor correction an instant just before the epoch lands
    // on the same day as the epoch itself. The API drops any dueAt <= 0, so
    // this is not reachable through PUT today -- but dayDelta is a general
    // helper, a negative offset can push a small positive dueAt below zero,
    // and mutation testing showed nothing else was holding the line.
    eq("one ms before the epoch is the previous day",
        BriefPolicy.dayDelta(-1L, 0L), -1)
    eq("exactly the epoch is day zero", BriefPolicy.dayDelta(0L, 0L), 0)
    eq("a full day before the epoch", BriefPolicy.dayDelta(-DAY, 0L), -1)
    eq("just over a day before", BriefPolicy.dayDelta(-DAY - 1L, 0L), -2)
    // The same thing reached the way it actually could be: a tiny dueAt
    // pushed negative by a western offset.
    eq("a small dueAt with a negative offset still floors",
        BriefPolicy.dayDelta(1L, DAY, -5 * 3600_000L), -1)

    println("\nBriefPolicy.bandOf — boundaries")
    eq("yesterday is OVERDUE", BriefPolicy.bandOf(T - DAY, T, IST), BriefPolicy.Band.OVERDUE)
    eq("today is SOON", BriefPolicy.bandOf(T, T, IST), BriefPolicy.Band.SOON)
    eq("day 7 is still SOON", BriefPolicy.bandOf(T + 7 * DAY, T, IST), BriefPolicy.Band.SOON)
    eq("day 8 is LATER", BriefPolicy.bandOf(T + 8 * DAY, T, IST), BriefPolicy.Band.LATER)
    eq("SOON_DAYS is the documented 7", BriefPolicy.SOON_DAYS, 7)
    eq("no due date means no band", BriefPolicy.bandOf(null, T, IST), null)

    println("\nBriefPolicy.relative — the chip the row shows")
    eq("today", BriefPolicy.relative(T, T, IST), "today")
    eq("tomorrow", BriefPolicy.relative(T + DAY, T, IST), "tomorrow")
    eq("yesterday", BriefPolicy.relative(T - DAY, T, IST), "yesterday")
    eq("10 days over", BriefPolicy.relative(T - 10 * DAY, T, IST), "10d over")
    eq("in 6 days", BriefPolicy.relative(T + 6 * DAY, T, IST), "in 6d")
    eq("no due date, no chip", BriefPolicy.relative(null, T, IST), null)
    // It is derived, so it cannot go stale: the SAME dueAt read a day later
    // must say something different.
    eq("the same dueAt reads differently a day on",
        BriefPolicy.relative(T + 6 * DAY, T + DAY, IST), "in 5d")

    println("\nBriefPolicy.overdueCount — the header badge")
    val dueMix = listOf(
        BriefPolicy.Section("a", T, T + DAY, listOf(
            BriefPolicy.Item("late", "", null, T - 3 * DAY),
            BriefPolicy.Item("soon", "", null, T + 2 * DAY))),
        BriefPolicy.Section("b", T, T + DAY, listOf(
            BriefPolicy.Item("alsoLate", "", null, T - DAY),
            BriefPolicy.Item("undated", "", null, null)))
    )
    eq("counts across every section", BriefPolicy.overdueCount(dueMix, T, IST), 2)
    eq("an undated item is never overdue",
        BriefPolicy.overdueCount(
            listOf(BriefPolicy.Section("c", T, T + DAY, listOf(BriefPolicy.Item("x", "")))), T, IST), 0)

    println("\nBriefPolicy.kindOrNull — a wrong glyph beats a lost item")
    eq("a known kind", BriefPolicy.kindOrNull("bill"), "bill")
    eq("case is normalised", BriefPolicy.kindOrNull("BILL"), "bill")
    eq("whitespace is trimmed", BriefPolicy.kindOrNull("  event "), "event")
    eq("an unknown kind is dropped, not refused", BriefPolicy.kindOrNull("spaceship"), null)
    eq("empty", BriefPolicy.kindOrNull(""), null)
    eq("null", BriefPolicy.kindOrNull(null), null)
    eq("all six are offered", BriefPolicy.KINDS.size, 6)

    println("\nBriefPolicy.rows — dueAt and kind reach the row")
    val dated = listOf(BriefPolicy.Section("a", T, T + DAY,
        listOf(BriefPolicy.Item("bill", "x", null, T - DAY, "bill"))))
    val r0 = BriefPolicy.rows(dated, T).first()
    eq("dueAt carried", r0.dueAt, T - DAY)
    eq("kind carried", r0.kind, "bill")

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("BriefPolicyTest: all $checks checks passed")
    } else {
        println("BriefPolicyTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
