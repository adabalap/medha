// Executable tests for the WAV reader/writer feeding InputData.Audio.
//
//     kotlinc app/src/main/java/com/adabala/medha/audio/Wav.kt \
//             tools/tests/WavTest.kt -include-runtime -d /tmp/w.jar
//     java -jar /tmp/w.jar
//
// Two things here are easy to get wrong and silent when you do:
//
//  1. The RIFF chunk size field is "everything after this field", NOT the
//     file size. Off by eight, most players still play it, and the one that
//     matters — a native decoder — may not.
//  2. Chunk walking. A WAV from ffmpeg routinely carries LIST/INFO before
//     `data`. A reader that assumes the canonical offset 36 reports a
//     confidently wrong duration on a perfectly valid file, which then
//     drives a wrong accept/reject against the 30-second limit.
//
// Both are asserted against assembled bytes rather than reasoned about.

import com.adabala.medha.audio.Wav

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

fun near(what: String, actual: Double, expected: Double, tol: Double = 1e-6) {
    checks++
    if (kotlin.math.abs(actual - expected) <= tol) println("  ok    $what")
    else {
        println("  FAIL  $what\n          expected: $expected\n          actual:   $actual")
        failures++
    }
}

fun le32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

fun le16(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

fun ascii(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.US_ASCII)

fun putLe32(b: ByteArray, at: Int, v: Int) {
    b[at] = (v and 0xFF).toByte()
    b[at + 1] = ((v ushr 8) and 0xFF).toByte()
    b[at + 2] = ((v ushr 16) and 0xFF).toByte()
    b[at + 3] = ((v ushr 24) and 0xFF).toByte()
}

fun putLe16(b: ByteArray, at: Int, v: Int) {
    b[at] = (v and 0xFF).toByte()
    b[at + 1] = ((v ushr 8) and 0xFF).toByte()
}

/**
 * Builds a WAV with an extra chunk between `fmt ` and `data`, the layout
 * Wav.parse has to walk rather than assume.
 */
fun wavWithExtraChunk(
    extraId: String,
    extraBody: ByteArray,
    pcm: ByteArray,
    sampleRate: Int = 16000,
    channels: Int = 1,
    bits: Int = 16
): ByteArray {
    val pad = extraBody.size and 1
    val total = 12 + 24 + (8 + extraBody.size + pad) + (8 + pcm.size)
    val out = ByteArray(total)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
    putLe32(out, 4, total - 8)
    "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)

    "fmt ".toByteArray(Charsets.US_ASCII).copyInto(out, 12)
    putLe32(out, 16, 16)
    putLe16(out, 20, 1)
    putLe16(out, 22, channels)
    putLe32(out, 24, sampleRate)
    putLe32(out, 28, sampleRate * channels * (bits / 8))
    putLe16(out, 32, channels * (bits / 8))
    putLe16(out, 34, bits)

    var p = 36
    extraId.toByteArray(Charsets.US_ASCII).copyInto(out, p)
    putLe32(out, p + 4, extraBody.size)
    extraBody.copyInto(out, p + 8)
    p += 8 + extraBody.size + pad

    "data".toByteArray(Charsets.US_ASCII).copyInto(out, p)
    putLe32(out, p + 4, pcm.size)
    pcm.copyInto(out, p + 8)
    return out
}

fun main() {
    // 16 kHz mono 16-bit => 32000 bytes/second. One second of silence.
    val oneSecond = ByteArray(32000)

    println("Wav — wrapPcm writes a canonical header")
    val w = Wav.wrapPcm(oneSecond)
    eq("total size is header + payload", w.size, 44 + 32000)
    eq("RIFF magic", ascii(w, 0, 4), "RIFF")
    // The classic bug: this field is total-8, not total.
    eq("RIFF size is everything after the field", le32(w, 4), w.size - 8)
    eq("WAVE magic", ascii(w, 8, 4), "WAVE")
    eq("fmt chunk id", ascii(w, 12, 4), "fmt ")
    eq("fmt chunk is 16 bytes", le32(w, 16), 16)
    eq("audio format is PCM", le16(w, 20), 1)
    eq("channels", le16(w, 22), 1)
    eq("sample rate", le32(w, 24), 16000)
    eq("byte rate", le32(w, 28), 32000)
    eq("block align", le16(w, 32), 2)
    eq("bits per sample", le16(w, 34), 16)
    eq("data chunk id", ascii(w, 36, 4), "data")
    eq("data size is the payload", le32(w, 40), 32000)

    println("\nWav — isWav recognises what wrapPcm wrote")
    check("wrapped bytes are a WAV", Wav.isWav(w))
    check("raw PCM is not", !Wav.isWav(oneSecond))
    check("empty is not", !Wav.isWav(ByteArray(0)))
    check("a short prefix is not", !Wav.isWav("RIFF".toByteArray()))
    // RIFF without WAVE is some other RIFF container (AVI, WebP), and must
    // not be wrapped-as-is into the engine.
    val riffNotWave = ByteArray(16).also {
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
        "AVI ".toByteArray(Charsets.US_ASCII).copyInto(it, 8)
    }
    check("RIFF that is not WAVE is not a WAV", !Wav.isWav(riffNotWave))

    println("\nWav — parse round-trips what wrapPcm wrote")
    val f = Wav.parse(w)
    check("parses", f != null)
    eq("sample rate", f?.sampleRate, 16000)
    eq("channels", f?.channels, 1)
    eq("bits", f?.bitsPerSample, 16)
    eq("data bytes", f?.dataBytes, 32000)
    near("one second", f?.seconds ?: -1.0, 1.0)

    println("\nWav — duration is read, not assumed")
    // The case that makes parsing worth it: 44.1 kHz stereo is 176400
    // bytes/second, 5.5x the rate ChatRequestParser's estimate assumes. The
    // same byte count is 5.5x shorter in reality.
    val cd = Wav.wrapPcm(ByteArray(176400), sampleRate = 44100, channels = 2)
    near("44.1k stereo second", Wav.durationSeconds(cd) ?: -1.0, 1.0)
    val estimateWouldSay = 176400 / 32000.0
    near("the 16k-mono estimate would have said 5.5s", estimateWouldSay, 5.5125)
    check("so parsing and estimating genuinely disagree",
        kotlin.math.abs((Wav.durationSeconds(cd) ?: 0.0) - estimateWouldSay) > 4.0)

    println("\nWav — parse walks past chunks before data")
    val withList = wavWithExtraChunk("LIST", "INFOISFTffmpeg".toByteArray(Charsets.US_ASCII), oneSecond)
    val lf = Wav.parse(withList)
    check("still parses", lf != null)
    eq("finds the real data size", lf?.dataBytes, 32000)
    near("and the real duration", lf?.seconds ?: -1.0, 1.0)

    println("\nWav — an odd-sized chunk is padded to a word boundary")
    // 13 bytes of body + 1 pad. A reader that forgets the pad byte lands
    // one byte into "data" and finds no chunk at all.
    val oddExtra = wavWithExtraChunk("LIST", ByteArray(13) { 65 }, oneSecond)
    val of = Wav.parse(oddExtra)
    check("parses past an odd chunk", of != null)
    eq("data size intact", of?.dataBytes, 32000)

    println("\nWav — a truncated file reports what is actually there")
    val truncated = w.copyOf(44 + 16000)
    val tf = Wav.parse(truncated)
    check("parses", tf != null)
    eq("reports the bytes present, not the declared 32000", tf?.dataBytes, 16000)
    near("half a second", tf?.seconds ?: -1.0, 0.5)

    println("\nWav — non-WAV input yields null rather than a wrong answer")
    eq("raw PCM", Wav.parse(oneSecond), null)
    eq("duration of raw PCM", Wav.durationSeconds(oneSecond), null)
    eq("empty", Wav.parse(ByteArray(0)), null)
    eq("garbage", Wav.parse("not audio at all".toByteArray()), null)
    // A WAVE header with no fmt chunk is unparseable, not zero-length.
    val noFmt = ByteArray(12).also {
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(it, 8)
    }
    eq("WAVE with no chunks", Wav.parse(noFmt), null)

    println("\nWav — ensureWav sniffs rather than trusting the caller")
    val alreadyWav = Wav.ensureWav(w)
    check("an existing WAV is returned unchanged", alreadyWav === w)
    eq("size unchanged", alreadyWav.size, w.size)
    // The failure this prevents: a client that declares pcm16 while actually
    // sending a WAV would otherwise get a second header stapled on, and 44
    // bytes of header decoded as samples is a click, not an error.
    eq("still one second after ensureWav", Wav.durationSeconds(alreadyWav), 1.0)

    val wrapped = Wav.ensureWav(oneSecond)
    check("raw PCM gets wrapped", Wav.isWav(wrapped))
    eq("to exactly one header's worth larger", wrapped.size, oneSecond.size + 44)
    near("and one second long", Wav.durationSeconds(wrapped) ?: -1.0, 1.0)

    println("\nWav — wrapPcm rejects impossible formats")
    for ((what, body) in listOf<Pair<String, () -> Unit>>(
        "zero sample rate" to { Wav.wrapPcm(oneSecond, sampleRate = 0) },
        "negative sample rate" to { Wav.wrapPcm(oneSecond, sampleRate = -1) },
        "zero channels" to { Wav.wrapPcm(oneSecond, channels = 0) },
        "bits not a multiple of 8" to { Wav.wrapPcm(oneSecond, bitsPerSample = 12) },
        "zero bits" to { Wav.wrapPcm(oneSecond, bitsPerSample = 0) }
    )) {
        val threw = runCatching { body() }.isFailure
        check("rejects $what", threw)
    }

    println("\nWav — an empty payload is a valid, zero-length WAV")
    // Not an error: a client that records nothing should get an empty
    // transcript, not a 500 from the header writer.
    val empty = Wav.wrapPcm(ByteArray(0))
    eq("header only", empty.size, 44)
    check("is a WAV", Wav.isWav(empty))
    near("zero seconds", Wav.durationSeconds(empty) ?: -1.0, 0.0)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("WavTest: all $checks checks passed")
    } else {
        println("WavTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
