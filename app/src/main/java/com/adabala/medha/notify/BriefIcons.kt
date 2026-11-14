package com.adabala.medha.notify

import com.adabala.medha.R

/**
 * The band and kind lookups, in one place because two surfaces need them.
 *
 * The widget and the full-brief screen must agree about what colour a row's
 * bar is and which glyph sits beside it: the screen is what the widget opens
 * into, and a tap that recolours everything makes the user re-find the row
 * they were already looking at. Two copies of the same `when` is how that
 * drifts.
 *
 * Resource ids only, never resolved colours. [bar] returns a drawable id so
 * the widget can hand it to `setImageViewResource` and let the LAUNCHER
 * resolve the `@color` inside it against its own configuration, which is
 * what keeps values-night and the Material You palette working out there.
 * Resolving a colour in Medha's process and shipping the int instead is the
 * one thing that looks right and is wrong in the other theme.
 *
 * Lives in `notify` rather than in [BriefRender] because it touches `R`, and
 * BriefRender is the half of this that the test harness can compile.
 */
object BriefIcons {

    /**
     * Only [BriefPolicy.Band.LATER] takes the theme. Later is the absence of
     * urgency rather than a grade of it, so it is chrome and may be themed;
     * overdue and this week are signal, and no preference recolours them.
     */
    fun bar(band: BriefPolicy.Band?, theme: BriefTheme = BriefTheme.DYNAMIC): Int =
        when (band) {
            BriefPolicy.Band.OVERDUE -> R.drawable.bar_overdue
            BriefPolicy.Band.SOON -> R.drawable.bar_soon
            BriefPolicy.Band.LATER -> theme.laterBar
            null -> R.drawable.bar_none
        }

    fun label(band: BriefPolicy.Band?): Int = when (band) {
        BriefPolicy.Band.OVERDUE -> R.string.band_overdue
        BriefPolicy.Band.SOON -> R.string.band_soon
        BriefPolicy.Band.LATER -> R.string.band_later
        null -> R.string.band_none
    }

    fun labelColor(band: BriefPolicy.Band?): Int = when (band) {
        BriefPolicy.Band.OVERDUE -> R.color.band_overdue
        BriefPolicy.Band.SOON -> R.color.band_soon
        BriefPolicy.Band.LATER -> R.color.band_later
        null -> R.color.band_none
    }

    /**
     * Not exhaustive on purpose. [BriefPolicy.kindOrNull] has already dropped
     * anything outside [BriefPolicy.KINDS], so the else branch means "no kind
     * given" rather than "unknown kind" — and a kind added to KINDS without
     * an icon here degrades to the note glyph instead of failing to draw.
     */
    fun icon(kind: String?): Int = when (kind) {
        "bill" -> R.drawable.ic_kind_bill
        "date" -> R.drawable.ic_kind_date
        "event" -> R.drawable.ic_kind_event
        "alert" -> R.drawable.ic_kind_alert
        "done" -> R.drawable.ic_kind_done
        else -> R.drawable.ic_kind_note
    }
}
