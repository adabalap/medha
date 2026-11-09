package com.adabala.medha.oai

/**
 * Decides, in order, which engine configurations to try when loading a
 * model with media encoders.
 *
 * ## Why a ladder at all
 *
 * `Engine.initialize()` is the only place that can answer whether a given
 * `.litertlm` will run its vision and audio towers on a given accelerator.
 * Nothing readable from the file or the AAR says so in advance. The first
 * real device to run this reported:
 *
 *     LiteRtLmJniException: Model requires one of [cpu] but Audio backend is GPU
 *
 * on a model that supports audio perfectly well — just not on the GPU. A
 * single attempt would have fallen back to text-only and the obvious
 * conclusion would have been "these weights have no audio encoder", which
 * is wrong and would have sent a whole team back to shipping whisper.cpp.
 *
 * ## Why the modalities are split
 *
 * That error names **Audio** specifically. A ladder that only ever tries
 * both encoders on the same accelerator cannot express "vision on the GPU,
 * audio on the CPU", which is exactly what the message points at and is
 * strictly better than demoting both: image encoding is the heavier of the
 * two and the one that benefits most from the GPU.
 *
 * ## Why rungs are cached
 *
 * Each failed rung costs a full model load — about 8.6s on a Snapdragon
 * SM8850 — and the outcome is a property of (model, device), so it is the
 * same on every start. Paying to rediscover it each time is pure waste.
 * [order] takes the rung that worked last time and tries it first, keeping
 * the rest as a fallback in case the model or the hardware changed.
 *
 * Android-free: see tools/tests/MultimodalLadderTest.kt.
 */
object MultimodalLadder {

    /** Accelerators, named to match LlmEngine.ConfiguredBackend. */
    const val CPU = "CPU"

    /**
     * One configuration to attempt.
     *
     * [label] is stable and user-visible: it is what `/health` reports as
     * `multimodal_mode` and what gets persisted as the cache hint, so it
     * must not be reworded casually.
     */
    data class Rung(
        val label: String,
        val visionBackend: String?,
        val audioBackend: String?
    ) {
        val wantsMedia: Boolean get() = visionBackend != null || audioBackend != null
    }

    const val TEXT_ONLY = "text-only"

    /**
     * The label for "nothing was demoted" on [backend].
     *
     * ## Why every label leads with the configured backend
     *
     * The earlier scheme named only what moved — `media on CPU` — and the
     * first person to read it on a real device asked, reasonably, why the
     * whole model had dropped to CPU. It had not: the language model was on
     * the GPU throughout and only the audio encoder moved. But a mode line
     * that names CPU and nothing else cannot say so, and `/health` reporting
     * `"backend": "GPU"` three fields earlier was not enough to undo the
     * impression.
     *
     * So the label now always states the base first and the demotion
     * second: `all on GPU`, `GPU, audio on CPU`, `GPU, media on CPU`. The
     * leading token is the accelerator running the language model, which is
     * the number people care about, and anything after the comma is the
     * exception.
     */
    fun preferredLabel(backend: String): String = "all on $backend"

    /**
     * Builds the ladder for a load.
     *
     * @param backend the configured accelerator, e.g. "GPU".
     * @param wantVision the AAR can express and configure image input.
     * @param wantAudio likewise for audio.
     * @param hint the [Rung.label] that succeeded last time, if any.
     *
     * The returned list always ends with the text-only rung, so a load can
     * never fail outright because every media configuration was rejected.
     */
    fun order(
        backend: String,
        wantVision: Boolean,
        wantAudio: Boolean,
        hint: String? = null
    ): List<Rung> {
        val rungs = LinkedHashMap<String, Rung>()

        // Keyed by label, so two rungs meaning the same attempt collapse
        // into one. Worth being precise about what that is and is not:
        //
        // With the `backend != CPU` guards below, NO input reaches a
        // collision today — mutation testing confirmed it, by removing the
        // de-duplication entirely without failing a single test. So this is
        // not a guard that currently does anything, and an `if (label !in
        // rungs)` around it was deleted rather than left as reassurance the
        // next reader would have to re-derive.
        //
        // The map stays because it makes duplication structurally
        // impossible rather than conditionally avoided: adding a rung later
        // cannot silently double a cold start. The property is asserted for
        // every backend/modality combination in the test, which is what
        // would catch that.
        fun add(r: Rung) {
            rungs[r.label] = r
        }

        if (wantVision || wantAudio) {
            add(
                Rung(
                    preferredLabel(backend),
                    if (wantVision) backend else null,
                    if (wantAudio) backend else null
                )
            )
            // The split rung, and the reason it is second: it is only
            // meaningful when both modalities are wanted and the
            // configured backend is not already CPU.
            if (wantVision && wantAudio && backend != CPU) {
                add(Rung("$backend, audio on CPU", backend, CPU))
            }
            if (backend != CPU) {
                add(
                    Rung(
                        "$backend, media on CPU",
                        if (wantVision) CPU else null,
                        if (wantAudio) CPU else null
                    )
                )
            }
        }

        add(Rung(TEXT_ONLY, null, null))

        val ordered = rungs.values.toList()
        if (hint == null) return ordered

        // A hint only reorders; it never prunes. If the model file or the
        // device changed since the hint was stored, the remaining rungs
        // must still be reachable or the engine would be permanently stuck
        // in whatever mode it once fell back to.
        val first = ordered.firstOrNull { it.label == hint } ?: return ordered
        return listOf(first) + ordered.filter { it.label != hint }
    }

    /**
     * Whether a rung is worth remembering for next time.
     *
     * The text-only rung is not. Caching it would mean a model swapped for
     * one WITH encoders never gets its media rungs tried first — and worse,
     * since the ladder is ordered, text-only first means it succeeds
     * immediately and the media rungs are never tried at all.
     */
    fun worthCaching(label: String): Boolean = label != TEXT_ONLY
}
