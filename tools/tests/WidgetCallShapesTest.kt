// Pins the call shapes used by BriefWidgets.kt, which this harness cannot
// compile.
//
// The three widget providers need RemoteViews, Context and an Android
// classpath, so nothing before Gradle type-checks a line of them. Every edit
// there is a shape that can only fail eight minutes into CI, in a job that
// costs a push to start. So the shapes live here: same argument order, same
// nullability, same named arguments skipping the same defaults, and the same
// exhaustive `when` over BriefRender.Fold — which is the one that silently
// stops compiling the day somebody adds a fourth Fold case.
//
// It asserts behaviour as well as compilation, because a signature can stay
// valid while the meaning underneath it changes.
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefRender

var failures = 0
var checks = 0

fun eq(what: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) println("  ok    $what")
    else {
        println("  FAIL  $what\n          expected: $expected\n          actual:   $actual")
        failures++
    }
}

const val DAY = 24 * 60 * 60 * 1000L

// Stands in for the provider's string resources. The real one calls
// context.getString with the same argument counts; what is checked here is
// that the `when` is exhaustive and each case carries the fields it needs.
fun foldText(fold: BriefRender.Fold): String = when (fold) {
    is BriefRender.Fold.Overdue -> "${fold.more} more · ${fold.overdue} overdue"
    is BriefRender.Fold.Soon -> "${fold.more} more · ${fold.soon} due this week"
    is BriefRender.Fold.Clear -> "${fold.more} more · nothing else due this week"
}

// Stands in for barFor()/iconFor(), which return R.drawable ids and so cannot
// live here. The shape being pinned is the exhaustive `when` over a nullable
// enum: add a band without a branch and this stops compiling.
fun barName(band: BriefPolicy.Band?): String = when (band) {
    BriefPolicy.Band.OVERDUE -> "bar_overdue"
    BriefPolicy.Band.SOON -> "bar_soon"
    BriefPolicy.Band.LATER -> "bar_later"
    null -> "bar_none"
}

fun main() {
    println("WidgetCallShapes — the shapes Gradle would have checked")

    val now = 1778490000000L
    val sections = BriefPolicy.put(
        emptyList(), "quicksilver",
        listOf(
            BriefPolicy.Item("bill", "payment missed", null, now - 10 * DAY, "bill"),
            BriefPolicy.Item("water", "due Friday", null, now + 3 * DAY, "date"),
            BriefPolicy.Item("loan", "21 Nov", null, now + 10 * DAY, null),
            BriefPolicy.Item("undated", "no date", null, null, null)
        ),
        now
    )

    // ---- BriefWidgetProvider.draw() -------------------------------------
    val offset: Long = BriefPolicy.deviceOffset(now)
    val face = BriefRender.face(sections, now, offset, BriefRender.ROWS_4X2)

    eq("face() fills the 4x2 capacity", face.lines.size, 3)
    val line = face.lines[0]
    eq("the first line is the overdue one", line.title, "bill")
    eq("mark is a String, ready for setTextViewText", line.mark, "10d over")
    eq("band selects a bar drawable", barName(line.band), "bar_overdue")
    eq("kind selects an icon", line.kind, "bill")
    eq("attribution is available when a second app writes", line.clientId, "quicksilver")

    // getOrNull, because a 4x4 has eight slots and rarely eight lines
    eq("a slot past the end yields null, not an exception", face.lines.getOrNull(7), null)

    eq("the header badge takes an Int", face.overdue, 1)
    eq("contributors decides whether to attribute", face.contributors, 1)
    eq("isEmpty decides the empty state", face.isEmpty, false)

    // ---- the fold ---------------------------------------------------------
    val fold = face.fold
    eq("one item is below the fold", fold, BriefRender.Fold.Clear(1))
    eq("and the when over Fold is exhaustive", fold?.let { foldText(it) },
        "1 more · nothing else due this week")

    eq("the overdue case carries two counts",
        foldText(BriefRender.Fold.Overdue(4, 2)), "4 more · 2 overdue")
    eq("the soon case carries two counts",
        foldText(BriefRender.Fold.Soon(3, 1)), "3 more · 1 due this week")

    // ---- the 2x2, which has no fold view at all ---------------------------
    val small = BriefRender.face(sections, now, offset, BriefRender.ROWS_2X2)
    eq("2x2 draws one line", small.lines.size, 1)
    eq("and still reports a fold the layout has nowhere to put",
        small.fold, BriefRender.Fold.Soon(3, 1))

    // ---- an undated line, where the fallback matters ----------------------
    val only = BriefPolicy.put(emptyList(), "a", listOf(BriefPolicy.Item("n", "x")), now)
    val u = BriefRender.face(only, now, offset, BriefRender.ROWS_2X2).lines.first()
    eq("an undated line falls back to the post age", u.mark, "just now")
    eq("and takes the neutral bar", barName(u.band), "bar_none")

    // ---- BriefWidgets.refresh() / LocalServer.tzOffset() ------------------
    eq(
        "deviceOffset agrees with the platform",
        BriefPolicy.deviceOffset(now),
        java.util.TimeZone.getDefault().getOffset(now).toLong()
    )

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("WidgetCallShapesTest: all $checks checks passed")
    } else {
        println("WidgetCallShapesTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
