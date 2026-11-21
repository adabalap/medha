// Executable tests for the post-hoc max_tokens truncation on the
// multimodal path.
//
//     kotlinc app/src/main/java/com/adabala/medha/oai/TokenCap.kt \
//             tools/tests/TokenCapTest.kt -include-runtime -d /tmp/tc.jar
//     java -jar /tmp/tc.jar
//
// Small surface, three ways to silently corrupt every capped response:
//
//  1. divide by a zero token estimate and empty the text
//  2. back up to a word boundary that is nowhere near the limit, throwing
//     away most of the answer to avoid one half-word
//  3. report truncated=false after cutting, so the client sees
//     finish_reason "stop" on a response that was cut

import com.adabala.medha.oai.TokenCap

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

/** The shape SystemInfo.estimateTokens has: roughly characters / 4. */
val chars4: (String) -> Int = { (it.length + 3) / 4 }

fun main() {
    val text = "the quick brown fox jumps over the lazy dog"   // 43 chars, ~11 tokens

    println("TokenCap — no cap means no change")
    for ((what, cap) in listOf("null" to null, "zero" to 0, "negative" to -5)) {
        val r = TokenCap.apply(text, cap, chars4)
        eq("$what leaves the text alone", r.text, text)
        check("$what reports not truncated", !r.truncated)
    }

    println("\nTokenCap — under the limit is untouched")
    val under = TokenCap.apply(text, 100, chars4)
    eq("text unchanged", under.text, text)
    check("not truncated", !under.truncated)

    val exact = TokenCap.apply(text, chars4(text), chars4)
    eq("exactly at the limit is unchanged", exact.text, text)
    check("and not flagged", !exact.truncated)

    println("\nTokenCap — over the limit cuts and says so")
    val over = TokenCap.apply(text, 4, chars4)
    check("is shorter", over.text.length < text.length)
    check("reports truncated", over.truncated)
    check("is a prefix of the original", text.startsWith(over.text))
    check("respects the budget", chars4(over.text) <= 5)

    println("\nTokenCap — cuts on a word boundary when one is near")
    // 16-char budget lands inside "brown"; the space at 15 is one back.
    val w = TokenCap.apply(text, 4, chars4)
    check("does not end mid-word", !w.text.endsWith("brow"))
    check("ends on a whole word", w.text.split(' ').last() in text.split(' '))

    println("\nTokenCap — but not when the only boundary is far away")
    // One very long word then a space right at the start. Backing up to
    // offset 1 would throw away the entire answer to avoid a half-word.
    val lopsided = "a " + "x".repeat(200)
    val lop = TokenCap.apply(lopsided, 10, chars4)
    check("keeps most of the budget", lop.text.length > TokenCap.WORD_LOOKBACK)
    check("rather than collapsing to the early boundary", lop.text.length > 2)
    check("truncated", lop.truncated)

    println("\nTokenCap — boundary of the lookback window")
    // A space exactly WORD_LOOKBACK back is still used; one further is not.
    val atEdge = "y".repeat(100) + " " + "z".repeat(TokenCap.WORD_LOOKBACK - 1)
    val edge = TokenCap.apply(atEdge + "tail", 30, chars4)
    check("produces something", edge.text.isNotEmpty())
    check("truncated", edge.truncated)

    println("\nTokenCap — a degenerate estimator cannot empty the text")
    // An estimate of zero or less is handled by the under-the-limit return,
    // not by a guard of its own: maxTokens is >= 1 here, so 0 <= 5 exits
    // first. Asserted so that stays true if the ordering is ever changed.
    val zeroEst = TokenCap.apply(text, 5) { 0 }
    eq("text survives a zero estimate", zeroEst.text, text)
    check("not truncated", !zeroEst.truncated)

    val negEst = TokenCap.apply(text, 5) { -3 }
    eq("a negative estimate also survives", negEst.text, text)
    check("not truncated", !negEst.truncated)

    println("\nTokenCap — an estimator reporting MORE tokens than characters")
    // Not hypothetical: Telugu runs ~2.2x the tokens of the same text, so a
    // script-aware estimator returns more tokens than characters. The
    // character budget then floors to zero and, without the lower coerce,
    // every capped response comes back as an empty string rather than a
    // short one. This is the test that makes that bound load-bearing.
    val dense = TokenCap.apply(text, 1) { it.length * 10 }
    check("returns at least one character", dense.text.isNotEmpty())
    check("is a prefix of the original", text.startsWith(dense.text))
    check("reports truncated", dense.truncated)

    val denseTelugu = TokenCap.apply("నమస్కారం ఎలా ఉన్నారు", 1) { it.length * 3 }
    check("Telugu likewise survives", denseTelugu.text.isNotEmpty())
    check("and is flagged", denseTelugu.truncated)

    println("\nTokenCap — empty and tiny inputs")
    val empty = TokenCap.apply("", 5, chars4)
    eq("empty stays empty", empty.text, "")
    check("not truncated", !empty.truncated)

    val oneChar = TokenCap.apply("x", 1, chars4)
    eq("a single character survives", oneChar.text, "x")
    check("not truncated", !oneChar.truncated)

    // A budget of 1 token on a long single word still returns something
    // rather than an empty string.
    val tiny = TokenCap.apply("supercalifragilistic", 1, chars4)
    check("a 1-token budget returns text", tiny.text.isNotEmpty())
    check("truncated", tiny.truncated)

    println("\nTokenCap — trailing whitespace is not left behind")
    val trailing = TokenCap.apply("hello     world and more words here", 2, chars4)
    eq("no trailing space", trailing.text, trailing.text.trimEnd())

    println("\nTokenCap — Telugu survives the cut")
    // 2.2x the tokens for the same text, so this is the path that actually
    // hits the cap in practice. The cut must not leave a broken cluster at
    // the end, and must still be a prefix of the original.
    val te = "నమస్కారం ఎలా ఉన్నారు ఈ రోజు వాతావరణం చాలా బాగుంది"
    val teCut = TokenCap.apply(te, 4, chars4)
    check("is a prefix", te.startsWith(teCut.text))
    check("truncated", teCut.truncated)
    check("not empty", teCut.text.isNotEmpty())
    check("ends on a space-delimited word", teCut.text.none { it == ' ' } ||
        te.split(' ').contains(teCut.text.split(' ').last()))

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("TokenCapTest: all $checks checks passed")
    } else {
        println("TokenCapTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
