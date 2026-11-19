package com.adabala.medha.connect

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import com.adabala.medha.diag.Diagnostics
import com.adabala.medha.auth.ClientRegistry
import com.adabala.medha.data.MedhaDatabase
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
 * WHAT IS KEPT, AND THE PROMISE THAT CHANGED
 *
 * Through v0.19.2 this file said that raw notification text was never
 * written anywhere — that text lived on the stack for the length of
 * [onNotificationPosted] and became either a self-expiring brief item or
 * nothing. The reason given was that "a readable history of every
 * notification is a worse thing to leave on a phone than the notifications
 * themselves", and that judgement was right. It is recorded here rather
 * than deleted, because a promise this file used to make should not quietly
 * disappear from it.
 *
 * What changed is where the thinking happens. Medha could see the stream
 * and had no memory to reason about it, so it guessed — and v0.19.1
 * shipped a home screen whose agenda was four emails with dates scraped
 * out of their own prose. The agent on the other side of the loopback has
 * the corpus and the correlation and could not see the stream at all. The
 * fix is to let it read observations instead of Medha's guesses, which
 * means the observations have to survive long enough to be read.
 *
 * So there IS a log now — see [com.adabala.medha.connect.CapturePolicy] —
 * and the old judgement is answered by making it conditional rather than by
 * overruling it:
 *
 *   - Nothing is written unless some client has been granted
 *     [ClientRegistry.Cap.CAPTURES]. Not a preference that can drift out of
 *     step with reality: the same grant that allows the log to be read is
 *     the one that causes it to exist. A phone with no such client keeps
 *     the original promise exactly.
 *   - Revoking the last grant deletes what was already collected, rather
 *     than merely stopping collection.
 *   - It is bounded in both directions, by age and by row count, and the
 *     bound is enforced on every insert rather than by a schedule that can
 *     stop running.
 *   - It passes the SAME filter as the brief, so the passcode guarantee
 *     below is not weakened by it. A notification that never became a brief
 *     row never becomes a capture either.
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
        // Resolved once. Both the brief row and the capture want it, and this
        // is a PackageManager round trip on a path that runs for every
        // notification the phone shows.
        val label = appLabel(posted.pkg)
        val item = NotificationItem
            .toItem(posted, now, BriefPolicy.deviceOffset(now))
            .copy(source = label)

        runCatching {
            val hub = NotificationHub(applicationContext)
            val existing = hub.brief()
                .firstOrNull { it.clientId == CLIENT_ID }
                ?.items
                .orEmpty()
            hub.setBrief(CLIENT_ID, NotificationItem.merge(existing, item), TTL_MS)
        }.onFailure { Diagnostics.i(TAG, "could not write the brief: ${it.message}") }

        capture(posted, label, now)
    }

    /**
     * Records the observation, but only while something can read it.
     *
     * The gate is a capability grant, not a setting, and that is the whole
     * argument for the log existing at all: on a phone where nothing holds
     * [ClientRegistry.Cap.CAPTURES] this is a no-op and no history
     * accumulates. See the class comment.
     *
     * Deliberately AFTER the brief write and in its own runCatching: the
     * brief is what the user sees, and a failure in the agent's feed must
     * never be the reason their home screen stops updating.
     *
     * [posted] rather than [item]: the item has already been interpreted --
     * a kind guessed from the package, a dueAt scraped out of prose -- and
     * handing those downstream is how Medha's guesses became the agent's
     * facts. What goes in the log is what the phone actually said.
     */
    private fun capture(posted: Posted, label: String, now: Long) {
        runCatching {
            if (!ClientRegistry.get(applicationContext).anyoneHas(ClientRegistry.Cap.CAPTURES)) {
                return
            }
            MedhaDatabase.get(applicationContext).captureAdd(
                at = now,
                pkg = posted.pkg,
                label = label,
                category = posted.category,
                title = posted.title,
                text = posted.text
            )
        }.onFailure { Diagnostics.i(TAG, "could not record the capture: ${it.message}") }
    }

    /**
     * The app's name as a person would say it: "Outlook", "WhatsApp".
     *
     * Every notification-sourced item lands in one section, so without
     * this the whole stream was attributable to the literal string
     * "notifications" — a shopping list from family and a Teams
     * announcement looked like they came from the same place, and the row
     * that said "from notifications" told the reader nothing they could
     * use.
     *
     * Falls back to the package name rather than to nothing: "com.whatsapp"
     * is ugly and still more informative than silence, and it is also the
     * signal that a label lookup failed.
     */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            .ifBlank { pkg }
    }.getOrDefault(pkg).take(40)

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
        // The brief owns this name: BriefPolicy.rows() ranks this section
        // last among equally urgent ones, so the two must agree.
        private const val CLIENT_ID = BriefPolicy.INFERRED_CLIENT_ID

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

        /**
         * Whether Android has bound this service at all.
         *
         * Read out of `Settings.Secure` rather than inferred from whether
         * `onNotificationPosted` has fired: a quiet phone and a revoked
         * grant look identical from inside the service, and the two need
         * different things said about them.
         *
         * Substring match on the package name is what every app does here --
         * the setting is a colon-joined list of flattened ComponentNames and
         * has no public parser. It is only ever used to *report* state, never
         * to gate anything, so a false positive costs a wrong word on a
         * screen rather than an access decision.
         */
        fun hasSystemGrant(context: Context): Boolean = runCatching {
            android.provider.Settings.Secure
                .getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty()
                .contains(context.packageName)
        }.getOrDefault(false)

        /**
         * Whether anything is actually being observed right now.
         *
         * Both gates, because neither alone reads a thing: Android's grant
         * is the consent, and Medha's own switch is how the feature is
         * stopped without going hunting for that settings screen again.
         *
         * Exists so the `/captures` route can tell a client the difference
         * between "nothing new since your cursor" and "nothing is being
         * recorded", which are the same empty array otherwise -- and an
         * agent that cannot tell them apart will cheerfully report a clear
         * day.
         */
        fun isEnabled(context: Context): Boolean =
            hasSystemGrant(context) && enabled(context)

        fun blocked(context: Context): Set<String> =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(KEY_BLOCKED, emptySet())
                .orEmpty()
    }
}
