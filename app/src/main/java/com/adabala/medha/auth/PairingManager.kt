package com.adabala.medha.auth

import java.security.SecureRandom

/**
 * Short-lived pairing codes, so a web app can be granted a token.
 *
 * ## Why web apps need this at all
 *
 * Medha already has a consent handshake: a third-party **Android app** fires
 * [MedhaAccessContract.ACTION_REQUEST_ACCESS], Medha reads
 * `getCallingPackage()`, shows the user who is asking, and hands back a token.
 * The entire security of that flow rests on `getCallingPackage()`, which the
 * system fills in and the caller cannot forge.
 *
 * A PWA has no package. It is a page in a browser, and if it fired that intent
 * at all the caller would be Chrome. There is nothing to identify and nothing
 * to trust, so the intent flow cannot be stretched to cover it — a web app
 * needs a mechanism where the *user*, not the platform, vouches for who is
 * asking.
 *
 * ## The flow
 *
 * ```
 *   user, in Medha          web app                      Medha server
 *   --------------          -------                      ------------
 *   taps "Pair a web app"
 *     -> issue()
 *     sees  418 902
 *                           user types 418902
 *                           POST /pair {code, name}  ->  redeem(code, origin)
 *                           <- {token, clientId}         creates client bound
 *                                                        to the Origin header
 * ```
 *
 * ## Why a 6-digit code is enough
 *
 * Six digits is a million possibilities, which on its own is not much. What
 * makes it sound is that all three of these hold at once:
 *
 *  - a code exists only while the user is looking at Medha's screen, for
 *    [DEFAULT_TTL_MS];
 *  - [MAX_ATTEMPTS] wrong guesses destroy it, so an attacker gets five tries
 *    at 1-in-a-million, not a million tries; and
 *  - only one code is ever live, so guesses cannot be spread across many.
 *
 * The code is deliberately short because a human retypes it into another
 * device. A 32-character token would be more entropy nobody can transcribe,
 * and the brute-force ceiling above is what actually bounds the risk.
 *
 * Codes are held in memory only. A pairing code surviving a process restart
 * would be a code outliving the user's attention, which is the one property
 * this design depends on not having.
 */
class PairingManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Int = { SecureRandom().nextInt(1_000_000) }
) {

    /** An outstanding code, as shown to the user. */
    data class Pending(
        val code: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val capabilities: Set<String>,
        val attemptsLeft: Int
    ) {
        /** `418 902` — grouped, because people transcribe groups accurately. */
        val display: String get() = "${code.take(3)} ${code.drop(3)}"
    }

    sealed class Result {
        /** Code matched. [capabilities] is what the user authorised. */
        data class Ok(val capabilities: Set<String>) : Result()

        /** No code has been issued, or the last one was already used. */
        data object NoPendingCode : Result()

        /** A code is live but the digits do not match. */
        data class WrongCode(val attemptsLeft: Int) : Result()

        /** The code existed and has aged out. */
        data object Expired : Result()

        /** Too many wrong guesses; the code has been destroyed. */
        data object Burned : Result()

        /**
         * The caller sent no usable `Origin` header. A web app must have one,
         * and `Origin: null` — a sandboxed frame or a file:// page — is not
         * usable: there would be no origin to bind the token to, so the token
         * would be usable from any sandboxed frame anywhere.
         */
        data object NoOrigin : Result()
    }

    private val lock = Any()
    private var pending: Pending? = null

    /**
     * Issues a code, replacing any outstanding one.
     *
     * Replacing rather than refusing: the user tapping the button again
     * plainly means "give me a code", and the most likely reason is that they
     * lost track of the first one. Keeping both live would weaken the
     * one-code-at-a-time property that bounds brute force.
     */
    fun issue(
        capabilities: Set<String>,
        ttlMs: Long = DEFAULT_TTL_MS
    ): Pending = synchronized(lock) {
        val now = clock()
        val digits = (random() % 1_000_000).toString().padStart(6, '0')
        val p = Pending(
            code = digits,
            issuedAt = now,
            expiresAt = now + ttlMs,
            capabilities = capabilities.toSet(),
            attemptsLeft = MAX_ATTEMPTS
        )
        pending = p
        return p
    }

    /** The live code, or null. Expiry is evaluated on read. */
    fun active(): Pending? = synchronized(lock) {
        val p = pending ?: return null
        if (clock() >= p.expiresAt) {
            pending = null
            return null
        }
        return p
    }

    fun cancel() = synchronized(lock) { pending = null }

    /**
     * Attempts to redeem [code] for the caller at [origin].
     *
     * On success the code is consumed, so a replayed request fails even
     * within the TTL.
     */
    fun redeem(code: String, origin: String?): Result = synchronized(lock) {
        val p = pending ?: return Result.NoPendingCode
        val now = clock()

        if (now >= p.expiresAt) {
            pending = null
            return Result.Expired
        }

        // Origin is checked before the code, so a caller that could never be
        // bound to an origin does not get to spend an attempt. Otherwise a
        // sandboxed frame could burn through the user's code by guessing.
        if (origin == null) return Result.NoOrigin

        // Constant-time-ish: both are fixed-length digit strings of our own
        // making, so a plain comparison leaks at most the length, which is a
        // published constant. Not worth a MessageDigest dance.
        if (code.trim().filter { it.isDigit() } != p.code) {
            val left = p.attemptsLeft - 1
            return if (left <= 0) {
                pending = null
                Result.Burned
            } else {
                pending = p.copy(attemptsLeft = left)
                Result.WrongCode(left)
            }
        }

        pending = null
        return Result.Ok(p.capabilities)
    }

    companion object {
        /**
         * Five minutes. Long enough to walk to another device and type six
         * digits; short enough that a code forgotten on screen is not a
         * standing grant.
         */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        const val MAX_ATTEMPTS = 5

        /**
         * The single instance MainActivity and LocalServer both use.
         *
         * A process-wide singleton rather than something threaded through,
         * because the two ends of the flow live in different components —
         * the user generates a code in the Activity, a web app redeems it in
         * the server running inside InferenceService — and both are in the
         * same process. Two instances would mean every pairing attempt
         * failing with "no pairing code is active" while a code is plainly
         * on screen, which is the kind of bug that eats an evening.
         *
         * Deliberately *not* persisted, and deliberately lost on process
         * death: see the class doc.
         */
        @Volatile private var INSTANCE: PairingManager? = null

        fun shared(): PairingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PairingManager().also { INSTANCE = it }
            }
    }
}
