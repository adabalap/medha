package com.adabala.medha.oai

/**
 * Turns an engine exception into an answer a client can act on.
 *
 * WHY THIS EXISTS
 *
 * LiteRT-LM reports its limits by throwing, and everything it throws is a
 * LiteRtLmJniException with the detail in the message string. Medha's
 * StatusPages handler catches Throwable and answers 500 `internal_error`,
 * which is the correct response to "something broke unexpectedly" and the
 * wrong one to "your prompt is 179 tokens over the limit".
 *
 * From a real device:
 *
 *     E/LocalServer: Unhandled server error on /generate --
 *     LiteRtLmJniException: Failed to call nativeSendMessage:
 *     INVALID_ARGUMENT: Input token ids are too long. Exceeding the
 *     maximum number of tokens allowed: 4275 >= 4096
 *
 * A client that gets a 500 retries, gets another 500, and reports that
 * Medha is down. A client that gets 400 context_length_exceeded trims the
 * conversation and carries on -- and 400 with that code is what the
 * OpenAI-shaped clients Medha serves already know how to handle.
 *
 * Deliberately returns null for anything it does not recognise. Guessing a
 * 4xx for an unknown engine fault would tell a client its request was bad
 * when the truth is that something broke, and it would stop the error being
 * reported as a fault worth fixing.
 *
 * Pure: no Android, no Ktor, no LiteRT types -- it reads a message string,
 * so the harness can run it.
 */
object EngineFailure {

    data class Mapped(val status: Int, val code: String, val message: String)

    /** "...allowed: 4275 >= 4096", in either order of spacing. */
    private val TOKENS = Regex("""(\d+)\s*>=\s*(\d+)""")

    private val TOO_LONG = listOf(
        "input token ids are too long",
        "exceeding the maximum number of tokens",
    )

    fun map(t: Throwable?): Mapped? {
        val text = chain(t) ?: return null
        val lower = text.lowercase()

        if (TOO_LONG.any { it in lower }) {
            val m = TOKENS.find(text)
            val got = m?.groupValues?.get(1)?.toIntOrNull()
            val cap = m?.groupValues?.get(2)?.toIntOrNull()
            val detail = if (got != null && cap != null) {
                "The prompt is $got tokens; this model accepts $cap. " +
                    "Send fewer messages, or shorter ones."
            } else {
                "The prompt is longer than this model accepts. " +
                    "Send fewer messages, or shorter ones."
            }
            return Mapped(400, "context_length_exceeded", detail)
        }

        return null
    }

    /**
     * Every message in the cause chain, joined.
     *
     * The useful sentence is often on a cause rather than the throwable that
     * arrives: a coroutine boundary wraps it, and matching only the top
     * message would miss it. Bounded, because a cause chain can be circular.
     */
    private fun chain(t: Throwable?): String? {
        if (t == null) return null
        val parts = mutableListOf<String>()
        var c: Throwable? = t
        var hops = 0
        while (c != null && hops++ < 16) {
            c.message?.let { parts.add(it) }
            c = c.cause
        }
        return if (parts.isEmpty()) null else parts.joinToString(" | ")
    }
}
