package com.adabala.medha.oai

/**
 * Asks the installed LiteRT-LM AAR, at runtime, whether it can take images
 * or audio — and reports exactly what it found.
 *
 * ## What was wrong with v1, and why it matters
 *
 * The first version of this probe reported `vision: false, audio: false` on
 * a build whose AAR fully supports both. It was not a lookup failure: all
 * five classes it asked for resolved. It failed because of how it looked.
 *
 * v1 matched on **method names**, against a hand-written hint list
 * (`addAudio`, `sendAudio`, `addPcm`, `withImage`, …). That encoded an
 * assumption about the *shape* of the API — that a capability would be
 * announced by a method named after it. LiteRT-LM does not work that way.
 * In litertlm-android 0.13.1 the capability is carried by:
 *
 *  - **types**: `InputData.Audio(ByteArray)`, `InputData.Image(ByteArray)`,
 *    `Content.AudioBytes`, `Content.AudioFile`, `Content.ImageBytes`,
 *    `Content.ImageFile`
 *  - **constructor parameters**: `EngineConfig(…, visionBackend: Backend?,
 *    audioBackend: Backend?, maxNumImages: Int?, …)`
 *
 * The entry points themselves are named for what they do, not what they
 * take: `Session.generateContent(List<InputData>)` and
 * `Conversation.sendMessage(Contents)`. Neither contains the substring
 * "audio" or "image", so v1 was structurally incapable of finding a working
 * multimodal binding no matter which classes it was handed.
 *
 * The lesson, recorded here because it is easy to repeat: a probe written to
 * avoid guessing can still bake a guess into its own design. v1's guess was
 * "capabilities are named in methods". This version looks for the things the
 * SDK actually uses to express a capability — class names and parameter
 * accessors — and dumps both the class list and the full method list so the
 * next shape change is visible as evidence rather than as a silent false.
 *
 * ## Scope
 *
 * This reports what the **AAR** can accept. It says nothing about whether
 * the loaded `.litertlm` weights carry an audio or vision encoder — a model
 * without one will fail at `Engine.initialize()` with the backends set, and
 * that is [com.adabala.medha.LlmEngine]'s business to detect and report.
 * `/health` combines the two: it advertises a capability only when the AAR
 * supports it *and* the engine actually came up with it enabled.
 *
 * Pure reflection over `java.lang.Class`, no Android types, so every branch
 * is executable off device. See tools/tests/MultimodalProbeTest.kt.
 */
object MultimodalProbe {

    private const val PKG = "com.google.ai.edge.litertlm"

    // Nested Kotlin classes are `Outer$Inner` on the JVM. InputData itself is
    // top level (declared beside Session in Session.kt, not inside it), which
    // is precisely why v1's class lookups all succeeded while its answer was
    // still wrong.
    const val INPUT_DATA = "$PKG.InputData"
    const val INPUT_IMAGE = "$PKG.InputData\$Image"
    const val INPUT_AUDIO = "$PKG.InputData\$Audio"
    const val INPUT_TEXT = "$PKG.InputData\$Text"
    const val CONTENT_IMAGE_BYTES = "$PKG.Content\$ImageBytes"
    const val CONTENT_IMAGE_FILE = "$PKG.Content\$ImageFile"
    const val CONTENT_AUDIO_BYTES = "$PKG.Content\$AudioBytes"
    const val CONTENT_AUDIO_FILE = "$PKG.Content\$AudioFile"
    const val ENGINE_CONFIG = "$PKG.EngineConfig"
    const val SESSION = "$PKG.Session"
    const val CONVERSATION = "$PKG.Conversation"
    const val ENGINE = "$PKG.Engine"
    const val MESSAGE = "$PKG.Message"
    const val CONTENTS = "$PKG.Contents"

    /** Every class the probe asks about, in report order. */
    val PROBED_CLASSES = listOf(
        ENGINE, ENGINE_CONFIG, SESSION, CONVERSATION, MESSAGE, CONTENTS,
        INPUT_DATA, INPUT_TEXT, INPUT_IMAGE, INPUT_AUDIO,
        CONTENT_IMAGE_BYTES, CONTENT_IMAGE_FILE,
        CONTENT_AUDIO_BYTES, CONTENT_AUDIO_FILE
    )

    /** Classes whose presence proves the AAR accepts image input. */
    val IMAGE_TYPES = listOf(INPUT_IMAGE, CONTENT_IMAGE_BYTES, CONTENT_IMAGE_FILE)

    /** Classes whose presence proves the AAR accepts audio input. */
    val AUDIO_TYPES = listOf(INPUT_AUDIO, CONTENT_AUDIO_BYTES, CONTENT_AUDIO_FILE)

    /**
     * Kotlin `val`s on EngineConfig compile to these JVM getters. Their
     * presence proves the engine can be *configured* for the modality, which
     * is a different and equally necessary fact from being able to express
     * the input: Medha can build an `InputData.Audio` all day and the native
     * engine will still refuse it unless `audioBackend` was set at init.
     */
    const val GET_VISION_BACKEND = "getVisionBackend"
    const val GET_AUDIO_BACKEND = "getAudioBackend"
    const val GET_MAX_NUM_IMAGES = "getMaxNumImages"

    data class Report(
        /** The AAR can express and configure image input. */
        val vision: Boolean,
        /** The AAR can express and configure audio input. */
        val audio: Boolean,
        /** Why [vision] is what it is — the classes and accessors found. */
        val visionEvidence: List<String>,
        /** Why [audio] is what it is. */
        val audioEvidence: List<String>,
        /** Probed class names that resolved. */
        val classesFound: List<String>,
        /** Probed class names that did not resolve. */
        val classesMissing: List<String>,
        /** True when a stateless `Session.generateContent(List)` exists. */
        val hasSessionPath: Boolean,
        /** True when `Conversation.sendMessage(Contents)` exists. */
        val hasConversationPath: Boolean,
        /** Every public method on the resolved classes, for diagnostics. */
        val allMethods: List<String>,
        /** Null when at least one class resolved; a reason when none did. */
        val error: String? = null
    ) {
        companion object {
            fun unavailable(reason: String) = Report(
                vision = false, audio = false,
                visionEvidence = emptyList(), audioEvidence = emptyList(),
                classesFound = emptyList(), classesMissing = PROBED_CLASSES,
                hasSessionPath = false, hasConversationPath = false,
                allMethods = emptyList(), error = reason
            )
        }
    }

    /**
     * Probes using [resolve] to turn a binary class name into a Class, or
     * null when it is absent.
     *
     * Taking the resolver rather than calling `Class.forName` directly is the
     * testable seam: tests hand in stand-ins shaped like the real 0.13.1
     * classes and assert on the verdict, including the negative cases that
     * v1 got wrong.
     */
    fun probe(resolve: (String) -> Class<*>?): Report {
        val resolved = LinkedHashMap<String, Class<*>>()
        val missing = mutableListOf<String>()
        for (name in PROBED_CLASSES) {
            val c = runCatching { resolve(name) }.getOrNull()
            if (c != null) resolved[name] = c else missing += name
        }
        if (resolved.isEmpty()) {
            return Report.unavailable(
                "no $PKG classes on the classpath; is the AAR present in this build?"
            )
        }

        val methods = mutableListOf<String>()
        for ((name, c) in resolved) {
            val ms = runCatching { c.methods }.getOrNull() ?: continue
            for (m in ms) {
                methods += buildString {
                    append(name.substringAfterLast('.')).append('.').append(m.name).append('(')
                    append(m.parameterTypes.joinToString(", ") { it.simpleName })
                    append("): ").append(m.returnType.simpleName)
                }
            }
        }

        val cfg = resolved[ENGINE_CONFIG]
        val canConfigureVision = cfg != null && hasNoArgMethod(cfg, GET_VISION_BACKEND)
        val canConfigureAudio = cfg != null && hasNoArgMethod(cfg, GET_AUDIO_BACKEND)

        val visionEvidence = buildList {
            IMAGE_TYPES.filter { it in resolved }.forEach { add("type $it") }
            if (canConfigureVision) add("EngineConfig.$GET_VISION_BACKEND()")
            if (cfg != null && hasNoArgMethod(cfg, GET_MAX_NUM_IMAGES)) {
                add("EngineConfig.$GET_MAX_NUM_IMAGES()")
            }
        }
        val audioEvidence = buildList {
            AUDIO_TYPES.filter { it in resolved }.forEach { add("type $it") }
            if (canConfigureAudio) add("EngineConfig.$GET_AUDIO_BACKEND()")
        }

        // Both halves are required. An input type with no way to switch the
        // backend on is not a usable capability, and a config field with no
        // way to express the input is not either. Reporting true on half of
        // it would reproduce v1's failure in the opposite direction: an
        // advertised capability that fails at the first real request.
        val canExpressImage = IMAGE_TYPES.any { it in resolved }
        val canExpressAudio = AUDIO_TYPES.any { it in resolved }

        return Report(
            vision = canExpressImage && canConfigureVision,
            audio = canExpressAudio && canConfigureAudio,
            visionEvidence = visionEvidence,
            audioEvidence = audioEvidence,
            classesFound = resolved.keys.toList(),
            classesMissing = missing,
            hasSessionPath = resolved[SESSION]?.let { hasMethodNamed(it, "generateContent") } == true,
            hasConversationPath =
                resolved[CONVERSATION]?.let { hasMethodNamed(it, "sendMessage") } == true,
            allMethods = methods.sorted()
        )
    }

    /** Probes the AAR actually on the classpath. */
    fun probeInstalled(): Report = probe { name ->
        runCatching { Class.forName(name) }.getOrNull()
    }

    private fun hasNoArgMethod(c: Class<*>, name: String): Boolean =
        runCatching { c.getMethod(name) }.isSuccess

    private fun hasMethodNamed(c: Class<*>, name: String): Boolean =
        runCatching { c.methods.any { it.name == name } }.getOrDefault(false)

    /**
     * A short line for `/system` and the drawer, readable in a bug report.
     *
     * States the negative as a fact about *this build* rather than about
     * Medha: the server code is ready, and whether it runs depends on the
     * AAR and on the model file.
     */
    fun summary(r: Report): String = when {
        r.error != null -> "unavailable: ${r.error}"
        r.vision && r.audio -> "AAR accepts images and audio"
        r.vision -> "AAR accepts images; no audio binding"
        r.audio -> "AAR accepts audio; no image binding"
        else -> "no image or audio binding in this build of the LiteRT-LM AAR"
    }
}
