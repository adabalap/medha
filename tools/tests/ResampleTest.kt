// Getting a video's audio to the one rate the engine accepts.
//
// The test that matters is the alias one. Dropping every third sample to go
// from 48 kHz to 16 kHz looks like it works: nothing crashes, the audio
// still plays, and a 12 kHz component silently reappears as a 4 kHz whine
// sitting on top of the speech. The transcript just comes back subtly
// wrong, with no symptom pointing at the cause.
//
// So this drives real tones through the real function and measures what
// comes out, rather than asserting that a function was called.
import com.adabala.medha.audio.Resample
import kotlin.math.PI
import kotlin.math.sin

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

/** A pure tone, full-ish scale, as 16-bit PCM. */
fun tone(hz: Double, rate: Int, seconds: Double, amp: Double = 12000.0): ShortArray {
    val n = (rate * seconds).toInt()
    return ShortArray(n) { i -> (amp * sin(2.0 * PI * hz * i / rate)).toInt().toShort() }
}

/** Naive decimation: what this file exists to prove is not good enough. */
fun dropEveryNth(pcm: ShortArray, factor: Int) =
    ShortArray(pcm.size / factor) { i -> pcm[i * factor] }

fun main() {
    println("Resample.downmix")
    eq("mono is returned untouched",
        Resample.downmix(shortArrayOf(1, 2, 3), 1).toList(), listOf<Short>(1, 2, 3))
    eq("stereo halves the sample count",
        Resample.downmix(ShortArray(100), 2).size, 50)
    eq("opposite channels cancel",
        Resample.downmix(shortArrayOf(1000, -1000), 2).toList(), listOf<Short>(0))
    eq("equal channels are preserved, not doubled",
        Resample.downmix(shortArrayOf(5000, 5000), 2).toList(), listOf<Short>(5000))
    // Averaging two near-full-scale shorts overflows if done in Short
    // arithmetic, and a wrapped sample inverts: the loudest moment of a
    // recording becomes a click.
    eq("two near-full-scale samples do not wrap",
        Resample.downmix(shortArrayOf(32000, 32000), 2).toList(), listOf<Short>(32000))

    println("\nResample.kernel")
    val k = Resample.kernel(7200.0, 48000)
    eq("odd length, so there is an exact centre tap", k.size % 2, 1)
    check("unity gain at DC, so filtering does not change the volume",
        kotlin.math.abs(k.sum() - 1.0) < 1e-9)

    println("\nResample.resample — the plumbing")
    eq("same rate in and out is a no-op",
        Resample.resample(tone(1000.0, 16000, 0.1), 16000, 16000).size, 1600)
    eq("empty stays empty", Resample.resample(ShortArray(0), 48000, 16000).size, 0)
    val oneSecond = Resample.resample(tone(1000.0, 48000, 1.0), 48000, 16000)
    check("one second at 48k becomes one second at 16k",
        oneSecond.size in 15900..16000)
    check("44100 is handled like any other ratio",
        Resample.resample(tone(1000.0, 44100, 1.0), 44100, 16000).size in 15900..16100)

    println("\nResample.resample — speech survives")
    // A 1 kHz tone is well inside the passband and must come through at
    // close to its original level.
    val inLevel = Resample.rms(tone(1000.0, 48000, 0.5))
    val outLevel = Resample.rms(Resample.resample(tone(1000.0, 48000, 0.5), 48000, 16000))
    check("1 kHz keeps its level (${outLevel.toInt()} vs ${inLevel.toInt()})",
        outLevel > inLevel * 0.85)
    val at3k = Resample.rms(Resample.resample(tone(3000.0, 48000, 0.5), 48000, 16000))
    check("3 kHz does too, which is where speech actually lives",
        at3k > inLevel * 0.80)

    println("\nResample.resample — THE ALIAS TEST")
    // 12 kHz is above the 8 kHz Nyquist of a 16 kHz signal. Decimated
    // naively it folds to |16000 - 12000| = 4 kHz at full strength, right
    // on top of speech. Filtered first, it must be gone.
    val twelveK = tone(12000.0, 48000, 0.5)
    val naive = Resample.rms(dropEveryNth(twelveK, 3))
    val filtered = Resample.rms(Resample.resample(twelveK, 48000, 16000))
    check("naive decimation DOES alias, loudly (rms ${naive.toInt()})",
        naive > inLevel * 0.5)
    check("the real resampler kills it (rms ${filtered.toInt()}, was ${naive.toInt()})",
        filtered < naive * 0.05)
    check("and that is at least 26 dB of rejection",
        filtered < inLevel * 0.05)

    // 15 kHz folds to 1 kHz, which would be indistinguishable from a vowel.
    val fifteenK = tone(15000.0, 48000, 0.5)
    check("15 kHz is rejected too, not folded onto a vowel",
        Resample.rms(Resample.resample(fifteenK, 48000, 16000)) < inLevel * 0.05)

    println("\nResample.resample — nothing clips")
    val loud = tone(500.0, 48000, 0.3, amp = 32700.0)
    val loudOut = Resample.resample(loud, 48000, 16000)
    check("a full-scale input does not wrap to the opposite polarity",
        Resample.peak(loudOut) <= 32767)
    check("and is not crushed either", Resample.peak(loudOut) > 30000)

    println("\nResample.toEngineRate — the whole pipeline")
    // 48 kHz stereo is what a phone's video camera records.
    val stereo = ShortArray(48000 * 2) { i ->
        (10000.0 * sin(2.0 * PI * 800.0 * (i / 2) / 48000)).toInt().toShort()
    }
    val engine = Resample.toEngineRate(stereo, 48000, 2)
    check("one second of 48k stereo becomes one second of 16k mono",
        engine.size in 15900..16000)
    check("and still carries the tone", Resample.rms(engine) > 5000)
    eq("16k mono in is 16k mono out, untouched",
        Resample.toEngineRate(tone(440.0, 16000, 0.25), 16000, 1).size, 4000)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("ResampleTest: all $checks checks passed")
    } else {
        println("ResampleTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
