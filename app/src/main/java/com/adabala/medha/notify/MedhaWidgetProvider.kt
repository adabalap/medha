package com.adabala.medha.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.adabala.medha.BriefActivity
import com.adabala.medha.R

/**
 * Medha's brief, on the home screen.
 *
 * Any paired client can write to it with `PUT /widget/content`. This exists
 * because Samsung's Now Brief has no third-party API — see [BriefPolicy] for
 * the full reasoning — so the digest shape has nowhere to go on the platform
 * and Medha provides its own.
 *
 * Shows [BriefPolicy.PAGE_ROWS] rows at a time. When the brief holds more, it
 * rotates through pages (driven by [BriefRotator], which explains why the
 * timer cannot come from the widget framework) and tapping opens
 * [BriefActivity] with the whole list — the same gesture Samsung's own digest
 * uses, and the reason the overflow does not have to be discarded.
 *
 * Deliberately dumb about *what* to show: it asks [BriefPolicy] for a page
 * and draws it. The alternative — a widget per consumer app — would mean each
 * PWA needing its own APK, which is exactly the fragmentation the loopback
 * architecture exists to avoid.
 */
class MedhaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        draw(context, mgr, ids, tick = 0)
        // Placing the first widget is otherwise invisible to the rotator,
        // which gates on a widget existing. Without this, a brief that
        // already overflows would not start rotating until the next write or
        // the next screen-on -- and "why isn't it scrolling" is a support
        // question nobody should have to ask.
        onWidgetPlaced?.invoke()
    }

    override fun onReceive(context: Context, intent: Intent) {
        // A rotation tick arrives as a normal APPWIDGET_UPDATE carrying the
        // counter, so there is one update path rather than a second custom
        // action with its own lifecycle.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE &&
            intent.hasExtra(EXTRA_TICK)
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: mgr.getAppWidgetIds(ComponentName(context, MedhaWidgetProvider::class.java))
            draw(context, mgr, ids, intent.getLongExtra(EXTRA_TICK, 0L))
            return
        }
        super.onReceive(context, intent)
    }

    private fun draw(context: Context, mgr: AppWidgetManager, ids: IntArray, tick: Long) {
        if (ids.isEmpty()) return

        // Read and page once for all instances: someone with two Medha
        // widgets placed should not pay two deserialisations, and both must
        // show the same page.
        val raw = context
            .getSharedPreferences(NotificationHub.WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(NotificationHub.WIDGET_KEY, null)

        val now = System.currentTimeMillis()
        val sections = if (raw == null) emptyList() else BriefJson.parse(raw, now)
        val page = BriefPolicy.page(sections, now, tick)
        val contributors = BriefPolicy.prune(sections, now).size

        for (id in ids) {
            val v = RemoteViews(context.packageName, R.layout.widget_medha)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            // Opens the full brief, not MainActivity: the widget shows three
            // of possibly twelve rows, so "tap for the rest" is the whole
            // point of the gesture.
            v.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, BriefActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
            )

            if (page.rows.isEmpty()) {
                v.setTextViewText(R.id.widgetEmpty, context.getString(R.string.widget_empty))
                v.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
                v.setTextViewText(R.id.widgetCount, "")
                v.setViewVisibility(R.id.widgetFooter, View.GONE)
                ROWS.forEach { v.setViewVisibility(it.row, View.GONE) }
            } else {
                v.setViewVisibility(R.id.widgetEmpty, View.GONE)
                v.setTextViewText(
                    R.id.widgetCount,
                    if (contributors > 1) context.getString(R.string.widget_count, contributors) else ""
                )

                ROWS.forEachIndexed { i, slot ->
                    val r = page.rows.getOrNull(i)
                    if (r == null) {
                        v.setViewVisibility(slot.row, View.GONE)
                    } else {
                        v.setViewVisibility(slot.row, View.VISIBLE)
                        v.setTextViewText(slot.title, r.title)
                        v.setTextViewText(slot.text, r.text)
                        // Attribution only when more than one app is
                        // contributing. With a single writer the client id on
                        // every line is noise the user already knows.
                        v.setTextViewText(
                            slot.meta,
                            if (contributors > 1) "${r.clientId} · ${BriefPolicy.age(r.updatedAt, now)}"
                            else BriefPolicy.age(r.updatedAt, now)
                        )
                    }
                }

                // Footer only when there is more than one page. A "1/1" that
                // never changes is chrome, and the tap hint matters most to
                // someone who has just noticed there is more than one page.
                if (page.overflows) {
                    v.setViewVisibility(R.id.widgetFooter, View.VISIBLE)
                    v.setTextViewText(
                        R.id.widgetFooter,
                        context.getString(
                            R.string.widget_footer, page.indicator, page.totalRows
                        )
                    )
                } else {
                    v.setViewVisibility(R.id.widgetFooter, View.GONE)
                }
            }
            mgr.updateAppWidget(id, v)
        }
    }

    private class Slot(val row: Int, val title: Int, val text: Int, val meta: Int)

    companion object {
        /**
         * One entry per row in `widget_medha.xml`, and the list length must
         * equal [BriefPolicy.PAGE_ROWS].
         *
         * v0.9.0 had three entries here against a server that accepted five
         * items, so items four and five vanished with no error anywhere. The
         * layout is generated, BriefPolicyTest asserts PAGE_ROWS is 3, and
         * check_resources.py confirms these ids exist — three independent
         * places that disagree if someone changes one.
         */
        private val ROWS = listOf(
            Slot(R.id.widgetRow1, R.id.widgetTitle1, R.id.widgetText1, R.id.widgetMeta1),
            Slot(R.id.widgetRow2, R.id.widgetTitle2, R.id.widgetText2, R.id.widgetMeta2),
            Slot(R.id.widgetRow3, R.id.widgetTitle3, R.id.widgetText3, R.id.widgetMeta3)
        )

        private const val EXTRA_TICK = "com.adabala.medha.extra.BRIEF_TICK"

        /**
         * Set by [BriefRotator] while it is running.
         *
         * A static hook because an AppWidgetProvider is a BroadcastReceiver:
         * the system constructs a fresh instance per broadcast, so there is
         * no object for anything to hold a reference to. Same process, so
         * this is a direct call and not IPC.
         */
         @Volatile var onWidgetPlaced: (() -> Unit)? = null

        /**
         * Redraws every placed instance, at page [tick].
         *
         * Called both after a content write (tick 0, so a fresh write shows
         * its first page) and from [BriefRotator] on each rotation.
         */
        fun refresh(context: Context, tick: Long = 0L) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, MedhaWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(
                        Intent(context, MedhaWidgetProvider::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                            putExtra(EXTRA_TICK, tick)
                        }
                    )
                }
            }
        }
    }
}
