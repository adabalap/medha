package com.adabala.medha.audio

/**
 * Minimal WAV reading and writing, for the audio path into LiteRT-LM.
 *
 * ## Why this exists
 *
 * `InputData.Audio` in litertlm-android documents exactly one accepted
 * container: *"Supported format: WAV."* Android's `AudioRecord` — what any
 * client recording on the device actually uses, hg_agent included — hands
 * back **raw PCM with no header at all**. Passing those bytes straight
 * through produces a native-side failure whose message says nothing about
 * the missing 44 bytes that caused it.
 *
 * So Medha accepts raw PCM and wraps it, rather than making every client
 * hand-assemble a header and get the little-endian field order wrong in its
 * own way. `pcm16` is advertised in `/health` precisely because this file
 * makes it true.
 *
 * ## Why the duration is parsed rather than estimated
 *
 * [com.adabala.medha.oai.ChatRequestParser] estimates clip length from the
 * base64 byte count assuming 16 kHz mono 16-bit, because measuring properly
 * there would mean decoding a megabyte of base64 just to reject it. That
 * estimate is correct for the documented format and wrong by 5.5x for
 * 44.1 kHz stereo — a 30-second limit would silently accept a clip of nearly
 * three minutes, or reject a legitimate one, depending on direction.
 *
 * Wherever the real bytes are already in hand — the multipart upload path —
 * [durationSeconds] reads the actual header instead. Rejecting on a parsed
 * fact and rejecting on a guess produce the same HTTP status and completely
 * different trust.
 *
 * No Android types: every branch here runs in tools/tests/WavTest.kt.
 */
object Wav {

    /** `RIFF` */
    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)

    /** `WAVE` */
    private val WAVE = byteArrayOf(0x57, 0x41, 0x56, 0x45)

    const val HEADER_BYTES = 44
    const val DEFAULT_SAMPLE_RATE = 16000
    const val DEFAULT_CHANNELS = 1
    const val DEFAULT_BITS_PER_SAMPLE = 16

    /** What a parsed header says. [dataBytes] excludes the header itself. */
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Int
    ) {
        val bytesPerSecond: Int get() = sampleRate * channels * (bitsPerSample / 8)
        val seconds: Double
            get() = if (bytesPerSecond > 0) dataBytes.toDouble() / bytesPerSecond else 0.0
    }

    /** True when [bytes] starts with a RIFF/WAVE signature. */
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes.regionMatches(0, RIFF) &&
            bytes.regionMatches(8, WAVE)

    /**
     * Wraps raw little-endian PCM in a canonical 44-byte WAV header.
     *
     * Does not copy or validate the samples; a caller handing in an odd
     * number of bytes for 16-bit audio gets a file whose final sample is
     * half-written, which is the caller's bug and not worth a silent
     * truncation that hides it.
     */
    fun wrapPcm(
        pcm: ByteArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE
    ): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(bitsPerSample % 8 == 0 && bitsPerSample > 0) {
            "bitsPerSample must be a positive multiple of 8"
        }

        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(HEADER_BYTES + pcm.size)

        RIFF.copyInto(out, 0)
        // RIFF chunk size = everything after this field. Not the file size:
        // getting these two confused is the classic WAV bug, and players
        // differ in how loudly they complain about it.
        out.putLe32(4, HEADER_BYTES - 8 + pcm.size)
        WAVE.copyInto(out, 8)

        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(out, 12)
        out.putLe32(16, 16)              // PCM fmt chunk is 16 bytes
        out.putLe16(20, 1)               // audio format 1 = uncompressed PCM
        out.putLe16(22, channels)
        out.putLe32(24, sampleRate)
        out.putLe32(28, byteRate)
        out.putLe16(32, blockAlign)
        out.putLe16(34, bitsPerSample)

        "data".toByteArray(Charsets.US_ASCII).copyInto(out, 36)
        out.putLe32(40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * Returns [bytes] unchanged when it is already a WAV, or wraps it when
     * it is raw PCM.
     *
     * Sniffs the signature rather than trusting [declaredFormat], because a
     * client that says "pcm16" while actually sending a WAV would otherwise
     * get a second header stapled on top of the first — 44 bytes of garbage
     * decoded as samples, which sounds like a click and reads like a model
     * that cannot transcribe.
     */
    fun ensureWav(
        bytes: ByteArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE
    ): ByteArray =
        if (isWav(bytes)) bytes else wrapPcm(bytes, sampleRate, channels, bitsPerSample)

    /**
     * Reads the format from a WAV header, walking the chunk list to find
     * `fmt ` and `data`.
     *
     * Walks rather than assuming offset 12 and 36: a WAV written by ffmpeg
     * routinely carries a `LIST`/`INFO` chunk before `data`, and a reader
     * that assumes the canonical layout reports a wildly wrong length on a
     * perfectly valid file. Returns null on anything it cannot parse,
     * because a confident wrong duration is worse than no duration.
     */
    fun parse(bytes: ByteArray): Format? {
        if (!isWav(bytes)) return null

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataBytes = -1

        // Chunks start after "RIFF"<size>"WAVE".
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = bytes.le32(p + 4)
            // A negative or absurd size means a corrupt file; stop rather
            // than letting it drive an out-of-bounds read.
            if (size < 0) return null
            val body = p + 8

            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    channels = bytes.le16(body + 2)
                    sampleRate = bytes.le32(body + 4)
                    bitsPerSample = bytes.le16(body + 14)
                }
                "data" -> {
                    // Trust the smaller of the declared size and what is
                    // actually present: a truncated download declares the
                    // length it meant to have.
                    dataBytes = minOf(size, bytes.size - body).coerceAtLeast(0)
                }
            }

            if (dataBytes >= 0 && sampleRate > 0) break
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            p = body + size + (size and 1)
        }

        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || dataBytes < 0) return null
        return Format(sampleRate, channels, bitsPerSample, dataBytes)
    }

    /**
     * Clip length in seconds from the real header, or null when [bytes] is
     * not a parseable WAV.
     */
    fun durationSeconds(bytes: ByteArray): Double? = parse(bytes)?.seconds

    // --- little-endian helpers ------------------------------------------
    //
    // Hand-rolled rather than via ByteBuffer so this file stays free of any
    // import that would stop it compiling in the off-device harness.

    private fun ByteArray.regionMatches(at: Int, probe: ByteArray): Boolean {
        if (at + probe.size > size) return false
        for (i in probe.indices) if (this[at + i] != probe[i]) return false
        return true
    }

    private fun ByteArray.putLe16(at: Int, v: Int) {
        this[at] = (v and 0xFF).toByte()
        this[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putLe32(at: Int, v: Int) {
        this[at] = (v and 0xFF).toByte()
        this[at + 1] = ((v ushr 8) and 0xFF).toByte()
        this[at + 2] = ((v ushr 16) and 0xFF).toByte()
        this[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.le16(at: Int): Int =
        if (at + 2 > size) 0
        else (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(at: Int): Int =
        if (at + 4 > size) 0
        else (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)
}
