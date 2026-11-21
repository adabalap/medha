package com.adabala.medha.sched

/**
 * [InferenceScheduler]'s tunables, deliberately in their own file with zero
 * Android or kotlinx.coroutines imports.
 *
 * Why split out of InferenceScheduler.kt: that file needs a real Android
 * classpath and a real kotlinx.coroutines jar just to *compile*, because
 * Kotlin type-checks a file as a whole rather than only the symbols a given
 * test exercises. [validated]'s clamping is pure arithmetic with real bugs
 * possible (the pause/resume hysteresis, the queue-depth ceiling) — keeping
 * it here means that arithmetic can be compiled and unit tested on a plain
 * JVM classpath, in CI or a sandbox, with nothing else pulled in.
 */
data class SchedulerConfig(
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
    /**
     * Ceiling on how long one admitted request may wait for the engine plus
     * run, before [InferenceScheduler.TimedOut] is thrown. See that class for
     * exactly what this does and does not stop.
     */
    val requestTimeoutMs: Long = 120_000
) {
    fun validated(): SchedulerConfig {
        // Clamp the pause watermark FIRST, then derive the resume bound from
        // the clamped value. Doing both inside one copy() reads the raw
        // receiver for the second expression, so a pause value of 0.0
        // produced an empty coercion range and threw.
        val pause = thermalPauseAt.coerceIn(MIN_PAUSE, MAX_PAUSE)
        // Enforce the hysteresis gap rather than trusting the caller: equal
        // watermarks reintroduce exactly the oscillation they prevent.
        val resume = thermalResumeAt.coerceIn(MIN_RESUME, pause - HYSTERESIS)
        return copy(
            maxQueueDepth = maxQueueDepth.coerceIn(1, 64),
            thermalPauseAt = pause,
            thermalResumeAt = resume,
            batchMinBatteryPercent = batchMinBatteryPercent.coerceIn(0, 95),
            // requestTimeoutMs and maxGateWaitMs are intentionally
            // unclamped: a caller who explicitly sets a very short timeout
            // (e.g. in a test) should get exactly that, not a silently
            // raised floor.
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
