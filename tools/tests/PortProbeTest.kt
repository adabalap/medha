// Whether a port is bindable, asked before Ktor asks.
//
// This runs against real sockets, not a mock, because the bug it guards is
// a disagreement between two socket configurations. Ktor's CIO sets
// SO_REUSEADDR; a probe that does not would call a port taken whenever
// Medha's own previous run left one in TIME_WAIT, and send someone hunting
// for a conflict that is not there. Only a real bind can tell you that.
//
// The trace this came from, on a real device:
//
//     E/InferenceService: Server failed to bind on port 8080 -- ...
//     Thread: DefaultDispatcher-worker-3
//     Fatal: java.net.BindException: Address already in use
//
// Two reports of one failure: the service caught the cancellation and said
// the right thing, while the BindException itself went up another thread to
// the default handler and killed the app.
import com.adabala.medha.net.PortProbe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

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

const val HOST = "127.0.0.1"

/** An ephemeral port the OS just handed out and we immediately gave back. */
fun borrowFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName(HOST)).use { it.localPort }

/** A socket held open for the duration of [body], and the port it holds. */
fun <T> holding(body: (Int) -> T): T =
    ServerSocket().use { s ->
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(HOST), 0), 1)
        body(s.localPort)
    }

fun main() {
    println("PortProbeTest")

    println("\nA port nobody holds")
    val free = borrowFreePort()
    check("probes as free", PortProbe.isFree(HOST, free))
    check("and still does on a second ask", PortProbe.isFree(HOST, free))

    println("\nA port something is listening on")
    holding { taken ->
        check("probes as taken", !PortProbe.isFree(HOST, taken))
        check("and stays taken while held", !PortProbe.isFree(HOST, taken))
        check("a different port is unaffected", PortProbe.isFree(HOST, borrowFreePort()))
    }

    println("\nThe probe must not take the port it is testing")
    // The whole design rests on this. If probing left the socket bound, or
    // left a TIME_WAIT that a REUSEADDR bind could not get past, every start
    // would fail the moment the probe said it would succeed.
    val p = borrowFreePort()
    check("probe says free", PortProbe.isFree(HOST, p))
    val bound = runCatching {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName(HOST), p), 1)
            true
        }
    }.getOrDefault(false)
    check("and a real bind then succeeds", bound)
    check("and the port is free again afterwards", PortProbe.isFree(HOST, p))

    println("\nSuggesting a port that works")
    val taken = holding { held ->
        val s = PortProbe.firstFree(HOST, listOf(held, borrowFreePort()))
        check("skips the one in use", s != null && s != held)
        held
    }
    eq("excludes the port asked about", PortProbe.firstFree(HOST, listOf(9191), exclude = 9191), null)
    eq("rejects a port below 1024", PortProbe.firstFree(HOST, listOf(80)), null)
    eq("rejects a port above 65535", PortProbe.firstFree(HOST, listOf(70000)), null)
    eq("returns null rather than guessing", PortProbe.firstFree(HOST, emptyList()), null)
    check("the shipped suggestions are all in range",
        PortProbe.SUGGESTIONS.all { it in 1024..65535 })
    check("and none of them is 8080, the default that collides",
        8080 !in PortProbe.SUGGESTIONS)

    println("\nWhat it tells the person")
    holding { held ->
        val msg = PortProbe.explain(HOST, held)
        check("names the port that is in use", msg.contains(held.toString()))
        check("says it is another app, not a Medha fault",
            msg.contains("another app"))
        check("is a sentence, not a stack frame",
            !msg.contains("Exception") && msg.endsWith("."))
    }
    val none = PortProbe.firstFree(HOST, emptyList())
    eq("invents nothing when there is nothing to suggest", none, null)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("PortProbeTest: all $checks checks passed")
    else { println("PortProbeTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
