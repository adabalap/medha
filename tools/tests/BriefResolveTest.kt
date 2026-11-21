// The same obligation arriving twice, from two apps that cannot see each
// other.
//
// The consequence of a match here is that a row DISAPPEARS from someone's
// home screen, so the checks that matter most are the ones asserting what
// must NOT be collapsed: a recurring meeting's future instances, two bills
// from the same merchant, a one-word title.
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefResolve

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
const val IST = 5 * HOUR + 30 * MIN
val IST_MIDNIGHT = 1763251200000L - IST
val NOW = IST_MIDNIGHT + 9 * HOUR

fun committed(title: String, dueAt: Long? = null, link: String? = null, inferred: Boolean = false) =
    BriefPolicy.Item(title, "body", link, dueAt, dueInferred = inferred, committed = true)

fun observed(title: String, dueAt: Long? = null, inferred: Boolean = false) =
    BriefPolicy.Item(title, "body", null, dueAt, dueInferred = inferred, committed = false)

fun section(id: String, vararg items: BriefPolicy.Item) =
    BriefPolicy.Section(id, NOW, NOW + DAY, items.toList())

fun titles(s: List<BriefPolicy.Section>) = s.flatMap { it.items }.map { it.title }

fun main() {
    println("BriefResolve.tokens — what identifies a thing")
    check("a mail prefix identifies nothing",
        BriefResolve.tokens("RE: Fundamentals of Banking Reminder") ==
            BriefResolve.tokens("Fundamentals of Banking"))
    check("nor does a calendar verb",
        BriefResolve.tokens("Invitation: Design review") ==
            BriefResolve.tokens("Design review"))
    check("nor a weekday", "wed" !in BriefResolve.tokens("Design review Wed"))
    check("short words are dropped", BriefResolve.tokens("a to do") == emptySet<String>())
    check("case does not matter",
        BriefResolve.tokens("DESIGN REVIEW") == BriefResolve.tokens("design review"))

    println("\nBriefResolve.overlap")
    eq("nothing against nothing is zero",
        BriefResolve.overlap(emptySet(), setOf("a")), 0.0)
    eq("identical is one", BriefResolve.overlap(setOf("a", "b"), setOf("a", "b")), 1.0)
    // Against the SMALLER set, so a short reminder matched to a quoted
    // thread is not punished for the thread's length.
    eq("a subset of a long title still scores one",
        BriefResolve.overlap(setOf("design", "review"),
            setOf("design", "review", "with", "finance", "thursday")), 1.0)

    println("\nsame — the case this exists for")
    // The 17:30 meeting: the agent has it from a note, the phone has it from
    // Teams, and neither can see the other.
    val fromNote = committed("Milestone Program", IST_MIDNIGHT + 17 * HOUR + 30 * MIN)
    val fromTeams = observed("Milestone Program", IST_MIDNIGHT + 17 * HOUR + 30 * MIN)
    check("the same meeting from two apps is one thing",
        BriefResolve.same(fromNote, fromTeams, IST))
    check("even when only one of them knows the time",
        BriefResolve.same(committed("Milestone Program"), fromTeams, IST))
    check("and through a mail prefix",
        BriefResolve.same(committed("Fundamentals of Banking Reminder"),
            observed("RE: Fundamentals of Banking Reminder"), IST))

    println("\nsame — what must NOT be collapsed")
    // A recurring meeting shares every word with its own future instances.
    // Collapsing them hides tomorrow's.
    // Multi-word on purpose. This read `committed("Standup", ...)` first,
    // whose single token is rejected by MIN_SHARED before the day check is
    // ever reached -- so deleting the day check left the suite green.
    check("today's standup is not tomorrow's",
        !BriefResolve.same(
            committed("Daily standup call", IST_MIDNIGHT + 10 * HOUR),
            committed("Daily standup call", IST_MIDNIGHT + DAY + 10 * HOUR), IST))
    check("nor is next week's",
        !BriefResolve.same(
            committed("Fundamentals of Banking session", IST_MIDNIGHT + 10 * HOUR),
            committed("Fundamentals of Banking session", IST_MIDNIGHT + 7 * DAY), IST))
    check("and the day is the LOCAL day, not UTC",
        // 23:00 IST and 01:00 IST the next morning are two days apart
        // locally and the same UTC day. Getting the offset wrong here
        // merges a late meeting into the next morning's.
        !BriefResolve.same(
            committed("Release cut window", IST_MIDNIGHT + 23 * HOUR),
            committed("Release cut window", IST_MIDNIGHT + DAY + HOUR), IST))
    check("but two copies on the same day are",
        BriefResolve.same(
            committed("Daily standup call", IST_MIDNIGHT + 10 * HOUR),
            observed("Daily standup call", IST_MIDNIGHT + 10 * HOUR + 5 * MIN), IST))
    // A ratio alone would let a one-word title swallow anything containing
    // that word.
    check("a one-word title resolves nothing away",
        !BriefResolve.same(committed("Rent"), committed("Rent reminder for November"), IST))
    check("nor does a two-word title against an unrelated one",
        !BriefResolve.same(committed("Pay rent"), committed("Pay the electricity bill"), IST))
    check("unrelated meetings stay apart",
        !BriefResolve.same(committed("Design review"), committed("Budget review"), IST))
    check("and one shared word is never enough",
        !BriefResolve.same(committed("Banking fundamentals"), committed("Banking holiday"), IST))
    // Two shared words but a LOW ratio: {annual, security, training, module}
    // against {annual, security, badge, renewal} is 2 of 4, under the
    // threshold. Every negative case above is rejected by MIN_SHARED before
    // the ratio is consulted, so without this pair MIN_OVERLAP could be set
    // to zero and nothing would notice.
    check("two shared words out of four is not the same thing",
        !BriefResolve.same(
            committed("Annual security training module"),
            committed("Annual security badge renewal"), IST))
    check("nor three out of six",
        !BriefResolve.same(
            committed("Quarterly finance planning workshop agenda items"),
            committed("Quarterly finance planning retrospective notes summary"), IST))
    check("while a genuine subset still matches",
        BriefResolve.same(
            committed("Design review"),
            observed("Design review with finance"), IST))

    println("\nquality — which copy survives")
    check("committed beats observed",
        BriefResolve.quality(committed("x")) > BriefResolve.quality(observed("x")))
    check("a declared date beats a guessed one",
        BriefResolve.quality(committed("x", NOW)) >
            BriefResolve.quality(committed("x", NOW, inferred = true)))
    check("a date beats none",
        BriefResolve.quality(committed("x", NOW)) > BriefResolve.quality(committed("x")))
    check("a link breaks a tie",
        BriefResolve.quality(committed("x", NOW, link = "https://a.example/1")) >
            BriefResolve.quality(committed("x", NOW)))
    // The one comparison that must never be close: no amount of links or
    // dates outweighs a person having committed to something.
    check("no number of other signals outweighs committed",
        BriefResolve.quality(committed("x")) >
            BriefResolve.quality(observed("x", NOW)) )

    println("\ndedupe — across sections only")
    val both = listOf(
        section("quicksilver", fromNote, committed("Pick up the dry cleaning")),
        section("notifications", fromTeams, observed("Lucid"))
    )
    val out = BriefResolve.dedupe(both, IST)
    eq("one of the two copies goes", out.sumOf { it.items.size }, 3)
    check("and it is the notification that goes, not the note",
        titles(out).contains("Milestone Program") &&
            out.first { it.clientId == "quicksilver" }.items.any { it.title == "Milestone Program" } &&
            out.first { it.clientId == "notifications" }.items.none { it.title == "Milestone Program" })
    check("everything else is untouched",
        titles(out).containsAll(listOf("Pick up the dry cleaning", "Lucid")))
    eq("and it says how many went", BriefResolve.duplicateCount(both, IST), 1)

    // Within one client: collapsed, but only at a stricter bar. Two bills
    // from one merchant are still two bills.
    println("\ndedupe within one app — the brief that showed each task three times")
    // ONE section, deliberately. dedupe used to return a one-section brief
    // before looking at a single item, and one section is the common case:
    // Quicksilver alone. These are the rows a real brief showed.
    val oneApp = listOf(section("quicksilver",
        committed("Pick up dry cleaner clothes", NOW + 2 * DAY),
        committed("Stalled: Apply for Sriram Nagar 3rd Electric Meter"),
        committed("Apply for Sriram Nagar 3rd Electric"),
        committed("Complete mess registration"),
        committed("Thomas Blackwell DOB"),
        committed("Pick up dry cleaner clothes"),
        committed("Apply for Sriram Nagar 3rd Electric Meter")))
    val oneOut = BriefResolve.dedupe(oneApp, IST)
    eq("seven rows for four things become four", oneOut.sumOf { it.items.size }, 4)
    eq("dry cleaning appears once", titles(oneOut).count { "dry cleaner" in it }, 1)
    eq("the electric meter appears once", titles(oneOut).count { "Electric" in it }, 1)
    check("the dry cleaning that survives is the one with a day on it",
        oneOut.flatMap { it.items }.first { "dry cleaner" in it.title }.dueAt == NOW + 2 * DAY)
    check("unrelated rows are untouched",
        titles(oneOut).containsAll(listOf("Complete mess registration", "Thomas Blackwell DOB")))
    eq("and it says how many went", BriefResolve.duplicateCount(oneApp, IST), 3)

    println("\n…but not at the expense of two real things")
    // "Electricity bill" twice may be two meters. Two identifying words is
    // under STRICT_SHARED, so these never match within one app.
    val twoBills = listOf(section("quicksilver",
        committed("Electricity bill", NOW + DAY), committed("Electricity bill", NOW + DAY)))
    eq("two short titles from one app both survive",
        BriefResolve.dedupe(twoBills, IST).sumOf { it.items.size }, 2)
    // The tokenizer drops one-character words, so without the numbers rule
    // these two are identical words -- and they are two meters.
    val meters = listOf(section("quicksilver",
        committed("Pay electricity bill for meter 1"), committed("Pay electricity bill for meter 2")))
    eq("titles that differ only in a number both survive",
        BriefResolve.dedupe(meters, IST).sumOf { it.items.size }, 2)
    val days = listOf(section("quicksilver",
        committed("Pay Sriram Nagar electricity bill", NOW + DAY),
        committed("Pay Sriram Nagar electricity bill", NOW + 31 * DAY)))
    eq("the same bill in two different months both survive",
        BriefResolve.dedupe(days, IST).sumOf { it.items.size }, 2)
    eq("numbers ignore leading zeros", BriefResolve.numbers("Flat 007, 3rd floor"), setOf("7", "3"))
    eq("a title with no numbers has none", BriefResolve.numbers("Pick up dry cleaner clothes"), emptySet<String>())
    // The stricter bar is a different number, not the same one renamed: a
    // pair that clears the cross-app bar can still be refused within one app.
    val loose = listOf(section("quicksilver",
        committed("Renew Sriram Nagar property tax"), committed("Renew Sriram Nagar tax receipt copy")))
    eq("a match good enough across apps is not enough within one",
        BriefResolve.dedupe(loose, IST).sumOf { it.items.size }, 2)
    check("…and it IS good enough across apps",
        BriefResolve.same(committed("Renew Sriram Nagar property tax"),
            committed("Renew Sriram Nagar tax receipt copy"), IST))

    println("\nwithin one app, the outcome does not depend on order")
    val rows = oneApp.first().items
    val orders = listOf(rows, rows.reversed(), rows.drop(3) + rows.take(3), rows.shuffled(java.util.Random(7)))
    check("every ordering leaves four rows",
        orders.all { BriefResolve.dedupe(listOf(section("quicksilver", *it.toTypedArray())), IST)
            .sumOf { s -> s.items.size } == 4 })
    check("and the dated dry cleaning survives in every ordering",
        orders.all { o -> BriefResolve.dedupe(listOf(section("quicksilver", *o.toTypedArray())), IST)
            .flatMap { it.items }.first { "dry cleaner" in it.title }.dueAt == NOW + 2 * DAY })

    println("\ndedupe — shape and edges")
    eq("one section is returned unchanged",
        BriefResolve.dedupe(listOf(section("a", committed("x"))), IST).size, 1)
    eq("no sections stays empty",
        BriefResolve.dedupe(emptyList(), IST), emptyList<BriefPolicy.Section>())
    // The common path, run on every widget redraw: when there is nothing to
    // merge, the very same list comes back rather than a rebuilt copy.
    // Written as `take(1) === take(1)` first, which allocates twice and so
    // could never hold -- the test was wrong, not the code.
    val single = listOf(section("a", committed("x")))
    check("a one-section brief comes back as the same list, not a copy",
        BriefResolve.dedupe(single, IST) === single)
    val distinct = listOf(
        section("quicksilver", committed("Pay the rent")),
        section("notifications", observed("Lucid screen recorder"))
    )
    check("and so does a brief with no duplicates in it",
        BriefResolve.dedupe(distinct, IST) === distinct)
    // A section emptied by resolution is dropped rather than left as a
    // heading with nothing under it, like everywhere else in the brief.
    val swallowed = listOf(
        section("quicksilver", fromNote),
        section("notifications", fromTeams)
    )
    val after = BriefResolve.dedupe(swallowed, IST)
    eq("an emptied section is dropped", after.size, 1)
    eq("and the better copy is what is left", after.first().clientId, "quicksilver")
    check("section metadata survives",
        after.all { it.updatedAt == NOW && it.expiresAt == NOW + DAY })

    println("\ndedupe — a tie keeps the section that has been there longest")
    // Equal quality on both sides, so which one survives is decided by
    // order alone. Documented as "ties keep the earlier section" and
    // untested until a mutation flipped the comparison and nothing noticed.
    val tied = listOf(
        section("quicksilver", committed("Design review session")),
        section("web", committed("Design review session"))
    )
    val tieOut = BriefResolve.dedupe(tied, IST)
    eq("one survives", tieOut.sumOf { it.items.size }, 1)
    eq("and it is the earlier section's", tieOut.first().clientId, "quicksilver")
    // And the reverse order resolves the other way, which is what makes it
    // order and not a preference for a particular client id.
    eq("reversed, the other one wins",
        BriefResolve.dedupe(tied.reversed(), IST).first().clientId, "web")

    println("\ndedupe — a one-word title is never resolved away")
    val oneWord = listOf(
        section("quicksilver", committed("Rent")),
        section("notifications", observed("Rent"))
    )
    eq("both survive, because neither can be identified",
        BriefResolve.dedupe(oneWord, IST).sumOf { it.items.size }, 2)

    println("\ndedupe — an item that already lost takes nobody with it")
    // Matching is not transitive, and that is the whole hazard here.
    // A ~ B and B ~ C, but A does not match C. B loses to A. If B then
    // carries on comparing itself, it eliminates C -- a row nothing else
    // matched, gone for a reason no one could trace back.
    //
    //   A  "Design review session"   committed + declared date   q=14
    //   B  "Design review workshop"  committed, undated         q=8
    //   C  "Review workshop notes"   observed, undated          q=0
    val chainA = committed("Design review session", NOW + 2 * HOUR)
    val chainB = committed("Design review workshop")
    val chainC = observed("Review workshop notes")
    check("A matches B", BriefResolve.same(chainA, chainB, IST))
    check("B matches C", BriefResolve.same(chainB, chainC, IST))
    check("but A does not match C", !BriefResolve.same(chainA, chainC, IST))
    val chain = listOf(
        section("quicksilver", chainA),
        section("web", chainB),
        section("notifications", chainC)
    )
    val chained = BriefResolve.dedupe(chain, IST)
    eq("two survive, not one", chained.sumOf { it.items.size }, 2)
    eq("the winner and the bystander",
        titles(chained).sorted(),
        listOf("Design review session", "Review workshop notes"))

    println("\ndedupe does not depend on who posted first")
    // The bug this replaced: a pairwise walk let an item that had ALREADY
    // lost carry on eliminating others, so the same three obligations
    // resolved to two rows for three section orderings and one row for the
    // other three. Which rows you saw depended on which client happened to
    // write first.
    //
    // Asserted over every permutation rather than one example, because a
    // single ordering is exactly what hid it.
    fun permutations(xs: List<Int>): List<List<Int>> =
        if (xs.size <= 1) listOf(xs)
        else xs.flatMap { h -> permutations(xs - h).map { listOf(h) + it } }

    val trio = listOf(chainA, chainB, chainC)
    val outcomes = permutations(listOf(0, 1, 2)).map { order ->
        val secs = order.mapIndexed { i, k ->
            BriefPolicy.Section("client$i", NOW, NOW + DAY, listOf(trio[k]))
        }
        titles(BriefResolve.dedupe(secs, IST)).sorted()
    }
    eq("all six orderings give the same answer", outcomes.toSet().size, 1)
    eq("and it is the winner plus the bystander",
        outcomes.first(),
        listOf("Design review session", "Review workshop notes"))

    println("\ndedupe is stable: running it twice changes nothing more")
    val once = BriefResolve.dedupe(both, IST)
    val twice = BriefResolve.dedupe(once, IST)
    eq("same count", twice.sumOf { it.items.size }, once.sumOf { it.items.size })
    eq("same titles", titles(twice), titles(once))

    println("\nThree sections, one obligation in all of them")
    val three = listOf(
        section("quicksilver", fromNote),
        section("notifications", fromTeams),
        section("web", observed("Milestone Program"))
    )
    val merged = BriefResolve.dedupe(three, IST)
    eq("exactly one survives", merged.sumOf { it.items.size }, 1)
    eq("and it is the best-sourced one", merged.first().clientId, "quicksilver")

    println("\n" + "-".repeat(50))
    if (failures == 0) println("BriefResolveTest: all $checks checks passed")
    else { println("BriefResolveTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
