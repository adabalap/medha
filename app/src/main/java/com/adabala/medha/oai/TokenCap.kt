package com.adabala.medha.oai

/**
 * Applies `max_tokens` to text that has already been generated.
 *
 * ## Why this is a truncation and not a cap
 *
 * The text path uses [com.adabala.medha.LlmEngine.generateCapped], which
 * stops the model mid-stream, so tokens past the limit are never produced
 * and the phone never spends the compute. That needs a streaming bridge,
 * and the multimodal path's streaming surface is a native callback API
 * whose Flow bridge is not yet written. Until it is, a multimodal response
 * is generated in full and cut here.
 *
 * The cut is therefore a real loss of work, which is why it is confined to
 * one documented place rather than inlined at a call site where it would
 * read as free.
 *
 * ## Why the word boundary needs care
 *
 * Cutting at an arbitrary character offset ends a response mid-word, which
 * in Telugu can split a grapheme cluster and render as a broken glyph
 * rather than a shortened word. Backing up to the previous space fixes
 * that, but only when the space is *near* the limit: backing up from a
 * 2000-character budget to a space at offset 3 would throw away the whole
 * answer to avoid a half-word. The lookback window bounds that.
 *
 * Pure, no Android, no engine: see tools/tests/TokenCapTest.kt.
 */
object TokenCap {

    /**
     * How far back to look for a word boundary. Past this, the half-word
     * is the lesser evil.
     */
    const val WORD_LOOKBACK = 20

    data class Result(
        val text: String,
        /** True when [text] is shorter than what was generated. */
        val truncated: Boolean
    )

    /**
     * Cuts [text] to approximately [maxTokens], using [estimateTokens] to
     * convert between the two.
     *
     * @param estimateTokens the same estimator the rest of Medha uses, so
     *   the budget here and the count reported to the client agree. Injected
     *   rather than imported because SystemInfo needs an Android context.
     */
    fun apply(text: String, maxTokens: Int?, estimateTokens: (String) -> Int): Result {
        if (maxTokens == null || maxTokens <= 0) return Result(text, false)
        if (text.isEmpty()) return Result(text, false)

        val actual = estimateTokens(text)
        if (actual <= maxTokens) return Result(text, false)

        // There was an `if (actual <= 0) return` guard here against a
        // degenerate estimator dividing by zero below. Mutation testing
        // removed it and every test still passed, because it is
        // unreachable: maxTokens is >= 1 by this point, so an estimate of
        // zero or less already satisfies `actual <= maxTokens` and returns
        // on the line above. Deleted rather than kept as reassurance —
        // unreachable defensive code is a claim the next reader has to
        // re-derive. `actual` is strictly positive from here.

        // coerceIn's lower bound, by contrast, IS reachable and load
        // bearing. An estimator that returns MORE tokens than characters is
        // not hypothetical: Telugu runs about 2.2x the tokens of the same
        // text, so a script-aware estimator would do exactly that, and the
        // ratio then floors to zero characters — emptying every capped
        // response instead of shortening it.
        val budget = (text.length.toDouble() * maxTokens / actual).toInt().coerceIn(1, text.length)
        val cut = text.substring(0, budget)

        // Already on a boundary, or the next character is one: nothing to
        // back up over.
        if (budget >= text.length || text[budget].isWhitespace()) {
            return Result(cut.trimEnd(), budget < text.length)
        }

        val lastSpace = cut.lastIndexOf(' ')
        val out = if (lastSpace > 0 && budget - lastSpace <= WORD_LOOKBACK) {
            cut.substring(0, lastSpace)
        } else {
            cut
        }
        return Result(out.trimEnd(), true)
    }
}
