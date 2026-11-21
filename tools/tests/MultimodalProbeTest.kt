// Executable tests for the multimodal capability probe.
//
//     kotlinc app/src/main/java/com/adabala/medha/oai/MultimodalProbe.kt \
//             tools/tests/MultimodalProbeTest.kt -include-runtime -d /tmp/p.jar
//     java -jar /tmp/p.jar
//
// The probe decides what /health advertises, so getting it wrong means
// either claiming a capability that does not exist — which makes the client
// stop falling back and start failing — or hiding one that does.
//
// It hid one. v1 of this probe reported vision:false audio:false against
// litertlm-android 0.13.1, which supports both, because it matched on
// METHOD NAMES against a guessed hint list while the SDK expresses the
// capability in TYPES (InputData.Audio, Content.ImageBytes) and in
// CONSTRUCTOR PARAMETERS (EngineConfig.audioBackend). The entry points are
// named generateContent() and sendMessage(), which contain neither "audio"
// nor "image".
//
// The first test below is therefore a regression test for that exact
// failure: a stand-in set shaped like the real 0.13.1 surface, in which NO
// method name contains "audio" or "image" at all, must still report both
// capabilities. If that test ever goes back to false, v1's bug is back.

import com.adabala.medha.oai.MultimodalProbe

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

// --- stand-ins shaped like litertlm-android 0.13.1 -----------------------
//
// Read off the real source at tag v0.13.1 of google-ai-edge/litert-lm:
//   kotlin/java/com/google/ai/edge/litertlm/{Session,Message,Config,Engine}.kt
// Note what is NOT here: no method anywhere is called addAudio, sendAudio,
// addImage or anything else containing the modality name. That is the whole
// point.

class RealEngine {
    fun initialize() {}
    fun createSession(cfg: Any?): Any? = null
    fun createConversation(cfg: Any?): Any? = null
    fun close() {}
}

/** `data class EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)` */
class RealEngineConfig {
    fun getModelPath(): String = ""
    fun getBackend(): Any? = null
    fun getVisionBackend(): Any? = null
    fun getAudioBackend(): Any? = null
    fun getMaxNumTokens(): Int? = null
    fun getMaxNumImages(): Int? = null
    fun getCacheDir(): String? = null
}

/** `class Session { fun generateContent(List<InputData>): String; … }` */
class RealSession {
    fun runPrefill(inputData: List<Any>) {}
    fun runDecode(): String = ""
    fun generateContent(inputData: List<Any>): String = ""
    fun generateContentStream(inputData: List<Any>, cb: Any?) {}
    fun cancelProcess() {}
    fun close() {}
}

/** `class Conversation { fun sendMessage(Contents): Message; … }` */
class RealConversation {
    fun sendMessage(text: String): Any? = null
    fun sendMessageAsync(text: String): Any? = null
    fun getTokenCount(): Int = 0
    fun cancelProcess() {}
    fun close() {}
}

class RealMessage { fun getContents(): Any? = null; fun getRole(): Any? = null }
class RealContents { fun getContents(): List<Any> = emptyList() }

// sealed class InputData, with its three data-class subtypes
class RealInputData
class RealInputDataText { fun getText(): String = "" }
class RealInputDataImage { fun getBytes(): ByteArray = ByteArray(0) }
class RealInputDataAudio { fun getBytes(): ByteArray = ByteArray(0) }

// sealed class Content, with the subtypes that carry media
class RealContentImageBytes { fun getBytes(): ByteArray = ByteArray(0) }
class RealContentImageFile { fun getAbsolutePath(): String = "" }
class RealContentAudioBytes { fun getBytes(): ByteArray = ByteArray(0) }
class RealContentAudioFile { fun getAbsolutePath(): String = "" }

/** An older/stripped AAR: text only, no media types, no media config. */
class TextOnlyEngineConfig {
    fun getModelPath(): String = ""
    fun getBackend(): Any? = null
    fun getMaxNumTokens(): Int? = null
    fun getCacheDir(): String? = null
}

// --- resolvers ------------------------------------------------------------

fun resolverOf(map: Map<String, Class<*>>): (String) -> Class<*>? = { map[it] }

val FULL_0_13_1: Map<String, Class<*>> = mapOf(
    MultimodalProbe.ENGINE to RealEngine::class.java,
    MultimodalProbe.ENGINE_CONFIG to RealEngineConfig::class.java,
    MultimodalProbe.SESSION to RealSession::class.java,
    MultimodalProbe.CONVERSATION to RealConversation::class.java,
    MultimodalProbe.MESSAGE to RealMessage::class.java,
    MultimodalProbe.CONTENTS to RealContents::class.java,
    MultimodalProbe.INPUT_DATA to RealInputData::class.java,
    MultimodalProbe.INPUT_TEXT to RealInputDataText::class.java,
    MultimodalProbe.INPUT_IMAGE to RealInputDataImage::class.java,
    MultimodalProbe.INPUT_AUDIO to RealInputDataAudio::class.java,
    MultimodalProbe.CONTENT_IMAGE_BYTES to RealContentImageBytes::class.java,
    MultimodalProbe.CONTENT_IMAGE_FILE to RealContentImageFile::class.java,
    MultimodalProbe.CONTENT_AUDIO_BYTES to RealContentAudioBytes::class.java,
    MultimodalProbe.CONTENT_AUDIO_FILE to RealContentAudioFile::class.java
)

fun main() {
    println("MultimodalProbe — REGRESSION: the real 0.13.1 surface reports both")
    val full = MultimodalProbe.probe(resolverOf(FULL_0_13_1))
    check("vision is true", full.vision)
    check("audio is true", full.audio)
    check("no error", full.error == null)
    check("session path found", full.hasSessionPath)
    check("conversation path found", full.hasConversationPath)
    eq("nothing missing", full.classesMissing, emptyList<String>())
    eq("all 14 classes found", full.classesFound.size, 14)

    // The bug in one assertion. These are v1's hint lists, copied verbatim
    // from the deleted code. Replayed against the real 0.13.1 surface they
    // match NOTHING — which is exactly why v1 answered false on an AAR that
    // supports both. If the probe ever goes back to name matching, the two
    // checks under this one are what fail.
    println("\nMultimodalProbe — v1's hint list finds nothing on the real surface")
    val v1ImageHints = listOf(
        "addimage", "sendimage", "withimage", "setimage",
        "addbitmap", "imageinput", "visioninput", "addvision"
    )
    val v1AudioHints = listOf(
        "addaudio", "sendaudio", "withaudio", "setaudio",
        "audioinput", "addpcm", "addwav"
    )
    val v1Matches = full.allMethods.filter {
        val n = it.substringAfter('.').substringBefore('(').lowercase()
        (v1ImageHints + v1AudioHints).any { h -> n.contains(h) }
    }
    eq("v1 would have matched no method", v1Matches, emptyList<String>())
    check("yet the probe reports audio", full.audio)
    check("and reports vision", full.vision)

    // Where the capability actually shows up in a name: on EngineConfig's
    // accessors, which v1 never looked at as evidence because its hints
    // required an add/send/with/set prefix.
    println("\nMultimodalProbe — the only modality-named methods are config accessors")
    val modalityNamed = full.allMethods.filter {
        val n = it.substringAfter('.').substringBefore('(').lowercase()
        n.contains("audio") || n.contains("image") || n.contains("vision")
    }.map { it.substringBefore('(') }.sorted()
    eq("exactly the three EngineConfig getters", modalityNamed, listOf(
        "EngineConfig.getAudioBackend",
        "EngineConfig.getMaxNumImages",
        "EngineConfig.getVisionBackend"
    ))
    check("none of them is an input method", modalityNamed.none { it.startsWith("Session.") })

    println("\nMultimodalProbe — evidence explains the verdict")
    check("audio evidence cites the type", full.audioEvidence.any { it.contains("InputData\$Audio") })
    check("audio evidence cites the config", full.audioEvidence.any { it.contains("getAudioBackend") })
    check("vision evidence cites the type", full.visionEvidence.any { it.contains("InputData\$Image") })
    check("vision evidence cites the config", full.visionEvidence.any { it.contains("getVisionBackend") })
    check("vision evidence cites maxNumImages", full.visionEvidence.any { it.contains("getMaxNumImages") })

    println("\nMultimodalProbe — a text-only AAR reports nothing")
    val textOnly = MultimodalProbe.probe(resolverOf(mapOf(
        MultimodalProbe.ENGINE to RealEngine::class.java,
        MultimodalProbe.ENGINE_CONFIG to TextOnlyEngineConfig::class.java,
        MultimodalProbe.SESSION to RealSession::class.java,
        MultimodalProbe.CONVERSATION to RealConversation::class.java,
        MultimodalProbe.INPUT_DATA to RealInputData::class.java,
        MultimodalProbe.INPUT_TEXT to RealInputDataText::class.java
    )))
    check("vision false", !textOnly.vision)
    check("audio false", !textOnly.audio)
    check("no error — the AAR is present, just text-only", textOnly.error == null)
    check("missing media types are listed",
        MultimodalProbe.INPUT_AUDIO in textOnly.classesMissing)
    eq("summary says so", MultimodalProbe.summary(textOnly),
        "no image or audio binding in this build of the LiteRT-LM AAR")

    // Both halves are required, and these two cases are what pin that. A
    // type with no backend switch is unusable: Medha could build an
    // InputData.Audio and the native engine would refuse it at inference
    // because audioBackend was never set at init. Reporting true here would
    // be v1's bug with the sign flipped — advertising a capability that
    // fails on first use instead of hiding one that works.
    println("\nMultimodalProbe — media types WITHOUT the config switch are not a capability")
    val typesOnly = MultimodalProbe.probe(resolverOf(mapOf(
        MultimodalProbe.ENGINE to RealEngine::class.java,
        MultimodalProbe.ENGINE_CONFIG to TextOnlyEngineConfig::class.java,
        MultimodalProbe.INPUT_AUDIO to RealInputDataAudio::class.java,
        MultimodalProbe.INPUT_IMAGE to RealInputDataImage::class.java
    )))
    check("audio false without getAudioBackend", !typesOnly.audio)
    check("vision false without getVisionBackend", !typesOnly.vision)

    println("\nMultimodalProbe — the config switch WITHOUT media types is not one either")
    val configOnly = MultimodalProbe.probe(resolverOf(mapOf(
        MultimodalProbe.ENGINE to RealEngine::class.java,
        MultimodalProbe.ENGINE_CONFIG to RealEngineConfig::class.java,
        MultimodalProbe.INPUT_TEXT to RealInputDataText::class.java
    )))
    check("audio false without an audio input type", !configOnly.audio)
    check("vision false without an image input type", !configOnly.vision)

    println("\nMultimodalProbe — vision and audio are decided independently")
    val visionOnly = MultimodalProbe.probe(resolverOf(mapOf(
        MultimodalProbe.ENGINE_CONFIG to RealEngineConfig::class.java,
        MultimodalProbe.INPUT_IMAGE to RealInputDataImage::class.java
    )))
    check("vision true", visionOnly.vision)
    check("audio false", !visionOnly.audio)
    eq("summary names only vision", MultimodalProbe.summary(visionOnly),
        "AAR accepts images; no audio binding")

    val audioOnly = MultimodalProbe.probe(resolverOf(mapOf(
        MultimodalProbe.ENGINE_CONFIG to RealEngineConfig::class.java,
        MultimodalProbe.CONTENT_AUDIO_BYTES to RealContentAudioBytes::class.java
    )))
    check("audio true via Content.AudioBytes alone", audioOnly.audio)
    check("vision false", !audioOnly.vision)
    eq("summary names only audio", MultimodalProbe.summary(audioOnly),
        "AAR accepts audio; no image binding")

    println("\nMultimodalProbe — an absent AAR degrades instead of throwing")
    val none = MultimodalProbe.probe { null }
    check("reports an error", none.error != null)
    check("claims nothing", !none.vision && !none.audio)
    eq("lists everything as missing", none.classesMissing.size, MultimodalProbe.PROBED_CLASSES.size)
    check("summary says unavailable", MultimodalProbe.summary(none).startsWith("unavailable"))

    println("\nMultimodalProbe — a throwing resolver is survivable")
    // A real classloader can throw NoClassDefFoundError, not just return
    // null, when a class resolves but its dependencies do not. That must not
    // take the whole probe (and with it /health) down.
    val throwing = MultimodalProbe.probe { name ->
        if (name == MultimodalProbe.ENGINE_CONFIG) throw NoClassDefFoundError("boom")
        FULL_0_13_1[name]
    }
    check("does not propagate the error", true)
    check("EngineConfig counted as missing",
        MultimodalProbe.ENGINE_CONFIG in throwing.classesMissing)
    check("so audio degrades to false", !throwing.audio)
    check("and vision degrades to false", !throwing.vision)

    println("\nMultimodalProbe — probing this classpath finds no AAR")
    // The AAR is not here, which is the point: the probe must degrade to
    // "unavailable" rather than throwing, because that is exactly what it
    // does on a build where the AAR failed to link.
    val installed = MultimodalProbe.probeInstalled()
    check("does not throw", true)
    check("reports unavailable off-device", installed.error != null)
    check("and claims nothing", !installed.vision && !installed.audio)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("MultimodalProbeTest: all $checks checks passed")
    } else {
        println("MultimodalProbeTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
