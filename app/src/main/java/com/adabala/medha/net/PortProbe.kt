package com.adabala.medha.net

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Is this port bindable, asked BEFORE Ktor tries.
 *
 * WHY THIS EXISTS
 *
 * Ktor's CIO engine binds on its own coroutine, inside the accept job, after
 * `start(wait = false)` has already returned. So this:
 *
 *     runCatching { it.start() }.onFailure { e -> onServerFailed(e, port) }
 *
 * never sees a BindException. It catches the JobCancellationException that
 * follows, logs "Server failed to bind", and reports the problem correctly --
 * while the original BindException travels up a different thread to the
 * default uncaught handler and kills the process. Both happened, in this
 * order, on a real device:
 *
 *     E/InferenceService: Server failed to bind on port 8080 -- ...
 *     Thread: DefaultDispatcher-worker-3
 *     Fatal: java.net.BindException: Address already in use
 *
 * Something else on the phone was listening on 8080, which is an ordinary
 * thing for a port that common, and Medha died rather than saying so.
 *
 * Binding a plain socket first turns that into a synchronous failure the
 * caller's own try/catch can see. It is not a guarantee -- another process
 * can take the port between the probe and the real bind -- so the handler in
 * MedhaApplication covers that remainder. This closes the case that actually
 * happens.
 *
 * ABOUT reuseAddress
 *
 * Deliberately TRUE, which is the looser test. Ktor's CIO sets SO_REUSEADDR
 * on its listening socket, so a socket left in TIME_WAIT by Medha's own
 * previous run does not stop it binding. A strict probe would call that port
 * taken and send someone hunting for a conflict that does not exist. The
 * probe has to answer the question Ktor will ask, not a stricter one.
 */
object PortProbe {

    /** The only interface Medha ever binds. */
    const val LOOPBACK = "127.0.0.1"

    /**
     * Ports to offer when the configured one is taken.
     *
     * DEFAULT_PORT was 8080 until v0.19.2, which was both the most
     * contested number on any device -- dev servers, proxies and a long
     * tail of apps all reach for it -- and a port none of this repo's own
     * samples used. It is now 8001, matching them.
     *
     * 8001 stays on this list even though it is the default, because
     * [firstFree] excludes whichever port is actually taken: someone who
     * moved to 9000 and hit a collision there should be offered the
     * default back. It is no longer FIRST, since suggesting the default to
     * someone already on it would be a non-answer.
     *
     * 8080 is deliberately absent. It is the one number most likely to
     * collide again, and sending someone from one contested port to
     * another is a suggestion that wastes a round trip.
     */
    val SUGGESTIONS = listOf(8081, 8099, 8001, 8765, 9099, 11434)

    /** True if a server socket can be bound to [host]:[port] right now. */
    fun isFree(host: String, port: Int): Boolean =
        try {
            ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName(host), port), 1)
            }
            true
        } catch (_: BindException) {
            false
        } catch (_: java.io.IOException) {
            // Permission denied, address not available, and the rest. Not
            // free for our purposes, and not worth telling them apart: the
            // real bind is about to fail the same way.
            false
        }

    /**
     * The first of [candidates] that is bindable, skipping [exclude].
     *
     * Returns null rather than a fallback, because a wrong suggestion is
     * worse than none: someone will type it in and hit the same wall.
     */
    fun firstFree(host: String, candidates: List<Int> = SUGGESTIONS, exclude: Int = -1): Int? =
        candidates.firstOrNull { it != exclude && it in 1024..65535 && isFree(host, it) }

    /**
     * A sentence for a person, naming a port that works when one can be found.
     *
     * This is the whole point of the probe: "Address already in use" on a
     * thread name is not something anyone can act on.
     */
    fun explain(host: String, port: Int): String {
        val free = firstFree(host, exclude = port)
        return if (free != null) {
            "Port $port is already in use by another app. Try $free instead."
        } else {
            "Port $port is already in use by another app. Pick a different one."
        }
    }
}
