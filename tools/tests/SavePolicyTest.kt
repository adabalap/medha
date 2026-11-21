// The save outbox's rules.
//
// A capture is something Medha noticed; a save is something a person asked
// for. The tests that matter here are the ones that separate those: a save
// must survive far longer than an observation, it must not be duplicated by a
// second tap, and it must not be silently rewritten on the way through — the
// whole design puts the decision in Quicksilver, and a decision made on
// edited evidence is made on the wrong thing.
import com.adabala.medha.connect.SavePolicy

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
    println("SavePolicyTest")

    println("\npageSize — clamped, never refused")
    eq("absent gives the default", SavePolicy.pageSize(null), SavePolicy.PAGE_DEFAULT)
    eq("zero gives the default", SavePolicy.pageSize(0), SavePolicy.PAGE_DEFAULT)
    eq("negative gives the default", SavePolicy.pageSize(-5), SavePolicy.PAGE_DEFAULT)
    eq("a sane ask is honoured", SavePolicy.pageSize(40), 40)
    eq("the maximum is honoured exactly", SavePolicy.pageSize(SavePolicy.PAGE_MAX), SavePolicy.PAGE_MAX)
    eq("over the maximum is clamped", SavePolicy.pageSize(999_999), SavePolicy.PAGE_MAX)
    // Overshoot whatever the cap currently is, so raising PAGE_MAX cannot
    // turn this into a test of a number that no longer bounds anything.
    check("no input produces a page over the cap",
        (listOf(null, 0, -1, 1, SavePolicy.PAGE_MAX, SavePolicy.PAGE_MAX + 1,
                SavePolicy.PAGE_MAX * 7, Int.MAX_VALUE))
            .all { SavePolicy.pageSize(it) <= SavePolicy.PAGE_MAX })

    println("\nretention — an instruction outlives an observation")
    check("a save is kept far longer than a capture",
        SavePolicy.RETAIN_DAYS > com.adabala.medha.connect.CapturePolicy.RETAIN_DAYS)
    check("…by enough to cover a long absence, not a long weekend",
        SavePolicy.RETAIN_DAYS >= 60)
    eq("the cutoff is the window behind now", SavePolicy.cutoff(1_000_000_000_000L),
        1_000_000_000_000L - SavePolicy.RETAIN_MS)
    check("the window is positive, so cutoff never runs ahead of now",
        SavePolicy.cutoff(1_000_000_000_000L) < 1_000_000_000_000L)

    println("\nrow ceiling — a thumb cannot fill this, so a full table means trouble")
    eq("room means nothing is dropped", SavePolicy.overBy(SavePolicy.MAX_ROWS), 0)
    eq("one under is still fine", SavePolicy.overBy(SavePolicy.MAX_ROWS - 1), 0)
    eq("one over drops exactly one", SavePolicy.overBy(SavePolicy.MAX_ROWS + 1), 1)
    eq("a burst drops the whole overage, not one row",
        SavePolicy.overBy(SavePolicy.MAX_ROWS + 250), 250)
    check("the ceiling is far below the capture log's",
        SavePolicy.MAX_ROWS < com.adabala.medha.connect.CapturePolicy.MAX_ROWS)

    println("\nverbatim, within a ceiling")
    val short = "Onion\nAmla komma\nBanana 1/2 dozen"
    eq("ordinary text is returned untouched", SavePolicy.clipText(short), short)
    eq("ordinary titles are returned untouched", SavePolicy.clipTitle("కళ్యాణి"), "కళ్యాణి")
    check("nothing is stripped, trimmed or normalised",
        SavePolicy.clipText("  Posted by WhatsApp · 22m ago  ") == "  Posted by WhatsApp · 22m ago  ")
    eq("null text becomes empty, not null", SavePolicy.clipText(null), "")
    eq("null title becomes empty, not null", SavePolicy.clipTitle(null), "")
    val huge = "x".repeat(SavePolicy.MAX_TEXT * 3)
    eq("oversized text is cut to the ceiling", SavePolicy.clipText(huge).length, SavePolicy.MAX_TEXT)
    eq("text exactly at the ceiling is untouched",
        SavePolicy.clipText("y".repeat(SavePolicy.MAX_TEXT)).length, SavePolicy.MAX_TEXT)
    eq("oversized titles are cut too",
        SavePolicy.clipTitle("t".repeat(SavePolicy.MAX_TITLE * 2)).length, SavePolicy.MAX_TITLE)
    check("a clipped row admits it", SavePolicy.wasTruncated(null, huge))
    check("a clipped title admits it too",
        SavePolicy.wasTruncated("t".repeat(SavePolicy.MAX_TITLE + 1), "fine"))
    check("a row that fits does not claim truncation", !SavePolicy.wasTruncated("కళ్యాణి", short))
    check("exactly at the ceiling is not truncation",
        !SavePolicy.wasTruncated("t".repeat(SavePolicy.MAX_TITLE), "y".repeat(SavePolicy.MAX_TEXT)))

    println("\nwhat is worth storing")
    check("a real capture is savable", SavePolicy.isSavable("కళ్యాణి", short))
    check("a title with no body is savable", SavePolicy.isSavable("Pick up the dry cleaning", null))
    check("a body with no title is savable", SavePolicy.isSavable(null, short))
    check("nothing at all is refused", !SavePolicy.isSavable(null, null))
    check("blank is refused the same as null", !SavePolicy.isSavable("   ", "\n\t "))

    println("\nfingerprint — stops a double tap, not a repeat next week")
    val a = SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short, 1_700_000_000_000L)
    val b = SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short, 1_700_000_000_000L)
    eq("the same save twice is the same key", a, b)
    check("a second tap cannot produce a new row", a == b)
    val week = SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short, 1_700_604_800_000L)
    check("the same list a week later is a different save", a != week)
    check("a different app is a different save",
        a != SavePolicy.fingerprint("Telegram", "కళ్యాణి", short, 1_700_000_000_000L))
    check("a different title is a different save",
        a != SavePolicy.fingerprint("WhatsApp", "Kalyani", short, 1_700_000_000_000L))
    check("a different body is a different save",
        a != SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short + "\nGhee", 1_700_000_000_000L))
    eq("the key is a fixed width", a.length, 16)
    check("the key is hex", a.all { it in "0123456789abcdef" })
    // The separator exists so content cannot forge a key. Without it,
    // ("a","b") and ("ab","") would concatenate to the same string.
    check("fields cannot be run together to forge a match",
        SavePolicy.fingerprint("a", "b", "c", 1L) !=
            SavePolicy.fingerprint("ab", "", "c", 1L))
    check("…in the other direction too",
        SavePolicy.fingerprint("a", "", "bc", 1L) !=
            SavePolicy.fingerprint("a", "b", "c", 1L))
    check("empty and null field are the same key",
        SavePolicy.fingerprint(null, null, null, 7L) ==
            SavePolicy.fingerprint("", "", "", 7L))
    check("500 distinct saves produce 500 distinct keys",
        (0 until SavePolicy.MAX_ROWS)
            .map { SavePolicy.fingerprint("WhatsApp", "list $it", short, 1_700_000_000_000L + it) }
            .toSet().size == SavePolicy.MAX_ROWS)

    println("\nofferSave — only what Quicksilver does not already have")
    check("a notification-sourced line offers Save",
        SavePolicy.offersSave(com.adabala.medha.notify.BriefPolicy.INFERRED_CLIENT_ID))
    check("…which is the literal the listener posts under", SavePolicy.offersSave("notifications"))
    check("a line Quicksilver posted does not", !SavePolicy.offersSave("quicksilver"))
    check("an admin line does not", !SavePolicy.offersSave("admin"))
    check("an arbitrary client does not", !SavePolicy.offersSave("some-web-app"))
    check("case matters — ids are not display names", !SavePolicy.offersSave("Notifications"))

    println("\nrow — what is actually stored for a brief line")
    val now = 1_700_000_900_000L
    val r = SavePolicy.row("WhatsApp", "కళ్యాణి", short, 1_700_000_000_000L, now)
    check("a real line produces a row", r != null)
    eq("the origin is kept", r?.origin, "WhatsApp")
    eq("the title is kept verbatim", r?.title, "కళ్యాణి")
    eq("the text is kept verbatim", r?.text, short)
    eq("the post time is kept", r?.postedAt, 1_700_000_000_000L)
    check("an ordinary line is not marked truncated", r?.truncated == false)
    eq("its key is the fingerprint of what is stored", r?.fp,
        SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short, 1_700_000_000_000L))
    check("a blank line produces no row", SavePolicy.row("WhatsApp", " ", "", 1L, now) == null)
    val noPost = SavePolicy.row("WhatsApp", "కళ్యాణి", short, null, now)
    eq("a line with no post time falls back to now", noPost?.postedAt, now)
    eq("…and is keyed on it", noPost?.fp, SavePolicy.fingerprint("WhatsApp", "కళ్యాణి", short, now))
    eq("a null origin is stored as empty, not null", SavePolicy.row(null, "t", "x", 1L, now)?.origin, "")
    // The property this function exists for: the key the route serves is
    // recomputed from the stored row, so the write must key the same thing.
    val big = SavePolicy.row("WhatsApp", "t", huge, 5L, now)
    check("an oversized line is stored clipped", big?.text?.length == SavePolicy.MAX_TEXT)
    check("…and says so", big?.truncated == true)
    eq("…and is keyed on the CLIPPED text, which is what the route will serve", big?.fp,
        SavePolicy.fingerprint("WhatsApp", "t", huge.substring(0, SavePolicy.MAX_TEXT), 5L))
    check("…not on the original, which nothing downstream ever sees",
        big?.fp != SavePolicy.fingerprint("WhatsApp", "t", huge, 5L))

    println("\ncursor arithmetic — borrowed from the capture log on purpose")
    eq("a null cursor starts at the beginning", SavePolicy.cursor(null), 0L)
    eq("a negative cursor starts at the beginning", SavePolicy.cursor(-9L), 0L)
    eq("a real cursor is kept", SavePolicy.cursor(42L), 42L)
    eq("an empty page hands back the head", SavePolicy.nextCursor(null, 17L), 17L)
    eq("a full page hands back its last id", SavePolicy.nextCursor(9L, 17L), 9L)
    check("a short page reports no backlog", !SavePolicy.hasMore(3, 10, 9L, 17L))
    check("a full page below the head reports a backlog", SavePolicy.hasMore(10, 10, 9L, 17L))
    check("a full page at the head reports none", !SavePolicy.hasMore(10, 10, 17L, 17L))
    check("an empty page never reports a backlog", !SavePolicy.hasMore(0, 10, null, 17L))

    println("\n${checks - failures}/$checks passed")
    if (failures > 0) {
        println("FAILED")
        kotlin.system.exitProcess(1)
    }
    println("all green")
}
