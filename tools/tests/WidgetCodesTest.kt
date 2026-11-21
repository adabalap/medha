// Where a tap on the brief widget goes.
//
// The system keys a PendingIntent on its request code plus the intent's
// filter equality, which compares a data URI by scheme, host and path
// PREFIX and ignores extras. Two rows pointing at different pages of the
// same bank are equal by that test, so sharing a request code would make
// them one PendingIntent and FLAG_UPDATE_CURRENT would repoint both at
// whichever was written last. Tapping the EMI row would open the row below.
//
// The first version of this arithmetic had exactly that bug: the widget
// background was passed its own widget id, which collided with row 0 of the
// widget before it. It was caught by reading rather than by running, which
// is the reason this file exists — the scheme now lives outside the Android
// class so the harness can actually execute it.
import com.adabala.medha.notify.BriefRender
import com.adabala.medha.notify.WidgetCodes

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

fun main() {
    println("WidgetCodesTest")

    // Real app widget ids are small positive integers handed out by the
    // system, so this covers far more than anyone will place.
    val widgetIds = (0..200).toList()
    val maxRows = BriefRender.ROWS_4X4

    println("\nEvery tap target of every widget is its own code")
    val seen = mutableMapOf<Int, String>()
    var collision: String? = null
    for (w in widgetIds) {
        val targets = buildList {
            add(WidgetCodes.root(w) to "widget $w root")
            for (i in 0 until maxRows) add(WidgetCodes.of(w, i) to "widget $w row $i")
        }
        for ((code, who) in targets) {
            val prior = seen[code]
            if (prior != null && collision == null) collision = "$who collides with $prior"
            seen[code] = who
        }
    }
    check("no collisions across ${widgetIds.size} widgets x ${maxRows + 1} targets",
        collision == null)
    if (collision != null) println("          $collision")
    eq("and the count is exactly what was generated",
        seen.size, widgetIds.size * (maxRows + 1))

    println("\nThe bug that shipped in the first draft")
    // root(1) was once `1`, the widget id itself, which is of(0, 0).
    check("the root of widget 1 is not row 0 of widget 0",
        WidgetCodes.root(1) != WidgetCodes.of(0, 0))
    check("the root of widget n is never a row of widget n-1",
        (1..50).all { w ->
            val r = WidgetCodes.root(w)
            (0 until maxRows).none { i -> WidgetCodes.of(w - 1, i) == r }
        })

    println("\nThe root sits below its own rows")
    check("root is lower than every row of the same widget",
        (0..50).all { w -> (0 until maxRows).all { i -> WidgetCodes.root(w) < WidgetCodes.of(w, i) } })

    println("\nCodes are non-negative")
    // A negative request code is legal but makes logs unreadable, and the
    // mask is there to keep a large widget id from wrapping into one.
    check("never negative, even for a huge widget id",
        listOf(0, 1, 1000, Int.MAX_VALUE / 31, Int.MAX_VALUE)
            .all { w -> WidgetCodes.root(w) >= 0 && WidgetCodes.of(w, maxRows - 1) >= 0 })

    println("\nThe stride has room for the widest size")
    check("the 4x4's rows fit", WidgetCodes.fitsStride(BriefRender.ROWS_4X4))
    check("so do the smaller sizes",
        WidgetCodes.fitsStride(BriefRender.ROWS_4X2) && WidgetCodes.fitsStride(BriefRender.ROWS_2X2))
    check("and there is headroom left", WidgetCodes.fitsStride(BriefRender.ROWS_4X4 + 4))
    // The guard that matters: if someone adds a size this cannot hold, the
    // codes silently start overlapping the next widget's range.
    check("but it refuses a size the stride cannot hold",
        !WidgetCodes.fitsStride(WidgetCodes.STRIDE))
    check("exactly at the boundary it still fits",
        WidgetCodes.fitsStride(WidgetCodes.STRIDE - 1))

    println("\n" + "-".repeat(50))
    if (failures == 0) println("WidgetCodesTest: all $checks checks passed")
    else { println("WidgetCodesTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
