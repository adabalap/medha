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
import com.adabala.medha.MainActivity
import com.adabala.medha.R
import org.json.JSONObject

/**
 * Home-screen surface any Medha client can push content to via PUT /widget/content.
 *
 * Deliberately dumb: it renders whatever the last writer stored. The alternative
 * -- a widget per consumer app -- would mean each PWA needing its own APK, which
 * is exactly the fragmentation the loopback architecture exists to avoid.
 */
class MedhaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val content = context
            .getSharedPreferences(NotificationHub.WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(NotificationHub.WIDGET_KEY, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        for (id in ids) {
            val v = RemoteViews(context.packageName, R.layout.widget_medha)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            v.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            )

            val items = content?.optJSONArray("items")
            if (items == null || items.length() == 0) {
                v.setTextViewText(R.id.widgetEmpty, context.getString(R.string.widget_empty))
                v.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
                for (row in ROWS) v.setViewVisibility(row.first, View.GONE)
            } else {
                v.setViewVisibility(R.id.widgetEmpty, View.GONE)
                ROWS.forEachIndexed { i, (rowId, pair) ->
                    if (i < items.length()) {
                        val o = items.getJSONObject(i)
                        v.setViewVisibility(rowId, View.VISIBLE)
                        v.setTextViewText(pair.first, o.optString("title"))
                        v.setTextViewText(pair.second, o.optString("text"))
                    } else {
                        v.setViewVisibility(rowId, View.GONE)
                    }
                }
            }
            mgr.updateAppWidget(id, v)
        }
    }

    companion object {
        private val ROWS = listOf(
            R.id.widgetRow1 to (R.id.widgetTitle1 to R.id.widgetText1),
            R.id.widgetRow2 to (R.id.widgetTitle2 to R.id.widgetText2),
            R.id.widgetRow3 to (R.id.widgetTitle3 to R.id.widgetText3)
        )

        /** Nudges every placed instance after a content write. */
        fun refresh(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, MedhaWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(
                        Intent(context, MedhaWidgetProvider::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                    )
                }
            }
        }
    }
}
