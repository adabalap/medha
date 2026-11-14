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
 * Medha's brief, on the home screen, in three sizes.
 *
 * Any paired client writes to it with `PUT /widget/content`. This exists
 * because Samsung's Now Brief has no third-party API — see [BriefPolicy] for
 * the reasoning — so the digest shape has nowhere to go on the platform and
 * Medha provides its own.
 *
 * WHY THREE CLASSES INSTEAD OF ONE RESIZABLE ONE
 *
 * The widget picker lists one entry per provider. However well a single
 * provider resizes, it is still one choice under "Medha", and someone who
 * wants the small one has to place the big one and drag it. Three receivers
 * is the only way to offer three sizes as three things to pick.
 *
 * NOTHING ROTATES
 *
 * Until v0.13.0 the widget paged through the brief on a timer and showed
 * "1/2" in the footer. That is gone, and not as a simplification — it was
 * wrong. A brief is read in a two-second glance, so a rotating one means the
 * row you came to check may not be on screen when you look, and you end up
 * waiting for your own home screen. It was also a hedge against the ordering
 * being wrong; the ordering is now by urgency ([BriefPolicy.rows]), so the
 * top rows are the right rows and the footer names what is underneath them.
 * Auto-advance is driven by the launcher anyway, so a launcher that does not
 * implement it would have shown page one forever.
 *
 * WHAT THIS CLASS IS ALLOWED TO DECIDE
 *
 * Almost nothing. [BriefRender] decides what to draw, because this file
 * cannot be compiled by anything short of Gradle and therefore cannot be
 * tested; everything here is binding a [BriefRender.Face] to views and
 * turning enums into resource ids. If a decision needs a test, it belongs in
 * BriefRender. If it needs `R.anything`, it belongs here.
 */
abstract class BriefWidgetProvider : AppWidgetProvider() {

    /** The layout this size draws into. */
    protected abstract val layout: Int

    /** Row slots in [layout]. Must equal the matching `BriefRender.ROWS_*`. */
    protected abstract val slots: List<Slot>

    /**
     * The footer view, or null for a size with no room for one.
     *
     * Null rather than "set it invisible": `R.id.widgetFold` does not exist
     * in the 2x2 layout, and a RemoteViews action naming a view the layout
     * does not contain is an error at update time, not a no-op.
     */
    protected open val foldId: Int? = R.id.widgetFold

    protected class Slot(
        val row: Int,
        val tile: Int,
        val icon: Int,
        val title: Int,
        val text: Int,
        val pill: Int,
        val mark: Int
    )

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) =
        draw(context, mgr, ids)

    private fun draw(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return

        // Read and decide once for all instances of this size: two widgets
        // placed should not pay two deserialisations.
        val raw = context
            .getSharedPreferences(NotificationHub.WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(NotificationHub.WIDGET_KEY, null)

        val now = System.currentTimeMillis()
        val sections = if (raw == null) emptyList() else BriefJson.parse(raw, now)
        // Read per render, not cached: the offset changes under DST and when
        // the phone crosses a timezone, and a brief that bands "today"
        // against last month's offset is wrong in exactly the hours that
        // matter most.
        val offset = BriefPolicy.deviceOffset(now)
        val face = BriefRender.face(sections, now, offset, slots.size)
        // Read per render, like the offset: a preference changed while the
        // widget sits on the home screen has to reach it on the next redraw,
        // and there is no instance of this class for a cache to live on.
        val theme = BriefTheme.current(context)

        for (id in ids) {
            val v = RemoteViews(context.packageName, layout)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            // Opens the full brief rather than MainActivity: even the 4x4
            // shows a subset, so "tap for the rest" is the point of the
            // gesture.
            v.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, BriefActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
            )

            v.setImageViewResource(R.id.widgetBadge, theme.badge)

            v.setTextViewText(
                R.id.widgetOverdue,
                if (face.overdue > 0) {
                    context.getString(R.string.widget_overdue, face.overdue)
                } else {
                    ""
                }
            )

            if (face.isEmpty) {
                v.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
                slots.forEach { v.setViewVisibility(it.row, View.GONE) }
                foldId?.let { v.setViewVisibility(it, View.GONE) }
            } else {
                v.setViewVisibility(R.id.widgetEmpty, View.GONE)

                slots.forEachIndexed { i, slot ->
                    val line = face.lines.getOrNull(i)
                    if (line == null) {
                        v.setViewVisibility(slot.row, View.GONE)
                    } else {
                        v.setViewVisibility(slot.row, View.VISIBLE)
                        v.setTextViewText(slot.title, line.title)
                        v.setTextViewText(slot.text, line.text)
                        v.setTextViewText(
                            slot.mark,
                            // Countdown first, attribution second, and
                            // attribution only when a second app is writing:
                            // with one writer the client id on every line is
                            // noise the user already knows.
                            if (face.contributors > 1) "${line.mark} · ${line.clientId}"
                            else line.mark
                        )
                        // setImageViewResource, NOT a tint. It marshals a
                        // resource id, so the LAUNCHER resolves the drawable
                        // and the @color inside it against its own
                        // configuration — which is what makes values-night
                        // and the Material You palette in values-v31 work
                        // out here. Any call that takes a resolved colour int
                        // would bake in Medha's configuration instead and be
                        // wrong in one of the two themes, invisibly.
                        v.setImageViewResource(slot.tile, BriefIcons.tile(line.band))
                        v.setImageViewResource(slot.pill, BriefIcons.pill(line.band))
                        v.setImageViewResource(slot.icon, BriefIcons.icon(line.kind))
                    }
                }

                foldId?.let { fold ->
                    val claim = face.fold
                    if (claim == null) {
                        v.setViewVisibility(fold, View.GONE)
                    } else {
                        v.setViewVisibility(fold, View.VISIBLE)
                        v.setTextViewText(fold, foldText(context, claim))
                    }
                }
            }
            mgr.updateAppWidget(id, v)
        }
    }

    private fun foldText(context: Context, fold: BriefRender.Fold): String = when (fold) {
        is BriefRender.Fold.Overdue ->
            context.getString(R.string.widget_fold_overdue, fold.more, fold.overdue)
        is BriefRender.Fold.Soon ->
            context.getString(R.string.widget_fold_soon, fold.more, fold.soon)
        is BriefRender.Fold.Clear ->
            context.getString(R.string.widget_fold_clear, fold.more)
    }

}

/**
 * 4x2 — the default size.
 *
 * Keeps the class name it had before the three-size split so that widgets
 * already placed on someone's home screen survive the upgrade and simply
 * redraw in the new design. Renaming it would have silently removed them.
 */
class MedhaWidgetProvider : BriefWidgetProvider() {
    override val layout = R.layout.widget_brief_4x2
    override val slots = listOf(
        Slot(R.id.widgetRow1, R.id.widgetTile1, R.id.widgetIcon1,
            R.id.widgetTitle1, R.id.widgetText1, R.id.widgetPill1, R.id.widgetMark1),
        Slot(R.id.widgetRow2, R.id.widgetTile2, R.id.widgetIcon2,
            R.id.widgetTitle2, R.id.widgetText2, R.id.widgetPill2, R.id.widgetMark2),
        Slot(R.id.widgetRow3, R.id.widgetTile3, R.id.widgetIcon3,
            R.id.widgetTitle3, R.id.widgetText3, R.id.widgetPill3, R.id.widgetMark3)
    )
}

/** 2x2 — the next thing, and nothing else. No footer: there is no room. */
class MedhaWidget2x2Provider : BriefWidgetProvider() {
    override val layout = R.layout.widget_brief_2x2
    override val foldId: Int? = null
    override val slots = listOf(
        Slot(R.id.widgetRow1, R.id.widgetTile1, R.id.widgetIcon1,
            R.id.widgetTitle1, R.id.widgetText1, R.id.widgetPill1, R.id.widgetMark1)
    )
}

/** 4x4 — the horizon. */
class MedhaWidget4x4Provider : BriefWidgetProvider() {
    override val layout = R.layout.widget_brief_4x4
    // Count comes from BriefRender, not a literal: the layout, the
    // capacity and this list are three statements of one number, and
    // check_widget_wiring.py compares all three.
    override val slots = (1..BriefRender.ROWS_4X4).map { n ->
        Slot(
            ROW_IDS[n - 1], TILE_IDS[n - 1], ICON_IDS[n - 1],
            TITLE_IDS[n - 1], TEXT_IDS[n - 1], PILL_IDS[n - 1], MARK_IDS[n - 1]
        )
    }

    private companion object {
        // Listed rather than resolved by name: R.id lookup by string needs
        // Resources.getIdentifier, which R8 cannot see through and which
        // fails silently in a minified release build.
        val ROW_IDS = intArrayOf(
            R.id.widgetRow1, R.id.widgetRow2, R.id.widgetRow3, R.id.widgetRow4,
            R.id.widgetRow5, R.id.widgetRow6, R.id.widgetRow7
        )
        val TILE_IDS = intArrayOf(
            R.id.widgetTile1, R.id.widgetTile2, R.id.widgetTile3, R.id.widgetTile4,
            R.id.widgetTile5, R.id.widgetTile6, R.id.widgetTile7
        )
        val PILL_IDS = intArrayOf(
            R.id.widgetPill1, R.id.widgetPill2, R.id.widgetPill3, R.id.widgetPill4,
            R.id.widgetPill5, R.id.widgetPill6, R.id.widgetPill7
        )
        val ICON_IDS = intArrayOf(
            R.id.widgetIcon1, R.id.widgetIcon2, R.id.widgetIcon3, R.id.widgetIcon4,
            R.id.widgetIcon5, R.id.widgetIcon6, R.id.widgetIcon7
        )
        val TITLE_IDS = intArrayOf(
            R.id.widgetTitle1, R.id.widgetTitle2, R.id.widgetTitle3, R.id.widgetTitle4,
            R.id.widgetTitle5, R.id.widgetTitle6, R.id.widgetTitle7
        )
        val TEXT_IDS = intArrayOf(
            R.id.widgetText1, R.id.widgetText2, R.id.widgetText3, R.id.widgetText4,
            R.id.widgetText5, R.id.widgetText6, R.id.widgetText7
        )
        val MARK_IDS = intArrayOf(
            R.id.widgetMark1, R.id.widgetMark2, R.id.widgetMark3, R.id.widgetMark4,
            R.id.widgetMark5, R.id.widgetMark6, R.id.widgetMark7
        )
    }
}

/**
 * Redraws every placed brief widget, whatever its size.
 *
 * A free-standing object because an AppWidgetProvider is a BroadcastReceiver:
 * the system builds a fresh instance per broadcast, so there is no live
 * object for callers to hold, and three sizes means three component names to
 * poke rather than one.
 */
object BriefWidgets {

    private val PROVIDERS = listOf(
        MedhaWidgetProvider::class.java,
        MedhaWidget2x2Provider::class.java,
        MedhaWidget4x4Provider::class.java
    )

    fun refresh(context: Context) {
        for (cls in PROVIDERS) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(
                        Intent(context, cls).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                    )
                }
            }
        }
    }
}
