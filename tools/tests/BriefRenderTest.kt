// What the widget decides to draw — the part that had no tests at all until
// v0.13.0, because it lived inside MedhaWidgetProvider and nothing short of
// Gradle could compile that.
//
// The decisions under test are the ones a user actually sees: which rows
// survive the cut at each size, what the right-hand mark says, which band
// colours the bar, and whether the footer's claim about what is hidden is
// true.
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefRender

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
const val T = 1778490000000L

fun due(title: String, daysOut: Long?, kind: String? = null) =
    BriefPolicy.Item(title, "text", null, daysOut?.let { T + it * DAY }, kind)

fun main() {
    println("BriefRender — capacities match the layouts")
    eq("2x2 shows one line", BriefRender.ROWS_2X2, 1)
    eq("4x2 shows three", BriefRender.ROWS_4X2, 3)
    eq("4x4 shows eight", BriefRender.ROWS_4X4, 8)
    check("and none exceeds what the store can hold",
        BriefRender.ROWS_4X4 <= BriefPolicy.MAX_SECTIONS * BriefPolicy.MAX_ITEMS_PER_SECTION)

    // A brief with one overdue, one due this week, three later.
    val brief = BriefPolicy.put(
        emptyList(), "quicksilver",
        listOf(
            due("later1", 20), due("bill", -10, "bill"), due("later2", 25),
            due("water", 3, "date"), due("later3", 30)
        ),
        T
    )

    println("\nBriefRender.face — 2x2 is the next thing, not a summary")
    val small = BriefRender.face(brief, T, 0L, BriefRender.ROWS_2X2)
    eq("exactly one line", small.lines.size, 1)
    eq("and it is the overdue one", small.lines.first().title, "bill")
    eq("the mark is a countdown, not an age", small.lines.first().mark, "10d over")
    eq("the band drives the bar colour", small.lines.first().band, BriefPolicy.Band.OVERDUE)
    eq("kind is carried for the icon", small.lines.first().kind, "bill")
    eq("the header badge counts the whole brief, not the drawn part", small.overdue, 1)
    eq("total is the whole brief", small.total, 5)

    println("\nBriefRender.face — 4x2 names what is below the fold")
    val mid = BriefRender.face(brief, T, 0L, BriefRender.ROWS_4X2)
    eq("three lines", mid.lines.map { it.title }, listOf("bill", "water", "later1"))
    eq("in band order", mid.lines.map { it.band },
        listOf(BriefPolicy.Band.OVERDUE, BriefPolicy.Band.SOON, BriefPolicy.Band.LATER))
    eq("the fold reports two hidden, none of them urgent",
        mid.fold, BriefRender.Fold.Clear(2))

    println("\nBriefRender.face — 4x4 holds the lot")
    val big = BriefRender.face(brief, T, 0L, BriefRender.ROWS_4X4)
    eq("all five drawn", big.lines.size, 5)
    eq("nothing hidden means no fold line at all", big.fold, null)

    println("\nBriefRender.fold — the claim has to be true")
    // Two overdue, one slot: the fold must say so rather than reassure.
    val twoOverdue = BriefPolicy.put(
        emptyList(), "a", listOf(due("od1", -5), due("od2", -3), due("far", 40)), T
    )
    eq("more overdue than room says overdue, not 'nothing else'",
        BriefRender.face(twoOverdue, T, 0L, 1).fold, BriefRender.Fold.Overdue(2, 1))

    // Hidden items that are merely soon must not be reported as clear.
    val soonHidden = BriefPolicy.put(
        emptyList(), "a", listOf(due("od", -1), due("s1", 2), due("s2", 4)), T
    )
    eq("a hidden this-week item is named",
        BriefRender.face(soonHidden, T, 0L, 1).fold, BriefRender.Fold.Soon(2, 2))

    // Only when nothing hidden is urgent may the footer reassure.
    val clear = BriefPolicy.put(
        emptyList(), "a", listOf(due("od", -1), due("l1", 20), due("l2", 25)), T
    )
    eq("and only then does it say nothing else is due",
        BriefRender.face(clear, T, 0L, 1).fold, BriefRender.Fold.Clear(2))

    println("\nBriefRender — undated and empty")
    val undated = BriefPolicy.put(emptyList(), "a", listOf(BriefPolicy.Item("note", "x")), T)
    val u = BriefRender.face(undated, T, 0L, BriefRender.ROWS_4X2)
    eq("an undated line falls back to the post age", u.lines.first().mark, "just now")
    eq("and carries no band, so the bar stays neutral", u.lines.first().band, null)
    eq("no fold when there is nothing more", u.fold, null)

    val none = BriefRender.face(emptyList(), T, 0L, BriefRender.ROWS_4X2)
    check("an empty brief draws nothing", none.isEmpty)
    eq("and claims nothing", none.fold, null)
    eq("and counts nothing", none.overdue, 0)

    println("\nBriefRender — attribution")
    var two = BriefPolicy.put(emptyList(), "quicksilver", listOf(due("a", -1)), T)
    two = BriefPolicy.put(two, "sandeshika", listOf(due("b", -2)), T + 1000)
    eq("contributors counted", BriefRender.face(two, T, 0L, 3).contributors, 2)
    eq("single writer reports one", small.contributors, 1)
    check("lines carry the client so the provider can attribute",
        BriefRender.face(two, T, 0L, 3).lines.all { it.clientId.isNotBlank() })

    println("\nBriefRender — the fold counts what is hidden, not what paging kept")
    // Eight sections of five is forty items, well past BriefPolicy.MAX_ROWS.
    // The footer must count the real remainder.
    var many = emptyList<BriefPolicy.Section>()
    for (c in 1..8) {
        many = BriefPolicy.put(
            many, "app$c",
            (1..5).map { due("app$c-$it", 20L + it) }, T + c * 1000L
        )
    }
    val crowded = BriefRender.face(many, T, 0L, BriefRender.ROWS_4X2)
    eq("forty items are all accounted for", crowded.total, 40)
    eq("and the fold says thirty-seven, not nine",
        crowded.fold, BriefRender.Fold.Clear(37))

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("BriefRenderTest: all $checks checks passed")
    } else {
        println("BriefRenderTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
