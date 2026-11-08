package com.adabala.medha

import android.content.Context
import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.data.MedhaDatabase
import com.adabala.medha.auth.ClientRegistry
import com.adabala.medha.auth.PairingManager
import com.adabala.medha.net.CorsPolicy
import com.adabala.medha.net.RateLimiter
import com.adabala.medha.net.WebOrigin
import com.adabala.medha.notify.BriefJson
import com.adabala.medha.oai.ChatRequestParser
import com.adabala.medha.oai.MultimodalProbe
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.NotificationHub
import com.adabala.medha.sched.InferenceScheduler
import com.adabala.medha.rag.Embedder
import com.adabala.medha.rag.NoEmbedder
import com.adabala.medha.rag.Retriever
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
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
import kotlinx.serialization.json.putJsonObject

// ------------------------------ request DTOs ------------------------------

@Serializable
data class GenerateRequest(
    val prompt: String,
    val system: String? = null,
    /**
     * Hard cap on output tokens. Previously absent, which made every
     * client's output limit advisory. Enforced by stopping generation
     * early rather than truncating after the fact, so the tokens past the
     * cap are never produced — see LlmEngine.generateCapped.
     */
    @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
data class GenerateResponse(
    val text: String,
    val model: String?,
    val promptTokens: Int,
    val tokens: Int,
    val ms: Long,
    val tokensPerSec: Double,
    val sessionId: String? = null,
    /** "stop" normally, "length" when max_tokens ended it. */
    @kotlinx.serialization.SerialName("finish_reason") val finishReason: String = "stop"
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
    val stream: Boolean = false,
    // Non-standard Medha extension, absent from the OpenAI spec: when set,
    // retrieval-augments the prompt from this RAG collection before
    // generating, the same as POST /chat's collection/ragTopK fields. Any
    // standard OpenAI client that never sets this sees no difference from
    // before it existed — ignoreUnknownKeys means an unrelated client's own
    // extra fields are similarly harmless here. Requires the RAG capability
    // in addition to GENERATE; see the /v1/chat/completions handler.
    val collection: String? = null,
    val ragTopK: Int = 3
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
data class PairRequest(val code: String)

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
data class WidgetItem(
    val title: String,
    val text: String = "",
    /**
     * Optional http(s) URL for the item. Tapping the line in Medha's brief
     * opens it. Anything that is not http(s) is dropped rather than
     * rejected, so one bad item does not fail a whole post -- see
     * BriefJson.isSafeLink for why the scheme list is deliberately boring.
     */
    val link: String? = null
)

@Serializable
data class WidgetRequest(
    val items: List<WidgetItem>,
    /**
     * How long this section should live, in seconds. Clamped server-side to
     * [com.adabala.medha.notify.BriefPolicy.MIN_TTL_MS]..`MAX_TTL_MS`, so a
     * client cannot pin content to the home screen indefinitely. Omitted
     * means the 24-hour default.
     */
    val ttlSeconds: Long? = null
)

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
 * into it, so same-origin PWAs work with no setup. A web app served from any
 * other origin pairs first (see PairingManager) and then sends
 * `Authorization: Bearer <token>` like any other client.
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
    private val notifier: NotificationHub,
    /**
     * Shared with MainActivity: the user generates a code in the UI and a web
     * app redeems it here, so both sides must see the same instance. Held by
     * InferenceService and handed to both.
     */
    private val pairing: PairingManager
) {
    private var server: ApplicationEngine? = null

    /**
     * Per-client budget for POST /notify: a burst of [NOTIFY_BURST] then
     * [NOTIFY_PER_MINUTE] a minute sustained. Sized for an app that posts a
     * handful of cards when it syncs and then goes quiet, and far below what
     * a runaway setInterval would want.
     */
    private val notifyLimiter = RateLimiter(
        capacity = NOTIFY_BURST,
        refillPerMinute = NOTIFY_PER_MINUTE
    )
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
                    Diagnostics.e(TAG, "Unhandled server error on ${call.request.path()}", cause)
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

            // NOTE: no install(CORS). Cross-origin handling is done by hand in
            // the interceptor below, because the allowed set changes as web
            // apps pair and because Ktor 2.3's CORS plugin cannot emit the
            // Private Network Access header Chrome requires. See CorsPolicy.

            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()

                // ---------------------------- CORS ----------------------------
                // First, before auth: a preflight carries no Authorization
                // header by definition, so answering it after the auth check
                // would 401 every cross-origin request before the browser ever
                // sent the real one.
                val decision = CorsPolicy.decide(
                    originHeader = call.request.headers[HttpHeaders.Origin],
                    method = call.request.httpMethod.value,
                    // Plain == on a nullable rather than String?.equals(…,
                    // ignoreCase): the extension-on-nullable form reads as a
                    // call on a possibly-null receiver and is easy to
                    // misread when skimming a security check.
                    requestsPrivateNetwork =
                        call.request.headers[CorsPolicy.REQUEST_PRIVATE_NETWORK]
                            ?.trim()?.lowercase() == "true",
                    allowedOrigins = allowedOrigins()
                )
                decision.headers.forEach { (k, v) -> call.response.header(k, v) }
                decision.shortCircuitStatus?.let { status ->
                    call.respond(HttpStatusCode.fromValue(status))
                    return@intercept finish()
                }

                val len = call.request.contentLength() ?: 0L
                if (len > MAX_BODY_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ErrorResponse("body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MB", "too_large")
                    )
                    return@intercept finish()
                }

                val client = resolveClient(call)
                if (client != null) {
                    // ---------------------- origin binding ----------------------
                    // A token issued to a web app only works from that web
                    // app's origin. Without this, a token copied out of one
                    // site's localStorage and pasted into another page would
                    // work perfectly: CORS alone governs who may *read* the
                    // reply, and says nothing about who may hold a token.
                    //
                    // An absent Origin is allowed through deliberately. curl,
                    // a native client and the hello-medha sample all send
                    // none, and the user may legitimately copy a token into
                    // one of those from the client list.
                    val presentedOrigin = WebOrigin.canonical(call.request.headers[HttpHeaders.Origin])
                    val bound = client.webOrigin
                    if (bound != null && presentedOrigin != null && presentedOrigin != bound) {
                        Diagnostics.w(
                            TAG,
                            "token for '${client.id}' presented from $presentedOrigin, bound to $bound"
                        )
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "this token is bound to $bound and cannot be used from $presentedOrigin",
                                "origin_mismatch"
                            )
                        )
                        return@intercept finish()
                    }

                    call.attributes.put(CLIENT_KEY, client)
                    // Throttled internally to at most one write per hour per
                    // client, so this costs a map lookup on the vast majority
                    // of requests. See ClientRegistry.touch.
                    registry.touch(client.token)
                }

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
                    val mm = multimodal()
                    call.respondJson(buildJsonObject {
                        put("status", "ok")
                        put("modelLoaded", engine.isLoaded)
                        put("backend", engine.configuredBackend.name)
                        put("busy", engine.isBusy)
                        put("authRequired", requireAuth)
                        put("error", engine.lastError ?: "")
                        // Capability block so a client can switch multimodal
                        // on by itself instead of being configured by hand.
                        // vision/audio come from an actual reflection probe
                        // of the installed AAR, not from a flag: claiming a
                        // capability Medha does not have would stop the
                        // client falling back and make it fail instead.
                        putJsonObject("capabilities") {
                            put("text", true)
                            put("vision", mm.vision)
                            put("audio", mm.audio)
                        }
                        put("max_context", MAX_CONTEXT_TOKENS)
                        put("max_audio_seconds", ChatRequestParser.MAX_AUDIO_SECONDS.toInt())
                        put("max_image_bytes", ChatRequestParser.MAX_IMAGE_BYTES)
                        put("queue_depth", engine.queueDepth)
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
                        // The probe's own findings, so a build that does
                        // support multimodal can be identified from one call
                        // rather than from guesswork. imageMethods /
                        // audioMethods carry real signatures.
                        putJsonObject("multimodal") {
                            val mm = multimodal()
                            put("vision", mm.vision)
                            put("audio", mm.audio)
                            put("summary", MultimodalProbe.summary(mm))
                            putJsonArray("imageMethods") { mm.imageMethods.forEach { add(it) } }
                            putJsonArray("audioMethods") { mm.audioMethods.forEach { add(it) } }
                            put("sdkMethodCount", mm.allMethods.size)
                        }
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
                        scheduler.submit(priorityOf(call)) {
                            engine.generateCapped(req.prompt, req.system, req.maxTokens)
                        }
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
                            tokensPerSec = r.tokensPerSec,
                            finishReason = if (r.hitCap) "length" else "stop"
                        )
                    )
                }

                post("/generate/stream") {
                    if (!engine.isLoaded) return@post call.notReady()
                    // Was missing entirely: any authenticated client, even one
                    // granted zero capabilities, could still generate text
                    // through this endpoint while /generate correctly required
                    // GENERATE. Streaming and non-streaming must enforce the
                    // same authorization, not just the same admission control.
                    call.requireCap(ClientRegistry.Cap.GENERATE) ?: return@post
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
                    val collection = req.collection?.takeIf { it.isNotBlank() }
                    val context = if (collection != null) {
                        // Separate from the MEMORY capability this endpoint
                        // already requires: MEMORY lets a client hold a
                        // conversation, RAG lets it read back a collection's
                        // contents. /rag/query and /v1/chat/completions both
                        // draw this same distinction; this closes the one
                        // place that didn't.
                        val ragClient = call.requireCap(ClientRegistry.Cap.RAG) ?: return@post
                        retriever.retrieve(ragClient.scope(collection), req.message, req.ragTopK)
                            .map { it.text }
                    } else {
                        emptyList()
                    }

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
                    // Same gap as /generate/stream: this is the endpoint most
                    // third-party OpenAI clients actually hit, so it cannot be
                    // the one that skips authorization.
                    call.requireCap(ClientRegistry.Cap.GENERATE) ?: return@post
                    // Parsed from the raw body, not via OAChatRequest.
                    //
                    // OpenAI allows `content` to be a string OR an array of
                    // typed parts, and multimodal clients send the array.
                    // Against the String-typed field this used to throw, and
                    // StatusPages turned that into a 500 -- the worst answer
                    // available, because a client whose documented fallback
                    // is "4xx means no vision/audio here" reads a 500 as a
                    // broken server and cannot degrade. See ChatRequestParser.
                    val req = try {
                        ChatRequestParser.parse(call.receiveText())
                    } catch (e: ChatRequestParser.Invalid) {
                        return@post call.respond(
                            HttpStatusCode.fromValue(e.status),
                            ErrorResponse(e.message, e.code)
                        )
                    }

                    // Multimodal: answer truthfully about this build.
                    //
                    // 501 and not 500: the request was understood and is
                    // well-formed, this build just cannot serve it. The
                    // capability block in /health says the same thing ahead
                    // of time, so a client should not get here at all --
                    // but one that ignores /health still gets an answer it
                    // can act on rather than a crash.
                    if (req.isMultimodal) {
                        val mm = multimodal()
                        val needVision = req.hasImages && !mm.vision
                        val needAudio = req.hasAudio && !mm.audio
                        if (needVision || needAudio) {
                            val missing = listOfNotNull(
                                if (needVision) "images" else null,
                                if (needAudio) "audio" else null
                            ).joinToString(" and ")
                            Diagnostics.i(
                                TAG,
                                "rejected multimodal request ($missing): " +
                                    MultimodalProbe.summary(mm)
                            )
                            return@post call.respond(
                                HttpStatusCode.NotImplemented,
                                ErrorResponse(
                                    "this build cannot process $missing: " +
                                        MultimodalProbe.summary(mm) +
                                        ". GET /health reports capabilities ahead of time.",
                                    "multimodal_unsupported"
                                )
                            )
                        }
                    }

                    val system = ChatRequestParser.systemText(req)

                    // Non-standard RAG extension (see OAChatRequest). A second,
                    // separate capability check: GENERATE lets a client run the
                    // model, RAG lets it read back a collection's contents —
                    // the same distinction /rag/query and /chat already draw.
                    val collection = req.collection?.takeIf { it.isNotBlank() }
                    val ragContext: List<String> = if (collection != null) {
                        val ragClient = call.requireCap(ClientRegistry.Cap.RAG) ?: return@post
                        val lastUser = req.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
                        retriever.retrieve(ragClient.scope(collection), lastUser, req.ragTopK)
                            .map { it.text }
                    } else {
                        emptyList()
                    }

                    val prompt = buildString {
                        // Same "Relevant context:\n[1] ...\n[2] ..." shape as
                        // MemoryRepository.buildPrompt, so a caller who has
                        // learned this format from /chat sees the same thing
                        // here rather than a second format to account for.
                        if (ragContext.isNotEmpty()) {
                            append("Relevant context:\n")
                            ragContext.forEachIndexed { i, c ->
                                append("[").append(i + 1).append("] ").append(c.trim()).append("\n")
                            }
                            append("\n")
                        }
                        append(ChatRequestParser.promptText(req))
                    }

                    // 413 rather than letting the model silently drop the
                    // front of the prompt, which looks like the model
                    // ignoring instructions.
                    val promptTokens = SystemInfo.estimateTokens(prompt)
                    if (promptTokens > MAX_CONTEXT_TOKENS) {
                        return@post call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ErrorResponse(
                                "prompt is about $promptTokens tokens; the context is " +
                                    "$MAX_CONTEXT_TOKENS",
                                "context_overflow"
                            )
                        )
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
                        // generateCapped, not generate: max_tokens used
                        // to be advisory, so a client asking for 300 could
                        // get a thousand. The cap stops generation rather
                        // than truncating after the fact, so the phone never
                        // spends the compute -- which matters most for
                        // Telugu, roughly 2.2x the tokens for the same text.
                        val r = try {
                            scheduler.submit(priorityOf(call)) {
                                engine.generateCapped(prompt, system, req.maxTokens)
                            }
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
                                    // Reported from what actually happened,
                                    // not assumed: "length" only when the
                                    // cap is what ended it.
                                    put("finish_reason", if (r.hitCap) "length" else "stop")
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

                // ------------------------------ pairing ------------------------------
                //
                // The one unauthenticated write endpoint. A web app cannot use
                // the intent handshake -- it has no package for Medha to
                // identify -- so the user vouches for it instead, by reading a
                // code off Medha's screen and typing it into the web app.
                //
                // Three things keep this from being an open door:
                //   - a code only exists for five minutes after the user taps
                //     "Pair a web app", and only one is ever live;
                //   - five wrong guesses destroy it;
                //   - a request with no usable Origin is refused outright,
                //     since there would be nothing to bind the token to.

                post(PAIR_PATH) {
                    val origin = WebOrigin.canonical(call.request.headers[HttpHeaders.Origin])
                    val req = runCatching { call.receive<PairRequest>() }.getOrNull()
                        ?: return@post call.badRequest("expected {\"code\":\"123456\"}")

                    when (val r = pairing.redeem(req.code, origin)) {
                        is PairingManager.Result.Ok -> {
                            // origin is non-null here: redeem() returns
                            // NoOrigin before reaching Ok otherwise.
                            val client = registry.createForWeb(origin!!, r.capabilities)
                            Diagnostics.i(TAG, "paired web app '${client.id}' at $origin")
                            // Rebuild nothing: allowedOrigins() reads the
                            // registry live, so the new origin works on the
                            // very next request.
                            call.respondJson(buildJsonObject {
                                put("token", client.token)
                                put("clientId", client.id)
                                put("namespace", client.namespace)
                                put("origin", origin)
                                putJsonArray("capabilities") {
                                    client.capabilities.sorted().forEach { add(it) }
                                }
                            })
                        }

                        is PairingManager.Result.NoOrigin -> call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                "pairing requires a browser Origin; a sandboxed frame or " +
                                    "file:// page cannot pair",
                                "no_origin"
                            )
                        )

                        is PairingManager.Result.WrongCode -> call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "wrong code; ${r.attemptsLeft} attempt(s) left",
                                "wrong_code"
                            )
                        )

                        is PairingManager.Result.Burned -> call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "too many wrong attempts; generate a new code in Medha",
                                "code_burned"
                            )
                        )

                        is PairingManager.Result.Expired -> call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("code expired; generate a new one in Medha", "code_expired")
                        )

                        is PairingManager.Result.NoPendingCode -> call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "no pairing code is active; open Medha and tap 'Pair a web app'",
                                "no_pending_code"
                            )
                        )
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
                    // Rate limited because this is the one endpoint where a
                    // client makes the *device* do something, and it is cheap
                    // to call. Everything else is gated by how slowly the
                    // model runs. See RateLimiter.
                    if (!notifyLimiter.allow(client.id)) {
                        val retry = notifyLimiter.retryAfterSeconds(client.id)
                        call.response.header(HttpHeaders.RetryAfter, retry.toString())
                        return@post call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse(
                                "notification rate limit reached; retry in ${retry}s",
                                "rate_limited"
                            )
                        )
                    }
                    val req = call.receive<NotifyRequest>()
                    if (req.title.isBlank()) return@post call.badRequest("title is required")
                    if (req.title.length > MAX_NOTIFY_TITLE) {
                        return@post call.badRequest("title exceeds $MAX_NOTIFY_TITLE characters")
                    }
                    if (req.text.length > MAX_NOTIFY_TEXT) {
                        return@post call.badRequest("text exceeds $MAX_NOTIFY_TEXT characters")
                    }
                    val ok = notifier.post(
                        NotificationHub.Request(
                            id = req.id, title = req.title, text = req.text,
                            ongoing = req.ongoing,
                            progressCurrent = req.progressCurrent, progressMax = req.progressMax,
                            silent = req.silent, clientId = client.id
                        )
                    )
                    call.respondJson(buildJsonObject {
                        put("posted", ok)
                        put("id", req.id)
                        // False means the post was refused by the platform,
                        // which on Android 13+ almost always means the user
                        // has not granted Medha POST_NOTIFICATIONS. Say so,
                        // because a bare "posted: false" sends integrators
                        // hunting through their own code.
                        if (!ok) put("reason", "notifications are disabled for Medha on this device")
                    })
                }

                delete("/notify/{id}") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@delete
                    notifier.cancel(client.id, call.parameters["id"].orEmpty())
                    call.respondJson(buildJsonObject { put("cancelled", true) })
                }

                // ------------------------------- the brief -------------------------------
                //
                // Medha's own digest surface, on the home screen, writable by
                // every paired client at once.
                //
                // This is what the Now Brief request resolves to. Samsung's
                // Now Brief has no third-party API -- the One UI 9 custom
                // cards are authored by the user in Settings and generated by
                // Samsung's AI, with no field an app can write -- so there is
                // nothing to integrate with. These endpoints are the writable
                // equivalent. See docs/NOTIFICATIONS.md.
                //
                // Each client owns a section. Writing replaces only your own.

                put("/widget/content") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@put
                    val req = call.receive<WidgetRequest>()
                    if (req.items.size > BriefPolicy.MAX_ITEMS_PER_SECTION) {
                        return@put call.badRequest(
                            "at most ${BriefPolicy.MAX_ITEMS_PER_SECTION} items per client"
                        )
                    }
                    // Plain `if` at the lambda's top level, not `?.let { return@put }`:
                    // a non-local return out of a nested lambda is rejected by
                    // the compiler here, and the rest of this file uses this
                    // shape anyway.
                    if (req.items.any { it.title.length > MAX_BRIEF_TITLE }) {
                        return@put call.badRequest("a title exceeds $MAX_BRIEF_TITLE characters")
                    }
                    if (req.items.any { it.text.length > MAX_BRIEF_TEXT }) {
                        return@put call.badRequest("a text exceeds $MAX_BRIEF_TEXT characters")
                    }
                    val stored = notifier.setBrief(
                        clientId = client.id,
                        items = req.items.map {
                            BriefPolicy.Item(it.title, it.text, it.link?.takeIf { l ->
                                BriefJson.isSafeLink(l)
                            })
                        },
                        ttlMs = req.ttlSeconds?.let { it * 1000 } ?: BriefPolicy.DEFAULT_TTL_MS
                    )
                    call.respondJson(buildJsonObject {
                        put("ok", true)
                        // What was actually kept, not what was sent. v0.9.0
                        // echoed the request size while silently rendering
                        // three of five items, so a client had no way to see
                        // the loss.
                        put("items", stored)
                        // Two different numbers, and conflating them was a
                        // bug: `rows` used to mean "drawn at once" when the
                        // widget had no paging, then silently became
                        // "capacity" when MAX_ROWS was raised to 12 for
                        // rotation. Both are reported, named for what they
                        // are, so a client can say "3 of your 7 showing"
                        // rather than guessing.
                        put("pageRows", BriefPolicy.PAGE_ROWS)
                        put("maxRows", BriefPolicy.MAX_ROWS)
                    })
                }

                get("/widget/content") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@get
                    // Namespace discipline, same as everywhere else: a client
                    // reads back its own section. Admin sees the whole brief,
                    // which is what the bundled UI uses to show the user what
                    // is on their home screen.
                    val all = notifier.brief()
                    val visible = if (client.isAdmin) all else all.filter { it.clientId == client.id }
                    val now = System.currentTimeMillis()
                    call.respondJson(buildJsonObject {
                        put("pageRows", BriefPolicy.PAGE_ROWS)
                        put("maxRows", BriefPolicy.MAX_ROWS)
                        put("contributors", all.size)
                        putJsonArray("sections") {
                            visible.forEach { s ->
                                addJsonObject {
                                    put("clientId", s.clientId)
                                    put("updatedAt", s.updatedAt)
                                    put("expiresAt", s.expiresAt)
                                    put("age", BriefPolicy.age(s.updatedAt, now))
                                    putJsonArray("items") {
                                        s.items.forEach { i ->
                                            addJsonObject {
                                                put("title", i.title)
                                                put("text", i.text)
                                                // Echoed back so a client can
                                                // tell a dropped link from a
                                                // kept one.
                                                if (i.link != null) put("link", i.link)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                }

                delete("/widget/content") {
                    val client = call.requireCap(ClientRegistry.Cap.NOTIFY) ?: return@delete
                    call.respondJson(buildJsonObject {
                        put("cleared", notifier.clearBrief(client.id))
                    })
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

        Diagnostics.i(TAG, "Medha server on http://$LOOPBACK:$port (auth=${if (requireAuth) "on" else "OFF"})")
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
        // Pairing has to be reachable without a token: obtaining one is the
        // entire point. It is not unprotected — it requires a six-digit code
        // that only exists while the user is looking at Medha's screen, and
        // burns after five wrong guesses. See PairingManager.
        if (path == PAIR_PATH) return true
        val lower = path.lowercase()
        return STATIC_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Origins permitted to read Medha's replies, recomputed per request.
     *
     * Not cached: a pairing has to take effect immediately, and more
     * importantly a revocation has to. A cached set would leave a revoked web
     * app able to read replies until the next service restart.
     */
    /**
     * The multimodal probe, run once and cached.
     *
     * Reflection over every method of five classes is not something to do
     * per request, and the answer cannot change while the process lives —
     * the AAR is fixed at install time.
     */
    private val multimodalProbe by lazy {
        MultimodalProbe.probeInstalled().also {
            Diagnostics.i(TAG, "multimodal probe: " + MultimodalProbe.summary(it))
            it.imageMethods.forEach { m -> Diagnostics.i(TAG, "  image candidate: $m") }
            it.audioMethods.forEach { m -> Diagnostics.i(TAG, "  audio candidate: $m") }
        }
    }

    private fun multimodal() = multimodalProbe

    private fun allowedOrigins(): Set<String> =
        CorsPolicy.loopbackOrigins(port) + registry.pairedWebOrigins()

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
        //
        // unordered(), not all(): this runs on every authenticated request
        // and all() sorts by createdAt, which nothing here needs.
        return registry.unordered().firstOrNull { constantTimeEquals(presented, it.token) }
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

        const val PAIR_PATH = "/pair"

        /** Android truncates well before these; they exist to bound memory. */
        private const val MAX_NOTIFY_TITLE = 200
        private const val MAX_NOTIFY_TEXT = 2000

        private const val NOTIFY_BURST = 10
        private const val NOTIFY_PER_MINUTE = 30

        /** Brief rows are one or two lines on a home screen, not prose. */
        /**
         * Advertised in /health as max_context. An estimate, because the
         * LiteRT-LM surface exposes no context length and the real figure
         * depends on the model file. Stated so a client can size prompts
         * and so the 413 below has a threshold; not a value to trust to the
         * token.
         */
        const val MAX_CONTEXT_TOKENS = 4096

        private const val MAX_BRIEF_TITLE = 80
        private const val MAX_BRIEF_TEXT = 200

        const val TOKEN_PLACEHOLDER = "__MEDHA_TOKEN__"
        const val PORT_PLACEHOLDER = "__MEDHA_PORT__"

        private val STATIC_SUFFIXES = listOf(
            ".html", ".js", ".css", ".png", ".svg", ".ico", ".webmanifest", ".woff2"
        )
    }
}

/**
 * Single place for the version string surfaced over HTTP and in the About
 * dialog.
 *
 * Sourced from [BuildConfig.VERSION_NAME], which Gradle generates from
 * `versionName` in app/build.gradle.kts — not hand-duplicated here. This used
 * to be a separately hand-maintained string literal, and it drifted: bumping
 * the Gradle version silently left this constant behind, so both `/system`'s
 * `"version"` field and the About screen kept reporting the old release after
 * a real version bump. A client polling `/system` to decide whether it needs
 * a newer server was being lied to, not just the UI.
 */
object BuildInfo {
    val VERSION: String = BuildConfig.VERSION_NAME
}
