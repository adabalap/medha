package com.adabala.medha.oai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses an OpenAI-shaped `/v1/chat/completions` body, including the
 * multimodal content-part form.
 *
 * ## Why this is not kotlinx.serialization
 *
 * The existing `OAChatRequest` types `content` as a `String`. OpenAI allows
 * it to be *either* a string or an array of typed parts, and the hg_agent
 * client sends the array form for photos and voice:
 *
 * ```json
 * {"messages":[{"role":"user","content":[
 *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}},
 *   {"type":"input_audio","input_audio":{"data":"<base64 wav>","format":"wav"}},
 *   {"type":"text","text":"Transcribe this..."}]}]}
 * ```
 *
 * Against a `String` field that throws, and StatusPages turns the throw into
 * a **500**. That is the worst possible answer: the client's documented
 * fallback is "4xx means this build has no vision/audio", so a 500 reads as
 * a broken server rather than an unsupported feature, and the agent cannot
 * degrade. Fixing it inside the serializable types would mean a custom
 * polymorphic serializer, which cannot be compiled or run outside a full
 * Android build.
 *
 * `org.json` is a plain Java library, so this file compiles and runs off
 * device and the parsing is covered by real assertions rather than reasoning
 * — the same reason BriefJson was split out. See tools/tests/ChatRequestParserTest.kt.
 *
 * This only *parses and validates*. Whether Medha can actually run a vision
 * or audio request is [com.adabala.medha.LlmEngine]'s business; this reports
 * what was asked for so the caller can answer accurately.
 */
object ChatRequestParser {

    /** An image part the caller supplied. */
    data class ImagePart(
        /** Declared MIME type, lowercased, e.g. `image/jpeg`. */
        val mimeType: String,
        /** Decoded byte length. */
        val bytes: Int,
        /** The base64 payload, still encoded. */
        val base64: String
    )

    /** An audio part the caller supplied. */
    data class AudioPart(
        /** Declared container, lowercased, e.g. `wav`. */
        val format: String,
        val bytes: Int,
        val base64: String,
        /**
         * Rough duration from the byte count, assuming the documented
         * 16 kHz mono 16-bit PCM. Only used to reject obviously oversized
         * clips before any decoding happens, so an estimate is the right
         * tool — a real parse would mean decoding a megabyte of base64 to
         * find out it is too long.
         */
        val estimatedSeconds: Double
    )

    data class Message(
        val role: String,
        val text: String,
        val images: List<ImagePart> = emptyList(),
        val audio: List<AudioPart> = emptyList()
    )

    data class Request(
        val model: String?,
        val messages: List<Message>,
        val stream: Boolean,
        val maxTokens: Int?,
        /** Medha extension, see OAChatRequest. */
        val collection: String?,
        val ragTopK: Int,
        /** `"te"`, `"en"` or `"auto"`; a hint for audio, never a guarantee. */
        val language: String?,
        /** True when `response_format` asked for a JSON object. */
        val wantsJsonObject: Boolean
    ) {
        val hasImages: Boolean get() = messages.any { it.images.isNotEmpty() }
        val hasAudio: Boolean get() = messages.any { it.audio.isNotEmpty() }
        val isMultimodal: Boolean get() = hasImages || hasAudio
        val images: List<ImagePart> get() = messages.flatMap { it.images }
        val audio: List<AudioPart> get() = messages.flatMap { it.audio }
    }

    /** A request Medha will not accept, with the status the caller gets. */
    class Invalid(val status: Int, val code: String, override val message: String) : Exception(message)

    /**
     * @throws Invalid with the HTTP status to return. 400 for a malformed
     *   body, 422 for a well-formed one asking for something out of range.
     *   Both are in the set the agent treats as "fall back", so a client
     *   that cannot read the body still does the right thing.
     */
    fun parse(raw: String): Request {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw Invalid(400, "bad_request", "body is not a JSON object")

        val msgs = root.optJSONArray("messages")
            ?: throw Invalid(400, "bad_request", "messages is required")
        if (msgs.length() == 0) throw Invalid(400, "bad_request", "messages must not be empty")

        val out = ArrayList<Message>(msgs.length())
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i)
                ?: throw Invalid(400, "bad_request", "messages[$i] is not an object")
            out += message(m, i)
        }

        val maxTokens = sequenceOf("max_tokens", "max_completion_tokens")
            .mapNotNull { k -> if (root.isNull(k)) null else root.optInt(k, -1).takeIf { it > 0 } }
            .firstOrNull()

        val responseFormat = root.optJSONObject("response_format")
        return Request(
            model = root.optString("model", "").ifBlank { null },
            messages = out,
            stream = root.optBoolean("stream", false),
            maxTokens = maxTokens,
            collection = root.optString("collection", "").ifBlank { null },
            ragTopK = root.optInt("ragTopK", 3).coerceIn(1, 20),
            // Accepted at the top level and per audio part; the top level
            // wins because that is where the agent sends it.
            language = root.optString("language", "").ifBlank { null }?.lowercase(),
            wantsJsonObject = responseFormat?.optString("type", "") == "json_object"
        )
    }

    private fun message(m: JSONObject, index: Int): Message {
        val role = m.optString("role", "").ifBlank { "user" }

        // The string form: `"content": "hello"`. Still the common case, and
        // must keep working exactly as before.
        if (m.opt("content") is String) {
            return Message(role, m.optString("content", ""))
        }

        val parts = m.optJSONArray("content")
            // Null content is legal in OpenAI's schema for assistant
            // messages carrying only tool calls. Treat it as empty text
            // rather than rejecting the whole request.
            ?: return Message(role, "")

        val text = StringBuilder()
        val images = mutableListOf<ImagePart>()
        val audio = mutableListOf<AudioPart>()

        for (p in 0 until parts.length()) {
            val part = parts.optJSONObject(p) ?: continue
            when (part.optString("type", "")) {
                "text" -> {
                    val t = part.optString("text", "")
                    if (t.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(t)
                    }
                }

                "image_url" -> images += image(part, index, p)

                "input_audio" -> audio += audio(part, index, p)

                // An unknown part type is skipped rather than fatal: OpenAI
                // keeps adding them, and a client sending a part Medha has
                // never heard of alongside usable text should get an answer
                // to the text.
                else -> Unit
            }
        }

        return Message(role, text.toString(), images, audio)
    }

    private fun image(part: JSONObject, mi: Int, pi: Int): ImagePart {
        val url = part.optJSONObject("image_url")?.optString("url", "").orEmpty()
        if (url.isEmpty()) {
            throw Invalid(400, "bad_request", "messages[$mi].content[$pi].image_url.url is required")
        }
        // Only data URLs. A remote http(s) URL would mean Medha fetching it,
        // and Medha does not make outbound requests -- the whole point is
        // that nothing leaves the device. Saying so beats a silent timeout.
        if (!url.startsWith("data:", ignoreCase = true)) {
            throw Invalid(
                422, "remote_image_unsupported",
                "image_url must be a data: URL; Medha does not fetch remote images, " +
                    "because nothing is supposed to leave the device"
            )
        }
        val comma = url.indexOf(',')
        if (comma < 0) throw Invalid(400, "bad_request", "malformed data URL in messages[$mi]")

        val header = url.substring(5, comma).lowercase()
        if (!header.contains(";base64")) {
            throw Invalid(422, "image_encoding_unsupported", "image data URL must be base64")
        }
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        if (mime !in SUPPORTED_IMAGE_TYPES) {
            throw Invalid(
                415, "image_type_unsupported",
                "unsupported image type '$mime'; supported: ${SUPPORTED_IMAGE_TYPES.joinToString()}"
            )
        }

        val b64 = url.substring(comma + 1)
        val bytes = base64Length(b64)
        if (bytes <= 0) throw Invalid(400, "bad_request", "image payload is empty")
        if (bytes > MAX_IMAGE_BYTES) {
            throw Invalid(
                413, "image_too_large",
                "image is ${bytes / 1024} KB; the limit is ${MAX_IMAGE_BYTES / 1024} KB " +
                    "(about 1024 px on the long side as JPEG)"
            )
        }
        return ImagePart(mime, bytes, b64)
    }

    private fun audio(part: JSONObject, mi: Int, pi: Int): AudioPart {
        val o = part.optJSONObject("input_audio")
            ?: throw Invalid(400, "bad_request", "messages[$mi].content[$pi].input_audio is required")
        val b64 = o.optString("data", "")
        if (b64.isEmpty()) throw Invalid(400, "bad_request", "input_audio.data is required")

        val format = o.optString("format", "").lowercase().ifBlank { "wav" }
        if (format !in SUPPORTED_AUDIO_FORMATS) {
            throw Invalid(
                415, "audio_format_unsupported",
                "unsupported audio format '$format'; supported: ${SUPPORTED_AUDIO_FORMATS.joinToString()}"
            )
        }

        val bytes = base64Length(b64)
        if (bytes <= 0) throw Invalid(400, "bad_request", "audio payload is empty")

        // 16 kHz mono 16-bit PCM = 32000 bytes/second, which is the format
        // the client documents sending. A WAV header is a rounding error at
        // these sizes and is not subtracted.
        val seconds = bytes / BYTES_PER_SECOND_16K_MONO_16BIT
        if (seconds > MAX_AUDIO_SECONDS) {
            throw Invalid(
                413, "audio_too_long",
                "audio is about ${"%.1f".format(seconds)}s; the limit is ${MAX_AUDIO_SECONDS}s " +
                    "per request — split longer recordings into chunks"
            )
        }
        return AudioPart(format, bytes, b64, seconds)
    }

    /**
     * Decoded byte length from a base64 string, without decoding it.
     *
     * Decoding to measure would mean allocating the whole payload just to
     * reject it, which is exactly backwards for a size check on a phone.
     * Whitespace is tolerated because some clients wrap long base64.
     */
    fun base64Length(b64: String): Int {
        var chars = 0
        var padding = 0
        for (c in b64) {
            when {
                c.isWhitespace() -> Unit
                c == '=' -> { padding++; chars++ }
                else -> chars++
            }
        }
        if (chars == 0) return 0
        return (chars / 4) * 3 - padding
    }

    /** Flattens a parsed request into the prompt text the engine takes. */
    fun promptText(req: Request): String = buildString {
        req.messages.filter { it.role != "system" }.forEach { m ->
            if (m.text.isBlank()) return@forEach
            append(m.role.replaceFirstChar { it.uppercase() }).append(": ")
            append(m.text).append('\n')
        }
        append("Assistant:")
    }

    fun systemText(req: Request): String? =
        req.messages.firstOrNull { it.role == "system" }?.text?.ifBlank { null }

    /**
     * JPEG and PNG only.
     *
     * `image/webp` was here until v0.11.0, and was never going to work:
     * LiteRT-LM's `InputData.Image` documents *"Supported format: PNG and
     * JPG"*, so a WebP would have passed this check and then failed inside
     * the native decoder with a message about neither WebP nor images.
     * Advertising a format the engine cannot read is worse than refusing
     * it — the refusal names the fix.
     */
    val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")

    /**
     * WAV, plus raw 16-bit PCM.
     *
     * The engine takes WAV only. `pcm16` is accepted because Android's
     * AudioRecord — what any on-device client actually records with —
     * produces headerless PCM, and [com.adabala.medha.audio.Wav.ensureWav]
     * puts a header on it before it reaches the engine. Advertised here
     * because that wrapping makes it true, not as an aspiration.
     */
    val SUPPORTED_AUDIO_FORMATS = setOf("wav", "pcm16")

    /**
     * 4 MB. A 1024px JPEG is a few hundred KB, so this is generous while
     * still refusing something that would be decoded into a bitmap on a
     * phone with the model already resident.
     */
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    /**
     * 30s, matching what /health advertises as max_audio_seconds. The client
     * documents chunking at about 28s, so this leaves headroom rather than
     * rejecting a chunk that is marginally over.
     */
    const val MAX_AUDIO_SECONDS = 30.0

    const val BYTES_PER_SECOND_16K_MONO_16BIT = 32000.0
}
