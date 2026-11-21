// Executable tests for the pure-logic pieces behind the PWA notification API.
//
// These run WITHOUT the Android SDK or Gradle:
//
//     kotlinc app/src/main/java/com/adabala/medha/net/WebOrigin.kt \
//             app/src/main/java/com/adabala/medha/net/CorsPolicy.kt \
//             app/src/main/java/com/adabala/medha/net/RateLimiter.kt \
//             app/src/main/java/com/adabala/medha/auth/PairingManager.kt \
//             tools/tests/NetPolicyTest.kt -include-runtime -d /tmp/t.jar
//     java -jar /tmp/t.jar
//
// verify.sh runs this automatically when kotlinc is on PATH.
//
// WebOrigin and CorsPolicy are an access-control boundary: if origin matching
// is wrong, any page on the internet can drive a device. That is not something
// to eyeball.

import com.adabala.medha.net.WebOrigin
import com.adabala.medha.net.CorsPolicy
import com.adabala.medha.net.RateLimiter
import com.adabala.medha.auth.PairingManager

var failures = 0
var checks = 0

fun check(what: String, ok: Boolean) {
    checks++
    if (ok) {
        println("  ok    $what")
    } else {
        println("  FAIL  $what")
        failures++
    }
}

fun <T> eq(what: String, actual: T, expected: T) {
    checks++
    if (actual == expected) {
        println("  ok    $what")
    } else {
        println("  FAIL  $what\n          expected: $expected\n          actual:   $actual")
        failures++
    }
}

// ---------------------------------------------------------------- WebOrigin

fun webOriginTests() {
    println("\nWebOrigin.canonical")
    eq("plain https", WebOrigin.canonical("https://notes.example.com"), "https://notes.example.com")
    eq("uppercase host folds", WebOrigin.canonical("HTTPS://Notes.Example.COM"), "https://notes.example.com")
    eq("default https port dropped", WebOrigin.canonical("https://x.com:443"), "https://x.com")
    eq("default http port dropped", WebOrigin.canonical("http://x.com:80"), "http://x.com")
    eq("non-default port kept", WebOrigin.canonical("http://x.com:8080"), "http://x.com:8080")
    eq("path stripped", WebOrigin.canonical("https://x.com/app/index.html"), "https://x.com")
    eq("query stripped", WebOrigin.canonical("https://x.com/?a=1"), "https://x.com")
    eq("fragment stripped", WebOrigin.canonical("https://x.com#top"), "https://x.com")
    eq("trailing slash", WebOrigin.canonical("https://x.com/"), "https://x.com")
    eq("whitespace trimmed", WebOrigin.canonical("  https://x.com  "), "https://x.com")
    eq("loopback with port", WebOrigin.canonical("http://127.0.0.1:8001"), "http://127.0.0.1:8001")
    eq("ipv6 bracketed", WebOrigin.canonical("http://[::1]:8001"), "http://[::1]:8001")

    println("\nWebOrigin.canonical — rejections")
    eq("null input", WebOrigin.canonical(null), null)
    eq("empty", WebOrigin.canonical(""), null)
    // The headline one: a sandboxed iframe or file:// page sends this literal.
    eq("literal \"null\" origin", WebOrigin.canonical("null"), null)
    eq("literal \"NULL\" origin", WebOrigin.canonical("NULL"), null)
    eq("no scheme", WebOrigin.canonical("example.com"), null)
    eq("scheme only", WebOrigin.canonical("https://"), null)
    eq("userinfo rejected", WebOrigin.canonical("https://user:pw@x.com"), null)
    eq("bad port", WebOrigin.canonical("https://x.com:99999"), null)
    eq("non-numeric port", WebOrigin.canonical("https://x.com:abc"), null)
    eq("bare ipv6 unbracketed", WebOrigin.canonical("http://::1"), null)
    eq("unterminated bracket", WebOrigin.canonical("http://[::1"), null)

    println("\nWebOrigin.isLoopback")
    check("127.0.0.1", WebOrigin.isLoopback("http://127.0.0.1:8001"))
    check("localhost", WebOrigin.isLoopback("http://localhost:8001"))
    check("[::1]", WebOrigin.isLoopback("http://[::1]:8001"))
    check("public host is not", !WebOrigin.isLoopback("https://example.com"))
    // A name someone else controls that happens to resolve to loopback must
    // not be treated as loopback; the browser sends the name, not the IP.
    check("localtest.me is not loopback", !WebOrigin.isLoopback("http://localtest.me:8001"))
    check("127.0.0.1.evil.com is not loopback", !WebOrigin.isLoopback("http://127.0.0.1.evil.com"))
    check("null is not loopback", !WebOrigin.isLoopback("null"))

    println("\nWebOrigin.slugForHost — output must satisfy ClientRegistry's id pattern")
    // ClientRegistry.create() require()s this pattern. Anything that slips
    // through surfaces on the phone as a pairing failure complaining about
    // the client id, which gives no hint that the host was the problem.
    for (host in listOf(
        "notes.example.com", "x", "ab", "EXAMPLE.COM", "127.0.0.1", "[::1]",
        "a--b.example.com", "-leading.example.com", "trailing-.com",
        "a-very-long-hostname-that-definitely-exceeds-thirty-two-characters.example.com",
        "???", "-", "--", "", "3com.example"
    )) {
        val slug = WebOrigin.slugForHost(host)
        check("slug(\"$host\") = \"$slug\" matches id pattern",
            WebOrigin.ID_PATTERN.matches(slug))
    }
    eq("dots become dashes", WebOrigin.slugForHost("notes.example.com"), "notes-example-com")
    eq("case folds", WebOrigin.slugForHost("EXAMPLE.COM"), "example-com")
    eq("single char is padded to reach the 2-char minimum", WebOrigin.slugForHost("x"), "x-app")
    eq("unusable host falls back", WebOrigin.slugForHost("???"), WebOrigin.FALLBACK_ID)
    eq("empty host falls back", WebOrigin.slugForHost(""), WebOrigin.FALLBACK_ID)

    println("\nWebOrigin.isPotentiallyTrustworthy")
    check("https", WebOrigin.isPotentiallyTrustworthy("https://x.com"))
    check("http loopback (why mixed content allows us)", WebOrigin.isPotentiallyTrustworthy("http://127.0.0.1:8001"))
    check("plain http public is not", !WebOrigin.isPotentiallyTrustworthy("http://x.com"))
}

// --------------------------------------------------------------- CorsPolicy

fun corsTests() {
    val allowed = setOf("https://notes.example.com", "http://127.0.0.1:8001")

    println("\nCorsPolicy — allowed origin")
    val get = CorsPolicy.decide("https://notes.example.com", "GET", false, allowed)
    check("allowed", get.allowed)
    eq("echoes the exact origin, never *", get.headers[CorsPolicy.ALLOW_ORIGIN], "https://notes.example.com")
    eq("Vary: Origin present", get.headers[CorsPolicy.VARY], "Origin")
    check("not short-circuited", !get.isPreflight)

    println("\nCorsPolicy — denied origin")
    val bad = CorsPolicy.decide("https://evil.example", "GET", false, allowed)
    check("not allowed", !bad.allowed)
    eq("no allow-origin header at all", bad.headers[CorsPolicy.ALLOW_ORIGIN], null)
    eq("still varies on Origin so caches cannot cross-serve", bad.headers[CorsPolicy.VARY], "Origin")

    println("\nCorsPolicy — the \"null\" origin")
    val nul = CorsPolicy.decide("null", "GET", false, allowed)
    // canonical() returns null for "null", which falls into the no-Origin
    // branch: allowed through to the bearer-token check, and granted nothing.
    eq("sandboxed frame gets no allow-origin", nul.headers[CorsPolicy.ALLOW_ORIGIN], null)
    // Belt and braces: even if "null" were somehow on the allow list, it must
    // not produce an echo header. A mutation that deleted the explicit "null"
    // guard in canonical() passed the check above, because the generic scheme
    // parse rejects a bare "null" too — so assert against a poisoned set.
    val poisoned = allowed + "null"
    eq("\"null\" is unmatchable even if present in the allow set",
        CorsPolicy.decide("null", "GET", false, poisoned).headers[CorsPolicy.ALLOW_ORIGIN], null)

    println("\nCorsPolicy — origin matching is exact, not prefix or suffix")
    // A prefix match here is the classic CORS hole: evil.com registers
    // notes.example.com.evil.com and inherits the grant. A mutation changing
    // the `in` check to startsWith passed the rest of this suite, so these
    // are the cases that pin it down.
    for (hostile in listOf(
        "https://notes.example.com.evil.example",   // suffix-appended
        "https://notes.example.com-evil.example",   // no dot boundary
        "https://evil.example",                      // unrelated
        "https://sub.notes.example.com",             // subdomain is NOT implied
        "http://notes.example.com",                  // scheme downgrade
        "https://notes.example.com:8443",            // different explicit port
        "https://anotes.example.com"                 // prefix-extended host
    )) {
        val d = CorsPolicy.decide(hostile, "GET", false, allowed)
        check("rejects $hostile", !d.allowed && d.headers[CorsPolicy.ALLOW_ORIGIN] == null)
    }
    // ...and the legitimate one still works, so the above is not just
    // "everything is denied".
    check("exact origin still allowed",
        CorsPolicy.decide("https://notes.example.com", "GET", false, allowed).allowed)

    println("\nCorsPolicy — preflight and Private Network Access")
    val pre = CorsPolicy.decide("https://notes.example.com", "OPTIONS", true, allowed)
    eq("204", pre.shortCircuitStatus, 204)
    eq("PNA granted when asked", pre.headers[CorsPolicy.ALLOW_PRIVATE_NETWORK], "true")
    check("methods advertised", pre.headers[CorsPolicy.ALLOW_METHODS]?.contains("POST") == true)
    check("Authorization allowed", pre.headers[CorsPolicy.ALLOW_HEADERS]?.contains("Authorization") == true)
    check("max-age set", pre.headers[CorsPolicy.MAX_AGE] != null)

    val preNoAsk = CorsPolicy.decide("https://notes.example.com", "OPTIONS", false, allowed)
    eq("PNA absent when not asked", preNoAsk.headers[CorsPolicy.ALLOW_PRIVATE_NETWORK], null)

    // The one that matters: an unpaired origin must never be told that a
    // private-network fetch would succeed.
    val preEvil = CorsPolicy.decide("https://evil.example", "OPTIONS", true, allowed)
    eq("PNA never granted to an unpaired origin", preEvil.headers[CorsPolicy.ALLOW_PRIVATE_NETWORK], null)
    eq("unpaired preflight still answered", preEvil.shortCircuitStatus, 204)
    check("but not allowed", !preEvil.allowed)

    println("\nCorsPolicy — no Origin header (curl, native client)")
    val none = CorsPolicy.decide(null, "POST", false, allowed)
    check("passes through", none.allowed)
    eq("no allow-origin invented", none.headers[CorsPolicy.ALLOW_ORIGIN], null)

    println("\nCorsPolicy — port-normalised match")
    val https443 = CorsPolicy.decide("https://notes.example.com:443", "GET", false, allowed)
    check(":443 matches the stored origin", https443.allowed)

    println("\nCorsPolicy.loopbackOrigins")
    val lb = CorsPolicy.loopbackOrigins(8001)
    check("includes 127.0.0.1:8001", "http://127.0.0.1:8001" in lb)
    check("includes localhost:8001", "http://localhost:8001" in lb)
    check("port-specific", "http://127.0.0.1:8080" !in lb)
}

// -------------------------------------------------------------- RateLimiter

fun rateLimiterTests() {
    println("\nRateLimiter")
    var now = 0L
    val rl = RateLimiter(capacity = 5, refillPerMinute = 60, clock = { now })

    check("burst of 5 allowed", (1..5).all { rl.allow("a") })
    check("6th denied", !rl.allow("a"))
    check("a different client is unaffected", rl.allow("b"))

    now += 1_000 // 1s at 60/min == 1 token
    check("one token back after 1s", rl.allow("a"))
    check("and only one", !rl.allow("a"))

    now += 60_000 // way more than capacity
    check("refill caps at capacity: 5 allowed", (1..5).all { rl.allow("a") })
    check("not 6", !rl.allow("a"))

    val after = rl.retryAfterSeconds("a")
    check("retryAfter is a positive whole second, never 0", after >= 1)

    rl.forget("a")
    check("forget resets the bucket", rl.allow("a"))

    // Eviction must not throw or wedge.
    val small = RateLimiter(capacity = 1, refillPerMinute = 1, maxKeys = 4, clock = { now })
    repeat(20) { small.allow("k$it") }
    check("bounded key map survives churn", small.allow("fresh"))
}

// ------------------------------------------------------------ PairingManager

fun pairingTests() {
    println("\nPairingManager")
    var now = 1_000L
    var next = 418902
    val pm = PairingManager(clock = { now }, random = { next })

    val p = pm.issue(setOf("notify", "generate"))
    eq("code is 6 digits", p.code, "418902")
    eq("display is grouped", p.display, "418 902")
    eq("attempts start at max", p.attemptsLeft, PairingManager.MAX_ATTEMPTS)
    check("active() sees it", pm.active() != null)

    println("\nPairingManager — origin is required before the code is checked")
    val noOrigin = pm.redeem("418902", null)
    check("null origin refused", noOrigin is PairingManager.Result.NoOrigin)
    eq("and no attempt was spent", pm.active()?.attemptsLeft, PairingManager.MAX_ATTEMPTS)

    println("\nPairingManager — happy path")
    val ok = pm.redeem("418902", "https://notes.example.com")
    check("redeemed", ok is PairingManager.Result.Ok)
    eq("capabilities carried through", (ok as PairingManager.Result.Ok).capabilities, setOf("notify", "generate"))
    check("code consumed", pm.active() == null)
    check("replay fails", pm.redeem("418902", "https://notes.example.com") is PairingManager.Result.NoPendingCode)

    println("\nPairingManager — formatting tolerance")
    next = 7
    val padded = pm.issue(setOf("notify"))
    eq("low numbers zero-pad to 6", padded.code, "000007")
    check("spaces and dashes in user input are ignored",
        pm.redeem("000 007", "https://x.com") is PairingManager.Result.Ok)

    println("\nPairingManager — brute force is bounded")
    next = 123456
    pm.issue(setOf("notify"))
    var burned = false
    repeat(PairingManager.MAX_ATTEMPTS) {
        val r = pm.redeem("999999", "https://evil.example")
        if (r is PairingManager.Result.Burned) burned = true
    }
    check("code burns within MAX_ATTEMPTS wrong guesses", burned)
    check("and is gone", pm.active() == null)
    check("the real code no longer works either",
        pm.redeem("123456", "https://x.com") is PairingManager.Result.NoPendingCode)

    println("\nPairingManager — expiry")
    next = 555555
    pm.issue(setOf("notify"), ttlMs = 1_000)
    now += 1_001
    check("expired code is refused", pm.redeem("555555", "https://x.com") is PairingManager.Result.Expired)
    check("active() reports nothing after expiry", pm.active() == null)

    println("\nPairingManager — only one code at a time")
    next = 111111
    pm.issue(setOf("notify"))
    next = 222222
    pm.issue(setOf("notify"))
    check("the older code is dead", pm.redeem("111111", "https://x.com") is PairingManager.Result.WrongCode)
    check("the newer one works", pm.redeem("222222", "https://x.com") is PairingManager.Result.Ok)
}

fun main() {
    webOriginTests()
    corsTests()
    rateLimiterTests()
    pairingTests()

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("NetPolicyTest: all $checks checks passed")
    } else {
        println("NetPolicyTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
