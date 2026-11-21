// Turning an engine limit into an answer a client can act on.
//
// The real one, from a device log:
//
//     LiteRtLmJniException: Failed to call nativeSendMessage:
//     INVALID_ARGUMENT: Input token ids are too long. Exceeding the
//     maximum number of tokens allowed: 4275 >= 4096
//
// That reached the generic Throwable handler and came back as 500
// internal_error. A client cannot do anything with a 500 except retry,
// which fails identically, so Medha looked down when the request was simply
// too long.
//
// The half of this that matters as much is what it must NOT map: an unknown
// engine fault has to stay a 500. Answering 4xx for it would tell a client
// its request was bad when something had actually broken, and the fault
// would stop being reported as one.
import com.adabala.medha.oai.EngineFailure

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

class Jni(msg: String) : RuntimeException(msg)

fun main() {
    println("EngineFailureTest")

    println("\nThe failure that shipped")
    val real = Jni(
        "Failed to call nativeSendMessage: INVALID_ARGUMENT: Input token ids " +
            "are too long. Exceeding the maximum number of tokens allowed: 4275 >= 4096"
    )
    val m = EngineFailure.map(real)
    check("is recognised", m != null)
    eq("as a client error, not a server one", m?.status, 400)
    eq("with the code OpenAI clients already handle", m?.code, "context_length_exceeded")
    check("naming the actual size", m?.message?.contains("4275") == true)
    check("and the actual limit", m?.message?.contains("4096") == true)
    check("and saying what to do about it",
        m?.message?.contains("fewer messages") == true)
    check("without leaking the native frame",
        m?.message?.contains("nativeSendMessage") != true)

    println("\nFound through a cause chain")
    // A coroutine boundary wraps it; matching only the top message misses it.
    val wrapped = RuntimeException("job failed", Jni("Input token ids are too long: 9000 >= 4096"))
    eq("a wrapped limit is still a 400", EngineFailure.map(wrapped)?.status, 400)
    val deep = RuntimeException("a", RuntimeException("b", Jni("Exceeding the maximum number of tokens allowed: 7 >= 4")))
    eq("three levels down too", EngineFailure.map(deep)?.code, "context_length_exceeded")

    println("\nWhen the numbers are missing")
    val vague = Jni("Input token ids are too long.")
    val vm = EngineFailure.map(vague)
    eq("still a 400", vm?.status, 400)
    check("says what to do without inventing a count",
        vm?.message?.contains("longer than this model accepts") == true &&
            !Regex("""\d""").containsMatchIn(vm.message))

    println("\nWhat must stay a 500")
    check("an unknown engine fault is not mapped",
        EngineFailure.map(Jni("INTERNAL: tensor arena allocation failed")) == null)
    check("a backend constraint is not mapped here",
        EngineFailure.map(Jni("Audio backend constraint mismatch. Model requires one of [cpu]")) == null)
    check("a null throwable is not mapped", EngineFailure.map(null) == null)
    check("a throwable with no message is not mapped",
        EngineFailure.map(RuntimeException()) == null)
    check("an empty-ish chain is not mapped",
        EngineFailure.map(RuntimeException(null as String?)) == null)

    println("\nA circular cause chain must not hang")
    // Bounded traversal: this used to be the kind of input that turns an
    // error handler into a spin.
    val a = RuntimeException("outer")
    val b = RuntimeException("inner", a)
    runCatching { a.initCause(b) }   // throws if already set; either way, try it
    val done = runCatching { EngineFailure.map(b) }.isSuccess
    check("terminates", done)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("EngineFailureTest: all $checks checks passed")
    else { println("EngineFailureTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
