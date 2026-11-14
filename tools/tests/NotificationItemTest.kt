// Turning a notification into a brief row: the glyph, the time, and keeping
// the section free of the same meeting four times over.
//
// The listener service cannot be compiled here, which is the whole reason
// these decisions do not live inside it.
import com.adabala.medha.connect.NotificationFilter.Posted
import com.adabala.medha.connect.NotificationItem
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

const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR

// 2026-05-11 09:00 UTC. With a +0 offset the local day starts at 00:00 UTC,
// which keeps the clock arithmetic in these tests readable.
const val T = 1778490000000L

fun post(pkg: String, title: String, text: String, category: String? = null) =
    Posted(pkg, title, text, category, false, false, 3, false)

fun main() {
    println("NotificationItem.kindFor — the glyph")
    eq("an explicit event category wins",
        NotificationItem.kindFor("com.anything", "event"), "event")
    eq("a calendar package", NotificationItem.kindFor("com.samsung.android.calendar", null), "date")
    eq("Teams is a meeting", NotificationItem.kindFor("com.microsoft.teams", null), "event")
    eq("Zoom too", NotificationItem.kindFor("us.zoom.videomeetings", null), "event")
    eq("Outlook is mail, not a meeting",
        NotificationItem.kindFor("com.microsoft.office.outlook", null), "note")
    eq("a bank is a bill", NotificationItem.kindFor("com.icicibank.imobile", null), "bill")
    eq("anything else is a note", NotificationItem.kindFor("com.whatsapp", null), "note")
    check("and every answer is one BriefPolicy accepts",
        listOf("com.microsoft.teams", "com.icicibank.x", "com.whatsapp", "com.a.calendar")
            .all { BriefPolicy.kindOrNull(NotificationItem.kindFor(it, null)) != null })

    println("\nNotificationItem.dueAtFrom — relative, which is what reminders say")
    eq("in 10 minutes", NotificationItem.dueAtFrom("starts in 10 minutes", T), T + 10 * MIN)
    eq("in 1 hour", NotificationItem.dueAtFrom("Review in 1 hour", T), T + HOUR)
    eq("in 2 days", NotificationItem.dueAtFrom("due in 2 days", T), T + 2 * DAY)
    eq("abbreviations", NotificationItem.dueAtFrom("in 45 mins", T), T + 45 * MIN)

    println("\nNotificationItem.dueAtFrom — clock times")
    // T is 09:00 UTC, so 14:30 the same day is five and a half hours out.
    eq("24-hour", NotificationItem.dueAtFrom("moved to 14:30", T), T + 5 * HOUR + 30 * MIN)
    eq("am/pm", NotificationItem.dueAtFrom("at 2:30 pm", T), T + 5 * HOUR + 30 * MIN)
    eq("midnight is 00, not 12", NotificationItem.dueAtFrom("at 12:00 am today", T), T - 9 * HOUR)
    eq("noon is 12", NotificationItem.dueAtFrom("at 12:00 pm", T), T + 3 * HOUR)
    eq("tomorrow is honoured",
        NotificationItem.dueAtFrom("tomorrow at 10:00", T), T + DAY + HOUR)

    println("\nNotificationItem.dueAtFrom — a time already gone means the next one")
    // 07:00 is two hours past at T, and nobody said today.
    eq("well past rolls to tomorrow",
        NotificationItem.dueAtFrom("standup at 7:00 am", T), T + DAY - 2 * HOUR)
    // 08:30 is only half an hour past: far likelier the meeting that began.
    eq("just past stays today",
        NotificationItem.dueAtFrom("standup at 8:30 am", T), T - 30 * MIN)
    eq("and an explicit today is never rolled",
        NotificationItem.dueAtFrom("today at 7:00 am", T), T - 2 * HOUR)

    println("\nNotificationItem.dueAtFrom — the timezone is a parameter, not a lookup")
    val ist = 5 * HOUR + 30 * MIN
    check("the same words give a different instant in a different zone",
        NotificationItem.dueAtFrom("at 14:30", T, ist) !=
            NotificationItem.dueAtFrom("at 14:30", T, 0L))

    println("\nNotificationItem.dueAtFrom — null rather than a guess")
    eq("no time at all", NotificationItem.dueAtFrom("Rakesh sent a message", T), null)
    eq("a bare number is not a time", NotificationItem.dueAtFrom("budget 2026 review", T), null)
    eq("an impossible hour", NotificationItem.dueAtFrom("at 99:00", T), null)
    eq("an impossible minute", NotificationItem.dueAtFrom("at 10:75", T), null)

    println("\nNotificationItem.toItem")
    val it1 = NotificationItem.toItem(post("com.microsoft.teams", "Standup", "in 10 minutes"), T)
    eq("title", it1.title, "Standup")
    eq("text", it1.text, "in 10 minutes")
    eq("kind", it1.kind, "event")
    eq("dueAt", it1.dueAt, T + 10 * MIN)
    eq("never carries a link, because a notification has no safe url",
        it1.link, null)
    val bare = NotificationItem.toItem(post("com.x.y", "", "Sync finished"), T)
    eq("a title-less notification promotes its text", bare.title, "Sync finished")
    eq("and leaves the detail empty rather than repeating it", bare.text, "")

    println("\nNotificationItem.merge — the same meeting, four times")
    val a = BriefPolicy.Item("Standup", "in 10 minutes", null, T + 10 * MIN, "event")
    val b = BriefPolicy.Item("Standup", "in 9 minutes", null, T + 9 * MIN, "event")
    val c = BriefPolicy.Item("Payslip", "available", null, null, "note")
    val merged = NotificationItem.merge(listOf(a, c), b)
    eq("the repost replaces rather than stacks", merged.size, 2)
    eq("and the NEW countdown is the one kept", merged[0].text, "in 9 minutes")
    eq("newest first", merged[0].title, "Standup")
    check("the unrelated item survives", merged.any { it.title == "Payslip" })
    eq("case and padding do not defeat it",
        NotificationItem.merge(listOf(a), a.copy(title = "  STANDUP ")).size, 1)
    eq("the section stays inside the store's per-client cap",
        (1..9).fold(emptyList<BriefPolicy.Item>()) { acc, i ->
            NotificationItem.merge(acc, c.copy(title = "item $i"))
        }.size,
        BriefPolicy.MAX_ITEMS_PER_SECTION)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("NotificationItemTest: all $checks checks passed")
    } else {
        println("NotificationItemTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
