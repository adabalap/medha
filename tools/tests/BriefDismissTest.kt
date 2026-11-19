// Removing a line from your own brief, and having it stay removed.
//
// The trap this file exists for: clients own their section and re-post it
// whole, so deleting an item from the store removes it for about thirty
// seconds. A dismissal has to live separately from the content and be
// applied on every read, or the gesture silently does nothing.
import com.adabala.medha.notify.BriefDismiss
import com.adabala.medha.notify.BriefDismiss.Mark
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

const val DAY = 24 * 60 * 60 * 1000L
const val T = 1778490000000L

fun item(title: String, dueAt: Long? = null) = BriefPolicy.Item(title, "x", null, dueAt, null)

fun brief(vararg items: BriefPolicy.Item) =
    BriefPolicy.put(emptyList(), "quicksilver", items.toList(), T)

fun titles(s: List<BriefPolicy.Section>) = s.flatMap { it.items }.map { it.title }

fun main() {
    println("BriefDismiss.key — what makes two items the same item")
    val nov = item("Electricity", T)
    val dec = item("Electricity", T + 30 * DAY)
    check("the same bill next month is NOT the same item",
        BriefDismiss.key("a", nov) != BriefDismiss.key("a", dec))
    check("the same item from a different app is not either",
        BriefDismiss.key("a", nov) != BriefDismiss.key("b", nov))
    eq("case and padding do not make a new item",
        BriefDismiss.key("a", item("  ELECTRICITY ", T)), BriefDismiss.key("a", nov))
    check("an undated item still has an identity",
        BriefDismiss.key("a", item("Note")).isNotEmpty())
    check("a title cannot forge a separator and collide with another",
        BriefDismiss.key("a", item("x\u001Fy")) != BriefDismiss.key("a\u001Fx", item("y")))

    println("\nBriefDismiss.key — the client's own id wins, v0.20.0")
    // Why: the title-based key dissolved on a reword. An agent that
    // rewrites "Pay ICICI card" as "ICICI card payment" was posting
    // something Medha could not recognise, so an item the user had put away
    // came back -- looking like the agent's fault rather than the key's.
    val withId = item("Pay ICICI card", T).copy(id = "obl-77")
    val reworded = item("ICICI card payment", T).copy(id = "obl-77")
    eq("a reword is the same item when the id says so",
        BriefDismiss.key("a", withId), BriefDismiss.key("a", reworded))
    // A reschedule too. Arguably debatable for a meeting dismissed because
    // of its old time -- but a client that wants the dismissal dropped can
    // change the id, and one that cannot tell the difference should not be
    // deciding.
    eq("and so is a reschedule",
        BriefDismiss.key("a", withId),
        BriefDismiss.key("a", item("Pay ICICI card", T + 30 * DAY).copy(id = "obl-77")))
    check("a different id is a different item",
        BriefDismiss.key("a", withId) != BriefDismiss.key("a", withId.copy(id = "obl-78")))
    check("the same id from another client is still not yours",
        BriefDismiss.key("a", withId) != BriefDismiss.key("b", withId))
    // The two encodings must not be able to meet. A length prefix is always
    // digits and the id form writes `#` there, so no title can produce an
    // id-shaped key -- the same argument as the separator check above.
    // The case the `#` marker actually prevents, and the one the first
    // version of this test missed: an id-keyed item against an UNDATED item
    // whose title IS that id. Both halves end in the same bytes otherwise.
    // A mutation that dropped the marker survived the dated version of this
    // check, because the dueAt made the two differ for an unrelated reason.
    //
    // Concretely: an obligation with id "rent" and an undated row titled
    // "rent" from the same client. Dismissing one would dismiss the other.
    check("an id key cannot collide with an UNDATED title key",
        BriefDismiss.key("a", item("obl-77")) != BriefDismiss.key("a", withId))
    check("nor with a dated one",
        BriefDismiss.key("a", item("obl-77", T)) != BriefDismiss.key("a", withId))
    // And the reverse direction: a title that imitates the id encoding.
    check("a title cannot forge an id key",
        BriefDismiss.key("a", withId) !=
            BriefDismiss.key("a", item("#6:obl-77")))
    // Exhaustive over a small adversarial corpus: no id-form key may equal
    // any title-form key, whatever the client names things. This is the
    // property, rather than three examples of it.
    val words = listOf("rent", "obl-77", "#6:obl-77", "6:rent", "", "  ", "a|b")
    val idKeys = words.filter { it.isNotBlank() }
        .map { BriefDismiss.key("a", item("whatever", T).copy(id = it)) }
    val titleKeys = words.flatMap { w ->
        listOf(BriefDismiss.key("a", item(w)), BriefDismiss.key("a", item(w, T)))
    }
    check("no id key equals any title key, over every pairing",
        idKeys.none { it in titleKeys })
    check("and distinct ids stay distinct", idKeys.toSet().size == idKeys.size)

    println("\nBriefDismiss.key — the old encoding is unchanged")
    // Marks live for 30 days. A reshaped key would quietly resurrect
    // everything dismissed in the last month, once, on upgrade -- so the
    // title form has to keep producing the same bytes.
    eq("an item with no id is byte-identical to the three-arg form",
        BriefDismiss.key("a", nov), BriefDismiss.key("a", "Electricity", T))
    eq("the literal encoding is frozen",
        BriefDismiss.key("a", "Electricity", T), "1:a|11:electricity|$T")
    eq("and an undated one", BriefDismiss.key("a", "Note", null), "1:a|4:note|")
    // Blank and whitespace ids must fall back rather than becoming a shared
    // identity -- one id of "" across a section would make one dismissal
    // remove every row in it.
    eq("an empty id falls back to the title",
        BriefDismiss.key("a", nov.copy(id = "")), BriefDismiss.key("a", nov))
    eq("a whitespace id does too",
        BriefDismiss.key("a", nov.copy(id = "   ")), BriefDismiss.key("a", nov))

    println("\nBriefDismiss.key — the four-arg form is what the screen calls")
    // The full-brief screen dismisses from a BriefRender.Line, not an Item,
    // so it calls the four-arg form. If the two disagreed, tapping Dismiss
    // on a v2 item would store a mark matching nothing: the row would
    // animate away and return on the next render, with no error anywhere.
    // That regression was one line from shipping.
    eq("it agrees with the Item form, id and all",
        BriefDismiss.key("a", withId.id, withId.title, withId.dueAt),
        BriefDismiss.key("a", withId))
    eq("and with no id", BriefDismiss.key("a", null, "Electricity", T),
        BriefDismiss.key("a", nov))
    check("so a dismissal from the screen actually removes the item",
        BriefDismiss.apply(
            BriefPolicy.put(emptyList(), "quicksilver", listOf(withId), T),
            BriefDismiss.add(
                emptyList(),
                listOf(BriefDismiss.key("quicksilver", withId.id, withId.title, withId.dueAt)),
                T
            ),
            T
        ).isEmpty())

    println("\nBriefDismiss.apply — the item goes, and stays gone")
    val b = brief(item("Electricity", T), item("Rent", T + DAY), item("Note"))
    val marks = BriefDismiss.add(emptyList(), listOf(BriefDismiss.key("quicksilver", nov)), T)
    eq("dismissed one", titles(BriefDismiss.apply(b, marks, T)), listOf("Rent", "Note"))
    // The point of the whole file: the client re-posts everything, and the
    // dismissal still holds because it does not live in the content.
    val reposted = brief(item("Electricity", T), item("Rent", T + DAY), item("Note"))
    eq("and a full re-post does not bring it back",
        titles(BriefDismiss.apply(reposted, marks, T)), listOf("Rent", "Note"))

    println("\nBriefDismiss.apply — but next month's bill is a new bill")
    val next = brief(item("Electricity", T + 30 * DAY), item("Rent", T + DAY))
    eq("a new due date means a new item",
        titles(BriefDismiss.apply(next, marks, T)).sorted(), listOf("Electricity", "Rent"))

    println("\nBriefDismiss.apply — emptied sections disappear entirely")
    val one = brief(item("Only", T))
    val all = BriefDismiss.add(emptyList(), BriefDismiss.keysOf(one), T)
    eq("no heading left with nothing under it",
        BriefDismiss.apply(one, all, T).size, 0)
    eq("no marks is a no-op", BriefDismiss.apply(b, emptyList(), T).size, b.size)

    println("\nBriefDismiss.keysOf — dismissing a group")
    var two = BriefPolicy.put(emptyList(), "a", listOf(item("A1", T), item("A2", T + DAY)), T)
    two = BriefPolicy.put(two, "b", listOf(item("B1", T)), T + 1000)
    val justA = BriefDismiss.keysOf(two) { s, _ -> s.clientId == "a" }
    eq("a group is whatever the caller says it is", justA.size, 2)
    eq("and only that group goes",
        titles(BriefDismiss.apply(two, BriefDismiss.add(emptyList(), justA, T), T)),
        listOf("B1"))
    eq("no filter means everything", BriefDismiss.keysOf(two).size, 3)

    println("\nBriefDismiss.prune — dismissals do not live forever")
    val old = listOf(Mark("k", T - 31 * DAY))
    eq("past the TTL it is forgotten", BriefDismiss.prune(old, T).size, 0)
    eq("inside it, it holds", BriefDismiss.prune(listOf(Mark("k", T - 29 * DAY)), T).size, 1)
    check("a mark from the future is not trusted",
        BriefDismiss.prune(listOf(Mark("k", T + DAY)), T).isEmpty())
    eq("the same key twice collapses",
        BriefDismiss.prune(listOf(Mark("k", T), Mark("k", T - DAY)), T).size, 1)
    eq("and the newest wins",
        BriefDismiss.prune(listOf(Mark("k", T - DAY), Mark("k", T)), T).first().at, T)
    eq("the list is capped",
        BriefDismiss.prune((1..400).map { Mark("k$it", T - it * 1000L) }, T).size,
        BriefDismiss.MAX)
    check("and the cap keeps the NEWEST, not the first seen",
        BriefDismiss.prune((1..400).map { Mark("k$it", T - it * 1000L) }, T)
            .any { it.key == "k1" })

    println("\nBriefDismiss — storage survives a round trip")
    val round = listOf(Mark("a\u001Fb\u001F123", T), Mark("c\u001Fd\u001F", T - 1000))
    eq("encode then decode", BriefDismiss.decode(BriefDismiss.encode(round)), round)
    eq("empty", BriefDismiss.decode(""), emptyList<Mark>())
    eq("null", BriefDismiss.decode(null), emptyList<Mark>())
    // A corrupt preference must not be able to make the brief unreadable.
    eq("garbage lines are skipped, not thrown",
        BriefDismiss.decode("nonsense\n\n\tnokey\nabc\tkey\n123\tgood").map { it.key },
        listOf("good"))

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("BriefDismissTest: all $checks checks passed")
    } else {
        println("BriefDismissTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
