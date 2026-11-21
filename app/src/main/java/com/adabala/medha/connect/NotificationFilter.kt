package com.adabala.medha.connect

/**
 * Decides what a posted notification is allowed to become.
 *
 * WHY THIS IS THE MOST SAFETY-CRITICAL FILE IN MEDHA
 *
 * A NotificationListenerService reads EVERY notification on the device.
 * There is no narrower grant: Android offers one switch, and behind it sits
 * banking OTPs, password resets, private messages, medical appointments and
 * everything else. Medha asks for that switch because a work calendar that
 * lives inside Outlook or Teams never reaches CalendarContract, so there is
 * no structured route to it at all.
 *
 * Having asked for everything, this file is the promise about what happens
 * next. Nothing downstream re-checks it: the listener keeps only what this
 * returns [Verdict.Keep] for, and nothing else is parsed, inferred over,
 * stored, or drawn.
 *
 * It is pure Kotlin on purpose. The listener itself needs Android and so
 * cannot be compiled by the test harness, and "which notifications reach the
 * home screen" is not a question that should be answered by code nobody can
 * execute. Every rule below has tests.
 *
 * THE RULE THAT IS NOT NEGOTIABLE
 *
 * A one-time passcode must never reach the brief. The brief is drawn on a
 * home screen and on a lock screen widget: rendering an OTP there defeats
 * the entire point of sending it out of band, in front of whoever is
 * standing behind you. [looksLikeSecret] is deliberately over-eager — it
 * drops some harmless notifications to be sure of dropping every harmful
 * one, and that trade is the right way round.
 */
object NotificationFilter {

    sealed class Verdict {
        object Keep : Verdict()
        /** [why] is for the diagnostics screen, never for the brief. */
        data class Drop(val why: String) : Verdict()
    }

    /**
     * What the listener hands over: a notification reduced to the fields
     * that can matter, with no reference to anything Android.
     */
    data class Posted(
        val pkg: String,
        val title: String,
        val text: String,
        /** `Notification.category`, or null. */
        val category: String?,
        /** True for media controls, downloads, foreground services. */
        val ongoing: Boolean,
        /** A group header carrying no content of its own. */
        val groupSummary: Boolean,
        /** `NotificationManager.IMPORTANCE_*`; -1000 when unknown. */
        val importance: Int,
        /** True when the user has silenced this channel. */
        val silent: Boolean
    )

    /** Medha must never read itself back in and loop. */
    const val SELF = "com.adabala.medha"

    /**
     * Launchers, keyboards, system UI and the like. These post persistent
     * status notifications that are never news.
     */
    val SYSTEM_PREFIXES = listOf(
        "com.android.systemui",
        "com.android.providers",
        "com.samsung.android.lool",
        "com.sec.android.app.launcher",
        "android"
    )

    /**
     * Categories that are never a brief item.
     *
     * CATEGORY_CALL and CATEGORY_ALARM are live, demand attention now, and
     * have their own full-screen surfaces; putting them on a digest that
     * refreshes every few minutes is both late and redundant. TRANSPORT is
     * media controls.
     */
    val DROP_CATEGORIES = setOf("call", "alarm", "transport", "progress", "service")

    /**
     * Words that mean "this message contains a credential".
     *
     * Matched case-insensitively against title and text together. Deliberate
     * over-reach: "verification" catches plenty of harmless mail, and that
     * is a price worth paying.
     */
    val SECRET_WORDS = listOf(
        "otp", "one-time", "one time pass", "passcode", "pass code",
        "verification code", "verify code", "security code", "auth code",
        "authentication code", "2fa", "two-factor", "two factor",
        "login code", "sign-in code", "sign in code", "access code",
        "activation code", "recovery code", "backup code", "token is",
        "security token", "otp is", "confirmation code",
        "do not share", "don't share", "never share",
        "password", "pin is", "your pin", "cvv", "mpin"
    )

    /**
     * A standalone run of 4 to 8 digits, after hyphens and spaces INSIDE a
     * run are closed up so "771-223" reads as one six-digit code.
     */
    //
    // A hyphen between digits always joins: "771-223" is one code. A SPACE
    // only joins when at least three digits precede it, because otherwise
    // "Q3 2026" glues into "q32026", stops looking like a year, and a
    // perfectly ordinary planning notification gets dropped as a passcode.
    // That was a real bug, found by probing rather than by the tests.
    private val GLUE = Regex("""(?<=\d)-(?=\d)|(?<=\d\d\d) (?=\d)""")
    private val DIGIT_RUN = Regex("""(?<![\d:])(\d{4,8})(?![\d:])""")

    /**
     * Nouns that turn a number into a quantity rather than a code.
     *
     * Checked immediately after the digits: "12450 steps" is a step count,
     * "12450" alone is a passcode until proven otherwise.
     */
    val UNIT_AFTER = setOf(
        "step", "steps", "km", "mi", "miles", "members", "people", "points",
        "messages", "message", "unread", "items", "item", "files", "photos",
        "mb", "gb", "kb", "calories", "cal", "likes", "views", "new",
        "days", "day", "hours", "hrs", "minutes", "mins", "seconds", "secs"
    )

    /** Markers that make the number money. */
    val CURRENCY = setOf("rs", "rs.", "inr", "₹", "$", "usd", "eur", "£", "€", "aed")

    /**
     * True when the text could contain a credential.
     *
     * SUSPICIOUS BY DEFAULT. A bare run of 4 to 8 digits is treated as a
     * passcode unless something in the sentence makes it clearly a quantity
     * — money before it, a unit after it, or a plausible year.
     *
     * The first version of this did the opposite: it looked for framing
     * words like "code" or "verify" next to the digits, and only then
     * dropped. Probing it with ten real-world phrasings leaked eight,
     * including "Use 123456 to log in" and "118822 - Amazon", because
     * senders routinely post a code with no explanatory word at all. A rule
     * that needs the sender's cooperation is not a rule.
     *
     * The cost is real and accepted: a bare "1430" meaning half past two,
     * or a flight number, is dropped too. A missed brief line is a small
     * loss; an OTP on a lock screen in front of whoever is behind you is
     * not. Over-eager is the right way round, and the exemptions below are
     * deliberately the only way out.
     */
    fun looksLikeSecret(title: String, text: String): Boolean {
        val all = "$title $text".lowercase()
        if (SECRET_WORDS.any { it in all }) return true

        val glued = GLUE.replace(all, "")
        for (m in DIGIT_RUN.findAll(glued)) {
            val digits = m.value

            // A four-digit year is far more often a quarter or a plan than a
            // passcode, and dropping every "Q3 2026" would gut the work
            // items this feature exists for.
            if (digits.length == 4) {
                val n = digits.toIntOrNull()
                if (n != null && n in 1900..2099) continue
            }

            val before = glued.take(m.range.first).trimEnd()
            val lastWord = before.takeLastWhile { !it.isWhitespace() }
            if (lastWord in CURRENCY || CURRENCY.any { before.endsWith(it) }) continue

            val after = glued.drop(m.range.last + 1).trimStart()
            val nextWord = after.takeWhile { it.isLetter() }
            if (nextWord in UNIT_AFTER) continue

            // Percentages and anything with a decimal point are measurements.
            if (after.startsWith("%") || after.startsWith(".")) continue

            return true
        }
        return false
    }

    /**
     * The whole decision, in one place.
     *
     * Order matters only for the quality of [Verdict.Drop.why]; the rules do
     * not overlap in effect. The secret check runs before anything that
     * might be considered "useful", so no later rule can rescue one.
     */
    fun verdict(p: Posted, blocked: Set<String> = emptySet()): Verdict {
        if (p.pkg == SELF) return Verdict.Drop("Medha's own notification")
        if (p.pkg in blocked) return Verdict.Drop("app turned off by you")
        if (SYSTEM_PREFIXES.any { p.pkg == it || p.pkg.startsWith("$it.") }) {
            return Verdict.Drop("system or launcher notification")
        }

        if (looksLikeSecret(p.title, p.text)) {
            return Verdict.Drop("looks like a passcode, never shown")
        }

        if (p.groupSummary) return Verdict.Drop("group header, no content of its own")
        if (p.ongoing) return Verdict.Drop("ongoing notification, not news")
        if (p.category != null && p.category.lowercase() in DROP_CATEGORIES) {
            return Verdict.Drop("category ${p.category}")
        }
        if (p.silent) return Verdict.Drop("channel silenced by you")
        if (p.importance in 0..1) return Verdict.Drop("minimum importance")

        if (p.title.isBlank() && p.text.isBlank()) return Verdict.Drop("no content")

        return Verdict.Keep
    }

    /** Convenience for call sites that only care whether it survived. */
    fun keeps(p: Posted, blocked: Set<String> = emptySet()): Boolean =
        verdict(p, blocked) is Verdict.Keep
}
