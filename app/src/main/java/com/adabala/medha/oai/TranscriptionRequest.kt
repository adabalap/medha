package com.adabala.medha.oai

import com.adabala.medha.audio.Wav

/**
 * Validation and prompt construction for `POST /v1/audio/transcriptions`,
 * the OpenAI-compatible speech-to-text endpoint.
 *
 * ## Why this endpoint exists
 *
 * It is the shape every STT client already speaks, hg_agent included, and
 * it replaces running whisper.cpp in a proot'd Linux container beside
 * Medha. That arrangement cost about 3.5x realtime on CPU and a second
 * resident model of 0.9–2.1 GB, on a device with roughly 1.7 GB free with
 * Gemma loaded. The encoder that serves this endpoint is already in memory.
 *
 * ## Why the duration is parsed here and estimated in ChatRequestParser
 *
 * [ChatRequestParser] sees base64 and estimates length from the character
 * count, because decoding a megabyte to measure it would be backwards for a
 * size check. This endpoint receives the real bytes, so it reads the real
 * WAV header. A 44.1 kHz stereo clip is 5.5x shorter than the estimate
 * assumes, so the estimate would accept nearly three minutes against a
 * 30-second limit. Same status code, completely different trustworthiness.
 *
 * Android-free, so every branch runs in tools/tests/TranscriptionRequestTest.kt.
 */
object TranscriptionRequest {

    /** Response shapes Medha implements. */
    val FORMATS = setOf("json", "text", "verbose_json")

    /**
     * Formats OpenAI defines that Medha deliberately does not implement.
     *
     * Named separately so the error can say "not implemented here" rather
     * than "unknown": a client asking for subtitles has made a reasonable
     * request of a real API, and deserves better than being told its
     * spelling is wrong. Both need per-segment timestamps, which the engine
     * does not expose.
     */
    val UNIMPLEMENTED_FORMATS = setOf("srt", "vtt")

    const val DEFAULT_FORMAT = "json"

    /**
     * Hard byte ceiling on an upload, independent of the duration check.
     *
     * 30 seconds of 48 kHz 16-bit stereo is about 5.8 MB; 10 MB leaves room
     * for a generous header and an unusual-but-legitimate sample rate while
     * still refusing a file that would be read into memory on a phone with
     * the model resident. Checked before parsing, because the parse itself
     * needs the bytes in hand.
     */
    const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

    data class Parsed(
        val responseFormat: String,
        /** ISO-639-1 hint, lowercased, or null for autodetect. */
        val language: String?,
        /** Caller-supplied biasing prompt, or null. */
        val prompt: String?,
        val temperature: Double?
    )

    class Invalid(val status: Int, val code: String, override val message: String) :
        Exception(message)

    /**
     * Validates the non-file form fields.
     *
     * `model` is accepted and ignored: OpenAI requires it, every client
     * sends it, and Medha serves whatever single model is loaded. Rejecting
     * a mismatch would break clients for no gain, and silently pretending to
     * honour it would be worse — /health and the response's `model` field
     * both report what actually ran.
     */
    fun parseFields(fields: Map<String, String>): Parsed {
        val raw = fields["response_format"]?.trim()?.lowercase().orEmpty()
        val format = when {
            raw.isEmpty() -> DEFAULT_FORMAT
            raw in FORMATS -> raw
            raw in UNIMPLEMENTED_FORMATS -> throw Invalid(
                422, "response_format_unsupported",
                "response_format '$raw' needs per-segment timestamps, which this " +
                    "engine does not expose; use ${FORMATS.joinToString(", ")}"
            )
            else -> throw Invalid(
                400, "bad_request",
                "unknown response_format '$raw'; expected ${FORMATS.joinToString(", ")}"
            )
        }

        val language = fields["language"]?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "auto" }
        if (language != null && !language.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?"))) {
            throw Invalid(
                400, "bad_request",
                "language must be an ISO-639 code such as 'te' or 'en', not '$language'"
            )
        }

        val temperature = fields["temperature"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull()
                ?: throw Invalid(400, "bad_request", "temperature must be a number")
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw Invalid(400, "bad_request", "temperature must be between 0 and 2")
        }

        return Parsed(
            responseFormat = format,
            language = language,
            prompt = fields["prompt"]?.trim()?.takeIf { it.isNotEmpty() },
            temperature = temperature
        )
    }

    /**
     * Checks an upload and returns its real duration in seconds.
     *
     * Accepts raw PCM as well as WAV — [Wav.ensureWav] is applied by the
     * caller before this, so anything arriving here that is still not a
     * parseable WAV genuinely is not audio Medha can read.
     */
    fun validateAudio(bytes: ByteArray, maxSeconds: Double): Double {
        if (bytes.isEmpty()) throw Invalid(400, "bad_request", "file is empty")
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw Invalid(
                413, "file_too_large",
                "upload is ${bytes.size / 1024} KB; the limit is " +
                    "${MAX_UPLOAD_BYTES / 1024} KB"
            )
        }
        val format = Wav.parse(bytes) ?: throw Invalid(
            415, "audio_format_unsupported",
            "could not read the audio; send a WAV, or raw 16-bit PCM with " +
                "Content-Type audio/pcm. Compressed formats (mp3, m4a, ogg, flac) " +
                "are not decoded on device"
        )
        if (format.seconds > maxSeconds) {
            throw Invalid(
                413, "audio_too_long",
                "audio is ${"%.1f".format(format.seconds)}s; the limit is " +
                    "${maxSeconds.toInt()}s per request — split longer recordings into chunks"
            )
        }
        return format.seconds
    }

    /**
     * Builds the instruction that turns a general multimodal model into a
     * transcriber.
     *
     * The model is not a dedicated ASR system; it will happily answer a
     * question about the audio, summarise it, or translate it, and which of
     * those it does depends entirely on what it is told. "Transcribe"
     * without further qualification produces summaries often enough to be a
     * real bug, hence the explicit instruction not to.
     *
     * Naming the language matters most for Telugu: left to autodetect, the
     * model will sometimes transliterate into Latin script rather than
     * writing Telugu, which is a different and much less useful output.
     */
    fun instruction(language: String?, prompt: String?): String = buildString {
        append("Transcribe the audio exactly as spoken. ")
        append("Write only the words that were said. ")
        append("Do not summarise, translate, explain, or add commentary. ")
        if (language != null) {
            append("The audio is in ${languageName(language)}; ")
            append("write the transcript in ${scriptFor(language)}. ")
        }
        if (!prompt.isNullOrBlank()) {
            // Placed after the instruction and clearly labelled so a biasing
            // hint ("names: Spock, Medha") cannot read as the thing to
            // transcribe.
            append("Context that may help with spelling and names: ")
            append(prompt.trim())
            if (!prompt.trim().endsWith('.')) append('.')
            append(' ')
        }
        append("Transcript:")
    }

    /**
     * A readable language name for the few codes that actually come up,
     * falling back to the code itself.
     *
     * Deliberately short rather than a full ISO-639 table: a wrong name is
     * worse than a bare code, and the model understands "te" poorly but
     * "Telugu" well.
     */
    fun languageName(code: String): String = when (code.substringBefore('-')) {
        "te" -> "Telugu"
        "hi" -> "Hindi"
        "ta" -> "Tamil"
        "kn" -> "Kannada"
        "ml" -> "Malayalam"
        "mr" -> "Marathi"
        "bn" -> "Bengali"
        "gu" -> "Gujarati"
        "pa" -> "Punjabi"
        "ur" -> "Urdu"
        "en" -> "English"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "ja" -> "Japanese"
        "zh" -> "Chinese"
        "ar" -> "Arabic"
        else -> code
    }

    /** The script a transcript in [code] should be written in. */
    fun scriptFor(code: String): String = when (code.substringBefore('-')) {
        "te" -> "the Telugu script"
        "hi", "mr" -> "the Devanagari script"
        "ta" -> "the Tamil script"
        "kn" -> "the Kannada script"
        "ml" -> "the Malayalam script"
        "bn" -> "the Bengali script"
        "gu" -> "the Gujarati script"
        "pa" -> "the Gurmukhi script"
        "ur", "ar" -> "the Arabic script"
        "ja" -> "Japanese script"
        "zh" -> "Chinese characters"
        else -> "${languageName(code)}'s normal writing system"
    }

    /**
     * Strips the scaffolding a chat-tuned model wraps around a transcript.
     *
     * Even with the instruction above, the model intermittently answers
     * with `Transcript: "…"` or opens with "Sure, here is the transcript:".
     * A client that asked for text and got a sentence of preamble has to
     * strip it itself or store it, and every client would write this same
     * function slightly differently.
     */
    fun cleanTranscript(raw: String): String {
        var s = raw.trim()

        // Leading conversational preamble, up to the first colon, but only
        // when the prefix is short and really is scaffolding.
        //
        // A bare `the` was in this alternation and had to come out: it
        // turned the legitimate transcript "The meeting is at 3:" into
        // nothing, because "the" + 16 characters + a colon matches. Only
        // `the transcript/audio/text/recording` is scaffolding; `the`
        // alone is just a word. Likewise an arbitrary sentence containing a
        // colon ("He said: hello") must survive untouched.
        val preamble = Regex(
            """^((sure|okay|ok|certainly|here'?s|here is)\b[^:\n]{0,60}""" +
                """|the (transcript|audio|text|recording)\b[^:\n]{0,40}):\s*""",
            RegexOption.IGNORE_CASE
        )
        s = s.replace(preamble, "")

        s = s.removePrefix("Transcript:").removePrefix("transcript:").trim()

        // Surrounding quotes, only when BOTH ends have them: a transcript
        // that opens with a quoted phrase and does not close it must not
        // lose its first character.
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            val pairs = listOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')
            if (pairs.any { it.first == first && it.second == last }) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }
}
