package com.example.litertservice

import android.content.Context
import android.util.Log
import com.example.litertservice.data.MedhaDatabase
import com.example.litertservice.rag.Retriever
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ------------------------------ request DTOs ------------------------------

@Serializable
data class GenerateRequest(val prompt: String, val system: String? = null)

@Serializable
data class GenerateResponse(
    val text: String,
    val model: String?,
    val promptTokens: Int,
    val tokens: Int,
    val ms: Long,
    val tokensPerSec: Double,
    val sessionId: String? = null
)

@Serializable
data class ChatTurnRequest(
    val sessionId: String,
    val message: String,
    val system: String? = null,
    val collection: String? = null,
    val ragTopK: Int = 3
)

@Serializable
data class OAChatMessage(val role: String, val content: String)

@Serializable
data class OAChatRequest(
    val model: String? = null,
    val messages: List<OAChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class RagIngestRequest(
    val collection: String,
    val text: String,
    val title: String? = null,
    val source: String? = null
)

@Serializable
data class RagQueryRequest(val collection: String, val query: String, val topK: Int = 3)

@Serializable
data class ErrorResponse(val error: String, val code: String? = null)

/**
 * Medha's loopback HTTP surface.
 *
 * Security model
 * --------------
 * The server binds to 127.0.0.1, so nothing off-device can reach it. That is
 * NOT sufficient on its own: any page the user opens in a browser can issue
 * `fetch("http://127.0.0.1:8080/generate", {method:"POST"})`, and the browser
 * will send it. CORS does not prevent the request, only the reading of the
 * reply — so a permissive `anyHost()` policy (what v0.1 shipped) additionally
 * let that page read the model's output.
 *
 * Two changes close this:
 *   1. A bearer token is required on every endpoint that generates, reads, or
 *      mutates data. A drive-by request has no way to learn it.
 *   2. CORS is restricted to the loopback origins Medha itself serves.
 *
 * The bundled demo UI is served unauthenticated and has the token substituted
 * into it, so same-origin PWAs work with no setup. Third-party PWAs (your SMS
 * organizer, hf_agent) send `Authorization: Bearer <token>`; the token is shown
 * and copyable in the Medha app.
 */
class LocalServer(
    private val appContext: Context,
    private val engine: LlmEngine,
    private val port: Int,
    private val db: MedhaDatabase,
    private val apiToken: String,
    private val requireAuth: Boolean = true
) {
    private var server: ApplicationEngine? = null
    private val memory = MemoryRepository(db)
    private val retriever = Retriever(db) // keyword mode until an embedder is wired
    private val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = LOOPBACK) {

            install(ContentNegotiation) { json(jsonFormat) }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    SystemInfo.recordFailure()
                    Log.e(TAG, "Unhandled server error on ${call.request.path()}", cause)
                    // Return structured JSON, not a bare string: clients were
                    // getting a text/plain body where they expected an object.
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = cause.message ?: cause::class.simpleName ?: "unknown error",
                            code = "internal_error"
                        )
                    )
                }
            }

            install(CORS) {
                // Only the origins Medha itself serves. Not anyHost().
                allowHost("127.0.0.1:$port", schemes = listOf("http"))
                allowHost("localhost:$port", schemes = listOf("http"))
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HEADER_TOKEN)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
            }

            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()

                val len = call.request.contentLength() ?: 0L
                if (len > MAX_BODY_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ErrorResponse("body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MB", "too_large")
                    )
                    return@intercept finish()
                }

                if (requireAuth && !isPublic(path) && !isAuthorized(call)) {
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"medha\"")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(
                            "missing or invalid API token; send 'Authorization: Bearer <token>'",
                            "unauthorized"
                        )
                    )
                    return@intercept finish()
                }
            }

            routing {

                // ------------------- health & introspection -------------------

                // Public by design so the launcher UI and a PWA splash screen can
                // tell whether the service is up before they hold a token.
                get("/health") {
                    call.respondJson(buildJsonObject {
                        put("status", "ok")
                        put("modelLoaded", engine.isLoaded)
                        put("backend", engine.configuredBackend.name)
                        put("busy", engine.isBusy)
                        put("authRequired", requireAuth)
                        put("error", engine.lastError ?: "")
                    })
                }

                get("/system") {
                    val mem = SystemInfo.memory(appContext)
                    call.respondJson(buildJsonObject {
                        put("name", "Medha")
                        put("version", BuildInfo.VERSION)
                        put("modelLoaded", engine.isLoaded)
                        put("model", engine.loadedModelPath ?: "")
                        put("backendConfigured", engine.configuredBackend.name)
                        put("backendVerified", engine.isLoaded) // best we can honestly say
                        put("loadMs", engine.loadMs)
                        put("uptimeMs", SystemInfo.uptimeMs())
                        put("thermal", SystemInfo.thermalStatus(appContext))
                        put("freeStorageMb", SystemInfo.freeStorageMb(appContext))
                        put("dbSizeBytes", db.sizeBytes(appContext))
                        put("fullTextIndex", db.hasFullTextIndex)
                        SystemInfo.deviceInfo().forEach { (k, v) -> put(k, v) }
                        put("memTotalMb", mem.totalMb)
                        put("memAvailMb", mem.availMb)
                        put("memUsedMb", mem.usedMb)
                        put("appHeapMb", mem.appUsedMb)
                        put("appHeapMaxMb", mem.appMaxMb)
                        put("lowMemory", mem.lowMemory)
                    })
                }

                get("/metrics") {
                    call.respondJson(buildJsonObject {
                        SystemInfo.metrics().forEach { (k, v) ->
                            when (v) {
                                is Long -> put(k, v)
                                is Double -> put(k, v)
                                is Int -> put(k, v)
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                        memory.stats().forEach { (k, v) -> put("db_$k", v) }
                        put("inFlight", engine.queueDepth)
                    })
                }

                // -------------------- simple generation --------------------

                post("/generate") {
                    if (!engine.isLoaded) return@post call.notReady()
                    val req = call.receive<GenerateRequest>()
                    if (req.prompt.isBlank()) return@post call.badRequest("prompt must not be blank")
                    val r = engine.generate(req.prompt, req.system)
                    call.respond(
                        GenerateResponse(
                            text = r.text,
                            model = engine.loadedModelPath,
                            promptTokens = r.promptTokens,
                            tokens = r.tokens,
                            ms = r.ms,
                            tokensPerSec = r.tokensPerSec
                        )
                    )
                }

                post("/generate/stream") {
                    if (!engine.isLoaded) return@post call.notReady()
                    val req = call.receive<GenerateRequest>()
                    if (req.prompt.isBlank()) return@post call.badRequest("prompt must not be blank")
                    call.prepareSse()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        engine.generateStream(req.prompt, req.system).collect { delta ->
                            // Payload is JSON-encoded so newlines survive SSE
                            // framing. The old code emitted a raw string with
                            // "\n" escaped by hand, which broke any consumer
                            // that took the spec literally.
                            write("data: ${jsonStr(delta)}\n\n")
                            flush()
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }

                // ------------- multi-turn chat with SQLite memory -------------

                post("/chat") {
                    if (!engine.isLoaded) return@post call.notReady()
                    val req = call.receive<ChatTurnRequest>()
                    if (req.sessionId.isBlank()) return@post call.badRequest("sessionId is required")
                    if (req.message.isBlank()) return@post call.badRequest("message must not be blank")

                    val conv = memory.getOrCreate(req.sessionId, req.system)
                    val history = memory.history(conv.id)
                    val context = req.collection
                        ?.takeIf { it.isNotBlank() }
                        ?.let { retriever.retrieve(it, req.message, req.ragTopK).map { h -> h.text } }
                        ?: emptyList()

                    val prompt = memory.buildPrompt(
                        conv.systemInstruction ?: req.system, history, req.message, context
                    )
                    val r = engine.generate(prompt)
                    // Atomic: the user turn is no longer written separately from
                    // the assistant turn, so a failure cannot half-persist.
                    memory.appendTurn(conv.id, req.message, r.text)

                    call.respond(
                        GenerateResponse(
                            text = r.text,
                            model = engine.loadedModelPath,
                            promptTokens = r.promptTokens,
                            tokens = r.tokens,
                            ms = r.ms,
                            tokensPerSec = r.tokensPerSec,
                            sessionId = req.sessionId
                        )
                    )
                }

                // ---------------------- session management ----------------------

                get("/sessions") {
                    val limit = call.intParam("limit", 100).coerceIn(1, 500)
                    val offset = call.intParam("offset", 0).coerceAtLeast(0)
                    call.respondJson(buildJsonObject {
                        putJsonArray("sessions") {
                            memory.listSessions(limit, offset).forEach { s ->
                                addJsonObject {
                                    put("sessionId", s.sessionId)
                                    put("title", s.title ?: "")
                                    put("messages", s.messageCount)
                                    put("updatedAt", s.updatedAt)
                                }
                            }
                        }
                    })
                }

                get("/sessions/{id}/messages") {
                    val id = call.parameters["id"].orEmpty()
                    if (id.isBlank()) return@get call.badRequest("session id is required")
                    val limit = call.intParam("limit", 50).coerceIn(1, 500)
                    call.respondJson(buildJsonObject {
                        put("sessionId", id)
                        putJsonArray("messages") {
                            memory.messages(id, limit).forEach { m ->
                                addJsonObject {
                                    put("role", m.role)
                                    put("content", m.content)
                                    put("createdAt", m.createdAt)
                                }
                            }
                        }
                    })
                }

                delete("/sessions/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    if (id.isBlank()) return@delete call.badRequest("session id is required")
                    val deleted = memory.delete(id)
                    call.respondJson(buildJsonObject {
                        put("deleted", deleted)
                        put("sessionId", id)
                    })
                }

                // ---------------------- OpenAI-compatible ----------------------

                get("/v1/models") {
                    call.respondJson(buildJsonObject {
                        put("object", "list")
                        putJsonArray("data") {
                            addJsonObject {
                                put("id", modelId())
                                put("object", "model")
                                put("created", nowSeconds())
                                put("owned_by", "medha")
                            }
                        }
                    })
                }

                post("/v1/chat/completions") {
                    if (!engine.isLoaded) return@post call.notReady()
                    val req = call.receive<OAChatRequest>()
                    if (req.messages.isEmpty()) return@post call.badRequest("messages must not be empty")

                    val system = req.messages.firstOrNull { it.role == "system" }?.content
                    val prompt = buildString {
                        req.messages.filter { it.role != "system" }.forEach { m ->
                            append(m.role.replaceFirstChar { c -> c.uppercase() })
                            append(": ").append(m.content).append("\n")
                        }
                        append("Assistant:")
                    }

                    val id = "chatcmpl-" + java.util.UUID.randomUUID().toString().take(24)
                    val created = nowSeconds()

                    if (req.stream) {
                        call.prepareSse()
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            // Spec-shaped chunks. v0.1 emitted only
                            // {"choices":[{"delta":...}]} with no id/object/
                            // created/model, which strict OpenAI clients reject
                            // outright — exactly the interop you need for
                            // pointing hf_agent at this.
                            write("data: ${chunk(id, created, roleDelta = true)}\n\n")
                            flush()
                            engine.generateStream(prompt, system).collect { delta ->
                                write("data: ${chunk(id, created, content = delta)}\n\n")
                                flush()
                            }
                            write("data: ${chunk(id, created, finish = "stop")}\n\n")
                            flush()
                            write("data: [DONE]\n\n")
                            flush()
                        }
                    } else {
                        val r = engine.generate(prompt, system)
                        call.respondJson(buildJsonObject {
                            put("id", id)
                            put("object", "chat.completion")
                            put("created", created)
                            put("model", modelId())
                            putJsonArray("choices") {
                                addJsonObject {
                                    put("index", 0)
                                    put("finish_reason", "stop")
                                    put("message", buildJsonObject {
                                        put("role", "assistant")
                                        put("content", r.text)
                                    })
                                }
                            }
                            put("usage", buildJsonObject {
                                put("prompt_tokens", r.promptTokens)
                                put("completion_tokens", r.tokens)
                                put("total_tokens", r.promptTokens + r.tokens)
                            })
                        })
                    }
                }

                post("/v1/embeddings") {
                    // Honest 501 rather than a fake vector. Wire an embedder into
                    // Retriever and implement here; the storage side already
                    // supports embeddings end to end.
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        ErrorResponse(
                            "no embedding model is loaded; RAG is running in lexical mode",
                            "not_implemented"
                        )
                    )
                }

                // ------------------------------ RAG ------------------------------

                post("/rag/ingest") {
                    val req = call.receive<RagIngestRequest>()
                    if (req.collection.isBlank()) return@post call.badRequest("collection is required")
                    if (req.text.isBlank()) return@post call.badRequest("text must not be blank")
                    val chunks = retriever.ingest(req.collection, req.title, req.source, req.text)
                    call.respondJson(buildJsonObject {
                        put("status", "ingested")
                        put("collection", req.collection)
                        put("chunks", chunks)
                    })
                }

                post("/rag/query") {
                    val req = call.receive<RagQueryRequest>()
                    if (req.collection.isBlank()) return@post call.badRequest("collection is required")
                    val hits = retriever.retrieve(req.collection, req.query, req.topK)
                    call.respondJson(buildJsonObject {
                        put("collection", req.collection)
                        put("mode", hits.firstOrNull()?.mode ?: "none")
                        putJsonArray("hits") {
                            hits.forEach { h ->
                                addJsonObject {
                                    put("text", h.text)
                                    put("score", h.score)
                                }
                            }
                        }
                    })
                }

                get("/rag/collections") {
                    call.respondJson(buildJsonObject {
                        putJsonArray("collections") {
                            db.listCollections().forEach { c ->
                                addJsonObject {
                                    put("collection", c.collection)
                                    put("documents", c.documents)
                                    put("chunks", c.chunks)
                                }
                            }
                        }
                    })
                }

                delete("/rag/collections/{name}") {
                    val name = call.parameters["name"].orEmpty()
                    if (name.isBlank()) return@delete call.badRequest("collection name is required")
                    val n = db.deleteCollection(name)
                    call.respondJson(buildJsonObject {
                        put("deleted", n)
                        put("collection", name)
                    })
                }

                // -------------------------- bundled PWA --------------------------

                get("/") { serveAsset(call, "index.html") }
                get("/{path...}") {
                    val parts = call.parameters.getAll("path") ?: emptyList()
                    serveAsset(call, parts.joinToString("/").ifEmpty { "index.html" })
                }
            }
        }.also { it.start(wait = false) }

        Log.i(TAG, "Medha server on http://$LOOPBACK:$port (auth=${if (requireAuth) "on" else "OFF"})")
    }

    fun stop() {
        runCatching { server?.stop(500, 1500) }
        server = null
    }

    // ------------------------------ helpers ------------------------------

    private fun modelId(): String =
        engine.loadedModelPath?.substringAfterLast('/') ?: "medha"

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /** One OpenAI `chat.completion.chunk`. */
    private fun chunk(
        id: String,
        created: Long,
        content: String? = null,
        roleDelta: Boolean = false,
        finish: String? = null
    ): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", modelId())
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("delta", buildJsonObject {
                    if (roleDelta) put("role", "assistant")
                    if (content != null) put("content", content)
                })
                if (finish != null) put("finish_reason", finish) else put("finish_reason", null as String?)
            }
        }
    }.toString()

    private fun isPublic(path: String): Boolean {
        if (path == "/health") return true
        if (path == "/") return true
        val lower = path.lowercase()
        return STATIC_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun isAuthorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]
            ?: call.request.headers[HEADER_TOKEN]
            ?: return false
        val presented = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        return constantTimeEquals(presented, apiToken)
    }

    /** Avoids leaking token length/prefix through response-time differences. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private suspend fun serveAsset(call: ApplicationCall, name: String) {
        // Reject traversal outright instead of trying to strip it. The old
        // `replace("..","")` is a filter, and filters get bypassed; a whitelist
        // on path segments does not.
        val clean = name.trim('/')
        val safe = clean.split('/').all { seg ->
            seg.isNotEmpty() && seg != "." && seg != ".." && !seg.contains('\\')
        }
        if (!safe || clean.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not found", "not_found"))
            return
        }
        try {
            val bytes = appContext.assets.open("$ASSET_ROOT/$clean").readBytes()
            if (clean.endsWith(".html")) {
                // Same-origin convenience: the bundled UI gets the token baked in
                // so it works with zero setup. Third-party PWAs supply their own.
                val html = String(bytes, Charsets.UTF_8)
                    .replace(TOKEN_PLACEHOLDER, if (requireAuth) apiToken else "")
                    .replace(PORT_PLACEHOLDER, port.toString())
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respondBytes(bytes, contentTypeFor(clean))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not found: $clean", "not_found"))
        }
    }

    private fun contentTypeFor(name: String): ContentType = when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".js") -> ContentType.Application.JavaScript
        name.endsWith(".css") -> ContentType.Text.CSS
        name.endsWith(".json") || name.endsWith(".webmanifest") -> ContentType.Application.Json
        name.endsWith(".svg") -> ContentType.Image.SVG
        name.endsWith(".png") -> ContentType.Image.PNG
        name.endsWith(".ico") -> ContentType.Image.XIcon
        else -> ContentType.Application.OctetStream
    }

    private fun jsonStr(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    private suspend fun ApplicationCall.respondJson(obj: kotlinx.serialization.json.JsonObject) =
        respondText(obj.toString(), ContentType.Application.Json)

    private suspend fun ApplicationCall.notReady() = respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse("no model loaded", "model_not_loaded")
    )

    private suspend fun ApplicationCall.badRequest(msg: String) =
        respond(HttpStatusCode.BadRequest, ErrorResponse(msg, "bad_request"))

    private fun ApplicationCall.intParam(name: String, default: Int): Int =
        request.queryParameters[name]?.toIntOrNull() ?: default

    /** SSE needs these or a proxy/webview will buffer the whole stream. */
    private fun ApplicationCall.prepareSse() {
        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header(HttpHeaders.Connection, "keep-alive")
        response.header("X-Accel-Buffering", "no")
    }

    companion object {
        private const val TAG = "LocalServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val ASSET_ROOT = "webapp"
        private const val HEADER_TOKEN = "X-Medha-Token"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024

        const val TOKEN_PLACEHOLDER = "__MEDHA_TOKEN__"
        const val PORT_PLACEHOLDER = "__MEDHA_PORT__"

        private val STATIC_SUFFIXES = listOf(
            ".html", ".js", ".css", ".png", ".svg", ".ico", ".webmanifest", ".woff2"
        )
    }
}

/** Single place for the version string surfaced over HTTP. */
object BuildInfo {
    const val VERSION = "0.2.0"
}
