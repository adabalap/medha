// What earns a place in the brief, and the clock time that makes a dated
// row readable as a schedule.
//
// The state that prompted the rule: 19 items, 17 of them undated. A
// ₹2,50,000 loan instalment and a 17:30 meeting sharing a scroll with a
// shopping list, a webinar invitation, a Teams "sent a message" and a
// WhatsApp "Thanq thanq". Nothing had failed; the filter only ever asked
// whether a notification was junk, never whether it was an obligation.
//
// Two properties matter more than the split itself, and both are checked
// below: nothing is DISCARDED (an open item still exists, in full, in its
// own lane), and the rule is about having a time rather than about
// importance — it cannot be talked into promoting something.
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefTriage

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
const val MIN = 60 * 1000L
const val IST = 5 * HOUR + 30 * MIN

/** 2026-11-16T00:00:00+05:30 as an epoch instant. */
val IST_MIDNIGHT = 1763251200000L - IST
val NOW = IST_MIDNIGHT + 9 * HOUR          // 09:00 IST

fun item(title: String, dueAt: Long? = null) = BriefPolicy.Item(title, "body", null, dueAt)
fun section(id: String, vararg items: BriefPolicy.Item) =
    BriefPolicy.Section(id, NOW, NOW + DAY, items.toList())

fun main() {
    println("BriefTriageTest")

    println("\nThe rule")
    eq("a dated item is agenda", BriefTriage.laneOf(item("meeting", NOW + HOUR)),
        BriefTriage.Lane.AGENDA)
    eq("an undated item is open", BriefTriage.laneOf(item("dry cleaning")),
        BriefTriage.Lane.OPEN)
    eq("an OVERDUE item is still agenda", BriefTriage.laneOf(item("late", NOW - 9 * DAY)),
        BriefTriage.Lane.AGENDA)
    eq("a far-future item is still agenda", BriefTriage.laneOf(item("far", NOW + 300 * DAY)),
        BriefTriage.Lane.AGENDA)
    // The proxy is "has a time", not "looks important". A model may later
    // demote a dated row it can see is noise; nothing may promote an
    // undated one by deciding it feels urgent.
    eq("an urgent-SOUNDING undated item is still open",
        BriefTriage.laneOf(item("URGENT: wire the money now")), BriefTriage.Lane.OPEN)

    println("\nThe real screen that prompted this")
    val real = listOf(
        section("admin",
            item("Icicip · ₹2,50,000", NOW + 5 * DAY),
            item("Stalled: Pick up dry cleaner clothes"),
            item("Headroom GitHub Repository"),
            item("Apply for Sriram Nagar 3rd Electric Meter"),
            item("Complete mess registration")),
        section("notifications",
            item("Milestone Program", IST_MIDNIGHT + 17 * HOUR + 30 * MIN),
            item("Lucid"),
            item("Peddannaya"),
            item("Irfan Khan, Nawaz (Cognizant) in Teams"))
    )
    eq("nine items in", real.sumOf { it.items.size }, 9)
    eq("two reach the agenda", BriefTriage.count(real, BriefTriage.Lane.AGENDA), 2)
    eq("seven stay as open items", BriefTriage.count(real, BriefTriage.Lane.OPEN), 7)
    eq("and nothing is lost between the two lanes",
        BriefTriage.count(real, BriefTriage.Lane.AGENDA) +
            BriefTriage.count(real, BriefTriage.Lane.OPEN),
        real.sumOf { it.items.size })
    eq("the agenda is the instalment and the meeting",
        BriefTriage.only(real, BriefTriage.Lane.AGENDA)
            .flatMap { it.items }.map { it.title }.sorted(),
        listOf("Icicip · ₹2,50,000", "Milestone Program"))

    println("\nonly() keeps the section shape")
    val split = BriefTriage.only(real, BriefTriage.Lane.AGENDA)
    eq("both clients still contribute", split.map { it.clientId }, listOf("admin", "notifications"))
    check("section metadata survives",
        split.all { it.updatedAt == NOW && it.expiresAt == NOW + DAY })
    eq("a section with nothing in the lane is dropped, not left empty",
        BriefTriage.only(listOf(section("quiet", item("undated"))), BriefTriage.Lane.AGENDA),
        emptyList<BriefPolicy.Section>())
    eq("an empty input stays empty",
        BriefTriage.only(emptyList(), BriefTriage.Lane.OPEN),
        emptyList<BriefPolicy.Section>())

    println("\nNothing is capped")
    // The rule is a filter on kind, never on count: a day with fifteen
    // meetings shows fifteen, because that is what the day is.
    val busy = listOf(section("cal",
        *(1..15).map { item("meeting $it", NOW + it * HOUR) }.toTypedArray()))
    eq("fifteen dated items all reach the agenda",
        BriefTriage.count(busy, BriefTriage.Lane.AGENDA), 15)
    eq("and only() returns them all",
        BriefTriage.only(busy, BriefTriage.Lane.AGENDA).first().items.size, 15)

    println("\nclockAt — a time, but only when there is one")
    eq("a meeting today shows its clock time",
        BriefPolicy.clockAt(IST_MIDNIGHT + 17 * HOUR + 30 * MIN, NOW, IST), "17:30")
    eq("single digits are padded",
        BriefPolicy.clockAt(IST_MIDNIGHT + 9 * HOUR + 5 * MIN, NOW, IST), "09:05")
    eq("midnight exactly reads as a date, not a time",
        BriefPolicy.clockAt(IST_MIDNIGHT, NOW, IST), null)
    eq("a date-only item tomorrow has no clock",
        BriefPolicy.clockAt(IST_MIDNIGHT + DAY, NOW, IST), null)
    eq("a timed item tomorrow has no clock either — it is not today",
        BriefPolicy.clockAt(IST_MIDNIGHT + DAY + 14 * HOUR, NOW, IST), null)
    eq("nor does an overdue one", BriefPolicy.clockAt(NOW - 2 * DAY, NOW, IST), null)
    eq("null in, null out", BriefPolicy.clockAt(null, NOW, IST), null)
    eq("the zone is honoured: 17:30 IST is not 17:30 UTC",
        BriefPolicy.clockAt(IST_MIDNIGHT + 17 * HOUR + 30 * MIN, NOW, 0L), "12:00")
    check("every minute of today produces a well-formed clock",
        (0 until 24 * 60).all { m ->
            val at = IST_MIDNIGHT + m * MIN
            val s = BriefPolicy.clockAt(at, NOW, IST)
            (m == 0 && s == null) || (m > 0 && s != null && s.matches(Regex("""\d\d:\d\d""")))
        })

    println("\n" + "-".repeat(50))
    if (failures == 0) println("BriefTriageTest: all $checks checks passed")
    else { println("BriefTriageTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
