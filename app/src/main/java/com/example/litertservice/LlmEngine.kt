package com.example.litertservice

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Wrapper around the LiteRT-LM Kotlin API. All LiteRT specifics live here.
 *
 * One Engine holds the loaded .litertlm model for the life of the service.
 * Each request uses a throwaway Conversation for statelessness; the memory/
 * multi-turn layer lives above this in MemoryRepository.
 */
class LlmEngine(private val appContext: Context) {

    enum class ConfiguredBackend { GPU, CPU, NPU }

    @Volatile private var engine: Engine? = null
    @Volatile var loadedModelPath: String? = null; private set
    @Volatile var configuredBackend: ConfiguredBackend = ConfiguredBackend.GPU; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var loadMs: Long = 0; private set

    val isLoaded: Boolean get() = engine != null

    @Synchronized
    fun load(modelPath: String, backend: ConfiguredBackend) {
        val f = File(modelPath)
        require(f.exists() && f.canRead()) { "Model file not found or unreadable: $modelPath" }
        close()
        lastError = null
        configuredBackend = backend

        val be = when (backend) {
            ConfiguredBackend.GPU -> Backend.GPU()
            ConfiguredBackend.CPU -> Backend.CPU()
            ConfiguredBackend.NPU -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
        }
        val config = EngineConfig(
            modelPath = modelPath,
            backend = be,
            cacheDir = appContext.cacheDir.path
        )
        val start = System.currentTimeMillis()
        val e = Engine(config)
        e.initialize()
        loadMs = System.currentTimeMillis() - start
        engine = e
        loadedModelPath = modelPath
        Log.i(TAG, "Engine initialized: $modelPath backend=$backend in ${loadMs}ms")
    }

    /** Blocking one-shot generation. Returns text + measured timing. */
    @Synchronized
    fun generate(prompt: String, systemInstruction: String? = null): Result {
        val e = engine ?: error("No model loaded")
        val start = System.currentTimeMillis()
        val text = e.createConversation().use { conv ->
            extractText(conv.sendMessage(prompt))
        }
        val ms = System.currentTimeMillis() - start
        val tokens = SystemInfo.estimateTokens(text)
        SystemInfo.record(tokens, ms)
        return Result(text, tokens, ms)
    }

    /**
     * Streaming generation as a Flow of text deltas. Uses sendMessageAsync's Flow
     * form when available; falls back to a single emission if streaming isn't
     * supported by the installed AAR.
     */
    fun generateStream(prompt: String): Flow<String> = flow {
        val e = engine ?: error("No model loaded")
        val conv = e.createConversation()
        try {
            val flowMethod = runCatching {
                conv.javaClass.getMethod("sendMessageAsync", String::class.java)
            }.getOrNull()
            if (flowMethod != null) {
                @Suppress("UNCHECKED_CAST")
                val f = flowMethod.invoke(conv, prompt) as? Flow<Any?>
                if (f != null) {
                    f.collect { msg -> emit(extractText(msg)) }
                    return@flow
                }
            }
            // Fallback: non-streaming
            emit(extractText(conv.sendMessage(prompt)))
        } finally {
            runCatching { conv.close() }
        }
    }

    private fun extractText(msg: Any?): String {
        if (msg == null) return ""
        return runCatching {
            (msg.javaClass.getMethod("getText").invoke(msg) as? String) ?: msg.toString()
        }.getOrElse { msg.toString() }
    }

    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null
        loadedModelPath = null
    }

    fun recordError(msg: String?) { lastError = msg }

    data class Result(val text: String, val tokens: Int, val ms: Long)

    companion object { private const val TAG = "LlmEngine" }
}
