// Executable tests for the engine init ladder.
//
//     kotlinc app/src/main/java/com/adabala/medha/oai/MultimodalLadder.kt \
//             tools/tests/MultimodalLadderTest.kt -include-runtime -d /tmp/l.jar
//     java -jar /tmp/l.jar
//
// Every rung costs a full model load -- ~8.6s on an SM8850 -- so the order
// and the de-duplication are not cosmetic. Three ways to get this wrong:
//
//  1. Duplicate rungs. With backend == CPU, "media on GPU" and "media on
//     CPU" are the same attempt, and trying both doubles a cold start.
//  2. Caching the text-only rung. Put text-only first and it succeeds
//     immediately, so the media rungs are never tried -- a model swapped
//     for one WITH encoders would be permanently stuck in text-only.
//  3. A hint that prunes instead of reordering. Change the model or the
//     device and the engine can never climb back up.
//
// The labels are pinned here on purpose: they are persisted as the cache
// key and reported as /health's multimodal_mode, so rewording one silently
// invalidates every stored hint.

import com.adabala.medha.oai.MultimodalLadder
import com.adabala.medha.oai.MultimodalLadder.Rung

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

fun labels(rs: List<Rung>) = rs.map { it.label }

fun main() {
    println("MultimodalLadder — GPU, both modalities")
    val gpu = MultimodalLadder.order("GPU", wantVision = true, wantAudio = true)
    eq("four rungs in order", labels(gpu), listOf(
        "all on GPU",
        "GPU, audio on CPU",
        "GPU, media on CPU",
        "text-only"
    ))
    eq("rung 1 puts both on GPU", gpu[0], Rung("all on GPU", "GPU", "GPU"))
    // The rung the first real device needed. Its error named Audio
    // specifically -- "Model requires one of [cpu] but Audio backend is
    // GPU" -- so demoting only audio is the targeted fix, and keeps image
    // encoding (the heavier of the two) on the GPU.
    eq("rung 2 demotes audio only", gpu[1], Rung("GPU, audio on CPU", "GPU", "CPU"))
    eq("rung 3 demotes both", gpu[2], Rung("GPU, media on CPU", "CPU", "CPU"))
    eq("rung 4 is text-only", gpu[3], Rung("text-only", null, null))
    check("text-only wants no media", !gpu[3].wantsMedia)
    check("every other rung wants media", gpu.dropLast(1).all { it.wantsMedia })

    println("\nMultimodalLadder — CPU backend produces no duplicate rung")
    val cpu = MultimodalLadder.order("CPU", wantVision = true, wantAudio = true)
    eq("two rungs only", labels(cpu), listOf("all on CPU", "text-only"))
    check("no split rung — there is nothing to demote to", labels(cpu).none { it.contains(",") })
    eq("no label appears twice", labels(cpu).size, labels(cpu).toSet().size)

    println("\nMultimodalLadder — NPU behaves like any non-CPU backend")
    val npu = MultimodalLadder.order("NPU", wantVision = true, wantAudio = true)
    eq("full ladder", labels(npu), listOf(
        "all on NPU",
        "NPU, audio on CPU",
        "NPU, media on CPU",
        "text-only"
    ))

    println("\nMultimodalLadder — one modality means no split rung")
    val visionOnly = MultimodalLadder.order("GPU", wantVision = true, wantAudio = false)
    eq("vision only", labels(visionOnly), listOf("all on GPU", "GPU, media on CPU", "text-only"))
    eq("audio stays null", visionOnly[0].audioBackend, null)
    eq("vision is set", visionOnly[0].visionBackend, "GPU")

    val audioOnly = MultimodalLadder.order("GPU", wantVision = false, wantAudio = true)
    eq("audio only", labels(audioOnly), listOf("all on GPU", "GPU, media on CPU", "text-only"))
    eq("vision stays null", audioOnly[0].visionBackend, null)
    eq("audio is set", audioOnly[0].audioBackend, "GPU")

    println("\nMultimodalLadder — neither modality means text-only alone")
    val none = MultimodalLadder.order("GPU", wantVision = false, wantAudio = false)
    eq("one rung", labels(none), listOf("text-only"))

    println("\nMultimodalLadder — a hint reorders and never prunes")
    val hinted = MultimodalLadder.order("GPU", true, true, hint = "GPU, media on CPU")
    eq("the hint goes first", hinted.first().label, "GPU, media on CPU")
    eq("nothing is lost", hinted.size, gpu.size)
    eq("same set of rungs", labels(hinted).toSet(), labels(gpu).toSet())
    // The reason it must not prune: swap the model or change the device
    // and the engine has to be able to climb back up.
    check("the GPU rung is still reachable", "all on GPU" in labels(hinted))
    eq("and the rest keep their relative order",
        labels(hinted).drop(1), listOf("all on GPU", "GPU, audio on CPU", "text-only"))

    val splitHint = MultimodalLadder.order("GPU", true, true, hint = "GPU, audio on CPU")
    eq("the split rung can be hinted too", splitHint.first().label, "GPU, audio on CPU")
    eq("still four", splitHint.size, 4)

    println("\nMultimodalLadder — an unknown or stale hint is ignored")
    eq("a hint for another backend changes nothing",
        labels(MultimodalLadder.order("CPU", true, true, hint = "all on GPU")),
        listOf("all on CPU", "text-only"))
    eq("a nonsense hint changes nothing",
        labels(MultimodalLadder.order("GPU", true, true, hint = "media on QPU")),
        labels(gpu))
    eq("an empty hint changes nothing",
        labels(MultimodalLadder.order("GPU", true, true, hint = "")), labels(gpu))

    println("\nMultimodalLadder — text-only is never cached")
    // If it were, it would be hinted first, succeed immediately, and the
    // media rungs would never run again -- so a model swapped for one WITH
    // encoders would stay text-only forever.
    check("text-only is not worth caching", !MultimodalLadder.worthCaching("text-only"))
    check("all on GPU is", MultimodalLadder.worthCaching("all on GPU"))
    check("GPU, media on CPU is", MultimodalLadder.worthCaching("GPU, media on CPU"))
    check("the split rung is", MultimodalLadder.worthCaching("GPU, audio on CPU"))

    // And the damage it would do, stated as a test rather than a comment.
    val poisoned = MultimodalLadder.order("GPU", true, true, hint = "text-only")
    eq("hinting text-only really does put it first", poisoned.first().label, "text-only")
    check("which is exactly why worthCaching refuses it",
        !MultimodalLadder.worthCaching(poisoned.first().label))

    println("\nMultimodalLadder — labels are stable, because they are persisted")
    // Rewording any of these silently invalidates every stored hint and
    // changes what /health reports as multimodal_mode.
    eq("text-only constant", MultimodalLadder.TEXT_ONLY, "text-only")
    eq("CPU constant", MultimodalLadder.CPU, "CPU")
    eq("preferred label", MultimodalLadder.preferredLabel("GPU"), "all on GPU")

    println("\nMultimodalLadder — every label names the LLM's accelerator first")
    // The naming bug this replaced: labels used to name only what MOVED,
    // so "media on CPU" read as the whole model dropping to CPU when the
    // language model was on the GPU throughout. Every media rung's label
    // now starts with the configured backend; anything after the comma is
    // the exception.
    for (r in gpu.filter { it.wantsMedia }) {
        check("'${r.label}' leads with GPU", r.label.startsWith("all on GPU") || r.label.startsWith("GPU,"))
        check("'${r.label}' does not read as a whole-model demotion",
            !r.label.startsWith("media on") && !r.label.startsWith("vision on"))
    }
    check("the demotion is after a comma, not before",
        gpu[1].label.substringBefore(",") == "GPU")
    check("no label has leading or trailing space", gpu.all { it.label == it.label.trim() })
    check("every label is unique within a ladder", labels(gpu).size == labels(gpu).toSet().size)

    println("\nMultimodalLadder — every ladder ends in text-only")
    // A load must never fail outright just because every media
    // configuration was rejected.
    for (b in listOf("GPU", "CPU", "NPU")) {
        for (v in listOf(true, false)) {
            for (a in listOf(true, false)) {
                val l = MultimodalLadder.order(b, v, a)
                check("$b vision=$v audio=$a ends in text-only",
                    l.last().label == MultimodalLadder.TEXT_ONLY)
                check("$b vision=$v audio=$a has no duplicates",
                    labels(l).size == labels(l).toSet().size)
            }
        }
    }

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("MultimodalLadderTest: all $checks checks passed")
    } else {
        println("MultimodalLadderTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
