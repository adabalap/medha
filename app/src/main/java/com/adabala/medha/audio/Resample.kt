package com.adabala.medha.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sample-rate conversion to the one rate the engine accepts.
 *
 * WHY THIS HAS TO EXIST
 *
 * LiteRT-LM takes 16 kHz mono WAV and nothing else. Phones record video at
 * 48 kHz stereo and voice notes at 44.1 kHz, and Android has no offline
 * resampler worth the name: MediaCodec decodes to the source rate and will
 * not convert, so a video's audio track arrives at the wrong rate every
 * time. Without this file, "transcribe my video note" is a 400 telling the
 * user to go and run ffmpeg.
 *
 * WHY IT IS NOT JUST "TAKE EVERY THIRD SAMPLE"
 *
 * Because that is the version that looks like it works. Dropping samples
 * without filtering first folds every frequency above the new Nyquist back
 * down into the audible band — 12 kHz in a 48 kHz recording reappears as a
 * 4 kHz whine in the 16 kHz output, at full strength, sitting exactly on top
 * of speech. Nothing crashes. The audio still plays. The transcript just
 * comes back subtly wrong, and the cause is invisible.
 *
 * So the signal is low-passed before it is decimated, with a windowed-sinc
 * filter whose job is to be boring and correct. [resample] is pure
 * arithmetic on a ShortArray with no Android in sight, which is the only
 * reason any of this can be tested at all: ResampleTest drives a 12 kHz tone
 * through it and asserts the alias does NOT appear.
 */
object Resample {

    /** The only rate the engine accepts. */
    const val TARGET_HZ = 16_000

    /**
     * Filter length. Odd, so the filter has an exact centre tap and
     * therefore a whole-sample delay that needs no correction.
     *
     * 127 taps at 48 kHz is about 2.6 ms of audio — inaudible as delay,
     * long enough for a transition sharp enough to keep speech intact while
     * pushing the fold-down region well below anything that matters.
     */
    const val TAPS = 127

    /**
     * Cutoff, as a fraction of the OUTPUT Nyquist.
     *
     * 0.90 of 8 kHz is 7.2 kHz. Speech intelligibility is essentially done
     * by 7 kHz, and leaving that last margin is what keeps the transition
     * band from reaching up past Nyquist and aliasing anyway.
     */
    const val CUTOFF_FRACTION = 0.90

    /**
     * Stereo (or more) to mono, by averaging.
     *
     * Averaged in Int. Two shorts near full scale sum past Short.MAX_VALUE,
     * and doing this in Short arithmetic wraps the loudest moments of a
     * recording to the opposite polarity — which sounds like a click and
     * reads, to a model, like a consonant that was never spoken.
     */
    fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        require(channels >= 1) { "channels must be at least 1" }
        if (channels == 1) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[f * channels + c].toInt()
            out[f] = clamp(sum / channels)
        }
        return out
    }

    /**
     * A windowed-sinc low-pass kernel, Hamming-windowed and normalised to
     * unity gain at DC so the filter changes the spectrum and not the
     * volume.
     */
    fun kernel(cutoffHz: Double, sampleHz: Int, taps: Int = TAPS): DoubleArray {
        require(taps % 2 == 1) { "taps must be odd so the filter has a centre" }
        require(cutoffHz > 0 && sampleHz > 0)
        val fc = min(cutoffHz / sampleHz, 0.5)
        val mid = taps / 2
        val k = DoubleArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val n = i - mid
            val sinc = if (n == 0) 2.0 * fc else sin(2.0 * PI * fc * n) / (PI * n)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
            k[i] = sinc * window
            sum += k[i]
        }
        for (i in 0 until taps) k[i] /= sum
        return k
    }

    /** Convolution, with the edges held rather than zero-padded. */
    fun lowPass(pcm: ShortArray, k: DoubleArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val mid = k.size / 2
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            var acc = 0.0
            for (j in k.indices) {
                // Clamping the index repeats the first and last sample
                // outside the signal. Zero-padding would instead ramp the
                // first and last few milliseconds down to silence, which on
                // a short clip is a chunk of the actual speech.
                val s = (i + j - mid).coerceIn(0, pcm.size - 1)
                acc += pcm[s] * k[j]
            }
            out[i] = clamp(acc.roundToInt())
        }
        return out
    }

    /**
     * [pcm] from [fromHz] to [toHz], mono in and mono out.
     *
     * Low-passes first when downsampling, then reads the filtered signal at
     * the new spacing with linear interpolation — so a non-integer ratio
     * like 44100 to 16000 is handled the same way as a clean 3:1.
     */
    fun resample(pcm: ShortArray, fromHz: Int, toHz: Int = TARGET_HZ): ShortArray {
        require(fromHz > 0 && toHz > 0) { "rates must be positive" }
        if (pcm.isEmpty() || fromHz == toHz) return pcm

        val src = if (toHz < fromHz) {
            lowPass(pcm, kernel(CUTOFF_FRACTION * (toHz / 2.0), fromHz))
        } else {
            pcm
        }

        val ratio = fromHz.toDouble() / toHz
        val outLen = max(1, (pcm.size / ratio).toInt())
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val at = i * ratio
            val lo = at.toInt()
            val hi = min(lo + 1, src.size - 1)
            val frac = at - lo
            out[i] = clamp((src[lo] * (1 - frac) + src[hi] * frac).roundToInt())
        }
        return out
    }

    /**
     * The whole pipeline: however the audio arrived, 16 kHz mono.
     *
     * Downmix BEFORE resampling, deliberately: filtering one channel rather
     * than two is half the arithmetic, and on a phone already holding a
     * language model in memory that is not a rounding error.
     */
    fun toEngineRate(pcm: ShortArray, fromHz: Int, channels: Int): ShortArray =
        resample(downmix(pcm, channels), fromHz, TARGET_HZ)

    /** Saturating, because wrapping a loud passage inverts it. */
    private fun clamp(v: Int): Short =
        when {
            v > Short.MAX_VALUE -> Short.MAX_VALUE
            v < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> v.toShort()
        }

    /** RMS, exposed for tests and for a future silence-splitter. */
    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var acc = 0.0
        for (s in pcm) acc += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(acc / pcm.size)
    }

    /** Peak absolute sample. */
    fun peak(pcm: ShortArray): Int = pcm.fold(0) { a, s -> max(a, abs(s.toInt())) }
}
