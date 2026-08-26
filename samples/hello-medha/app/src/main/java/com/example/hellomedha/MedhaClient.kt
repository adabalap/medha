package com.example.hellomedha

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * A complete Medha client in one file, using nothing but `HttpURLConnection`
 * and `org.json` — both in the Android platform, no Retrofit, no OkHttp, no
 * dependencies to add. Copy it into your project and change the package.
 *
 * Deliberately not clever. The point is that talking to Medha is ordinary
 * HTTP, and seeing the raw request/response shapes is more useful to someone
 * integrating than a tidy abstraction that hides them.
 */
class MedhaClient(private val baseUrl: String, private val token: String) {

    /** Thrown for any non-2xx response, carrying enough to act on. */
    class ApiException(
        val status: Int,
        message: String,
        /** Seconds the server asked us to wait, when it sent Retry-After. */
        val retryAfterSeconds: Int? = null
    ) : Exception(message) {

        /** The token is dead or revoked — re-run the handshake. */
        val isUnauthorized: Boolean get() = status == 401

        /** The token lacks a capability. Asking again will not help. */
        val isForbidden: Boolean get() = status == 403

        /** Queue full or no model loaded. Retrying later is reasonable. */
        val isTransient: Boolean get() = status == 429 || status == 503 || status == 504
    }

    /** True if a model is loaded and ready to answer. */
    fun isReady(): Boolean = try {
        JSONObject(get("/health")).optBoolean("modelLoaded", false)
    } catch (e: Exception) {
        false
    }

    /** One-shot completion. Returns the assistant's reply text. */
    fun chat(messages: List<Pair<String, String>>, collection: String? = null): String {
        val body = requestBody(messages, stream = false, collection = collection)
        val json = JSONObject(post("/v1/chat/completions", body))
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /**
     * Streaming completion. [onDelta] is called on the calling thread for each
     * token as it arrives; the full text is returned at the end.
     *
     * The buffering here is the part worth copying carefully. An SSE event is
     * terminated by a blank line, and a single `read()` from the socket is
     * under no obligation to align with that — one read can deliver half an
     * event, or two and a half events. Parsing whatever each read happens to
     * return, rather than accumulating until a complete `\n\n`-delimited
     * event is present, produces corruption that only shows up under load or
     * on a slow connection, which is the worst possible time to discover it.
     */
    fun chatStream(
        messages: List<Pair<String, String>>,
        collection: String? = null,
        onDelta: (String) -> Unit
    ): String {
        val conn = open("POST", "/v1/chat/completions")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.doOutput = true
        conn.outputStream.use {
            it.write(requestBody(messages, stream = true, collection = collection).toByteArray())
        }
        throwIfError(conn)

        val full = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            // readLine() already splits on newlines, so a blank line is the
            // event terminator. Anything that is not a `data:` line -- SSE
            // comments used as keep-alives, future field types -- is skipped
            // rather than treated as payload.
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val payload = line.substring(6)
                if (payload == "[DONE]") break
                val delta = runCatching {
                    JSONObject(payload)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                }.getOrDefault("")
                if (delta.isNotEmpty()) {
                    full.append(delta)
                    onDelta(delta)
                }
            }
        }
        conn.disconnect()
        return full.toString()
    }

    /** Ingests text into a RAG collection. Requires the `rag` capability. */
    fun ingest(collection: String, text: String, title: String? = null): Int {
        val body = JSONObject().apply {
            put("collection", collection)
            put("text", text)
            if (title != null) put("title", title)
        }.toString()
        return JSONObject(post("/rag/ingest", body)).optInt("chunks", 0)
    }

    // ------------------------------- plumbing -------------------------------

    private fun requestBody(
        messages: List<Pair<String, String>>,
        stream: Boolean,
        collection: String?
    ): String = JSONObject().apply {
        put("model", "medha")
        put("stream", stream)
        put("messages", JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        })
        // Medha extension on the OpenAI shape. Omitted entirely when unused so
        // the request stays byte-identical to a standard one.
        if (collection != null) {
            put("collection", collection)
            put("ragTopK", 3)
        }
    }.toString()

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10_000
            // Generous: a long generation on a thermally throttled phone can
            // legitimately take minutes, and a client that gives up early
            // leaves the model still working on an answer nobody will read.
            readTimeout = 180_000
        }

    private fun get(path: String): String {
        val conn = open("GET", path)
        throwIfError(conn)
        return conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    }

    private fun post(path: String, body: String): String {
        val conn = open("POST", path)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        throwIfError(conn)
        return conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    }

    private fun throwIfError(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status in 200..299) return
        // Medha's errors are JSON with an `error` field; fall back to the raw
        // body so an unexpected failure is never reported as an empty string.
        val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val message = runCatching { JSONObject(raw).getString("error") }.getOrDefault(
            raw.ifBlank { "HTTP $status" }
        )
        val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull()
        conn.disconnect()
        throw ApiException(status, message, retryAfter)
    }
}
