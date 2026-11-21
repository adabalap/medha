// Turning a PUT /widget/content body into what the brief stores.
//
// This is the protocol boundary. Everything on the far side of it is a
// client's claim, and everything on this side reaches the user's home
// screen, so the interesting cases are all the ones where a client is
// wrong: a missing field, both fields, an id that is only spaces, a links
// array with nothing openable in it.
//
// It lives here rather than in LocalServer.kt because LocalServer.kt is not
// compilable by anything short of Gradle, and every decision this project
// has left in that file has eventually been wrong in a way nobody could
// execute a test against.
import com.adabala.medha.notify.BriefIntake
import com.adabala.medha.notify.BriefJson

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
    println("BriefIntake.shapeOf — which protocol arrived")
    eq("rows alone are v1", BriefIntake.shapeOf(true, false), BriefIntake.Shape.V1)
    eq("obligations alone are v2", BriefIntake.shapeOf(false, true), BriefIntake.Shape.V2)
    // Both is refused rather than merged or given a precedence rule. A
    // request that disagrees with itself about its own protocol is not
    // something to guess at on the user's behalf.
    eq("both is an error", BriefIntake.shapeOf(true, true), BriefIntake.Shape.BOTH)
    // And the one that matters most: `{}` must NOT read as a withdrawal.
    // `{"items": []}` is how a client takes its section down, and that is a
    // thing it has to mean; a lost payload is not.
    eq("neither is an error, not an empty post",
        BriefIntake.shapeOf(false, false), BriefIntake.Shape.NEITHER)
    check("every combination is accounted for",
        listOf(true, false).flatMap { a -> listOf(true, false).map { b -> a to b } }
            .map { (a, b) -> BriefIntake.shapeOf(a, b) }
            .toSet().size == 4)

    println("\nBriefIntake.idOrNull — a handle, not a payload")
    eq("a real id passes through", BriefIntake.idOrNull("obl-4312"), "obl-4312")
    eq("surrounding space is not part of it", BriefIntake.idOrNull("  obl-1  "), "obl-1")
    eq("absent is null", BriefIntake.idOrNull(null), null)
    // Blank must be null, not "". An empty id shared by every item would
    // produce one identical dismissal key for all of them, so putting away
    // one row would put away the whole section.
    eq("empty is null", BriefIntake.idOrNull(""), null)
    eq("whitespace is null", BriefIntake.idOrNull("   "), null)
    eq("a tab and newline are too", BriefIntake.idOrNull("\t\n"), null)
    eq("a long id is capped, not refused",
        BriefIntake.idOrNull("x".repeat(500))?.length, BriefJson.MAX_ID_LENGTH)
    check("the cap is a sane size for a uuid or a path",
        BriefJson.MAX_ID_LENGTH in 32..1024)
    // Case is NOT normalised here: an id is opaque, and two clients using
    // case-sensitive ids must not have them collapsed. BriefDismiss lowers
    // it for its own key, which is a different question.
    eq("case is preserved, because the id is opaque",
        BriefIntake.idOrNull("Obl-ABC"), "Obl-ABC")

    println("\nBriefIntake.dueAtOrNull — zero is a real instant")
    eq("a real instant passes", BriefIntake.dueAtOrNull(1_763_251_200_000L), 1_763_251_200_000L)
    eq("absent is null", BriefIntake.dueAtOrNull(null), null)
    // The whole reason this is not `?: 0`: an item stored with dueAt 0
    // renders as decades overdue and sorts above everything real.
    eq("zero is dropped", BriefIntake.dueAtOrNull(0L), null)
    eq("negative is dropped", BriefIntake.dueAtOrNull(-1L), null)
    eq("one millisecond past the epoch is kept, because it is a date",
        BriefIntake.dueAtOrNull(1L), 1L)

    println("\nBriefIntake.chooseLink — one tap target, and an honest count")
    val a = "https://notes.example/1"
    val b = "https://notes.example/2"
    eq("nothing to choose from", BriefIntake.chooseLink(emptyList()),
        BriefIntake.Links(null, 0))
    eq("one safe link is chosen and nothing is dropped",
        BriefIntake.chooseLink(listOf(a)), BriefIntake.Links(a, 0))
    // First, not best: the order is the client's statement of preference and
    // Medha has no basis for a better one.
    eq("the FIRST is chosen, and the rest counted",
        BriefIntake.chooseLink(listOf(a, b)), BriefIntake.Links(a, 1))
    eq("three means two dropped",
        BriefIntake.chooseLink(listOf(a, b, b)), BriefIntake.Links(a, 2))
    // An unsafe scheme is skipped rather than failing the item: a row with
    // no tap target beats no row. It still counts as dropped, which is how a
    // client discovers its custom scheme went nowhere.
    eq("a custom scheme is skipped, and the next one used",
        BriefIntake.chooseLink(listOf("quicksilver://note/1", a)),
        BriefIntake.Links(a, 1))
    eq("only a custom scheme means no target and a count of one",
        BriefIntake.chooseLink(listOf("quicksilver://note/1")),
        BriefIntake.Links(null, 1))
    eq("javascript: is not a tap target either",
        BriefIntake.chooseLink(listOf("javascript:alert(1)")),
        BriefIntake.Links(null, 1))
    eq("nor file:", BriefIntake.chooseLink(listOf("file:///etc/passwd")),
        BriefIntake.Links(null, 1))
    eq("nor intent:, which can name any component",
        BriefIntake.chooseLink(listOf("intent://x#Intent;end")),
        BriefIntake.Links(null, 1))
    eq("blanks are dropped, not chosen",
        BriefIntake.chooseLink(listOf("", "   ", a)), BriefIntake.Links(a, 2))
    check("http is accepted as well as https -- loopback has no TLS",
        BriefIntake.chooseLink(listOf("http://127.0.0.1:5000/n/1")).chosen != null)
    // Accounting: every entry is either the one chosen or counted dropped.
    // Without this, a miscount would misreport silently.
    listOf(
        listOf(a),
        listOf(a, b),
        listOf("quicksilver://x", a, b),
        listOf("quicksilver://x"),
        emptyList()
    ).forEach { links ->
        val r = BriefIntake.chooseLink(links)
        check("accounted for: ${links.size} in -> ${if (r.chosen != null) 1 else 0}+${r.dropped}",
            (if (r.chosen != null) 1 else 0) + r.dropped == links.size)
    }

    println("\nBriefIntake.tooLong — the 400 has to say which field")
    eq("both fit", BriefIntake.tooLong("t", "x", 80, 200), null)
    eq("exactly at the cap fits", BriefIntake.tooLong("t".repeat(80), "x".repeat(200), 80, 200), null)
    eq("one over on the title names the title",
        BriefIntake.tooLong("t".repeat(81), "x", 80, 200), "title")
    eq("one over on the text names the text",
        BriefIntake.tooLong("t", "x".repeat(201), 80, 200), "text")
    // Title first when both are over: one message, and the first field is
    // the one a client reads.
    eq("both over reports the title",
        BriefIntake.tooLong("t".repeat(81), "x".repeat(201), 80, 200), "title")
    eq("empty strings fit any cap", BriefIntake.tooLong("", "", 80, 200), null)

    println("\nsinceOrNull — when an obligation began, if believable")
    val now = 1_700_000_000_000L
    eq("a past instant is kept", BriefIntake.sinceOrNull(now - 48L * 86_400_000L, now), now - 48L * 86_400_000L)
    eq("absent stays absent", BriefIntake.sinceOrNull(null, now), null)
    eq("zero means no value, not 1970", BriefIntake.sinceOrNull(0L, now), null)
    eq("negative is refused", BriefIntake.sinceOrNull(-5L, now), null)
    eq("a little clock skew is tolerated", BriefIntake.sinceOrNull(now + 60_000L, now), now + 60_000L)
    eq("exactly at the skew limit is tolerated",
        BriefIntake.sinceOrNull(now + BriefIntake.SINCE_SKEW_MS, now), now + BriefIntake.SINCE_SKEW_MS)
    eq("beyond it, a future start is refused",
        BriefIntake.sinceOrNull(now + BriefIntake.SINCE_SKEW_MS + 1, now), null)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("BriefIntakeTest: all $checks checks passed")
    else { println("BriefIntakeTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
