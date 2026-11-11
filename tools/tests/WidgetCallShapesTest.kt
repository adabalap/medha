// Pins the BriefPolicy call shapes used by the three callers this harness
// cannot compile.
//
// MedhaWidgetProvider, BriefRotator and LocalServer all need an Android
// classpath, so nothing before Gradle type-checks them. When v0.12.2 threaded
// `offsetMs` through rows() and page() and started rendering the countdown,
// every one of those edits was a call shape that could only fail at Gradle
// time — eight minutes later, in a job that costs a push to start.
//
// So the shapes live here instead: same argument order, same nullability,
// same named arguments skipping the same defaults. If someone changes a
// BriefPolicy signature, this fails locally in seconds and the message names
// the caller that would have broken.
//
// It asserts behaviour as well as compilation, because a signature can stay
// valid while the meaning underneath it changes.
import com.adabala.medha.notify.BriefPolicy

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

fun main() {
    println("WidgetCallShapes — the shapes Gradle would have checked")

    val now = 1778490000000L
    val sections = BriefPolicy.put(
        emptyList(), "quicksilver",
        listOf(
            BriefPolicy.Item("bill", "payment missed", null, now - 10 * DAY, "bill"),
            BriefPolicy.Item("undated", "no date", null, null, null)
        ),
        now
    )

    // ---- MedhaWidgetProvider.draw() -------------------------------------
    val offset: Long = BriefPolicy.deviceOffset(now)
    val tick: Long = 3L
    val page = BriefPolicy.page(sections, now, tick, offsetMs = offset)
    eq("page(sections, now, tick, offsetMs =) returns rows", page.rows.isNotEmpty(), true)

    val dated = page.rows.first()
    val mark: String = BriefPolicy.relative(dated.dueAt, now, offset)
        ?: BriefPolicy.age(dated.updatedAt, now)
    eq("a dated row shows the countdown, not the post age", mark, "10d over")
    eq(
        "countdown leads the line, attribution second",
        "$mark · ${dated.clientId}", "10d over · quicksilver"
    )

    val undated = page.rows.last()
    val undatedMark: String = BriefPolicy.relative(undated.dueAt, now, offset)
        ?: BriefPolicy.age(undated.updatedAt, now)
    eq("an undated row falls back to age rather than blanking", undatedMark, "just now")

    // ---- BriefRotator.shouldRotate() ------------------------------------
    val at = now
    val count: Int = BriefPolicy
        .rows(sections, at, offsetMs = BriefPolicy.deviceOffset(at))
        .size
    eq("rows(sections, now, offsetMs =) skips the maxRows default", count, 2)

    // ---- LocalServer.tzOffset() -----------------------------------------
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
