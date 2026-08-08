package com.adabala.medha

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Device + runtime introspection.
 *
 * Honest note on backend: LiteRT-LM does not expose a "which accelerator am I
 * actually on" getter. We report the *configured* backend (what we asked for)
 * and whether the engine initialized successfully. If GPU init fails the
 * runtime may fall back internally; we surface "configured" vs "loadOk" so the
 * UI can be truthful rather than claim a backend we cannot verify.
 */
object SystemInfo {

    private val processStart = System.currentTimeMillis()

    fun deviceInfo(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "soc" to runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"
        }.getOrDefault("unknown"),
        "androidSdk" to Build.VERSION.SDK_INT.toString(),
        "abis" to Build.SUPPORTED_ABIS.joinToString(",")
    )

    data class Memory(
        val totalMb: Long,
        val availMb: Long,
        val usedMb: Long,
        val lowMemory: Boolean,
        val appUsedMb: Long,
        val appMaxMb: Long
    )

    fun memory(context: Context): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / MB
        val availMb = mi.availMem / MB
        val rt = Runtime.getRuntime()
        return Memory(
            totalMb = totalMb,
            availMb = availMb,
            usedMb = max(0, totalMb - availMb),
            lowMemory = mi.lowMemory,
            appUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
            appMaxMb = rt.maxMemory() / MB
        )
    }

    /** Free space where the model and DB live. A stalled import is usually this. */
    fun freeStorageMb(context: Context): Long = runCatching {
        val fs = StatFs(context.filesDir.absolutePath)
        (fs.availableBlocksLong * fs.blockSizeLong) / MB
    }.getOrDefault(-1L)

    /**
     * Thermal headroom matters a lot for sustained on-device inference: a
     * throttled SoC halves tokens/sec, and without this the dashboard makes it
     * look like the model got slower for no reason.
     */
    fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }

    /**
     * Forecast of how close the device is to throttling, 0.0 (cool) to 1.0+
     * (throttling now). API 30+ only.
     *
     * This is the number that actually explains a tokens/sec collapse.
     * [thermalStatus] is coarse and lags; headroom moves first. Returns -1 when
     * unavailable — the API also rejects calls made less than ~1s apart, and
     * needs a warm-up period after boot, so treat -1 as "no reading", not "cool".
     */
    fun thermalHeadroom(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1f
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val v = pm.getThermalHeadroom(FORECAST_SECONDS)
            if (v.isNaN() || v.isInfinite()) -1f else v
        }.getOrDefault(-1f)
    }

    /** Per-core CPU count and max clock, where the kernel exposes it. */
    fun cpuInfo(): Map<String, String> = runCatching {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = (0 until cores).mapNotNull { i ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }.getOrNull()
        }
        mapOf(
            "cpuCores" to cores.toString(),
            "cpuMaxMhz" to (maxKhz.maxOrNull()?.let { (it / 1000).toString() } ?: "unknown")
        )
    }.getOrDefault(mapOf("cpuCores" to "unknown", "cpuMaxMhz" to "unknown"))

    fun uptimeMs(): Long = System.currentTimeMillis() - processStart

    // ---- Aggregate inference metrics (thread-safe, cheap) ----
    private val requests = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val promptTokens = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val totalMs = AtomicLong(0)

    @Volatile private var lastTokensPerSec: Double = 0.0
    @Volatile private var lastLatencyMs: Long = 0

    fun record(promptToks: Int, completionToks: Int, ms: Long) {
        requests.incrementAndGet()
        promptTokens.addAndGet(promptToks.toLong())
        totalTokens.addAndGet(completionToks.toLong())
        totalMs.addAndGet(ms)
        lastLatencyMs = ms
        lastTokensPerSec = if (ms > 0) completionToks * 1000.0 / ms else 0.0
    }

    fun recordFailure() {
        failures.incrementAndGet()
    }

    fun metrics(): Map<String, Any> {
        val reqs = requests.get()
        val toks = totalTokens.get()
        val ms = totalMs.get()
        return mapOf(
            "totalRequests" to reqs,
            "totalFailures" to failures.get(),
            "totalPromptTokens" to promptTokens.get(),
            "totalTokens" to toks,
            "avgTokensPerSec" to if (ms > 0) toks * 1000.0 / ms else 0.0,
            "avgLatencyMs" to if (reqs > 0) ms / reqs else 0L,
            "lastTokensPerSec" to lastTokensPerSec,
            "lastLatencyMs" to lastLatencyMs,
            "uptimeMs" to uptimeMs()
        )
    }

    /**
     * Rough token estimate when the engine does not return a count (~4 chars per
     * token for English). Reported as an estimate everywhere it surfaces; do not
     * bill anything on it.
     */
    fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else max(1, text.length / 4)

    private const val MB = 1024L * 1024L

    /** Headroom forecast window. 10s is a reasonable near-term signal. */
    private const val FORECAST_SECONDS = 10
}
