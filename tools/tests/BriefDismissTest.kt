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
