// Whether Medha should interrupt you.
//
// The easiest thing in an app to get wrong, and the wrongness is invisible
// in review: a nudge that fires twice, or at 3am, or on every widget
// redraw, reads as perfectly reasonable logic right up until it is on a
// real phone at 3am.
import com.adabala.medha.notify.BriefNudge
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

const val HOUR = 60 * 60 * 1000L
const val DAY = 24 * HOUR

// A Monday, 09:00 UTC. With offset 0 the local hour equals the UTC hour,
// which keeps the quiet-hour arithmetic in these tests readable.
const val T = 1778490000000L

fun brief(vararg daysOut: Long): List<BriefPolicy.Section> =
    BriefPolicy.put(
        emptyList(), "quicksilver",
        daysOut.mapIndexed { i, d ->
            BriefPolicy.Item("item $i", "x", null, T + d * DAY, "bill")
        },
        T
    )

fun main() {
    println("BriefNudge.quiet — nothing is worth a phone lighting up at night")
    eq("09:00 is not quiet", BriefNudge.quiet(T, 0L), false)
    eq("22:00 is", BriefNudge.quiet(T + 13 * HOUR, 0L), true)
    eq("23:59 is", BriefNudge.quiet(T + 14 * HOUR + 59 * 60_000L, 0L), true)
    eq("02:00 is", BriefNudge.quiet(T + 17 * HOUR, 0L), true)
    eq("06:59 is", BriefNudge.quiet(T + 21 * HOUR + 59 * 60_000L, 0L), true)
    eq("07:00 is not", BriefNudge.quiet(T + 22 * HOUR, 0L), false)
    // The window wraps midnight, which a naive range check gets backwards.
    check("the window genuinely wraps midnight",
        BriefNudge.quiet(T + 13 * HOUR, 0L) && BriefNudge.quiet(T + 17 * HOUR, 0L))
    eq("and it is LOCAL, not UTC",
        BriefNudge.quiet(T, 13 * HOUR), true)

    println("\nBriefNudge.nudge — only for things already overdue")
    eq("nothing overdue, no nudge",
        BriefNudge.nudge(brief(3, 10, 20), T, 0L, BriefNudge.NEVER), null)
    eq("an empty brief, no nudge",
        BriefNudge.nudge(emptyList(), T, 0L, BriefNudge.NEVER), null)
    check("one overdue is enough",
        BriefNudge.nudge(brief(-2, 10), T, 0L, BriefNudge.NEVER) != null)

    println("\nBriefNudge.nudge — it names the thing, not just the count")
    val n = BriefNudge.nudge(brief(-9, -2, 5), T, 0L, BriefNudge.NEVER)!!
    eq("counts every overdue item", n.overdue, 2)
    eq("and names the one ignored longest", n.worst, "item 0")
    eq("with its countdown", n.worstMark, "9d over")

    println("\nBriefNudge.nudge — quiet hours win over everything")
    eq("overdue at 23:00 still says nothing",
        BriefNudge.nudge(brief(-9, -2), T + 14 * HOUR, 0L, BriefNudge.NEVER), null)
    check("and the same brief does nudge in the morning",
        BriefNudge.nudge(brief(-9, -2), T + 22 * HOUR, 0L, BriefNudge.NEVER) != null)

    println("\nBriefNudge.nudge — no nagging")
    val justNudged = BriefNudge.Memory(lastAt = T - HOUR, lastCount = 2)
    eq("the same two overdue, an hour later, says nothing",
        BriefNudge.nudge(brief(-9, -2), T, 0L, justNudged), null)
    check("but a THIRD going overdue is news",
        BriefNudge.nudge(brief(-9, -2, -1), T, 0L, justNudged) != null)
    check("and after the gap, the standing problem is raised again",
        BriefNudge.nudge(
            brief(-9, -2), T, 0L,
            BriefNudge.Memory(lastAt = T - 13 * HOUR, lastCount = 2)
        ) != null)
    eq("a count that SHRANK is never a reason to speak",
        BriefNudge.nudge(brief(-9), T, 0L, BriefNudge.Memory(T - HOUR, 3)), null)

    println("\nBriefNudge.nudge — a fresh install")
    check("never nudged before, and something is overdue",
        BriefNudge.nudge(brief(-1), T, 0L, BriefNudge.NEVER) != null)
    eq("NEVER means never", BriefNudge.NEVER.lastAt, 0L)

    println("\nBriefNudge — the gap is a parameter, so a test need not wait twelve hours")
    val tight = BriefNudge.Memory(lastAt = T - 2 * HOUR, lastCount = 1)
    eq("inside a one-hour gap", BriefNudge.nudge(brief(-1), T, 0L, tight, HOUR)?.overdue, 1)
    eq("inside a four-hour gap", BriefNudge.nudge(brief(-1), T, 0L, tight, 4 * HOUR), null)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("BriefNudgeTest: all $checks checks passed")
    } else {
        println("BriefNudgeTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
