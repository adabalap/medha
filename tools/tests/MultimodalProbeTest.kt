// Executable tests for the multimodal capability probe.
//
//     kotlinc app/src/main/java/com/adabala/medha/oai/MultimodalProbe.kt \
//             tools/tests/MultimodalProbeTest.kt -include-runtime -d /tmp/p.jar
//     java -jar /tmp/p.jar
//
// The probe decides what /health advertises, so getting it wrong means
// either claiming a capability that does not exist — which makes the client
// stop falling back and start failing — or hiding one that does. It takes
// the classes as an argument precisely so the matching can be run here
// against stand-ins shaped like the real AAR's plausible surfaces.

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

// --- stand-ins for the shapes the AAR might plausibly expose --------------

class TextOnlyConversation {
    fun sendMessage(text: String): String = ""
    fun sendMessageAsync(text: String): String = ""
    fun close() {}
}

class VisionConversation {
    fun sendMessage(text: String): String = ""
    fun addImage(bytes: ByteArray): VisionConversation = this
    fun close() {}
}

class AudioConversation {
    fun sendMessage(text: String): String = ""
    fun addAudio(pcm: ByteArray, sampleRate: Int): AudioConversation = this
    fun close() {}
}

class FullConversation {
    fun sendMessage(text: String): String = ""
    fun addImage(bytes: ByteArray): FullConversation = this
    fun addAudio(pcm: ByteArray): FullConversation = this
    fun close() {}
}

/** A name that merely contains "image" but is not an input path. */
class DecoyConversation {
    fun sendMessage(text: String): String = ""
    fun imageCount(): Int = 0
    fun close() {}
}

fun main() {

    println("\nMultimodalProbe — a text-only build reports no capability")
    val textOnly = MultimodalProbe.probe(listOf(TextOnlyConversation::class.java))
    check("vision false", !textOnly.vision)
    check("audio false", !textOnly.audio)
    eq("no image methods listed", textOnly.imageMethods.size, 0)
    check("but the full method list is still captured for diagnostics",
        textOnly.allMethods.any { it.contains("sendMessage") })
    check("summary names the build, not Medha",
        MultimodalProbe.summary(textOnly).contains("LiteRT-LM AAR"))

    println("\nMultimodalProbe — vision only")
    val vision = MultimodalProbe.probe(listOf(VisionConversation::class.java))
    check("vision true", vision.vision)
    check("audio still false", !vision.audio)
    check("the matching signature is reported, not just a boolean",
        vision.imageMethods.any { it.contains("addImage") && it.contains("byte[]") })
    eq("summary", MultimodalProbe.summary(vision), "vision entry points found; no audio")

    println("\nMultimodalProbe — audio only")
    val audio = MultimodalProbe.probe(listOf(AudioConversation::class.java))
    check("audio true", audio.audio)
    check("vision false", !audio.vision)
    check("signature carries the parameter types, so the binding can be written from it",
        audio.audioMethods.any { it.contains("addAudio") && it.contains("int") })

    println("\nMultimodalProbe — both")
    val full = MultimodalProbe.probe(listOf(FullConversation::class.java))
    check("vision", full.vision)
    check("audio", full.audio)
    eq("summary", MultimodalProbe.summary(full), "vision and audio entry points found")

    println("\nMultimodalProbe — several classes are searched together")
    val split = MultimodalProbe.probe(
        listOf(VisionConversation::class.java, AudioConversation::class.java)
    )
    check("vision found on one class", split.vision)
    check("audio found on the other", split.audio)

    println("\nMultimodalProbe — a bare accessor is not mistaken for an input path")
    // imageCount() contains "image" but is not a way to send one. The hints
    // all require a verb (addImage, sendImage, setImage...), so an accessor
    // does not trip them. Worth pinning: the obvious implementation —
    // matching on "image" alone — would advertise vision on a text-only
    // build, and the client would stop falling back and start failing.
    val decoy = MultimodalProbe.probe(listOf(DecoyConversation::class.java))
    check("imageCount() does not count as vision support", !decoy.vision)
    check("nothing is listed as an image method", decoy.imageMethods.isEmpty())
    check("but it is still in the diagnostic dump",
        decoy.allMethods.any { it.contains("imageCount") })

    println("\nMultimodalProbe — degenerate input")
    val none = MultimodalProbe.probe(emptyList())
    check("no classes is an error, not a silent false", none.error != null)
    check("and reports no capability", !none.vision && !none.audio)
    check("summary says unavailable", MultimodalProbe.summary(none).startsWith("unavailable"))

    println("\nMultimodalProbe — probing the real classpath here finds nothing")
    // The AAR is not on this classpath, which is the point: the probe must
    // degrade to "unavailable" rather than throwing, because that is
    // exactly what it does on a build where the AAR failed to link.
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
