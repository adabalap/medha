package com.adabala.medha.connect

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.connect.NotificationFilter.Posted
import com.adabala.medha.notify.NotificationHub

/**
 * Reads the phone's notifications and puts the useful ones on the brief.
 *
 * WHY THIS EXISTS AT ALL
 *
 * A work calendar that lives inside Outlook or Teams never reaches
 * CalendarContract: those apps keep their own store and sync nothing to the
 * platform provider. So there is no structured route to a work meeting on
 * this device, and the only thing that knows about it is the notification
 * the app posts. That is the entire justification for asking for an
 * access this broad, and it is worth stating plainly because the access IS
 * broad: Android offers one switch for every notification on the device,
 * with no way to scope it to an app.
 *
 * WHAT THIS CLASS IS ALLOWED TO DECIDE
 *
 * Nothing. [NotificationFilter] decides what survives and
 * [NotificationItem] decides what it becomes, because both are pure Kotlin
 * that the test harness executes and this file is not compilable by
 * anything short of Gradle. "Which notifications reach the home screen" is
 * not a question that should be answered by code nobody can run.
 *
 * WHAT IS NEVER KEPT
 *
 * Raw notification text is never written anywhere. It lives on the stack
 * for the length of [onNotificationPosted], and either becomes a brief item
 * that expires on its own or is discarded. There is no log of what you were
 * sent, deliberately: a readable history of every notification is a worse
 * thing to leave on a phone than the notifications themselves.
 *
 * Anything that looks like a passcode is dropped before any of that, and the
 * drop is unconditional — no allowlist, importance or category rescues one.
 * See [NotificationFilter.looksLikeSecret].
 */
class MedhaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Two switches, both of which must be on. Android's grant is the
        // consent; this one is so the feature can be stopped from inside
        // Medha without hunting through system settings.
        if (!enabled(applicationContext)) return

        val posted = runCatching { read(sbn) }.getOrNull() ?: return

        when (val v = NotificationFilter.verdict(posted, blocked(applicationContext))) {
            is NotificationFilter.Verdict.Drop -> {
                // The REASON is logged, never the content. Knowing that
                // something was dropped for looking like a passcode is
                // useful; a log line containing the passcode is the problem
                // this filter exists to prevent.
                Diagnostics.i(TAG, "dropped ${posted.pkg}: ${v.why}")
                return
            }
            is NotificationFilter.Verdict.Keep -> Unit
        }

        val now = System.currentTimeMillis()
        val item = NotificationItem.toItem(posted, now, BriefPolicy.deviceOffset(now))

        runCatching {
            val hub = NotificationHub(applicationContext)
            val existing = hub.brief()
                .firstOrNull { it.clientId == CLIENT_ID }
                ?.items
                .orEmpty()
            hub.setBrief(CLIENT_ID, NotificationItem.merge(existing, item), TTL_MS)
        }.onFailure { Diagnostics.i(TAG, "could not write the brief: ${it.message}") }
    }

    /**
     * Flattens a notification into the pure shape the filter understands.
     *
     * Every read is defensive. `extras` is a Bundle an arbitrary app filled
     * in, and a listener that throws on a malformed notification stops
     * receiving all of them until the phone reboots.
     */
    private fun read(sbn: StatusBarNotification): Posted? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null

        fun str(key: String): String =
            runCatching { extras.getCharSequence(key)?.toString() }.getOrNull().orEmpty().trim()

        val title = str(Notification.EXTRA_TITLE)
        val text = str(Notification.EXTRA_TEXT).ifBlank { str(Notification.EXTRA_BIG_TEXT) }

        return Posted(
            pkg = sbn.packageName.orEmpty(),
            title = title,
            text = text,
            category = n.category,
            ongoing = sbn.isOngoing,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            importance = importanceOf(sbn),
            // Always false here, and that is not an oversight. On API 26+
            // the channel owns whether a notification makes a sound, and a
            // listener may not read another app's channel -- so importance
            // IS the silence signal and guessing a second one from the
            // legacy flags produced different answers for the same
            // notification depending on which app posted it. The filter
            // keeps the field because it is the right shape for a better
            // signal, and because its rule is tested.
            silent = false
        )
    }

    /**
     * The channel importance, or [UNKNOWN_IMPORTANCE].
     *
     * Through Ranking rather than the channel, because a listener is not
     * allowed to read another app's NotificationChannel. An unknown value is
     * deliberately NOT treated as minimum: failing to read a ranking should
     * not silently stop the whole feature.
     */
    private fun importanceOf(sbn: StatusBarNotification): Int = runCatching {
        val ranking = NotificationListenerService.Ranking()
        if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance
        else UNKNOWN_IMPORTANCE
    }.getOrDefault(UNKNOWN_IMPORTANCE)

    /**
     * Not private: MainActivity writes [KEY_ENABLED] and must agree with
     * this file about its name and its default. Two string literals in two
     * files is how a setting ends up written somewhere nothing reads.
     */
    companion object {
        private const val TAG = "NotifListener"

        /**
         * The section these land in. Shows as attribution on the brief only
         * when a second app is also writing, like every other client.
         */
        private const val CLIENT_ID = "notifications"

        /**
         * Eight hours.
         *
         * Shorter than the 24-hour default: a notification is news, and
         * yesterday's news on today's home screen is the clutter this was
         * supposed to clear. Items with a `dueAt` still sort by urgency
         * within that window.
         */
        private const val TTL_MS = 8 * 60 * 60 * 1000L

        private const val UNKNOWN_IMPORTANCE = -1000

        const val KEY_ENABLED = "notif_listen"
        const val KEY_BLOCKED = "notif_blocked"

        /** Default ON: granting Android's access is the consent. */
        const val DEFAULT_ENABLED = true

        fun enabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        fun blocked(context: Context): Set<String> =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(KEY_BLOCKED, emptySet())
                .orEmpty()
    }
}
