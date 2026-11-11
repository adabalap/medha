// Executable tests for the brief's on-disk format, including the
// v0.9.0 -> v0.9.1 migration.
//
// Compiles the REAL app/src/main/java/.../notify/BriefJson.kt against
// tools/tests/stubs/org/json/Stubs.kt, which mirrors Android's org.json
// signatures and adds a small parser so this can actually run:
//
//     kotlinc tools/tests/stubs/org/json/Stubs.kt \
//             app/src/main/java/com/adabala/medha/notify/BriefPolicy.kt \
//             app/src/main/java/com/adabala/medha/notify/BriefJson.kt \
//             tools/tests/BriefJsonTest.kt -include-runtime -d /tmp/bj.jar
//     java -jar /tmp/bj.jar
//
// WHY THIS EXISTS: v0.9.0 stored the brief as one last-writer-wins blob.
// v0.9.1 stores per-client sections. If the new parser does not recognise the
// old shape, every existing install's home-screen widget goes blank on
// upgrade -- and a blank widget is indistinguishable from "nothing posted
// yet", so nobody would report it as data loss. That is not a thing to
// reason about and ship.

import com.adabala.medha.notify.BriefJson
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefPolicy.Item
import com.adabala.medha.notify.BriefPolicy.Section

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
const val NOW = 1_700_000_000_000L

fun main() {

    // ------------------------------------------------------- the migration
    println("\nBriefJson — v0.9.0 legacy blob survives upgrade")
    // Byte-for-byte the shape v0.9.0's setWidgetContent wrote.
    val legacy = """
        {"clientId":"journal","updatedAt":1699999999000,
         "items":[{"title":"Standup at 10","text":"with the platform team"},
                  {"title":"Rent due","text":"Friday"}]}
    """.trimIndent().replace("\n", "")

    val migrated = BriefJson.parse(legacy, NOW)
    eq("one section recovered", migrated.size, 1)
    eq("client id preserved", migrated.firstOrNull()?.clientId, "journal")
    eq("both items preserved",
        migrated.firstOrNull()?.items?.map { it.title },
        listOf("Standup at 10", "Rent due"))
    eq("original updatedAt kept", migrated.firstOrNull()?.updatedAt, 1699999999000L)
    // Dated from NOW, not from updatedAt: a blob older than the TTL would
    // otherwise expire the instant the user upgraded, which is the same blank
    // widget by another route.
    eq("TTL measured from the read, not from updatedAt",
        migrated.firstOrNull()?.expiresAt, NOW + BriefPolicy.DEFAULT_TTL_MS)
    check("and it is actually visible right after upgrade",
        BriefPolicy.rows(migrated, NOW).isNotEmpty())
    check("still visible an hour later",
        BriefPolicy.rows(migrated, NOW + 60 * 60 * 1000L).isNotEmpty())
    check("gone after the TTL, like any other section",
        BriefPolicy.rows(migrated, NOW + BriefPolicy.DEFAULT_TTL_MS + 1).isEmpty())

    println("\nBriefJson — a legacy blob with no clientId")
    val anon = """{"updatedAt":1699999999000,"items":[{"title":"X","text":"y"}]}"""
    eq("falls back to a placeholder rather than an empty id",
        BriefJson.parse(anon, NOW).firstOrNull()?.clientId, BriefJson.UNKNOWN)

    println("\nBriefJson — a legacy blob with no items is not a section")
    check("empty items yields nothing",
        BriefJson.parse("""{"clientId":"a","items":[]}""", NOW).isEmpty())

    // ---------------------------------------------------------- round trip
    println("\nBriefJson — encode/parse round trip")
    val original = listOf(
        Section("journal", NOW - 1000, NOW + DAY, listOf(Item("Note", "body"))),
        Section("builds", NOW - 2000, NOW + DAY, listOf(
            Item("main green", "4m 12s"), Item("release red", "test failure")))
    )
    val round = BriefJson.parse(BriefJson.encode(original), NOW)
    eq("section count", round.size, 2)
    eq("client ids", round.map { it.clientId }.toSet(), setOf("journal", "builds"))
    eq("timestamps survive", round.map { it.updatedAt }.toSet(),
        original.map { it.updatedAt }.toSet())
    eq("expiries survive", round.map { it.expiresAt }.toSet(),
        original.map { it.expiresAt }.toSet())
    eq("items survive in order",
        round.firstOrNull { it.clientId == "builds" }?.items?.map { it.title },
        listOf("main green", "release red"))
    eq("item text survives",
        round.firstOrNull { it.clientId == "journal" }?.items?.map { it.text },
        listOf("body"))

    println("\nBriefJson — round trip with awkward characters")
    // Titles come from arbitrary web apps. Quotes and newlines in a value
    // that is re-parsed on every widget draw is a crash waiting to happen.
    val tricky = listOf(Section("x", NOW, NOW + DAY, listOf(
        Item("He said \"go\"", "line one\nline two\ttabbed"),
        Item("back\\slash", "100% ₹500")
    )))
    val trickyRound = BriefJson.parse(BriefJson.encode(tricky), NOW)
    eq("quotes and newlines survive",
        trickyRound.firstOrNull()?.items?.map { it.title },
        listOf("He said \"go\"", "back\\slash"))
    eq("text with newline and tab survives",
        trickyRound.firstOrNull()?.items?.firstOrNull()?.text,
        "line one\nline two\ttabbed")
    eq("non-ASCII survives",
        trickyRound.firstOrNull()?.items?.getOrNull(1)?.text, "100% ₹500")

    // ------------------------------------------------------- malformed input
    println("\nBriefJson — malformed input costs the brief, not the widget host")
    // This is parsed on every home-screen draw. Throwing here would crash
    // the launcher's widget host, repeatedly, with no way for the user to
    // clear it short of removing the widget.
    for (bad in listOf(
        "", "   ", "not json at all", "{", "}", "[]", "[1,2,3]",
        """{"sections":"not an array"}""",
        """{"sections":[1,2,3]}""",
        """{"sections":[{"clientId":"a"}]}""",
        """{"sections":[{"clientId":"a","items":"nope"}]}""",
        """{"items":[{"noTitle":"x"}]}""",
        """{"sections":[{"clientId":"a","items":[{"title":""}]}]}"""
    )) {
        val got = runCatching { BriefJson.parse(bad, NOW) }
        check("survives ${if (bad.isBlank()) "<blank>" else bad.take(44)}",
            got.isSuccess && got.getOrNull()?.isEmpty() == true)
    }

    println("\nBriefJson — partially malformed input keeps the good sections")
    val mixed = """{"sections":[
        {"clientId":"good","updatedAt":$NOW,"expiresAt":${NOW + DAY},
         "items":[{"title":"Keep me","text":"ok"}]},
        {"clientId":"bad","items":"not an array"},
        7
    ]}""".replace("\n", "")
    val kept = BriefJson.parse(mixed, NOW)
    eq("only the valid section survives", kept.map { it.clientId }, listOf("good"))

    println("\nBriefJson — bounds are enforced on read, not just on write")
    // A hand-edited or downgraded-then-upgraded preference file could hold
    // more items than the layout can draw.
    val overfull = """{"sections":[{"clientId":"a","updatedAt":$NOW,
        "expiresAt":${NOW + DAY},"items":[
        {"title":"1"},{"title":"2"},{"title":"3"},
        {"title":"4"},{"title":"5"},{"title":"6"},{"title":"7"}]}]}""".replace("\n", "")
    eq("items capped at MAX_ITEMS_PER_SECTION on parse",
        BriefJson.parse(overfull, NOW).firstOrNull()?.items?.size,
        BriefPolicy.MAX_ITEMS_PER_SECTION)

    println("\nBriefJson — missing timestamps default sanely")
    val noTimes = """{"sections":[{"clientId":"a","items":[{"title":"t"}]}]}"""
    val d = BriefJson.parse(noTimes, NOW).firstOrNull()
    eq("updatedAt defaults to now", d?.updatedAt, NOW)
    eq("expiresAt defaults to now + default TTL",
        d?.expiresAt, NOW + BriefPolicy.DEFAULT_TTL_MS)
    check("so it renders rather than being silently expired",
        BriefPolicy.rows(BriefJson.parse(noTimes, NOW), NOW).isNotEmpty())

    println("\nBriefJson.isSafeLink — a tap on the home screen must not launch anything")
    // An item is written by a remote client and opened by a tap on the
    // user's home screen. `intent:` URLs can target an arbitrary component,
    // so the accepted scheme list has to be the boring one.
    for (good in listOf(
        "https://notes.example.com/item/1",
        "http://127.0.0.1:8001/x",
        "HTTPS://Example.com/Path?q=1#frag",
        "https://x.com/a%20b"
    )) check("accepts $good", BriefJson.isSafeLink(good))

    for (bad in listOf(
        null, "", "   ",
        "intent://scan/#Intent;scheme=zxing;end",
        "file:///sdcard/x.txt",
        "content://media/external/images/1",
        "javascript:alert(1)",
        "market://details?id=com.x",
        "tel:+911234567890",
        "example.com/no-scheme",
        "https://x.com/a b",                 // raw space
        "https://x.com/a\nb",                // embedded newline
        "https://x.com/" + "y".repeat(3000)  // absurd length
    )) check("rejects ${bad?.take(40) ?: "null"}", !BriefJson.isSafeLink(bad))

    println("\nBriefJson — links survive a round trip")
    val linked = listOf(Section("app", NOW, NOW + DAY, listOf(
        Item("With link", "body", "https://notes.example.com/1"),
        Item("Without link", "body", null)
    )))
    val back = BriefJson.parse(BriefJson.encode(linked), NOW).firstOrNull()
    eq("link preserved", back?.items?.get(0)?.link, "https://notes.example.com/1")
    eq("absent link stays null", back?.items?.get(1)?.link, null)
    // A stored item predating links must not acquire one.
    eq("an item with no link key parses as null",
        BriefJson.parse(
            """{"sections":[{"clientId":"a","updatedAt":$NOW,"expiresAt":${NOW + DAY},
               "items":[{"title":"t","text":"x"}]}]}""".replace("\n", ""), NOW
        ).firstOrNull()?.items?.firstOrNull()?.link, null)

    println("\nBriefJson — an unsafe stored link is dropped on read")
    // Belt and braces: the API filters on write, but a value already on disk
    // from an older build, or hand-edited, must not become tappable.
    val hostile = """{"sections":[{"clientId":"a","updatedAt":$NOW,"expiresAt":${NOW + DAY},
        "items":[{"title":"t","text":"x","link":"intent://evil#Intent;end"}]}]}"""
        .replace("\n", "")
    eq("intent: link stripped at parse time",
        BriefJson.parse(hostile, NOW).firstOrNull()?.items?.firstOrNull()?.link, null)
    check("but the item itself survives",
        BriefJson.parse(hostile, NOW).firstOrNull()?.items?.size == 1)

    println("\nBriefJson — rows carry the link through to the widget")
    eq("BriefPolicy.rows exposes it",
        BriefPolicy.rows(linked, NOW).firstOrNull()?.link, "https://notes.example.com/1")

    println("\nBriefJson — empty brief encodes and re-parses as empty")
    eq("no sections round-trips to no sections",
        BriefJson.parse(BriefJson.encode(emptyList()), NOW).size, 0)

    println("\nBriefJson — dueAt and kind round-trip")
    val withDue = listOf(BriefPolicy.Section("a", NOW, NOW + 86400000L, listOf(
        BriefPolicy.Item("ICICI credit card", "payment missed", null, 1778490000000L, "bill"),
        BriefPolicy.Item("plain", "no extras"))))
    val dueBack = BriefJson.parse(BriefJson.encode(withDue), NOW)
    eq("dueAt survives", dueBack.first().items[0].dueAt, 1778490000000L)
    eq("kind survives", dueBack.first().items[0].kind, "bill")
    eq("an item without them stays null", dueBack.first().items[1].dueAt, null)
    eq("kind too", dueBack.first().items[1].kind, null)

    println("\nBriefJson — a legacy item is undated, NOT 1970")
    // optLong's fallback is 0, which is a real instant. Taken literally it
    // would render every pre-v0.12.0 item as fifty-odd years overdue --
    // the entire widget turning red on upgrade.
    val undated = BriefJson.parse(
        "{\"sections\":[{\"clientId\":\"a\",\"updatedAt\":" + NOW +
        ",\"expiresAt\":" + (NOW + 86400000L) +
        ",\"items\":[{\"title\":\"old\",\"text\":\"x\"}]}]}", NOW)
    eq("parses", undated.size, 1)
    eq("dueAt is null, not zero", undated.first().items.first().dueAt, null)
    eq("so it has no band", BriefPolicy.bandOf(undated.first().items.first().dueAt, NOW), null)

    println("\nBriefJson — hostile dueAt values are dropped")
    for ((what, raw) in listOf(
        "explicit null" to "null",
        "zero" to "0",
        "negative" to "-5"
    )) {
        val parsed = BriefJson.parse(
            "{\"sections\":[{\"clientId\":\"a\",\"updatedAt\":" + NOW +
            ",\"expiresAt\":" + (NOW + 86400000L) +
            ",\"items\":[{\"title\":\"t\",\"dueAt\":" + raw + "}]}]}", NOW)
        eq("$what becomes null", parsed.first().items.first().dueAt, null)
    }

    println("\nBriefJson — an unknown kind is dropped, the item is kept")
    val oddKind = BriefJson.parse(
        "{\"sections\":[{\"clientId\":\"a\",\"updatedAt\":" + NOW +
        ",\"expiresAt\":" + (NOW + 86400000L) +
        ",\"items\":[{\"title\":\"t\",\"kind\":\"spaceship\"}]}]}", NOW)
    eq("the item survives", oddKind.first().items.size, 1)
    eq("the kind does not", oddKind.first().items.first().kind, null)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("BriefJsonTest: all $checks checks passed")
    } else {
        println("BriefJsonTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
