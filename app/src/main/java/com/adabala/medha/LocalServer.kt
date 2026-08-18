package com.adabala.medha

import android.content.Context
import android.util.Log
import com.adabala.medha.data.MedhaDatabase
import com.adabala.medha.auth.ClientRegistry
import com.adabala.medha.connectors.SmsConnector
import com.adabala.medha.notify.NotificationHub
import com.adabala.medha.sched.InferenceScheduler
import com.adabala.medha.rag.Embedder
import com.adabala.medha.rag.NoEmbedder
import com.adabala.medha.rag.Retriever
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
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
data class EmbeddingsRequest(
    val input: List<String>,
    val model: String? = null,
    /** Medha extension: "query" or "document" (default). */
    @kotlinx.serialization.SerialName("input_type") val inputType: String = "document"
)

@Serializable
data class ReindexRequest(val collection: String? = null)

@Serializable
data class StoreItem(val key: String, val value: String)

@Serializable
data class BulkStoreRequest(val items: List<StoreItem>)

@Serializable
data class MarkReadRequest(val threadId: Long? = null, val ids: List<Long> = emptyList())

@Serializable
data class SmsSendRequest(val address: String, val body: String)

@Serializable
data class NotifyRequest(
    val id: String = "default",
    val title: String,
    val text: String = "",
    val ongoing: Boolean = false,
    val progressCurrent: Int = -1,
    val progressMax: Int = -1,
    val silent: Boolean = true
)

@Serializable
data class WidgetItem(val title: String, val text: String = "")

@Serializable
data class WidgetRequest(val items: List<WidgetItem>)

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
    private val requireAuth: Boolean = true,
    private val embedder: Embedder = NoEmbedder,
    private val registry: ClientRegistry,
    private val scheduler: InferenceScheduler,
    private val sms: SmsConnector,
    private val notifier: NotificationHub
) {
    private var server: ApplicationEngine? = null
    private val memory = MemoryRepository(db)
    private val retriever = Retriever(db, embedder)
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

                val client = resolveClient(call)
                if (client != null) call.attributes.put(CLIENT_KEY, client)

                if (requireAuth && !isPublic(path) && client == null) {
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
                        put("thermalHeadroom", SystemInfo.thermalHeadroom(appContext))
                        SystemInfo.cpuInfo().forEach { (k, v) -> put(k, v) }
                        put("freeStorageMb", SystemInfo.freeStorageMb(appContext))
                        put("dbSizeBytes", db.sizeBytes(appContext))
                        put("fullTextIndex", db.hasFullTextIndex)
                        put("embeddingModel", retriever.embeddingId)
                        put("vectorSearch", retriever.vectorEnabled)
                        put("embeddedChunks", db.countEmbedded(retriever.embeddingId))
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
                    call.requireCap(ClientRegistry.Cap.GENERATE) ?: return@post
                    val req = call.receive<GenerateRequest>()
                    if (req.prompt.isBlank()) return@post call.badRequest("prompt must not be blank")
                    val r = try {
                        scheduler.submit(priorityOf(call)) { engine.generate(req.prompt, req.system) }
                    } catch (e: InferenceScheduler.Rejected) {
                        return@post call.rejected(e)
                    } catch (e: InferenceScheduler.TimedOut) {
                        return@post call.timedOut(e)
                    }
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
                    // Admission happens BEFORE the SSE response starts, via
                    // acquire() rather than submit(): once respondTextWriter
                    // sends headers, a Rejected/TimedOut caught afterwards
                    // could no longer send a clean 429/504 on top of it. See
                    // InferenceScheduler.acquire for the full reasoning.
                    val permit = try {
                        scheduler.acquire(priorityOf(call))
                    } catch (e: InferenceScheduler.Rejected) {
                        return@post call.rejected(e)
                    } catch (e: InferenceScheduler.TimedOut) {
                        return@post call.timedOut(e)
                    }
                    permit.use {
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
                }

                // ------------- multi-turn chat with SQLite memory -------------

                post("/chat") {
                    if (!engine.isLoaded) return@post call.notReady()
                    val client = call.requireCap(ClientRegistry.Cap.MEMORY) ?: return@post
                    val req = call.receive<ChatTurnRequest>()
                    if (req.sessionId.isBlank()) return@post call.badRequest("sessionId is required")
                    if (req.message.isBlank()) return@post call.badRequest("message must not be blank")

                    // Namespaced from the token, so two PWAs cannot collide on
                    // a session id or read each other's threads.
                    val conv = memory.getOrCreate(client.scope(req.sessionId), req.system)
                    val history = memory.history(conv.id)
                    val context = req.collection
                        ?.takeIf { it.isNotBlank() }
                        ?.let { retriever.retrieve(client.scope(it), req.message, req.ragTopK).map { h -> h.text } }
                        ?: emptyList()

                    val prompt = memory.buildPrompt(
                        conv.systemInstruction ?: req.system, history, req.message, context
                    )
                    val r = try {
                        scheduler.submit(priorityOf(call)) { engine.generate(prompt) }
                    } catch (e: InferenceScheduler.Rejected) {
                        return@post call.rejected(e)
                    } catch (e: InferenceScheduler.TimedOut) {
                        return@post call.timedOut(e)
                    }
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
                    val client = call.requireCap(ClientRegistry.Cap.MEMORY) ?: return@get
                    val limit = call.intParam("limit", 100).coerceIn(1, 500)
                    val offset = call.intParam("offset", 0).coerceAtLeast(0)
                    call.respondJson(buildJsonObject {
                        putJsonArray("sessions") {
                            memory.listSessions(500, 0)
                                .filter { client.owns(it.sessionId) }
                                .drop(offset).take(limit).forEach { s ->
                                addJsonObject {
                                    put("sessionId", client.unscope(s.sessionId))
                                    put("title", s.title ?: "")
                                    put("messages", s.messageCount)
                                    put("updatedAt", s.updatedAt)
                                }
                            }
                        }
                    })
                }

                get("/sessions/{id}/messages") {
                    val client = call.requireCap(ClientRegistry.Cap.MEMORY) ?: return@get
                    val id = client.scope(call.parameters["id"].orEmpty())
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
                    val client = call.requireCap(ClientRegistry.Cap.MEMORY) ?: return@delete
                    val id = client.scope(call.parameters["id"].orEmpty())
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
                        // Same admission control as /generate/stream: the OpenAI
                        // -compat surface is the one most third-party clients
                        // actually hit, so it cannot be the endpoint that skips
                        // queue/thermal gating. acquire(), not submit() — see
                        // InferenceScheduler.acquire for why.
                        val permit = try {
                            scheduler.acquire(priorityOf(call))
                        } catch (e: InferenceScheduler.Rejected) {
                            return@post call.rejected(e)
                        } catch (e: InferenceScheduler.TimedOut) {
                            return@post call.timedOut(e)
                        }
                        permit.use {
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
                        }
                    } else {
                        val r = try {
                            scheduler.submit(priorityOf(call)) { engine.generate(prompt, system) }
                        } catch (e: InferenceScheduler.Rejected) {
                            return@post call.rejected(e)
                        } catch (e: InferenceScheduler.TimedOut) {
                            return@post call.timedOut(e)
                        }
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
                    if (!embedder.isReady) {
                        return@post call.respond(
                            HttpStatusCode.NotImplemented,
                            ErrorResponse(
                                "no embedding model is loaded; RAG is running in lexical mode",
                                "not_implemented"
                            )
                        )
                    }
                    val req = call.receive<EmbeddingsRequest>()
                    val inputs = req.input
                    if (inputs.isEmpty()) return@post call.badRequest("input must not be empty")
                    if (inputs.size > MAX_EMBED_BATCH) {
                        return@post call.badRequest("at most $MAX_EMBED_BATCH inputs per request")
                    }

                    // Asymmetric by design: a caller embedding a search query
                    // needs the query prefix, one indexing a passage needs the
                    // document prefix. OpenAI's schema has no field for this,
                    // so Medha adds "input_type" and defaults to "document",
                    // which is the safe choice for an indexing pipeline.
                    val asQuery = req.inputType.equals("query", ignoreCase = true)
                    val vectors = inputs.map { t ->
                        if (asQuery) embedder.embedQuery(t) else embedder.embedDocument(t, null)
                    }
                    if (vectors.any { it == null }) {
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("embedding failed for one or more inputs", "embed_failed")
                        )
                    }

                    call.respondJson(buildJsonObject {
                        put("object", "list")
                        put("model", embedder.id)
                        putJsonArray("data") {
                            vectors.forEachIndexed { i, v ->
                                addJsonObject {
                                    put("object", "embedding")
                                    put("index", i)
                                    putJsonArray("embedding") { v!!.forEach { add(it) } }
                                }
                            }
                        }
                        put("usage", buildJsonObject {
                            val toks = inputs.sumOf { SystemInfo.estimateTokens(it) }
                            put("prompt_tokens", toks)
                            put("total_tokens", toks)
                        })
                    })
                }

                // ------------------------------ RAG ------------------------------

                post("/rag/ingest") {
                    val client = call.requireCap(ClientRegistry.Cap.RAG) ?: return@post
                    val req = call.receive<RagIngestRequest>()
                    if (req.collection.isBlank()) return@post call.badRequest("collection is required")
                    if (req.text.isBlank()) return@post call.badRequest("text must not be blank")
                    val r = retriever.ingest(client.scope(req.collection), req.title, req.source, req.text)
                    call.respondJson(buildJsonObject {
                        put("status", "ingested")
                        put("collection", req.collection)
                        put("chunks", r.chunks)
                        put("embedded", r.embedded)
                        put("mode", if (retriever.vectorEnabled) "hybrid" else "lexical")
                    })
                }

                post("/rag/query") {
                    val client = call.requireCap(ClientRegistry.Cap.RAG) ?: return@post
                    val req = call.receive<RagQueryRequest>()
                    if (req.collection.isBlank()) return@post call.badRequest("collection is required")
                    val hits = retriever.retrieve(client.scope(req.collection), req.query, req.topK)
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

                post("/rag/reindex") {
                    if (!embedder.isReady) {
                        return@post call.respond(
                            HttpStatusCode.NotImplemented,
                            ErrorResponse("no embedding model is loaded", "not_implemented")
                        )
                    }
                    val req = call.receive<ReindexRequest>()
                    val r = retriever.reindex(req.collection?.takeIf { it.isNotBlank() })
                    call.respondJson(buildJsonObject {
                        put("embedded", r.embedded)
                        put("remaining", r.remaining)
                        put("status", r.status)
                        put("model", embedder.id)
                    })
                }

                get("/rag/collections") {
                    val client = call.requireCap(ClientRegistry.Cap.RAG) ?: return@get
                    call.respondJson(buildJsonObject {
                        putJsonArray("collections") {
                            db.listCollections().filter { client.owns(it.collection) }.forEach { c ->
                                addJsonObject {
                                    put("collection", client.unscope(c.collection))
                                    put("documents", c.documents)
                                    put("chunks", c.chunks)
                                    put("embedded", c.embedded)
                                }
                            }
                        }
                    })
                }

                delete("/rag/collections/{name}") {
                    val client = call.requireCap(ClientRegistry.Cap.RAG) ?: return@delete
                    val name = client.scope(call.parameters["name"].orEmpty())
                    if (name.isBlank()) return@delete call.badRequest("collection name is required")
                    val n = db.deleteCollection(name)
                    call.respondJson(buildJsonObject {
                        put("deleted", n)
                        put("collection", name)
                    })
                }


                // ------------------------- key-value store -------------------------

                put("/store/{key...}") {
                    val client = call.requireCap(ClientRegistry.Cap.STORE) ?: return@put
                    val key = (call.parameters.getAll("key") ?: emptyList()).joinToString("/")
                    if (key.isBlank()) return@put call.badRequest("key is required")
                    val body = call.receiveText()
                    if (body.length > MAX_VALUE_BYTES) {
                        return@put call.badRequest("value exceeds ${MAX_VALUE_BYTES / 1024} KB")
                    }
                    db.kvPut(client.scope(key), client.id, body)
                    call.respondJson(buildJsonObject { put("key", key); put("ok", true) })
                }

                get("/store/{key...}") {
                    val client = call.requireCap(ClientRegistry.Cap.STORE) ?: return@get
                    val key = (call.parameters.getAll("key") ?: emptyList()).joinToString("/")
                    val v = db.kvGet(client.scope(key))
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, ErrorResponse("no such key", "not_found")
                        )
                    call.respondText(v, ContentType.Application.Json)
                }

                delete("/store/{key...}") {
                    val client = call.requireCap(ClientRegistry.Cap.STORE) ?: return@delete
                    val key = (call.parameters.getAll("key") ?: emptyList()).joinToString("/")
                    call.respondJson(buildJsonObject {
                        put("deleted", db.kvDelete(client.scope(key)))
                    })
                }

                get("/store") {
                    val client = call.requireCap(ClientRegistry.Cap.STORE) ?: return@get
                    val prefix = call.request.queryParameters["prefix"].orEmpty()
                    val limit = call.intParam("limit", 100).coerceIn(1, 1000)
                    val offset = call.intParam("offset", 0).coerceAtLeast(0)
                    val scoped = client.scope(prefix)
                    call.respondJson(buildJsonObject {
                        put("prefix", prefix)
                        put("total", db.kvCount(scoped))
                        putJsonArray("items") {
                            db.kvList(scoped, limit, offset).forEach { (k, v, at) ->
                                addJsonObject {
                                    put("key", client.unscope(k))
                                    put("updatedAt", at)
                                    put("value", v)
                                }
                            }
                        }
                    })
                }

                post("/store/bulk") {
                    val client = call.requireCap(ClientRegistry.Cap.STORE) ?: return@post
                    val req = call.receive<BulkStoreRequest>()
                    if (req.items.isEmpty()) return@post call.badRequest("items must not be empty")
                    if (req.items.size > MAX_BULK) {
                        return@post call.badRequest("at most $MAX_BULK items per request")
                    }
                    // One transaction: a 500-message classification pass is one
                    // commit rather than 500 fsyncs.
                    val n = db.kvPutAll(client.id, req.items.map { client.scope(it.key) to it.value })
                    call.respondJson(buildJsonObject { put("written", n) })
                }

                // --------------------------- SMS connector ---------------------------

                get("/connectors/sms/status") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    val st = sms.status()
                    call.respondJson(buildJsonObject {
                        // False on the "core" build, which ships without SMS
                        // permissions so it installs without a Play Protect block.
                        put("supported", st.supported)
                        put("canRead", st.canRead)
                        put("canSend", st.canSend)
                        put("isDefaultSmsApp", st.isDefaultSmsApp)
                        put("totalMessages", st.totalMessages)
                    })
                }

                get("/connectors/sms/conversations") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    if (!sms.canRead()) return@get call.smsDenied()
                    val limit = call.intParam("limit", 50).coerceIn(1, 200)
                    val offset = call.intParam("offset", 0).coerceAtLeast(0)
                    call.respondJson(buildJsonObject {
                        putJsonArray("conversations") {
                            sms.conversations(limit, offset).forEach { t ->
                                addJsonObject {
                                    put("threadId", t.threadId)
                                    put("address", t.address)
                                    put("displayName", t.displayName ?: "")
                                    put("snippet", t.snippet)
                                    put("messageCount", t.messageCount)
                                    put("unreadCount", t.unreadCount)
                                    put("lastAt", t.lastAt)
                                }
                            }
                        }
                    })
                }

                get("/connectors/sms/messages") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    if (!sms.canRead()) return@get call.smsDenied()
                    val msgs = sms.messages(
                        threadId = call.request.queryParameters["threadId"]?.toLongOrNull(),
                        since = call.request.queryParameters["since"]?.toLongOrNull(),
                        before = call.request.queryParameters["before"]?.toLongOrNull(),
                        unreadOnly = call.request.queryParameters["unreadOnly"] == "true",
                        limit = call.intParam("limit", 100)
                    )
                    call.respondJson(buildJsonObject {
                        putJsonArray("messages") { msgs.forEach { add(it.toJson()) } }
                        // Cursor for the next page. Timestamps, not offsets:
                        // messages arriving mid-scan shift every offset and
                        // cause duplicates or gaps during a backlog pass.
                        put("nextBefore", msgs.minOfOrNull { it.date } ?: 0L)
                        put("count", msgs.size)
                    })
                }

                get("/connectors/sms/messages/{id}") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    if (!sms.canRead()) return@get call.smsDenied()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.badRequest("numeric id required")
                    val m = sms.message(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, ErrorResponse("no such message", "not_found")
                        )
                    call.respondText(m.toJson().toString(), ContentType.Application.Json)
                }

                get("/connectors/sms/contacts/{address}") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    val addr = call.parameters["address"].orEmpty()
                    call.respondJson(buildJsonObject {
                        put("address", addr)
                        put("displayName", sms.contactName(addr) ?: "")
                    })
                }

                post("/connectors/sms/mark-read") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@post
                    val req = call.receive<MarkReadRequest>()
                    val n = when {
                        req.threadId != null -> sms.markThreadRead(req.threadId)
                        req.ids.isNotEmpty() -> sms.markRead(req.ids)
                        else -> return@post call.badRequest("threadId or ids required")
                    }
                    call.respondJson(buildJsonObject { put("updated", n) })
                }

                post("/connectors/sms/send") {
                    call.requireCap(ClientRegistry.Cap.SMS_SEND) ?: return@post
                    val req = call.receive<SmsSendRequest>()
                    sms.send(req.address, req.body).fold(
                        onSuccess = {
                            call.respondJson(buildJsonObject { put("sent", true) })
                        },
                        onFailure = { e ->
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorResponse(e.message ?: "send failed", "send_failed")
                            )
                        }
                    )
                }

                get("/connectors/sms/events") {
                    call.requireCap(ClientRegistry.Cap.SMS_READ) ?: return@get
                    if (!sms.canRead()) return@get call.smsDenied()
                    call.prepareSse()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        // One held connection instead of a PWA polling a content
                        // provider through HTTP every few seconds.
                        write("data: {\"type\":\"connected\"}\n\n"); flush()
                        sms.changes().collect { at ->
                            write("data: {\"type\":\"change\",\"at\":$at}\n\n")
                            flush()
                        }
                    }
                }

                // --------------------------- notifications ---------------------------

                get("/notify/capabilities") {
                    call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@get
                    call.respondJson(buildJsonObject {
                        notifier.capabilities().forEach { (k, v) ->
                            when (v) {
                                is Boolean -> put(k, v)
                                is Int -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    })
                }

                post("/notify") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@post
                    val req = call.receive<NotifyRequest>()
                    if (req.title.isBlank()) return@post call.badRequest("title is required")
                    val ok = notifier.post(
                        NotificationHub.Request(
                            id = req.id, title = req.title, text = req.text,
                            ongoing = req.ongoing,
                            progressCurrent = req.progressCurrent, progressMax = req.progressMax,
                            silent = req.silent, clientId = client.id
                        )
                    )
                    call.respondJson(buildJsonObject { put("posted", ok); put("id", req.id) })
                }

                delete("/notify/{id}") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@delete
                    notifier.cancel(client.id, call.parameters["id"].orEmpty())
                    call.respondJson(buildJsonObject { put("cancelled", true) })
                }

                put("/widget/content") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@put
                    val req = call.receive<WidgetRequest>()
                    notifier.setWidgetContent(client.id, req.items.map { it.title to it.text })
                    call.respondJson(buildJsonObject { put("ok", true); put("items", req.items.size) })
                }

                // ---------------------------- scheduler ----------------------------

                get("/scheduler") {
                    call.respondJson(buildJsonObject {
                        scheduler.status().forEach { (k, v) ->
                            when (v) {
                                is Boolean -> put(k, v)
                                is Int -> put(k, v)
                                is Float -> put(k, v)
                                is Long -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
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

    /**
     * Batch callers set X-Medha-Priority: batch. Everything else is treated as
     * interactive, because the safe default when a client says nothing is to
     * assume a human is waiting.
     */
    private fun priorityOf(call: ApplicationCall): InferenceScheduler.Priority =
        if (call.request.headers[HEADER_PRIORITY]?.equals("batch", true) == true) {
            InferenceScheduler.Priority.BATCH
        } else {
            InferenceScheduler.Priority.INTERACTIVE
        }

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

    /**
     * Resolves the bearer token to a client. Returns null when absent or
     * unknown. The namespace comes from THIS lookup and never from the request
     * body, so a client cannot ask for another client's prefix.
     */
    private fun resolveClient(call: ApplicationCall): ClientRegistry.Client? {
        val header = call.request.headers[HttpHeaders.Authorization]
            ?: call.request.headers[HEADER_TOKEN]
            ?: return null
        val presented = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (presented.isEmpty()) return null
        // Constant-time compare against each known token: a plain map lookup
        // would leak validity through timing on the hash comparison.
        return registry.all().firstOrNull { constantTimeEquals(presented, it.token) }
    }

    private fun ApplicationCall.client(): ClientRegistry.Client? =
        attributes.getOrNull(CLIENT_KEY)

    private suspend fun ApplicationCall.requireCap(cap: String): ClientRegistry.Client? {
        val c = client()
        if (c == null) {
            respond(HttpStatusCode.Unauthorized, ErrorResponse("no client", "unauthorized"))
            return null
        }
        if (!c.can(cap)) {
            respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("client '${c.id}' lacks capability '$cap'", "forbidden")
            )
            return null
        }
        return c
    }

    private suspend fun ApplicationCall.rejected(e: InferenceScheduler.Rejected) {
        response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
        respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.reason, "rejected"))
    }

    /**
     * 504, not 429: this request was admitted and genuinely waited too long,
     * as opposed to being turned away at the door. See
     * [InferenceScheduler.TimedOut] for what this guarantees and what it does
     * not.
     */
    private suspend fun ApplicationCall.timedOut(e: InferenceScheduler.TimedOut) {
        respond(HttpStatusCode.GatewayTimeout, ErrorResponse(e.message ?: "timed out", "timeout"))
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
                    .replace(TOKEN_PLACEHOLDER, if (requireAuth) (registry.admin()?.token ?: "") else "")
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

    private suspend fun ApplicationCall.smsDenied() = respond(
        HttpStatusCode.Forbidden,
        ErrorResponse("SMS permission not granted to Medha; grant it in the app", "sms_denied")
    )

    private fun SmsConnector.Message.toJson() = buildJsonObject {
        put("id", id)
        put("threadId", threadId)
        put("address", address)
        put("body", body)
        put("date", date)
        put("read", read)
        put("inbound", inbound)
    }

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
        private const val HEADER_PRIORITY = "X-Medha-Priority"
        private val CLIENT_KEY = io.ktor.util.AttributeKey<ClientRegistry.Client>("medhaClient")
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val MAX_EMBED_BATCH = 64
        private const val MAX_VALUE_BYTES = 512 * 1024
        private const val MAX_BULK = 1000

        const val TOKEN_PLACEHOLDER = "__MEDHA_TOKEN__"
        const val PORT_PLACEHOLDER = "__MEDHA_PORT__"

        private val STATIC_SUFFIXES = listOf(
            ".html", ".js", ".css", ".png", ".svg", ".ico", ".webmanifest", ".woff2"
        )
    }
}

/** Single place for the version string surfaced over HTTP. */
object BuildInfo {
    const val VERSION = "0.8.2"
}
