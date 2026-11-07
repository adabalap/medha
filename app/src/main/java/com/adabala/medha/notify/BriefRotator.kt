package com.adabala.medha.notify

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.adabala.medha.diag.Diagnostics

/**
 * Advances the brief widget through its pages on a timer.
 *
 * ## Why this is not done by the widget framework
 *
 * The obvious mechanism is `updatePeriodMillis` in the appwidget metadata.
 * It cannot do this: **Android clamps it to a 30-minute minimum**, silently.
 * Asking for 3000 gets you half an hour.
 *
 * The next idea is `AlarmManager`. Also wrong: `setRepeating` has been
 * inexact since API 19, Doze and App Standby throttle frequent alarms, and a
 * three-second repeating alarm is the kind of thing that gets an app blamed
 * for battery drain — correctly.
 *
 * `AdapterViewFlipper` with `android:autoStart` would let the *launcher*
 * animate at no cost to Medha, which is genuinely the most efficient option.
 * It is not used here for two reasons: the flip interval is a layout
 * attribute, so making it user-configurable would mean shipping one layout
 * per interval choice; and it requires a `RemoteViewsService` and a
 * `RemoteViewsFactory`, which is a large amount of untestable API surface in
 * a project that cannot run a device test. Worth revisiting, and noted in
 * docs/NOTIFICATIONS.md rather than left as a silent omission.
 *
 * So the tick comes from Medha's own foreground service, which is already
 * running with a wake lock whenever the brief is being written to.
 *
 * ## What keeps that from being wasteful
 *
 * Three gates, all of which must hold before a single tick fires:
 *
 *  1. **A widget is actually placed.** No widget, no work.
 *  2. **The screen is on.** Rotating a widget nobody can see is pure waste,
 *     and this is the gate that matters most — it ties the cost to screen-on
 *     time rather than to uptime. Android offers no way to know whether a
 *     widget is *visible* (the user may be in another app), so screen state
 *     is the best available proxy and is honestly just a proxy.
 *  3. **The brief overflows one page.** With three or fewer rows there is
 *     nothing to rotate, and ticking anyway would make a static brief
 *     flicker.
 *
 * When any gate closes the handler is unscheduled, not skipped — an idle
 * rotator posts no messages at all.
 */
class BriefRotator(
    private val appContext: Context,
    private val hub: NotificationHub
) {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * A monotonic counter, not a page index. [BriefPolicy.page] takes the
     * modulo, so this never needs to know how many pages exist — which
     * matters because the brief changes underneath it as clients write and
     * sections expire.
     */
    private var tick = 0L

    @Volatile private var intervalMs: Long = DEFAULT_INTERVAL_MS
    @Volatile private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> reschedule()
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(step)
            }
        }
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            // Gates are re-checked every tick, not just when scheduling, so
            // the rotator stops on its own when the brief shrinks below a
            // page or the screen goes off without the broadcast arriving.
            // Stopping means posting nothing further -- an idle rotator is
            // genuinely idle, not a slow poll.
            if (!shouldRotate()) return
            tick++
            MedhaWidgetProvider.refresh(appContext, tick)
            handler.postDelayed(this, intervalMs)
        }
    }

    /**
     * Starts rotating, or adjusts the interval, or stops — whichever the
     * current settings and state call for. Safe to call repeatedly; the
     * service calls it on every start so a changed setting takes effect
     * without anything having to be torn down.
     *
     * [intervalSeconds] of 0 means rotation off.
     */
    fun configure(intervalSeconds: Long) {
        val wasRunning = running
        intervalMs = (intervalSeconds * 1000)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        if (intervalSeconds <= 0) {
            stop()
            // Redraw once so the widget drops the page indicator and settles
            // on page 0 rather than freezing wherever rotation left it.
            tick = 0
            MedhaWidgetProvider.refresh(appContext, 0)
            return
        }

        if (!wasRunning) {
            running = true
            runCatching {
                // Two-arg registerReceiver with no RECEIVER_EXPORTED /
                // RECEIVER_NOT_EXPORTED flag is deliberate and legal ONLY
                // because this filter contains nothing but protected system
                // broadcasts. Apps targeting API 34+ that register for any
                // non-system broadcast without a flag get a
                // SecurityException at registration time.
                //
                // So: if you ever add an action to this filter that is not a
                // system broadcast, you must also pass
                // ContextCompat.RECEIVER_NOT_EXPORTED via
                // ContextCompat.registerReceiver. ACTION_SCREEN_ON and
                // ACTION_SCREEN_OFF are both protected and cannot be
                // declared in the manifest, which is why they are here.
                appContext.registerReceiver(
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
                )
            }.onFailure { Diagnostics.w(TAG, "could not watch screen state", it) }
            MedhaWidgetProvider.onWidgetPlaced = { reschedule() }
        }
        running = true
        reschedule()
    }

    fun stop() {
        handler.removeCallbacks(step)
        if (running) {
            running = false
            MedhaWidgetProvider.onWidgetPlaced = null
            // Unregistering an unregistered receiver throws, and this can be
            // reached from onDestroy after a failed register.
            runCatching { appContext.unregisterReceiver(screenReceiver) }
        }
    }

    /**
     * Re-evaluates the three gates and either schedules the next tick or
     * leaves the handler empty.
     */
    private fun reschedule() {
        handler.removeCallbacks(step)
        if (!running) return
        if (!shouldRotate()) return
        handler.postDelayed(step, intervalMs)
    }

    private fun shouldRotate(): Boolean {
        if (!widgetPlaced()) return false
        if (!screenOn()) return false
        val rows = BriefPolicy.rows(hub.brief(), System.currentTimeMillis()).size
        return BriefPolicy.pageCount(rows) > 1
    }

    private fun widgetPlaced(): Boolean = runCatching {
        AppWidgetManager.getInstance(appContext)
            .getAppWidgetIds(ComponentName(appContext, MedhaWidgetProvider::class.java))
            .isNotEmpty()
    }.getOrDefault(false)

    private fun screenOn(): Boolean = runCatching {
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    /**
     * Called after a client writes, so a brief that has just become
     * multi-page starts rotating without waiting for the next service start,
     * and one that has shrunk to a single page stops.
     */
    fun onBriefChanged() {
        if (running) reschedule()
    }

    companion object {
        private const val TAG = "BriefRotator"

        /** Three seconds, as asked for. */
        const val DEFAULT_INTERVAL_SECONDS = 3L

        const val DEFAULT_INTERVAL_MS = DEFAULT_INTERVAL_SECONDS * 1000

        /**
         * Two seconds. Below this the page changes faster than a glance can
         * read it, and every tick is a Binder call into the launcher.
         */
        const val MIN_INTERVAL_MS = 2_000L

        /** A minute. Past that, "rotating" is indistinguishable from static. */
        const val MAX_INTERVAL_MS = 60_000L
    }
}
