package com.adabala.medha.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adabala.medha.MainActivity
import com.adabala.medha.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * One notification API for PWAs, with three back-ends that degrade by device.
 *
 * ## The surfaces, and which are actually reachable
 *
 * | Surface | Third-party access |
 * |---|---|
 * | **Now Brief** (Samsung's AI daily digest) | **None.** No public API |
 * | **Now Bar** (lock-screen pill) | Yes, One UI 8+ via Android 16 Live Updates |
 * | Standard notification | Yes, everywhere |
 * | Home-screen widget | Yes, if the widget belongs to Medha |
 *
 * Now Brief cannot be targeted and nothing here pretends otherwise. The Now Bar
 * is reachable because One UI 8 adopted Android 16's Live Updates API, which
 * promotes an ongoing notification into the pill. It is designed for *ongoing
 * activities with progress*, not arbitrary cards, so a request without progress
 * simply posts as a normal notification.
 *
 * Clients call [capabilities] first and get a truthful answer for this device
 * rather than branching on OS version themselves.
 */
class NotificationHub(private val appContext: Context) {

    data class Request(
        val id: String,
        val title: String,
        val text: String,
        val ongoing: Boolean = false,
        val progressCurrent: Int = -1,
        val progressMax: Int = -1,
        val silent: Boolean = true,
        val clientId: String = "unknown"
    )

    private val manager get() = NotificationManagerCompat.from(appContext)

    fun capabilities(): Map<String, Any> = mapOf(
        "sdk" to Build.VERSION.SDK_INT,
        // Live Updates landed in Android 16 (API 36). Below that, an ongoing
        // notification still posts, it just never reaches the Now Bar.
        "liveUpdates" to (Build.VERSION.SDK_INT >= 36),
        "nowBarLikely" to (Build.VERSION.SDK_INT >= 36 &&
            Build.MANUFACTURER.equals("samsung", ignoreCase = true)),
        "notifications" to areNotificationsEnabled(),
        "widget" to true,
        "nowBrief" to false
    )

    fun areNotificationsEnabled(): Boolean =
        runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

    fun post(req: Request): Boolean {
        ensureChannel()
        val key = stableId(req.clientId, req.id)

        val open = PendingIntent.getActivity(
            appContext, key, Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val b = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_medha)
            .setContentTitle(req.title)
            .setContentText(req.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(req.silent)
            .setOngoing(req.ongoing)
            .setCategory(if (req.ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)

        if (req.progressMax > 0) {
            b.setProgress(req.progressMax, req.progressCurrent.coerceIn(0, req.progressMax), false)
        }

        val n = b.build()

        // Ask the platform to promote this into the status-bar chip / Now Bar.
        // Set reflectively: FLAG_PROMOTED_ONGOING only exists on API 36+, and a
        // direct reference would not compile against compileSdk 34. A device
        // that does not know the flag simply ignores the bit.
        if (req.ongoing && Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val f = Notification::class.java.getField("FLAG_PROMOTED_ONGOING")
                n.flags = n.flags or f.getInt(null)
            }.onFailure { Log.d(TAG, "promoted-ongoing flag unavailable") }
        }

        return runCatching {
            manager.notify(key, n)
            true
        }.onFailure { Log.w(TAG, "notify failed (permission?)", it) }.getOrDefault(false)
    }

    fun cancel(clientId: String, id: String) {
        runCatching { manager.cancel(stableId(clientId, id)) }
    }

    /**
     * Namespaced so two clients using id "progress" cannot overwrite each
     * other's notification.
     */
    private fun stableId(clientId: String, id: String): Int =
        ("$clientId/$id".hashCode() and 0x7FFFFFFF) or 1

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Medha apps", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications posted by apps using Medha" }
        )
    }

    // ------------------------------ widget ------------------------------

    /**
     * Widget content is stored rather than pushed: the widget host may not be
     * alive when a client posts, and AppWidgetManager updates are lossy if the
     * provider has not been placed on a home screen yet.
     */
    fun setWidgetContent(clientId: String, items: List<Pair<String, String>>) {
        val arr = JSONArray()
        items.take(MAX_WIDGET_ITEMS).forEach { (title, text) ->
            arr.put(JSONObject().apply {
                put("title", title)
                put("text", text)
            })
        }
        appContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(WIDGET_KEY, JSONObject().apply {
                put("clientId", clientId)
                put("updatedAt", System.currentTimeMillis())
                put("items", arr)
            }.toString())
            .apply()
        MedhaWidgetProvider.refresh(appContext)
    }

    fun widgetContent(): JSONObject? =
        appContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(WIDGET_KEY, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    companion object {
        private const val TAG = "NotificationHub"
        private const val CHANNEL_ID = "medha_apps"
        const val WIDGET_PREFS = "medha_widget"
        const val WIDGET_KEY = "content"
        const val MAX_WIDGET_ITEMS = 5
    }
}
