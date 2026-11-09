// Executable tests for POST /v1/audio/transcriptions' validation,
// instruction building and output cleanup.
//
//     kotlinc app/src/main/java/com/adabala/medha/audio/Wav.kt \
//             app/src/main/java/com/adabala/medha/oai/TranscriptionRequest.kt \
//             tools/tests/TranscriptionRequestTest.kt -include-runtime -d /tmp/t.jar
//     java -jar /tmp/t.jar
//
// This endpoint is what replaces whisper.cpp for hg_agent, so its failure
// modes are somebody else's debugging session. The ones worth pinning:
//
//  - the duration limit must be read from the WAV header, not estimated,
//    or a 44.1k stereo clip sails past a 30s limit at nearly three minutes
//  - srt/vtt must say "not implemented here", not "unknown format": a
//    client asking for subtitles made a valid request of a real API
//  - cleanTranscript must not eat real transcript text while stripping
//    the model's conversational scaffolding

import com.adabala.medha.audio.Wav
import com.adabala.medha.oai.TranscriptionRequest
import com.adabala.medha.oai.TranscriptionRequest.Invalid

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

/** Runs [body], expecting an Invalid with [status] and [code]. */
fun invalid(what: String, status: Int, code: String, body: () -> Unit) {
    checks++
    try {
        body()
        println("  FAIL  $what — expected Invalid($status, $code), nothing thrown")
        failures++
    } catch (e: Invalid) {
        if (e.status == status && e.code == code) println("  ok    $what")
        else {
            println("  FAIL  $what\n          expected: $status/$code\n          actual:   ${e.status}/${e.code}")
            failures++
        }
    } catch (e: Throwable) {
        println("  FAIL  $what — threw ${e.javaClass.simpleName} instead of Invalid")
        failures++
    }
}

fun main() {
    println("TranscriptionRequest — field defaults")
    val d = TranscriptionRequest.parseFields(emptyMap())
    eq("format defaults to json", d.responseFormat, "json")
    eq("no language", d.language, null)
    eq("no prompt", d.prompt, null)
    eq("no temperature", d.temperature, null)

    println("\nTranscriptionRequest — model is accepted and ignored")
    // Every OpenAI client sends it; rejecting a mismatch would break them
    // for no gain, since Medha serves whatever single model is loaded.
    val m = TranscriptionRequest.parseFields(mapOf("model" to "whisper-1"))
    eq("whisper-1 does not throw", m.responseFormat, "json")
    val m2 = TranscriptionRequest.parseFields(mapOf("model" to "gpt-4o-transcribe"))
    eq("nor does any other name", m2.responseFormat, "json")

    println("\nTranscriptionRequest — response_format")
    for (f in listOf("json", "text", "verbose_json")) {
        eq("accepts $f", TranscriptionRequest.parseFields(mapOf("response_format" to f)).responseFormat, f)
    }
    eq("case-insensitive",
        TranscriptionRequest.parseFields(mapOf("response_format" to "TEXT")).responseFormat, "text")
    eq("trims",
        TranscriptionRequest.parseFields(mapOf("response_format" to "  json ")).responseFormat, "json")
    eq("empty falls back to the default",
        TranscriptionRequest.parseFields(mapOf("response_format" to "")).responseFormat, "json")

    // The distinction that matters: srt is a real OpenAI format Medha does
    // not implement (422), "jsonn" is a typo (400). Collapsing both to 400
    // tells a subtitle client its spelling is wrong, which it is not.
    invalid("srt is 422 unsupported, not 400 unknown", 422, "response_format_unsupported") {
        TranscriptionRequest.parseFields(mapOf("response_format" to "srt"))
    }
    invalid("vtt likewise", 422, "response_format_unsupported") {
        TranscriptionRequest.parseFields(mapOf("response_format" to "vtt"))
    }
    invalid("a typo is 400", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("response_format" to "jsonn"))
    }

    println("\nTranscriptionRequest — language")
    eq("te", TranscriptionRequest.parseFields(mapOf("language" to "te")).language, "te")
    eq("uppercased is normalised",
        TranscriptionRequest.parseFields(mapOf("language" to "TE")).language, "te")
    eq("regional tag kept",
        TranscriptionRequest.parseFields(mapOf("language" to "en-us")).language, "en-us")
    eq("'auto' means no hint",
        TranscriptionRequest.parseFields(mapOf("language" to "auto")).language, null)
    eq("empty means no hint",
        TranscriptionRequest.parseFields(mapOf("language" to "  ")).language, null)
    invalid("a sentence is not a language code", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("language" to "telugu please"))
    }
    invalid("a single letter is not either", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("language" to "t"))
    }

    println("\nTranscriptionRequest — temperature")
    eq("parsed", TranscriptionRequest.parseFields(mapOf("temperature" to "0.4")).temperature, 0.4)
    eq("zero is allowed", TranscriptionRequest.parseFields(mapOf("temperature" to "0")).temperature, 0.0)
    invalid("not a number", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("temperature" to "warm"))
    }
    invalid("below range", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("temperature" to "-0.1"))
    }
    invalid("above range", 400, "bad_request") {
        TranscriptionRequest.parseFields(mapOf("temperature" to "2.1"))
    }

    println("\nTranscriptionRequest — audio validation reads the real header")
    val tenSeconds = Wav.wrapPcm(ByteArray(320000))           // 16k mono 16-bit
    eq("10s accepted", TranscriptionRequest.validateAudio(tenSeconds, 30.0), 10.0)

    invalid("empty file", 400, "bad_request") {
        TranscriptionRequest.validateAudio(ByteArray(0), 30.0)
    }
    invalid("not audio at all", 415, "audio_format_unsupported") {
        TranscriptionRequest.validateAudio("this is an mp3, honest".toByteArray(), 30.0)
    }
    invalid("over the duration limit", 413, "audio_too_long") {
        TranscriptionRequest.validateAudio(Wav.wrapPcm(ByteArray(32000 * 31)), 30.0)
    }
    invalid("over the byte ceiling", 413, "file_too_large") {
        TranscriptionRequest.validateAudio(ByteArray(TranscriptionRequest.MAX_UPLOAD_BYTES + 1), 30.0)
    }

    println("\nTranscriptionRequest — the encoder's format is enforced at the edge")
    // LiteRT-LM's Gemma4DataProcessor builds its AudioPreprocessorMiniAudio
    // for 16000 Hz / 1 channel. Anything else reached the native layer and
    // came back as a bare INTERNAL naming neither figure -- which is what a
    // real device actually hit. Caught here, the message can say what to do.
    invalid("44.1 kHz stereo", 415, "audio_format_unsupported") {
        TranscriptionRequest.validateAudio(
            Wav.wrapPcm(ByteArray(176400), sampleRate = 44100, channels = 2), 30.0)
    }
    invalid("44.1 kHz mono", 415, "audio_format_unsupported") {
        TranscriptionRequest.validateAudio(
            Wav.wrapPcm(ByteArray(88200), sampleRate = 44100, channels = 1), 30.0)
    }
    invalid("16 kHz stereo", 415, "audio_format_unsupported") {
        TranscriptionRequest.validateAudio(
            Wav.wrapPcm(ByteArray(64000), sampleRate = 16000, channels = 2), 30.0)
    }
    invalid("8 kHz mono", 415, "audio_format_unsupported") {
        TranscriptionRequest.validateAudio(
            Wav.wrapPcm(ByteArray(16000), sampleRate = 8000, channels = 1), 30.0)
    }
    eq("16 kHz mono is accepted",
        TranscriptionRequest.validateAudio(Wav.wrapPcm(ByteArray(32000)), 30.0), 1.0)

    println("\nTranscriptionRequest — the format error tells you how to fix it")
    val fmtErr = runCatching {
        TranscriptionRequest.validateAudio(
            Wav.wrapPcm(ByteArray(176400), sampleRate = 44100, channels = 2), 30.0)
    }.exceptionOrNull() as Invalid
    check("names what was sent", fmtErr.message.contains("44100"))
    check("names what is required", fmtErr.message.contains("16000"))
    check("gives the ffmpeg line", fmtErr.message.contains("ffmpeg"))
    check("mentions -ar", fmtErr.message.contains("-ar 16000"))
    check("mentions -ac", fmtErr.message.contains("-ac 1"))
    // The on-device path needs no conversion at all, and saying so stops
    // someone building an ffmpeg step they never needed.
    check("says AudioRecord can capture it directly", fmtErr.message.contains("AudioRecord"))

    println("\nTranscriptionRequest — raw PCM is still wrapped as 16 kHz mono")
    // ensureWav's defaults have to match what the encoder wants, or the
    // headerless-PCM path this endpoint advertises would fail validation.
    val wrapped = Wav.ensureWav(ByteArray(32000))
    eq("wrapped PCM passes", TranscriptionRequest.validateAudio(wrapped, 30.0), 1.0)
    eq("at the required rate", Wav.parse(wrapped)?.sampleRate, TranscriptionRequest.REQUIRED_SAMPLE_RATE)
    eq("and mono", Wav.parse(wrapped)?.channels, TranscriptionRequest.REQUIRED_CHANNELS)

    // THE case for parsing over estimating. 1.5 MB of 44.1 kHz stereo is
    // about 8.7 real seconds. ChatRequestParser's 16k-mono estimate would
    // call the same bytes 46 seconds and reject it.
    println("\nTranscriptionRequest — parsing beats estimating on a non-16k clip")
    // Duration is read from the header, not guessed from the byte count.
    // Measured directly here rather than through validateAudio, because a
    // 44.1k stereo clip is now rejected on FORMAT before duration is ever
    // reached -- which is correct, and would hide the point of this test.
    val stereo = Wav.wrapPcm(ByteArray(176400 * 9), sampleRate = 44100, channels = 2)
    eq("real duration is 9s", Wav.durationSeconds(stereo), 9.0)
    val estimate = (stereo.size - 44) / 32000.0
    check("the 16k-mono estimate would have said ~49s", estimate > 45.0)
    check("and would therefore have been wrong by 5x", estimate > 30.0)

    println("\nTranscriptionRequest — the instruction steers toward transcription")
    val plain = TranscriptionRequest.instruction(null, null)
    check("says transcribe", plain.contains("Transcribe", ignoreCase = true))
    check("forbids summarising", plain.contains("summarise", ignoreCase = true))
    check("forbids translating", plain.contains("translate", ignoreCase = true))
    check("names no language when none given", !plain.contains("Telugu"))

    val telugu = TranscriptionRequest.instruction("te", null)
    check("names the language, not the code", telugu.contains("Telugu"))
    // Without this the model transliterates into Latin often enough to be
    // the difference between a usable transcript and a useless one.
    check("asks for the Telugu script", telugu.contains("Telugu script"))

    val tagged = TranscriptionRequest.instruction("te-in", null)
    check("a regional tag still resolves the language", tagged.contains("Telugu"))

    val hinted = TranscriptionRequest.instruction("en", "names: Spock, Medha")
    check("includes the hint", hinted.contains("Spock"))
    // Labelled, so a biasing hint cannot read as the text to transcribe.
    check("labels it as context", hinted.contains("Context that may help"))
    check("the hint comes after the instruction",
        hinted.indexOf("Context that may help") > hinted.indexOf("Transcribe"))
    check("ends with the cue", hinted.trimEnd().endsWith("Transcript:"))

    eq("an unknown code falls back to itself",
        TranscriptionRequest.languageName("xx"), "xx")

    println("\nTranscriptionRequest — cleanTranscript strips scaffolding")
    eq("the Transcript: prefix",
        TranscriptionRequest.cleanTranscript("Transcript: hello there"), "hello there")
    eq("a conversational preamble",
        TranscriptionRequest.cleanTranscript("Sure, here is the transcript: hello there"),
        "hello there")
    eq("'Here's the transcript:'",
        TranscriptionRequest.cleanTranscript("Here's the transcript: hello"), "hello")
    eq("'The transcript is:'",
        TranscriptionRequest.cleanTranscript("The transcript is: hello"), "hello")
    eq("matched double quotes",
        TranscriptionRequest.cleanTranscript("\"hello there\""), "hello there")
    eq("matched smart quotes",
        TranscriptionRequest.cleanTranscript("“hello there”"), "hello there")
    eq("leading and trailing whitespace",
        TranscriptionRequest.cleanTranscript("   hello   "), "hello")
    eq("both prefix and quotes",
        TranscriptionRequest.cleanTranscript("Transcript: \"hello\""), "hello")

    println("\nTranscriptionRequest — cleanTranscript does NOT eat real text")
    // The regression that forced `the` out of the preamble alternation:
    // "the" + 16 chars + ":" matched, and this transcript became "".
    eq("a sentence that happens to end in a colon",
        TranscriptionRequest.cleanTranscript("The meeting is at 3:"),
        "The meeting is at 3:")
    eq("an internal colon",
        TranscriptionRequest.cleanTranscript("He said: hello"), "He said: hello")
    eq("a transcript starting with 'The'",
        TranscriptionRequest.cleanTranscript("The quick brown fox"), "The quick brown fox")
    // Only BOTH ends: an unmatched opening quote must not lose a character.
    eq("an unmatched opening quote",
        TranscriptionRequest.cleanTranscript("\"hello there"), "\"hello there")
    eq("an unmatched closing quote",
        TranscriptionRequest.cleanTranscript("hello there\""), "hello there\"")
    eq("a quoted phrase inside a longer line",
        TranscriptionRequest.cleanTranscript("she said \"hello\" and left"),
        "she said \"hello\" and left")
    eq("empty stays empty", TranscriptionRequest.cleanTranscript(""), "")
    eq("whitespace only becomes empty", TranscriptionRequest.cleanTranscript("   \n "), "")
    // Telugu text must survive untouched — no ASCII assumptions anywhere.
    eq("Telugu passes through",
        TranscriptionRequest.cleanTranscript("నమస్కారం, ఎలా ఉన్నారు?"),
        "నమస్కారం, ఎలా ఉన్నారు?")
    eq("Telugu behind a prefix",
        TranscriptionRequest.cleanTranscript("Transcript: నమస్కారం"), "నమస్కారం")

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("TranscriptionRequestTest: all $checks checks passed")
    } else {
        println("TranscriptionRequestTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
