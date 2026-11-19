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

    println("\nA GUESSED date may not promote")
    // The defect this exists to stop. dueAtFrom scrapes a time out of any
    // prose, so an email mentioning 11:30 became an obligation. Before the
    // provenance flag, "dated" meant "promoted", and the least reliable
    // signal in the system controlled the home screen.
    eq("a declared date is agenda",
        BriefTriage.laneOf(BriefPolicy.Item("standup", "", null, NOW + HOUR, null, null, false)),
        BriefTriage.Lane.AGENDA)
    eq("the same date, inferred, is not",
        BriefTriage.laneOf(BriefPolicy.Item("standup", "", null, NOW + HOUR, null, null, true)),
        BriefTriage.Lane.OPEN)

    println("\nWednesday night, as it actually rendered")
    // Five rows reached the agenda. One was an obligation.
    val wednesday = listOf(
        section("notifications",
            BriefPolicy.Item("Lambe, canceled : Nov 18 21:30 - 22:30",
                "Canceled: DACH Knowledge hour", null,
                IST_MIDNIGHT + 21 * HOUR + 30 * MIN, "event", "Teams", false),
            BriefPolicy.Item("Masa, Uma Maheswaraiah(Cognizant)",
                "RE: Upcoming Traveler Information (pending details)", null,
                NOW + DAY, "note", "Outlook", true),
            BriefPolicy.Item("Cameron, Justin",
                "RE: Fundamentals of Banking Reminder", null,
                NOW + DAY, "note", "Outlook", true),
            BriefPolicy.Item("Takawale, Pratik Ram (Cognizant)",
                "Re: Delayed employees for 11:30 on 17/11/2026", null,
                NOW + DAY, "note", "Outlook", true)),
        section("admin",
            BriefPolicy.Item("Icicip · ₹2,50,000", "Loan EMI bill due Sat 21 Nov",
                null, NOW + 3 * DAY, "bill", null, false))
    )
    eq("only the instalment survives",
        BriefTriage.only(wednesday, BriefTriage.Lane.AGENDA)
            .flatMap { it.items }.map { it.title },
        listOf("Icicip · ₹2,50,000"))
    eq("the three emails became open items",
        BriefTriage.count(wednesday, BriefTriage.Lane.OPEN), 4)
    eq("and still nothing is lost",
        BriefTriage.count(wednesday, BriefTriage.Lane.AGENDA) +
            BriefTriage.count(wednesday, BriefTriage.Lane.OPEN),
        wednesday.sumOf { it.items.size })

    println("\nA cancellation is not an appointment")
    // This one had a CORROBORATED date (category=event, from Teams), so
    // provenance alone would have let it through at 21:30.
    eq("'Canceled:' in the body keeps it off the agenda",
        BriefTriage.laneOf(BriefPolicy.Item(
            "Lambe, canceled : Nov 18 21:30 - 22:30", "Canceled: DACH Knowledge hour",
            null, NOW + HOUR, "event", "Teams", false)),
        BriefTriage.Lane.OPEN)
    check("in the title too", BriefTriage.isCancellation("Cancelled: standup", ""))
    check("the American spelling", BriefTriage.isCancellation("Canceled: standup", ""))
    check("a declined invitation", BriefTriage.isCancellation("Declined: review", ""))
    check("through a reply prefix", BriefTriage.isCancellation("RE: Canceled: standup", ""))
    // Dropping a real meeting is far worse than showing a cancelled one,
    // so the match is anchored and will not fire mid-sentence.
    check("not when the word merely appears",
        !BriefTriage.isCancellation("Standup", "the 4pm is not cancelled, go ahead"))
    check("nor inside another word",
        !BriefTriage.isCancellation("Cancellation policy review", ""))
    eq("so that meeting still reaches the agenda",
        BriefTriage.laneOf(BriefPolicy.Item(
            "Standup", "the 4pm is not cancelled", null, NOW + HOUR, "event", null, false)),
        BriefTriage.Lane.AGENDA)

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

    println("\nstate — the client saying what it knows, v0.20.0")
    // The point of the v2 protocol: the side with the corpus asserts this
    // instead of leaving Medha to read "Cancelled:" off a subject line.
    val dated = BriefPolicy.Item("Design review", "body", null, NOW + 2 * HOUR)
    eq("a dated obligation with no state is on the agenda",
        BriefTriage.laneOf(dated), BriefTriage.Lane.AGENDA)
    eq("state 'open' leaves it there",
        BriefTriage.laneOf(dated.copy(state = BriefPolicy.STATE_OPEN)),
        BriefTriage.Lane.AGENDA)
    eq("state 'cancelled' takes it off",
        BriefTriage.laneOf(dated.copy(state = BriefPolicy.STATE_CANCELLED)),
        BriefTriage.Lane.OPEN)
    eq("so does 'done' -- it happened, it is not upcoming",
        BriefTriage.laneOf(dated.copy(state = BriefPolicy.STATE_DONE)),
        BriefTriage.Lane.OPEN)
    // An unknown state must not demote: stateOrNull drops it to null, and
    // null means the client did not say, which is the pre-v2 behaviour.
    eq("a state Medha has never heard of is not a demotion",
        BriefTriage.laneOf(dated.copy(state = BriefPolicy.stateOrNull("snoozed"))),
        BriefTriage.Lane.AGENDA)

    println("\nstate only ever demotes")
    // The asymmetry, asserted rather than argued: a client claiming `open`
    // does NOT rescue an item whose own title announces a cancellation. A
    // wrongly demoted item is one tap away on the full brief; a wrongly
    // promoted one is how "Canceled: DACH Knowledge hour" came to sit on the
    // home screen at 21:30 as though it were happening.
    val announced = BriefPolicy.Item(
        "Canceled: DACH Knowledge hour", "body", null, NOW + 2 * HOUR
    )
    eq("the title rule still applies with no state",
        BriefTriage.laneOf(announced), BriefTriage.Lane.OPEN)
    eq("and 'open' does not override it",
        BriefTriage.laneOf(announced.copy(state = BriefPolicy.STATE_OPEN)),
        BriefTriage.Lane.OPEN)
    eq("while 'cancelled' agrees with it",
        BriefTriage.laneOf(announced.copy(state = BriefPolicy.STATE_CANCELLED)),
        BriefTriage.Lane.OPEN)
    // Nor can a state rescue an item that fails the other two conditions.
    eq("'open' does not give an undated item a time",
        BriefTriage.laneOf(
            BriefPolicy.Item("Shopping", "body", null, null, state = BriefPolicy.STATE_OPEN)
        ),
        BriefTriage.Lane.OPEN)
    eq("nor make a guessed date a commitment",
        BriefTriage.laneOf(
            dated.copy(dueInferred = true, state = BriefPolicy.STATE_OPEN)
        ),
        BriefTriage.Lane.OPEN)
    check("no state value promotes anything the base rule rejected",
        (BriefPolicy.STATES + setOf(null, "nonsense")).none { s ->
            BriefTriage.laneOf(
                BriefPolicy.Item("Shopping", "body", null, null,
                    state = BriefPolicy.stateOrNull(s))
            ) == BriefTriage.Lane.AGENDA
        })

    println("\nsettled — the flag the lane rule reads")
    check("done is settled",
        BriefPolicy.Item("x", "y", state = BriefPolicy.STATE_DONE).settled)
    check("cancelled is settled",
        BriefPolicy.Item("x", "y", state = BriefPolicy.STATE_CANCELLED).settled)
    check("open is not", !BriefPolicy.Item("x", "y", state = BriefPolicy.STATE_OPEN).settled)
    check("absent is not", !BriefPolicy.Item("x", "y").settled)
    check("and a Row agrees with its Item",
        BriefPolicy.Row("x", "y", "c", NOW, state = BriefPolicy.STATE_CANCELLED).settled)

    println("\nstateOrNull — the vocabulary")
    eq("open", BriefPolicy.stateOrNull("open"), BriefPolicy.STATE_OPEN)
    eq("done", BriefPolicy.stateOrNull("done"), BriefPolicy.STATE_DONE)
    eq("cancelled", BriefPolicy.stateOrNull("cancelled"), BriefPolicy.STATE_CANCELLED)
    // Both spellings arrive in the wild -- the same split that made the
    // title regex accept `cancell?ed`. One of them silently meaning nothing
    // would be the worst outcome: the client thinks it demoted the row.
    eq("the American spelling means the same thing",
        BriefPolicy.stateOrNull("canceled"), BriefPolicy.STATE_CANCELLED)
    eq("case and padding do not matter", BriefPolicy.stateOrNull("  CANCELLED "),
        BriefPolicy.STATE_CANCELLED)
    eq("an unknown value is null, not an error", BriefPolicy.stateOrNull("snoozed"), null)
    eq("absent is null", BriefPolicy.stateOrNull(null), null)
    eq("blank is null", BriefPolicy.stateOrNull("  "), null)
    check("every value it returns is one the lane rule understands",
        listOf("open", "done", "cancelled", "canceled", "CANCELED", "x", null, "")
            .mapNotNull { BriefPolicy.stateOrNull(it) }
            .all { it in BriefPolicy.STATES })

    println("\ninferredFrom — who vouched for the date")
    check("inferred is a guess", BriefPolicy.inferredFrom(BriefPolicy.PROVENANCE_INFERRED))
    check("declared is not", !BriefPolicy.inferredFrom(BriefPolicy.PROVENANCE_DECLARED))
    check("case and padding do not matter", BriefPolicy.inferredFrom("  INFERRED "))
    // Absent means declared, which keeps every v1 client's dates trusted.
    // The failure this field guards against is a guess passing as a
    // commitment, not the reverse.
    check("absent means declared", !BriefPolicy.inferredFrom(null))
    check("so does a value nobody recognises", !BriefPolicy.inferredFrom("probably"))
    eq("and it reaches the lane rule",
        BriefTriage.laneOf(
            dated.copy(dueInferred = BriefPolicy.inferredFrom("inferred"))
        ),
        BriefTriage.Lane.OPEN)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("BriefTriageTest: all $checks checks passed")
    else { println("BriefTriageTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
