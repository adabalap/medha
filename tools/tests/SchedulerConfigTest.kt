/**
 * Standalone JVM test for [com.adabala.medha.sched.SchedulerConfig.validated].
 *
 * Compiles and runs against the REAL source file
 * (app/src/main/java/com/adabala/medha/sched/SchedulerConfig.kt) — no copy,
 * no mock. This is possible because SchedulerConfig has zero Android or
 * kotlinx.coroutines dependencies, unlike InferenceScheduler.kt itself, which
 * needs a real Gradle/Android build to compile at all. See
 * app/src/test/java/.../InferenceSchedulerConcurrencyTest.kt for the parts
 * that genuinely need coroutines and must run via Gradle instead.
 *
 * Run with: tools/tests/run.sh
 */
import com.adabala.medha.sched.SchedulerConfig

private var failures = 0
private var total = 0

private fun check(label: String, condition: Boolean) {
    total++
    if (!condition) {
        failures++
        println("FAIL: $label")
    }
}

private fun approx(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) < eps

fun main() {
    // --- Defaults pass through unchanged ---
    run {
        val c = SchedulerConfig().validated()
        check("default maxQueueDepth stays 8", c.maxQueueDepth == 8)
        check("default thermalPauseAt stays 0.85", approx(c.thermalPauseAt, 0.85f))
        check("default thermalResumeAt stays 0.70", approx(c.thermalResumeAt, 0.70f))
    }

    // --- The exact bug the code comment describes: pause=0.0 must not throw ---
    run {
        val c = SchedulerConfig(thermalPauseAt = 0.0f, thermalResumeAt = 0.0f).validated()
        check("pause=0.0 clamps to MIN_PAUSE", approx(c.thermalPauseAt, SchedulerConfig.MIN_PAUSE))
        check(
            "resume=0.0 clamps to MIN_RESUME when pause floor allows it",
            approx(c.thermalResumeAt, SchedulerConfig.MIN_RESUME)
        )
        check("resume stays strictly below pause", c.thermalResumeAt < c.thermalPauseAt)
    }

    // --- Hysteresis gap is enforced even when caller sets resume == pause ---
    run {
        val c = SchedulerConfig(thermalPauseAt = 0.5f, thermalResumeAt = 0.5f).validated()
        check(
            "equal pause/resume still separated by >= HYSTERESIS",
            c.thermalPauseAt - c.thermalResumeAt >= SchedulerConfig.HYSTERESIS - 1e-4f
        )
    }

    // --- Hysteresis gap is enforced even when caller sets resume ABOVE pause ---
    run {
        val c = SchedulerConfig(thermalPauseAt = 0.4f, thermalResumeAt = 0.9f).validated()
        check(
            "inverted pause/resume input still resolves resume < pause",
            c.thermalResumeAt < c.thermalPauseAt
        )
    }

    // --- Extreme values clamp into range rather than propagating garbage ---
    run {
        val c = SchedulerConfig(thermalPauseAt = 99f, thermalResumeAt = -99f).validated()
        check("huge pause clamps to MAX_PAUSE", approx(c.thermalPauseAt, SchedulerConfig.MAX_PAUSE))
        check("huge negative resume clamps to MIN_RESUME", approx(c.thermalResumeAt, SchedulerConfig.MIN_RESUME))
    }

    // --- Queue depth ceiling ---
    run {
        check("queue depth 0 clamps to 1 (never fully closed)", SchedulerConfig(maxQueueDepth = 0).validated().maxQueueDepth == 1)
        check("queue depth -5 clamps to 1", SchedulerConfig(maxQueueDepth = -5).validated().maxQueueDepth == 1)
        check("queue depth 1000 clamps to 64", SchedulerConfig(maxQueueDepth = 1000).validated().maxQueueDepth == 64)
        check("queue depth 30 passes through unchanged", SchedulerConfig(maxQueueDepth = 30).validated().maxQueueDepth == 30)
    }

    // --- Battery percent ceiling ---
    run {
        check("battery -10 clamps to 0", SchedulerConfig(batchMinBatteryPercent = -10).validated().batchMinBatteryPercent == 0)
        check("battery 100 clamps to 95", SchedulerConfig(batchMinBatteryPercent = 100).validated().batchMinBatteryPercent == 95)
    }

    // --- requestTimeoutMs and maxGateWaitMs pass through unclamped ---
    // (a test suite is exactly the caller that needs an explicit short
    // timeout to honor what it asked for, not a silently raised floor)
    run {
        val c = SchedulerConfig(requestTimeoutMs = 50L, maxGateWaitMs = 10L).validated()
        check("requestTimeoutMs=50 is not silently raised", c.requestTimeoutMs == 50L)
        check("maxGateWaitMs=10 is not silently raised", c.maxGateWaitMs == 10L)
    }

    println("SchedulerConfigTest: $total checks, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
