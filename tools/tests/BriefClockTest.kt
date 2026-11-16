// When the brief next looks different, which is when the widget has to be
// redrawn.
//
// The bug this answers: updatePeriodMillis is 0 and nothing else asked for a
// redraw, so the widget's urgency was frozen at whatever the last posting
// app left behind. A row that said "in 2h" at noon still said "in 2h" at
// six, in the same colour, with a header count that disagreed with it.
//
// The checks that matter most are the NULL ones. An empty brief, or one
// where nothing is dated and nothing expires, must not arm an alarm: a
// daily wakeup to redraw a picture that cannot have changed is a battery
// cost with no user visible to pay it.
import com.adabala.medha.notify.BriefClock
import com.adabala.medha.notify.BriefPolicy

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

const val DAY = 24 * 60 * 60 * 1000L
const val HOUR = 60 * 60 * 1000L
const val IST = 5 * HOUR + 30 * 60 * 1000L   // +05:30, and deliberately not a whole hour

/** 2026-11-16T00:00:00Z, a midnight in UTC terms. */
const val UTC_MIDNIGHT = 1763251200000L

fun item(title: String, dueAt: Long?) = BriefPolicy.Item(title, "body", null, dueAt)
fun section(id: String, expiresAt: Long, vararg items: BriefPolicy.Item) =
    BriefPolicy.Section(id, UTC_MIDNIGHT, expiresAt, items.toList())

fun main() {
    println("BriefClockTest")

    println("\nnextLocalMidnight — in the device's zone, not UTC")
    // At IST midnight the UTC instant is 18:30 the previous day. The answer
    // has to be the next IST midnight, not the next UTC one.
    val istMidnight = UTC_MIDNIGHT - IST
    eq("exactly at local midnight, the answer is a full day later",
        BriefClock.nextLocalMidnight(istMidnight, IST), istMidnight + DAY)
    eq("an hour in, the rest of the day remains",
        BriefClock.nextLocalMidnight(istMidnight + HOUR, IST), istMidnight + DAY)
    eq("a minute before, it is a minute away",
        BriefClock.nextLocalMidnight(istMidnight + DAY - 60_000L, IST), istMidnight + DAY)
    check("always strictly in the future",
        (0 until 48).all { h ->
            val t = istMidnight + h * HOUR
            BriefClock.nextLocalMidnight(t, IST) > t
        })
    check("never more than a day away",
        (0 until 48).all { h ->
            val t = istMidnight + h * HOUR + 7 * 60_000L
            BriefClock.nextLocalMidnight(t, IST) - t <= DAY
        })
    eq("UTC is just an offset of zero",
        BriefClock.nextLocalMidnight(UTC_MIDNIGHT + HOUR, 0L), UTC_MIDNIGHT + DAY)
    check("a negative offset works too",
        BriefClock.nextLocalMidnight(UTC_MIDNIGHT, -5 * HOUR) > UTC_MIDNIGHT)
    check("pre-epoch instants floor downward, not toward zero",
        BriefClock.nextLocalMidnight(-3 * HOUR, 0L) == 0L)

    println("\nnextChangeAt — nothing to watch means no alarm")
    val now = istMidnight + 9 * HOUR
    eq("an empty brief arms nothing",
        BriefClock.nextChangeAt(emptyList(), now, IST), null)
    eq("sections that have already expired arm nothing",
        BriefClock.nextChangeAt(
            listOf(section("a", now - HOUR, item("gone", now + DAY))), now, IST),
        null)

    println("\nnextChangeAt — undated items do not need midnight")
    // This is the saving: a brief of undated notes reads identically
    // tomorrow, so the only thing worth waiting for is its expiry.
    val undated = listOf(section("a", now + 10 * DAY, item("no date", null)))
    eq("an undated brief waits for the expiry, not midnight",
        BriefClock.nextChangeAt(undated, now, IST), now + 10 * DAY)

    println("\nnextChangeAt — a dated item changes at midnight")
    val dated = listOf(section("a", now + 10 * DAY, item("due", now + 3 * DAY)))
    eq("midnight wins when it comes first",
        BriefClock.nextChangeAt(dated, now, IST), BriefClock.nextLocalMidnight(now, IST))
    check("and that is less than a day away",
        BriefClock.nextChangeAt(dated, now, IST)!! - now <= DAY)

    println("\nnextChangeAt — whichever comes first")
    val expiringSoon = listOf(
        section("a", now + 30 * 60_000L, item("due", now + 3 * DAY))
    )
    eq("an expiry inside the day beats midnight",
        BriefClock.nextChangeAt(expiringSoon, now, IST), now + 30 * 60_000L)

    val many = listOf(
        section("a", now + 5 * DAY, item("x", now + DAY)),
        section("b", now + 2 * HOUR, item("y", null)),
        section("c", now + 9 * DAY, item("z", null))
    )
    eq("the earliest of several expiries wins",
        BriefClock.nextChangeAt(many, now, IST), now + 2 * HOUR)

    println("\nnextChangeAt — the answer is always usable")
    // An alarm set for the past fires immediately and redraws forever.
    check("never in the past, over a day of start times",
        (0 until 24).all { h ->
            val t = istMidnight + h * HOUR + 13 * 60_000L
            val s = listOf(section("a", t + 3 * DAY, item("due", t + 2 * DAY)))
            val at = BriefClock.nextChangeAt(s, t, IST)
            at != null && at > t
        })

    println("\nnextChangeAt — the dismissed-to-empty case")
    // prune() runs first, so a section whose items were all filtered out
    // elsewhere still counts as live while it has not expired; what must
    // not happen is an alarm for a brief with no sections at all.
    eq("a live section with no items still only waits for its expiry",
        BriefClock.nextChangeAt(
            listOf(section("a", now + 4 * HOUR)), now, IST),
        now + 4 * HOUR)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("BriefClockTest: all $checks checks passed")
    else { println("BriefClockTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
