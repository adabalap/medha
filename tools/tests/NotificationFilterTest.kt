// What a notification is allowed to become.
//
// Medha asks for the one Android switch that reads EVERY notification,
// because a work calendar living inside Outlook or Teams never reaches
// CalendarContract and there is no narrower grant to ask for. This file is
// the evidence for the promise that comes with that: what gets dropped, and
// in particular that a one-time passcode can never reach a home screen.
//
// The listener itself needs Android and cannot be compiled here, which is
// exactly why the decision does not live in it.
import com.adabala.medha.connect.NotificationFilter
import com.adabala.medha.connect.NotificationFilter.Posted
import com.adabala.medha.connect.NotificationFilter.Verdict

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

fun note(
    pkg: String = "com.microsoft.teams",
    title: String = "Standup",
    text: String = "starts in 10 minutes",
    category: String? = null,
    ongoing: Boolean = false,
    groupSummary: Boolean = false,
    importance: Int = 3,
    silent: Boolean = false
) = Posted(pkg, title, text, category, ongoing, groupSummary, importance, silent)

fun kept(p: Posted, blocked: Set<String> = emptySet()) = NotificationFilter.keeps(p, blocked)
fun why(p: Posted, blocked: Set<String> = emptySet()): String =
    (NotificationFilter.verdict(p, blocked) as? Verdict.Drop)?.why ?: "KEPT"

fun main() {
    println("NotificationFilter — the ordinary case survives")
    check("a Teams meeting reminder is kept", kept(note()))
    check("an Outlook mail is kept",
        kept(note(pkg = "com.microsoft.office.outlook", title = "Rakesh", text = "Re: Q3 numbers")))

    // ------------------------------------------------------------------
    // The rule that is not negotiable.
    // ------------------------------------------------------------------
    println("\nNotificationFilter — a passcode never reaches the brief")
    val secrets = listOf(
        note(title = "ICICI Bank", text = "834921 is your OTP. Do not share."),
        note(title = "Your verification code", text = "Use 55213 to sign in"),
        note(title = "Google", text = "G-884412 is your Google verification code"),
        note(title = "Account", text = "Your one-time password is 7781"),
        note(title = "Reset", text = "Your password is Temp@1234"),
        note(title = "HDFC", text = "Never share your CVV or mPIN with anyone"),
        note(title = "Login", text = "2FA code: 991233"),
        note(title = "Steam", text = "Your Steam access code is QF4R2"),
        note(title = "Bank", text = "Use this security code to authenticate: 4471"),
        note(title = "Verify", text = "Code 7719 expires in 10 minutes")
    )
    secrets.forEachIndexed { i, s ->
        check("secret ${i + 1} dropped: \"${s.text.take(34)}\"", !kept(s))
    }
    eq("and the reason never quotes the text",
        why(secrets[0]), "looks like a passcode, never shown")

    // ------------------------------------------------------------------
    // The corpus below is the one that matters. The first version of this
    // filter passed every test above and still leaked EIGHT of these ten
    // real-world phrasings, because it looked for framing words like "code"
    // next to the digits and senders routinely post a code with no framing
    // word at all. These are kept as tests so that rule can never come back.
    // ------------------------------------------------------------------
    println("\nNotificationFilter — codes posted with no framing word at all")
    listOf(
        "Use 123456 to log in",
        "Enter 445566 on the website",
        "Your Medha token is 99112",
        "118822 - Amazon",
        "Microsoft account security info 772913",
        "Use 4471 to complete your transaction",
        "Dear user 882341 is valid for 10 mins",
        "Your Uber PIN 4412",
        "WhatsApp code 771-223",
        "Your code is 123 456",
        "Confirm it's you: 55231",
        "G-884412 is your Google verification code"
    ).forEach { s ->
        check("dropped: \"$s\"", NotificationFilter.looksLikeSecret("", s))
    }

    println("\nNotificationFilter — and the ordinary numbers it must not eat")
    listOf(
        "12450 steps today",
        "Rs 1250 debited for electricity",
        "Q3 2026 planning review",
        "Sprint 2025 retro at 4 pm",
        "Budget 2027 kickoff",
        "Standup starts in 10 minutes",
        "3 new messages",
        "Invoice paid: Rs 45000",
        "CPU at 95%",
        "6E flight check-in open"
    ).forEach { s ->
        check("kept: \"$s\"", !NotificationFilter.looksLikeSecret("", s))
    }

    println("\nNotificationFilter — the cost of being suspicious by default")
    // Stated plainly rather than hidden: a bare four-digit military time or
    // a flight number with no unit beside it IS dropped. A missed brief line
    // is a small loss; an OTP on a lock screen is not.
    check("a bare military time is dropped, and that is the accepted trade",
        NotificationFilter.looksLikeSecret("Review", "Moved to 1430 tomorrow"))
    check("a bare flight number is dropped too",
        NotificationFilter.looksLikeSecret("Indigo", "6E 2134 departs"))
    check("but the same time written normally survives",
        !NotificationFilter.looksLikeSecret("Review", "Moved to 2:30 pm tomorrow"))

    println("\nNotificationFilter — noise that is never news")
    check("Medha's own notification", !kept(note(pkg = "com.adabala.medha")))
    eq("and says so", why(note(pkg = "com.adabala.medha")), "Medha's own notification")
    check("system ui", !kept(note(pkg = "com.android.systemui")))
    check("a package merely starting with the same letters is NOT system",
        kept(note(pkg = "com.androidrocks.myapp")))
    check("an ongoing media control", !kept(note(ongoing = true)))
    check("a group summary", !kept(note(groupSummary = true)))
    check("a silenced channel", !kept(note(silent = true)))
    check("minimum importance", !kept(note(importance = 1)))
    check("unknown importance is not treated as minimum", kept(note(importance = -1000)))
    check("an empty notification", !kept(note(title = "", text = "")))

    println("\nNotificationFilter — categories with their own surfaces")
    listOf("call", "alarm", "transport", "progress", "service").forEach {
        check("category $it is dropped", !kept(note(category = it)))
    }
    check("category upper case is still dropped", !kept(note(category = "CALL")))
    check("an email category is kept", kept(note(category = "email")))
    check("an event category is kept", kept(note(category = "event")))

    println("\nNotificationFilter — the user's own blocklist")
    check("a blocked app is dropped",
        !kept(note(pkg = "com.whatsapp"), blocked = setOf("com.whatsapp")))
    eq("and the reason points at them, not at us",
        why(note(pkg = "com.whatsapp"), setOf("com.whatsapp")), "app turned off by you")
    check("blocking one app does not block another",
        kept(note(pkg = "com.microsoft.teams"), blocked = setOf("com.whatsapp")))

    println("\nNotificationFilter — the secret check cannot be outranked")
    // An OTP from an app the user explicitly allowed, at high importance,
    // in a category we keep, must STILL be dropped. If any later rule could
    // rescue one, the promise would be conditional.
    check("high importance does not rescue a secret",
        !kept(note(title = "Bank", text = "OTP 4412", importance = 5, category = "email")))
    check("nor does a blank blocklist", !kept(note(text = "your otp is 1234"), emptySet()))

    println("\nNotificationFilter.looksLikeSecret — used directly by diagnostics")
    check("plain word match", NotificationFilter.looksLikeSecret("", "your OTP"))
    check("case does not matter", NotificationFilter.looksLikeSecret("", "YOUR One-Time Pass"))
    check("a framed bare code", NotificationFilter.looksLikeSecret("", "code 8812"))
    check("an unframed bare code is not enough",
        !NotificationFilter.looksLikeSecret("", "8812 members"))

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("NotificationFilterTest: all $checks checks passed")
    } else {
        println("NotificationFilterTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
