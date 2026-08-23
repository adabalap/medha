package com.adabala.medha.sched

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
    @Volatile var config: SchedulerConfig = SchedulerConfig()
) {

    enum class Priority { INTERACTIVE, BATCH }

    class Rejected(val reason: String, val retryAfterSeconds: Int) : Exception(reason)

    /**
     * [SchedulerConfig.requestTimeoutMs] elapsed before [block] returned.
     *
     * Honest scope: [withTimeout] cancels cooperatively. A block whose actual
     * work is a blocking native call (the LiteRT decode) does not observe
     * cancellation mid-call — the underlying call keeps running and the mutex
     * stays held until it naturally returns. What this DOES guarantee: no
     * caller waits longer than [SchedulerConfig.requestTimeoutMs] for the engine to
     * become available, or for its own turn to finish once native execution is
     * genuinely responsive. A caller stacked behind a hung request now gets a
     * clear, bounded error instead of hanging forever — the queue no longer
     * fails silently and indefinitely, even though a truly wedged native call
     * is not itself force-killed. That still requires a process restart.
     */
    class TimedOut(val ms: Long) : Exception("generation exceeded ${ms}ms")

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
     * Throws [Rejected] if the request cannot be admitted, [TimedOut] if it
     * cannot be admitted within [SchedulerConfig.requestTimeoutMs].
     *
     * For callers that respond in one shot (`/generate`, `/chat`, the
     * non-streaming half of `/v1/chat/completions`). Streaming callers use
     * [acquire] instead — see its doc for why.
     */
    suspend fun <T> submit(priority: Priority, block: suspend () -> T): T {
        val permit = acquire(priority)
        return try {
            withTimeout(permit.remainingMs()) { block() }
        } catch (t: TimeoutCancellationException) {
            throw TimedOut(config.validated().requestTimeoutMs)
        } finally {
            permit.close()
        }
    }

    /**
     * Admits and gates like [submit], but returns control to the caller
     * holding the engine instead of running a block internally.
     *
     * Why streaming needs this and [submit] cannot be reused as-is: an SSE
     * response commits its headers the moment writing begins, and after that
     * a caller cannot cleanly turn a mid-stream failure into a 429/504 — the
     * status line is already on the wire. So the timeout here is scoped to
     * *admission* only (the queue wait and the thermal/battery gate wait,
     * both fully cancellable) and stops at the moment the engine is actually
     * handed over. A hang during the streamed native call itself is the same
     * known, documented limitation as [TimedOut]: not interruptible from
     * here. Callers must always call [Permit.close] (`use { }`), or the
     * engine and the admitted-request count leak.
     */
    suspend fun acquire(priority: Priority): Permit {
        val cfg = config.validated()
        val deadline = System.currentTimeMillis() + cfg.requestTimeoutMs

        if (depth.get() >= cfg.maxQueueDepth) {
            throw Rejected("queue full (${cfg.maxQueueDepth} in flight)", 5)
        }
        depth.incrementAndGet()
        if (priority == Priority.INTERACTIVE) interactiveWaiting.incrementAndGet()
        var admitted = false
        try {
            if (priority == Priority.BATCH) awaitBatchWindow(cfg)
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw TimedOut(cfg.requestTimeoutMs)
            try {
                withTimeout(remaining) { engineGate.lock() }
            } catch (t: TimeoutCancellationException) {
                throw TimedOut(cfg.requestTimeoutMs)
            }
            admitted = true
            if (priority == Priority.INTERACTIVE) interactiveWaiting.decrementAndGet()
            return Permit(priority, deadline)
        } finally {
            if (!admitted) {
                if (priority == Priority.INTERACTIVE) {
                    interactiveWaiting.updateAndGet { if (it > 0) it else 0 }
                }
                depth.decrementAndGet()
            }
        }
    }

    /** Held while the caller drives the engine. Release exactly once. */
    inner class Permit internal constructor(
        private val priority: Priority,
        private val deadline: Long
    ) : AutoCloseable {
        @Volatile private var released = false

        /** Time left of [SchedulerConfig.requestTimeoutMs] at the moment of admission. */
        fun remainingMs(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(1)

        override fun close() {
            if (released) return
            released = true
            depth.decrementAndGet()
            engineGate.unlock()
        }
    }

    /**
     * Blocks BATCH work until the device is cool enough, charged enough, and no
     * interactive request is waiting. Polls rather than subscribing because
     * `getThermalHeadroom` itself rejects calls made less than ~1s apart.
     */
    private suspend fun awaitBatchWindow(cfg: SchedulerConfig) {
        val deadline = System.currentTimeMillis() + cfg.maxGateWaitMs
        while (true) {
            val block = batchBlockReason(cfg)
            if (block == null && interactiveWaiting.get() == 0) {
                if (batchPaused) Diagnostics.i(TAG, "batch resumed")
                batchPaused = false
                lastGateReason = ""
                return
            }
            val reason = block ?: "yielding to interactive work"
            if (reason != lastGateReason) Diagnostics.i(TAG, "batch gated: $reason")
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
    private fun batchBlockReason(cfg: SchedulerConfig): String? {
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
