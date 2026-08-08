package com.adabala.medha.sched

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.adabala.medha.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Admission control, prioritisation and thermal/battery gating for inference.
 *
 * ## Why a scheduler and not threads
 *
 * One native engine cannot be driven from two threads, and GPU/NPU is a single
 * shared resource. Two concurrent decodes do not double throughput — they
 * contend for the same silicon, produce the same heat, and make latency worse
 * for both. Parallelism is not the lever. *Ordering* and *pacing* are.
 *
 * ## What it does
 *
 * - **Priority.** [Priority.INTERACTIVE] work (a human is waiting) is admitted
 *   ahead of [Priority.BATCH] work. Classifying an SMS backlog is BATCH.
 * - **Thermal gating.** BATCH work pauses above a configurable headroom
 *   watermark and resumes below a lower one. Two watermarks, not one, because a
 *   single threshold makes the queue oscillate on and off at the boundary.
 *   INTERACTIVE work is never thermally blocked — a user tapping a button
 *   should get an answer even on a warm phone; it is sustained batch decode
 *   that actually cooks the SoC.
 * - **Battery gating.** BATCH can be restricted to charging, or to a minimum
 *   battery level.
 * - **Admission control.** Bounded queue; excess returns 429 rather than
 *   building an unbounded backlog when a PWA loops.
 *
 * ## On temperature units
 *
 * The UI talks in "headroom" (0.0 cool -> 1.0 throttling), not degrees.
 * Android exposes no per-core temperature to normal apps; `getThermalHeadroom`
 * is a normalised forecast and is the only reading available without root.
 * Presenting a °C figure would mean inventing one.
 */
class InferenceScheduler(
    private val appContext: Context,
    @Volatile var config: Config = Config()
) {

    enum class Priority { INTERACTIVE, BATCH }

    data class Config(
        /** Reject new work beyond this many queued+running requests. */
        val maxQueueDepth: Int = 8,
        /** BATCH pauses at or above this headroom. */
        val thermalPauseAt: Float = 0.85f,
        /** BATCH resumes at or below this headroom. Must be < [thermalPauseAt]. */
        val thermalResumeAt: Float = 0.70f,
        /** BATCH only runs while charging. */
        val batchRequiresCharging: Boolean = false,
        /** BATCH only runs at or above this battery percent (0 disables). */
        val batchMinBatteryPercent: Int = 20,
        /** Give up waiting for a thermal window after this long. */
        val maxGateWaitMs: Long = 5 * 60 * 1000,
        /** Hard ceiling on a single generation. */
        val requestTimeoutMs: Long = 120_000
    ) {
        fun validated(): Config {
            // Clamp the pause watermark FIRST, then derive the resume bound
            // from the clamped value. Doing both inside one copy() reads the
            // raw receiver for the second expression, so a pause value of 0.0
            // produced an empty coercion range and threw.
            val pause = thermalPauseAt.coerceIn(MIN_PAUSE, MAX_PAUSE)
            // Enforce the hysteresis gap rather than trusting the caller: equal
            // watermarks reintroduce exactly the oscillation they prevent.
            val resume = thermalResumeAt.coerceIn(MIN_RESUME, pause - HYSTERESIS)
            return copy(
                maxQueueDepth = maxQueueDepth.coerceIn(1, 64),
                thermalPauseAt = pause,
                thermalResumeAt = resume,
                batchMinBatteryPercent = batchMinBatteryPercent.coerceIn(0, 95)
            )
        }

        companion object {
            const val MIN_PAUSE = 0.30f
            const val MAX_PAUSE = 1.50f
            const val MIN_RESUME = 0.20f
            /** MIN_PAUSE - HYSTERESIS must stay >= MIN_RESUME or the range empties. */
            const val HYSTERESIS = 0.05f
        }
    }

    class Rejected(val reason: String, val retryAfterSeconds: Int) : Exception(reason)

    /** Serialises the engine. Interactive callers queue ahead by admission order. */
    private val engineGate = Mutex()
    private val depth = AtomicInteger(0)
    private val interactiveWaiting = AtomicInteger(0)

    @Volatile private var batchPaused = false
    @Volatile private var lastGateReason: String = ""

    val queueDepth: Int get() = depth.get()
    val isBatchPaused: Boolean get() = batchPaused
    val gateReason: String get() = lastGateReason

    /**
     * Admits, gates, then runs [block] with the engine held exclusively.
     * Throws [Rejected] if the request cannot be admitted.
     */
    suspend fun <T> submit(priority: Priority, block: suspend () -> T): T {
        val cfg = config.validated()

        if (depth.get() >= cfg.maxQueueDepth) {
            throw Rejected("queue full (${cfg.maxQueueDepth} in flight)", 5)
        }
        depth.incrementAndGet()
        if (priority == Priority.INTERACTIVE) interactiveWaiting.incrementAndGet()
        try {
            if (priority == Priority.BATCH) awaitBatchWindow(cfg)
            return engineGate.withLock {
                if (priority == Priority.INTERACTIVE) interactiveWaiting.decrementAndGet()
                block()
            }
        } finally {
            if (priority == Priority.INTERACTIVE) {
                // Guard against double-decrement on the throw paths above.
                interactiveWaiting.updateAndGet { if (it > 0) it else 0 }
            }
            depth.decrementAndGet()
        }
    }

    /**
     * Blocks BATCH work until the device is cool enough, charged enough, and no
     * interactive request is waiting. Polls rather than subscribing because
     * `getThermalHeadroom` itself rejects calls made less than ~1s apart.
     */
    private suspend fun awaitBatchWindow(cfg: Config) {
        val deadline = System.currentTimeMillis() + cfg.maxGateWaitMs
        while (true) {
            val block = batchBlockReason(cfg)
            if (block == null && interactiveWaiting.get() == 0) {
                if (batchPaused) Log.i(TAG, "batch resumed")
                batchPaused = false
                lastGateReason = ""
                return
            }
            val reason = block ?: "yielding to interactive work"
            if (reason != lastGateReason) Log.i(TAG, "batch gated: $reason")
            batchPaused = block != null
            lastGateReason = reason

            if (System.currentTimeMillis() > deadline) {
                throw Rejected("batch gated too long: $reason", 120)
            }
            try {
                delay(POLL_MS)
            } catch (c: CancellationException) {
                throw c
            }
        }
    }

    /** Null when batch may run; otherwise a human-readable reason. */
    private fun batchBlockReason(cfg: Config): String? {
        val hr = SystemInfo.thermalHeadroom(appContext)
        if (hr >= 0f) {
            // Hysteresis: once paused, require the lower watermark to resume.
            val limit = if (batchPaused) cfg.thermalResumeAt else cfg.thermalPauseAt
            if (hr >= limit) {
                return "thermal headroom %.2f >= %.2f".format(hr, limit)
            }
        } else {
            // No headroom reading (pre-Android 11, or not yet warmed up). Fall
            // back to the coarse status so gating is not simply disabled.
            when (SystemInfo.thermalStatus(appContext)) {
                "severe", "critical", "emergency", "shutdown" ->
                    return "thermal status severe or worse"
            }
        }

        val (charging, level) = batteryState()
        if (cfg.batchRequiresCharging && !charging) return "waiting for charger"
        if (!charging && cfg.batchMinBatteryPercent > 0 && level in 0 until cfg.batchMinBatteryPercent) {
            return "battery $level% below ${cfg.batchMinBatteryPercent}%"
        }
        return null
    }

    private fun batteryState(): Pair<Boolean, Int> = runCatching {
        val i: Intent? = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        charging to if (level >= 0 && scale > 0) level * 100 / scale else -1
    }.getOrDefault(false to -1)

    fun status(): Map<String, Any> {
        val cfg = config.validated()
        val (charging, level) = batteryState()
        return mapOf(
            "queueDepth" to depth.get(),
            "maxQueueDepth" to cfg.maxQueueDepth,
            "interactiveWaiting" to interactiveWaiting.get(),
            "batchPaused" to batchPaused,
            "gateReason" to lastGateReason,
            "thermalHeadroom" to SystemInfo.thermalHeadroom(appContext),
            "thermalPauseAt" to cfg.thermalPauseAt,
            "thermalResumeAt" to cfg.thermalResumeAt,
            "charging" to charging,
            "batteryPercent" to level,
            "batchRequiresCharging" to cfg.batchRequiresCharging,
            "batchMinBatteryPercent" to cfg.batchMinBatteryPercent
        )
    }

    companion object {
        private const val TAG = "Scheduler"
        private const val POLL_MS = 2000L
    }
}
