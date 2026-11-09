// Executable tests for the OpenAI-shaped chat request parser, including the
// multimodal content-part form the hg_agent client sends.
//
//     kotlinc tools/tests/stubs/org/json/Stubs.kt \
//             app/src/main/java/com/adabala/medha/oai/ChatRequestParser.kt \
//             tools/tests/ChatRequestParserTest.kt -include-runtime -d /tmp/c.jar
//     java -jar /tmp/c.jar
//
// WHY: before this parser, `content` was typed String. The array form threw,
// StatusPages turned the throw into a 500, and the client's documented
// fallback is "4xx means this build has no vision/audio". A 500 reads as a
// broken server, so the agent could not degrade — it would simply fail.
//
// The payloads below are copied from the integration spec rather than
// invented, so "it parses" means "it parses what is actually sent".

import com.adabala.medha.oai.ChatRequestParser
import com.adabala.medha.oai.ChatRequestParser.Invalid

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

fun invalid(what: String, body: String, status: Int, code: String? = null) {
    checks++
    try {
        ChatRequestParser.parse(body)
        println("  FAIL  $what (parsed instead of rejecting)")
        failures++
    } catch (e: Invalid) {
        when {
            e.status != status ->
                { println("  FAIL  $what (status ${e.status}, expected $status)"); failures++ }
            code != null && e.code != code ->
                { println("  FAIL  $what (code '${e.code}', expected '$code')"); failures++ }
            else -> println("  ok    $what  [${e.status} ${e.code}]")
        }
    } catch (e: Exception) {
        println("  FAIL  $what (threw ${e::class.simpleName} instead of Invalid)")
        failures++
    }
}

/** base64 of n zero bytes, so byte-length maths can be asserted exactly. */
fun b64(n: Int): String {
    val tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder()
    var remaining = n
    while (remaining >= 3) { sb.append(tbl[0]).append(tbl[0]).append(tbl[0]).append(tbl[0]); remaining -= 3 }
    when (remaining) {
        1 -> sb.append(tbl[0]).append(tbl[0]).append("==")
        2 -> sb.append(tbl[0]).append(tbl[0]).append(tbl[0]).append("=")
    }
    return sb.toString()
}

fun main() {

    // ------------------------------------------------- the plain string form
    println("\nChatRequestParser — the existing string form still works")
    val plain = ChatRequestParser.parse(
        """{"messages":[{"role":"system","content":"Be brief."},
                        {"role":"user","content":"Hello"}],"stream":false}"""
    )
    eq("two messages", plain.messages.size, 2)
    eq("system extracted", ChatRequestParser.systemText(plain), "Be brief.")
    eq("user text", plain.messages[1].text, "Hello")
    check("not multimodal", !plain.isMultimodal)
    check("prompt ends with the assistant cue",
        ChatRequestParser.promptText(plain).endsWith("Assistant:"))
    check("system is not repeated into the prompt",
        !ChatRequestParser.promptText(plain).contains("Be brief."))

    // ------------------------------------------- the agent's exact payload
    println("\nChatRequestParser — the payload the agent actually sends")
    val agent = """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,${b64(120000)}"}},
        {"type":"input_audio","input_audio":{"data":"${b64(320000)}","format":"wav"}},
        {"type":"text","text":"Transcribe this recording exactly as spoken..."}]}],
        "stream":false,"max_tokens":300}""".replace("\n", "")
    val r = ChatRequestParser.parse(agent)
    check("parses rather than throwing", true)
    eq("one message", r.messages.size, 1)
    eq("text part extracted", r.messages[0].text, "Transcribe this recording exactly as spoken...")
    eq("one image", r.images.size, 1)
    eq("image mime", r.images[0].mimeType, "image/jpeg")
    eq("image size decoded without decoding the payload", r.images[0].bytes, 120000)
    eq("one audio", r.audio.size, 1)
    eq("audio format", r.audio[0].format, "wav")
    eq("audio duration estimated from bytes", r.audio[0].estimatedSeconds, 10.0)
    eq("max_tokens read", r.maxTokens, 300)
    check("reported as multimodal", r.isMultimodal)
    check("has images", r.hasImages)
    check("has audio", r.hasAudio)
    check("stream false", !r.stream)

    println("\nChatRequestParser — max_completion_tokens is accepted too")
    eq("newer OpenAI field name",
        ChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"x"}],"max_completion_tokens":128}"""
        ).maxTokens, 128)
    eq("absent means null",
        ChatRequestParser.parse("""{"messages":[{"role":"user","content":"x"}]}""").maxTokens, null)
    eq("zero is treated as absent, not as a cap of zero",
        ChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"x"}],"max_tokens":0}"""
        ).maxTokens, null)

    println("\nChatRequestParser — extensions the spec asks for")
    val ext = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":"x"}],"language":"TE",
            "response_format":{"type":"json_object"},"collection":"notes","ragTopK":7}"""
    )
    eq("language hint, lowercased", ext.language, "te")
    check("json_object response format seen", ext.wantsJsonObject)
    eq("RAG extension still parsed", ext.collection, "notes")
    eq("ragTopK", ext.ragTopK, 7)
    eq("ragTopK is clamped", ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":"x"}],"ragTopK":999}"""
    ).ragTopK, 20)

    // ------------------------------------------------------------ rejections
    println("\nChatRequestParser — malformed bodies are 400, never a crash")
    invalid("not JSON", "this is not json", 400, "bad_request")
    invalid("empty object", "{}", 400, "bad_request")
    invalid("no messages key", """{"stream":true}""", 400, "bad_request")
    invalid("empty messages", """{"messages":[]}""", 400, "bad_request")
    invalid("message not an object", """{"messages":[1,2]}""", 400, "bad_request")

    println("\nChatRequestParser — images")
    invalid("remote image URL", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"https://example.com/a.jpg"}}]}]}""".replace("\n", ""),
        422, "remote_image_unsupported")
    invalid("non-base64 data URL", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"data:image/jpeg,raw"}}]}]}""".replace("\n", ""),
        422, "image_encoding_unsupported")
    invalid("unsupported image type", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"data:image/tiff;base64,${b64(10)}"}}]}]}""".replace("\n", ""),
        415, "image_type_unsupported")
    invalid("oversized image", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,${b64(5 * 1024 * 1024)}"}}]}]}"""
        .replace("\n", ""), 413, "image_too_large")
    invalid("missing url", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{}}]}]}""".replace("\n", ""), 400, "bad_request")

    for (mime in listOf("image/jpeg", "image/png")) {
        val ok = ChatRequestParser.parse(
            """{"messages":[{"role":"user","content":[
               {"type":"image_url","image_url":{"url":"data:$mime;base64,${b64(1000)}"}}]}]}"""
                .replace("\n", "")
        )
        check("accepts $mime", ok.images.firstOrNull()?.mimeType == mime)
    }

    // WebP was accepted here until v0.11.0 and could never have worked:
    // LiteRT-LM's InputData.Image documents PNG and JPG only, so a WebP
    // passed this check and then failed inside the native decoder with a
    // message about neither WebP nor images. Refusing it at the edge names
    // the fix; accepting it buries it.
    invalid("webp, which the engine cannot decode", """{"messages":[{"role":"user","content":[
        {"type":"image_url","image_url":{"url":"data:image/webp;base64,${b64(1000)}"}}]}]}"""
        .replace("\n", ""), 415, "image_type_unsupported")
    eq("and is not advertised", "image/webp" in ChatRequestParser.SUPPORTED_IMAGE_TYPES, false)

    println("\nChatRequestParser — audio")
    invalid("unsupported format", """{"messages":[{"role":"user","content":[
        {"type":"input_audio","input_audio":{"data":"${b64(100)}","format":"mp3"}}]}]}""".replace("\n", ""),
        415, "audio_format_unsupported")
    // 28s is the chunk size the agent documents; 35s must be refused with a
    // message that says to chunk, not a generic error.
    invalid("over the duration cap", """{"messages":[{"role":"user","content":[
        {"type":"input_audio","input_audio":{"data":"${b64(35 * 32000)}","format":"wav"}}]}]}"""
        .replace("\n", ""), 413, "audio_too_long")
    invalid("empty audio", """{"messages":[{"role":"user","content":[
        {"type":"input_audio","input_audio":{"data":"","format":"wav"}}]}]}""".replace("\n", ""),
        400, "bad_request")

    val chunk = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":[
           {"type":"input_audio","input_audio":{"data":"${b64(28 * 32000)}","format":"wav"}}]}]}"""
            .replace("\n", "")
    )
    eq("a 28s chunk — the agent's own size — is accepted",
        chunk.audio.firstOrNull()?.estimatedSeconds, 28.0)

    // ---------------------------------------------------------- odd shapes
    println("\nChatRequestParser — shapes that must not break anything")
    val unknown = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":[
           {"type":"video_url","video_url":{"url":"data:video/mp4;base64,AAAA"}},
           {"type":"text","text":"what is this"}]}]}""".replace("\n", "")
    )
    // OpenAI keeps adding part types. A client sending one Medha has never
    // heard of alongside usable text should get an answer to the text.
    eq("an unknown part type is skipped, not fatal", unknown.messages[0].text, "what is this")
    check("and does not register as multimodal", !unknown.isMultimodal)

    val multi = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":[
           {"type":"text","text":"first"},{"type":"text","text":"second"}]}]}""".replace("\n", "")
    )
    eq("several text parts are joined with newlines", multi.messages[0].text, "first\nsecond")

    val nullContent = ChatRequestParser.parse(
        """{"messages":[{"role":"assistant","content":null},{"role":"user","content":"hi"}]}"""
    )
    eq("null content is empty text, not a rejection", nullContent.messages[0].text, "")
    check("and is dropped from the prompt rather than printed as 'null'",
        !ChatRequestParser.promptText(nullContent).contains("null"))

    val images2 = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":[
           {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,${b64(900)}"}},
           {"type":"image_url","image_url":{"url":"data:image/png;base64,${b64(900)}"}}]}]}"""
            .replace("\n", "")
    )
    eq("more than one image per request, as the spec allows", images2.images.size, 2)

    val upper = ChatRequestParser.parse(
        """{"messages":[{"role":"user","content":[
           {"type":"image_url","image_url":{"url":"DATA:IMAGE/JPEG;BASE64,${b64(50)}"}}]}]}"""
            .replace("\n", "")
    )
    eq("an uppercase data URL is normalised", upper.images.firstOrNull()?.mimeType, "image/jpeg")

    println("\nChatRequestParser.base64Length")
    eq("exact multiple of 3", ChatRequestParser.base64Length(b64(300)), 300)
    eq("one byte of padding", ChatRequestParser.base64Length(b64(301)), 301)
    eq("two bytes of padding", ChatRequestParser.base64Length(b64(302)), 302)
    eq("empty", ChatRequestParser.base64Length(""), 0)
    // Some clients wrap long base64 across lines.
    eq("whitespace is ignored", ChatRequestParser.base64Length("AAAA\n AAAA"), 6)

    println("\n--------------------------------------------------")
    if (failures == 0) {
        println("ChatRequestParserTest: all $checks checks passed")
    } else {
        println("ChatRequestParserTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
}
