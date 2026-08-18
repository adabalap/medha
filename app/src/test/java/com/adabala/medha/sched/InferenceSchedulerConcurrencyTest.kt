package com.adabala.medha.sched

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the admission-control, queue-cap, and timeout behavior added to
 * [InferenceScheduler] in this change. Everything here uses
 * [InferenceScheduler.Priority.INTERACTIVE] deliberately: that path never
 * calls [SystemInfo.thermalHeadroom] or touches [android.os.BatteryManager],
 * so a relaxed mock [Context] that's never actually invoked is sufficient —
 * there is no need for Robolectric here. Thermal/battery gating itself
 * (the BATCH path) is not covered by these tests; it needs real Android
 * sensor behavior and is exercised in the manual on-device checklist in
 * docs/TESTING.md instead.
 *
 * Run: ./gradlew testCoreDebugUnitTest --tests "*InferenceSchedulerConcurrencyTest*"
 *
 * NOT executed as part of the sandboxed review that produced this file: this
 * sandbox has no path to a real kotlinx-coroutines-core jar (Maven Central is
 * blocked, and the project isn't published as a raw jar via GitHub Releases),
 * so `withTimeout`/`Mutex`/`StandardTestDispatcher` can't be compiled here at
 * all. Every assertion below was worked through by hand against the actual
 * acquire()/submit()/Permit implementation, but you should treat a first
 * green run of this file — not this message — as the real confirmation.
 */
class InferenceSchedulerConcurrencyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
    }

    private fun schedulerWith(
        maxQueueDepth: Int = 8,
        requestTimeoutMs: Long = 120_000
    ) = InferenceScheduler(
        context,
        SchedulerConfig(maxQueueDepth = maxQueueDepth, requestTimeoutMs = requestTimeoutMs)
    )

    @Test
    fun `queue depth returns to zero after a request completes`() = runTest {
        val scheduler = schedulerWith()
        assertEquals(0, scheduler.queueDepth)
        scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { "ok" }
        assertEquals(0, scheduler.queueDepth)
    }

    @Test
    fun `queue depth returns to zero even when the block throws`() = runTest {
        val scheduler = schedulerWith()
        val thrown = runCatching {
            scheduler.submit<Unit>(InferenceScheduler.Priority.INTERACTIVE) {
                error("boom")
            }
        }.exceptionOrNull()
        assertTrue("block's own exception must propagate", thrown is IllegalStateException)
        assertEquals(
            "a thrown block must still release its admission slot",
            0,
            scheduler.queueDepth
        )
    }

    @Test
    fun `admission beyond maxQueueDepth is rejected with 429-shaped exception`() = runTest {
        val scheduler = schedulerWith(maxQueueDepth = 2)
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()

        // Two requests that hold their admission slot open until released.
        val job1 = launch {
            scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { gate1.await() }
        }
        val job2 = launch {
            scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { gate2.await() }
        }
        // Let both actually run up through their own admission (depth++)
        // before probing — runCurrent() drains ready work without moving
        // virtual time, so it doesn't accidentally trigger any timeout.
        runCurrent()
        assertEquals(2, scheduler.queueDepth)

        // A third request must be turned away immediately — not queued.
        val rejected = runCatching {
            scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { "should not run" }
        }.exceptionOrNull()
        assertTrue(
            "3rd request over a maxQueueDepth of 2 must throw Rejected",
            rejected is InferenceScheduler.Rejected
        )
        assertEquals(
            "queue-full rejection should tell the client a short, sane retry wait",
            5,
            (rejected as InferenceScheduler.Rejected).retryAfterSeconds
        )

        gate1.complete(Unit)
        gate2.complete(Unit)
        job1.join()
        job2.join()
        assertEquals(0, scheduler.queueDepth)
    }

    @Test
    fun `a request queued behind a hung one times out instead of hanging forever`() = runTest {
        val scheduler = schedulerWith(requestTimeoutMs = 1_000)
        val neverCompletes = CompletableDeferred<Unit>()

        // Request 1 holds the engine mutex indefinitely (simulates a wedged
        // native call — exactly the scenario TimedOut exists for).
        val holder = launch {
            scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { neverCompletes.await() }
        }
        runCurrent() // let `holder` actually take the mutex before probing

        // Request 2 queues behind it. Once this coroutine and `holder` are
        // the only two left runnable, and neither can progress except via
        // the pending internal withTimeout deadline, runTest's virtual clock
        // auto-advances to that deadline — the standard, documented way
        // kotlinx-coroutines-test resolves a real `delay`/`withTimeout`
        // without an actual real-time wait in the test.
        val thrown = runCatching {
            scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { "unreachable" }
        }.exceptionOrNull()

        assertTrue(
            "a caller stacked behind a hung request must get TimedOut, not hang",
            thrown is InferenceScheduler.TimedOut
        )

        neverCompletes.complete(Unit)
        holder.join()
        assertEquals(0, scheduler.queueDepth)
    }

    @Test
    fun `two interactive requests never run concurrently`() = runTest {
        val scheduler = schedulerWith()
        var inFlight = 0
        var sawOverlap = false

        suspend fun work() {
            inFlight++
            if (inFlight > 1) sawOverlap = true
            delay(50)
            inFlight--
        }

        val j1 = launch { scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { work() } }
        val j2 = launch { scheduler.submit(InferenceScheduler.Priority.INTERACTIVE) { work() } }
        j1.join()
        j2.join()

        assertFalse("the engine mutex must serialise concurrent callers", sawOverlap)
    }

    @Test
    fun `acquire-permit pair used directly releases the slot on close`() = runTest {
        val scheduler = schedulerWith()
        val permit = scheduler.acquire(InferenceScheduler.Priority.INTERACTIVE)
        assertEquals(1, scheduler.queueDepth)
        permit.close()
        assertEquals(0, scheduler.queueDepth)
        // Idempotent: a second close() must not double-decrement below zero.
        permit.close()
        assertEquals(0, scheduler.queueDepth)
    }
}
