package com.example.litertservice

import android.content.Context
import android.util.Log
import com.example.litertservice.data.MedhaDatabase
import com.example.litertservice.rag.Retriever
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------- request/response DTOs ----------
@Serializable data class GenerateRequest(val prompt: String, val system: String? = null)
@Serializable data class GenerateResponse(val text: String, val model: String?, val tokens: Int, val ms: Long, val tokensPerSec: Double)
@Serializable data class ChatTurnRequest(val sessionId: String, val message: String, val system: String? = null, val collection: String? = null, val ragTopK: Int = 3)
@Serializable data class OAChatMessage(val role: String, val content: String)
@Serializable data class OAChatRequest(val model: String? = null, val messages: List<OAChatMessage>, val stream: Boolean = false)
@Serializable data class RagIngestRequest(val collection: String, val text: String, val title: String? = null, val source: String? = null)
@Serializable data class RagQueryRequest(val collection: String, val query: String, val topK: Int = 3)
@Serializable data class ErrorResponse(val error: String)

class LocalServer(
    private val appContext: Context,
    private val engine: LlmEngine,
    private val port: Int,
    private val db: MedhaDatabase
) {
    private var server: ApplicationEngine? = null
    private val memory = MemoryRepository(db)
    private val retriever = Retriever(db) // keyword mode until an embedder is wired

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Unhandled server error", cause)
                    call.respondText(
                        "SERVER ERROR: ${cause::class.simpleName}: ${cause.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
            install(CORS) {
                anyHost()
                allowHeader("Content-Type")
                allowHeader("Authorization")
                allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Options)
            }

            routing {
                // ---------- health & introspection ----------
                get("/health") {
                    val obj = buildJsonObject {
                        put("status", "ok")
                        put("modelLoaded", engine.isLoaded)
                        put("model", engine.loadedModelPath ?: "")
                        put("backend", engine.configuredBackend.name)
                        put("error", engine.lastError ?: "")
                    }
                    call.respondText(obj.toString(), ContentType.Application.Json)
                }

                get("/system") {
                    val mem = SystemInfo.memory(appContext)
                    val obj = buildJsonObject {
                        put("name", "Medha")
                        put("modelLoaded", engine.isLoaded)
                        put("model", engine.loadedModelPath ?: "")
                        put("backendConfigured", engine.configuredBackend.name)
                        put("backendVerified", engine.isLoaded) // best we can honestly say
                        put("loadMs", engine.loadMs)
                        SystemInfo.deviceInfo().forEach { (k, v) -> put(k, v) }
                        put("memTotalMb", mem.totalMb)
                        put("memAvailMb", mem.availMb)
                        put("memUsedMb", mem.usedMb)
                        put("appHeapMb", mem.appUsedMb)
                        put("lowMemory", mem.lowMemory)
                    }
                    call.respondText(obj.toString(), ContentType.Application.Json)
                }

                get("/metrics") {
                    val m = SystemInfo.metrics()
                    val stats = memory.stats()
                    val obj = buildJsonObject {
                        m.forEach { (k, v) ->
                            when (v) {
                                is Long -> put(k, v)
                                is Double -> put(k, v)
                                is Int -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                        stats.forEach { (k, v) -> put("db_$k", v) }
                    }
                    call.respondText(obj.toString(), ContentType.Application.Json)
                }

                // ---------- simple generation ----------
                post("/generate") {
                    if (!engine.isLoaded) return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("no model loaded"))
                    val req = call.receive<GenerateRequest>()
                    val r = engine.generate(req.prompt, req.system)
                    call.respond(GenerateResponse(r.text, engine.loadedModelPath, r.tokens, r.ms,
                        if (r.ms > 0) r.tokens * 1000.0 / r.ms else 0.0))
                }

                post("/generate/stream") {
                    if (!engine.isLoaded) return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("no model loaded"))
                    val req = call.receive<GenerateRequest>()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        engine.generateStream(req.prompt).collect { delta ->
                            write("data: ${escapeSse(delta)}\n\n"); flush()
                        }
                        write("data: [DONE]\n\n"); flush()
                    }
                }

                // ---------- multi-turn chat with SQLite memory ----------
                post("/chat") {
                    if (!engine.isLoaded) return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("no model loaded"))
                    val req = call.receive<ChatTurnRequest>()
                    val conv = memory.getOrCreate(req.sessionId, req.system)
                    val history = memory.history(conv.id)
                    val context = if (req.collection != null)
                        retriever.retrieve(req.collection, req.message, req.ragTopK).map { it.text }
                    else emptyList()
                    val prompt = memory.buildPrompt(conv.systemInstruction ?: req.system, history, req.message, context)
                    val r = engine.generate(prompt)
                    memory.appendMessage(conv.id, "user", req.message)
                    memory.appendMessage(conv.id, "assistant", r.text)
                    call.respond(GenerateResponse(r.text, engine.loadedModelPath, r.tokens, r.ms,
                        if (r.ms > 0) r.tokens * 1000.0 / r.ms else 0.0))
                }

                // ---------- OpenAI-compatible ----------
                get("/v1/models") {
                    call.respondText(
                        """{"object":"list","data":[{"id":"${engine.loadedModelPath ?: "medha"}","object":"model","owned_by":"medha"}]}""",
                        ContentType.Application.Json
                    )
                }

                post("/v1/chat/completions") {
                    if (!engine.isLoaded) return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("no model loaded"))
                    val req = call.receive<OAChatRequest>()
                    val system = req.messages.firstOrNull { it.role == "system" }?.content
                    val prompt = buildString {
                        system?.let { append("System: ").append(it).append("\n\n") }
                        req.messages.filter { it.role != "system" }.forEach {
                            append(it.role.replaceFirstChar { c -> c.uppercase() }).append(": ").append(it.content).append("\n")
                        }
                        append("Assistant:")
                    }
                    if (req.stream) {
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            engine.generateStream(prompt).collect { delta ->
                                val chunk = """{"choices":[{"delta":{"content":${jsonStr(delta)}},"index":0}]}"""
                                write("data: $chunk\n\n"); flush()
                            }
                            write("data: [DONE]\n\n"); flush()
                        }
                    } else {
                        val r = engine.generate(prompt)
                        val body = """{"id":"chatcmpl-medha","object":"chat.completion","model":${jsonStr(engine.loadedModelPath ?: "medha")},"choices":[{"index":0,"message":{"role":"assistant","content":${jsonStr(r.text)}},"finish_reason":"stop"}],"usage":{"completion_tokens":${r.tokens},"total_tokens":${r.tokens}}}"""
                        call.respondText(body, ContentType.Application.Json)
                    }
                }

                // ---------- RAG ----------
                post("/rag/ingest") {
                    val req = call.receive<RagIngestRequest>()
                    retriever.ingest(req.collection, req.title, req.source, req.text)
                    call.respondText("""{"status":"ingested","collection":${jsonStr(req.collection)}}""", ContentType.Application.Json)
                }

                post("/rag/query") {
                    val req = call.receive<RagQueryRequest>()
                    val hits = retriever.retrieve(req.collection, req.query, req.topK)
                    val arr = hits.joinToString(",") { """{"text":${jsonStr(it.text)},"score":${it.score}}""" }
                    call.respondText("""{"hits":[$arr]}""", ContentType.Application.Json)
                }

                // ---------- bundled PWA ----------
                get("/") { serveAsset(call, "index.html") }
                get("/{path...}") {
                    val parts = call.parameters.getAll("path") ?: emptyList()
                    serveAsset(call, parts.joinToString("/").ifEmpty { "index.html" })
                }
            }
        }.also { it.start(wait = false) }
        Log.i(TAG, "Medha server on http://127.0.0.1:$port")
    }

    private suspend fun serveAsset(call: io.ktor.server.application.ApplicationCall, name: String) {
        val safe = name.replace("..", "")
        try {
            val bytes = appContext.assets.open("webapp/$safe").readBytes()
            call.respondBytes(bytes, contentTypeFor(safe))
        } catch (e: Exception) {
            call.respondText("Not found: $safe", status = HttpStatusCode.NotFound)
        }
    }

    private fun contentTypeFor(name: String): ContentType = when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".js") -> ContentType.Application.JavaScript
        name.endsWith(".css") -> ContentType.Text.CSS
        name.endsWith(".json") -> ContentType.Application.Json
        name.endsWith(".svg") -> ContentType.Image.SVG
        name.endsWith(".png") -> ContentType.Image.PNG
        else -> ContentType.Application.OctetStream
    }

    private fun escapeSse(s: String) = s.replace("\n", "\\n")
    private fun jsonStr(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
        return "\"$esc\""
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    companion object { private const val TAG = "LocalServer" }
}
