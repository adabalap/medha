package com.adabala.medha.oai

import java.lang.reflect.Method

/**
 * Asks the installed LiteRT-LM AAR, at runtime, whether it can take images
 * or audio — and reports exactly what it found.
 *
 * ## Why this is a probe and not a flag
 *
 * Medha binds LiteRT-LM by reflection already (see
 * [com.adabala.medha.LlmEngine]), because the Kotlin surface has changed
 * between builds. Multimodal makes that worse: whether
 * `litertlm-android:0.13.1` exposes an image or audio entry point at all,
 * and under what name, is not something that can be settled by reading
 * documentation or by guessing. The native library plainly supports it —
 * the same model runs multimodal under Termux — but the *Android* binding
 * is a separate question.
 *
 * So nothing here claims a capability. It looks for candidate entry points
 * on the real classes loaded on the real device, reports which were found,
 * and — importantly — dumps the full method list so the gap can be closed
 * from evidence rather than from another guess. One `/system` call on a
 * device with the model loaded answers what no amount of reasoning here can.
 *
 * Until a probe finds something, `/health` reports `vision: false` and
 * `audio: false`, and multimodal requests get a 501. That is the answer the
 * client is built to handle: its documented fallback is "4xx or 501 means
 * this build has no vision or audio".
 *
 * Pure reflection over `java.lang.Class`, no Android types, so the matching
 * logic is executable-testable. See tools/tests/MultimodalProbeTest.kt.
 */
object MultimodalProbe {

    /**
     * What a probe found. [imageMethods] and [audioMethods] are the actual
     * signatures, so a report can say *what* it found rather than only that
     * it found something.
     */
    data class Report(
        val vision: Boolean,
        val audio: Boolean,
        val imageMethods: List<String>,
        val audioMethods: List<String>,
        /** Every public method on the probed classes, for diagnostics. */
        val allMethods: List<String>,
        /** Null when the classes were found; a reason when they were not. */
        val error: String? = null
    ) {
        companion object {
            fun unavailable(reason: String) =
                Report(false, false, emptyList(), emptyList(), emptyList(), reason)
        }
    }

    /**
     * Method-name fragments that would indicate an image entry point.
     *
     * Deliberately broad. The cost of a false positive is one request
     * failing with a clear error; the cost of a false negative is the
     * feature looking unsupported on a build that supports it. Matching is
     * on the *name*, and a name alone is not proof the signature is usable
     * — which is why [Report.imageMethods] carries the full signature for a
     * human to read.
     */
    private val IMAGE_HINTS = listOf(
        "addimage", "sendimage", "withimage", "setimage",
        "addbitmap", "imageinput", "visioninput", "addvision"
    )

    private val AUDIO_HINTS = listOf(
        "addaudio", "sendaudio", "withaudio", "setaudio",
        "audioinput", "addpcm", "addwav"
    )

    /**
     * Probes [classes] for multimodal entry points.
     *
     * Takes the classes rather than looking them up so the matching can be
     * tested against stand-ins; [probeInstalled] does the lookup.
     */
    fun probe(classes: List<Class<*>>): Report {
        if (classes.isEmpty()) return Report.unavailable("no LiteRT-LM classes resolved")

        val all = mutableListOf<String>()
        val img = mutableListOf<String>()
        val aud = mutableListOf<String>()

        for (c in classes) {
            val methods: Array<Method> = runCatching { c.methods }.getOrNull() ?: continue
            for (m in methods) {
                val sig = signature(c, m)
                all += sig
                val lower = m.name.lowercase()
                if (IMAGE_HINTS.any { lower.contains(it) }) img += sig
                if (AUDIO_HINTS.any { lower.contains(it) }) aud += sig
            }
        }

        return Report(
            vision = img.isNotEmpty(),
            audio = aud.isNotEmpty(),
            imageMethods = img.sorted(),
            audioMethods = aud.sorted(),
            allMethods = all.sorted()
        )
    }

    /** Probes the AAR actually on the classpath. */
    fun probeInstalled(): Report {
        val names = listOf(
            "com.google.ai.edge.litertlm.Engine",
            "com.google.ai.edge.litertlm.Conversation",
            "com.google.ai.edge.litertlm.EngineConfig",
            "com.google.ai.edge.litertlm.InputData",
            "com.google.ai.edge.litertlm.Message"
        )
        val found = names.mapNotNull { n -> runCatching { Class.forName(n) }.getOrNull() }
        if (found.isEmpty()) {
            return Report.unavailable(
                "no com.google.ai.edge.litertlm classes on the classpath; " +
                    "is the AAR present in this build?"
            )
        }
        return probe(found)
    }

    private fun signature(c: Class<*>, m: Method): String =
        buildString {
            append(c.simpleName).append('.').append(m.name).append('(')
            append(m.parameterTypes.joinToString(", ") { it.simpleName })
            append("): ").append(m.returnType.simpleName)
        }

    /**
     * A short line for `/system`, readable in a bug report.
     *
     * Deliberately states the negative case as a fact about *this build*
     * rather than about Medha: the server code is ready for multimodal, and
     * whether it runs depends on the AAR and the model file.
     */
    fun summary(r: Report): String = when {
        r.error != null -> "unavailable: ${r.error}"
        r.vision && r.audio -> "vision and audio entry points found"
        r.vision -> "vision entry points found; no audio"
        r.audio -> "audio entry points found; no vision"
        else -> "no image or audio entry points in this build of the LiteRT-LM AAR"
    }
}
