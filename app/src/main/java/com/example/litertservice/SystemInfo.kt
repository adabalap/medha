package com.example.litertservice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Device + runtime introspection.
 *
 * Honest note on backend: LiteRT-LM does not expose a "which accelerator am I
 * actually on" getter. We report the *configured* backend (what we asked for)
 * and whether the engine initialized successfully. If GPU init fails the runtime
 * may fall back internally; we surface "configured" vs "loadOk" so the UI can be
 * truthful rather than claim a backend we can't verify.
 */
object SystemInfo {

    fun deviceInfo(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "soc" to (runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"
        }.getOrDefault("unknown")),
        "androidSdk" to Build.VERSION.SDK_INT.toString(),
        "abis" to Build.SUPPORTED_ABIS.joinToString(",")
    )

    data class Memory(
        val totalMb: Long, val availMb: Long, val usedMb: Long,
        val lowMemory: Boolean, val appUsedMb: Long
    )

    fun memory(context: Context): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val availMb = mi.availMem / (1024 * 1024)
        val rt = Runtime.getRuntime()
        val appUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return Memory(
            totalMb = totalMb,
            availMb = availMb,
            usedMb = max(0, totalMb - availMb),
            lowMemory = mi.lowMemory,
            appUsedMb = appUsedMb
        )
    }

    // --- Aggregate inference metrics (thread-safe, cheap) ---
    private val requests = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val totalMs = AtomicLong(0)
    @Volatile private var lastTokensPerSec: Double = 0.0
    @Volatile private var lastLatencyMs: Long = 0

    fun record(tokens: Int, ms: Long) {
        requests.incrementAndGet()
        totalTokens.addAndGet(tokens.toLong())
        totalMs.addAndGet(ms)
        lastLatencyMs = ms
        lastTokensPerSec = if (ms > 0) tokens * 1000.0 / ms else 0.0
    }

    fun metrics(): Map<String, Any> {
        val reqs = requests.get()
        val toks = totalTokens.get()
        val ms = totalMs.get()
        return mapOf(
            "totalRequests" to reqs,
            "totalTokens" to toks,
            "avgTokensPerSec" to if (ms > 0) toks * 1000.0 / ms else 0.0,
            "avgLatencyMs" to if (reqs > 0) ms / reqs else 0L,
            "lastTokensPerSec" to lastTokensPerSec,
            "lastLatencyMs" to lastLatencyMs
        )
    }

    /** Rough token estimate when the engine doesn't return a count (~4 chars/token). */
    fun estimateTokens(text: String): Int = max(1, text.length / 4)
}
