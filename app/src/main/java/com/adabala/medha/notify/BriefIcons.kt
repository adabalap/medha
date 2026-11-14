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
     * The wash behind a row's kind glyph.
     *
     * Replaced the hairline bar in v0.16.0. A bar is a reading aid for a
     * list; a tile is the thing the eye lands on first, which is what makes
     * a brief read as a card rather than as rows of text.
     *
     * Not themed. The accent picker moves the mark badge and the `later`
     * bar, which are decoration; these carry urgency, and no preference
     * recolours urgency.
     */
    fun tile(band: BriefPolicy.Band?): Int = when (band) {
        BriefPolicy.Band.OVERDUE -> R.drawable.tile_overdue
        BriefPolicy.Band.SOON -> R.drawable.tile_soon
        BriefPolicy.Band.LATER -> R.drawable.tile_later
        null -> R.drawable.tile_none
    }

    /** The wash behind the countdown. Same rule, same colours. */
    fun pill(band: BriefPolicy.Band?): Int = when (band) {
        BriefPolicy.Band.OVERDUE -> R.drawable.pill_overdue
        BriefPolicy.Band.SOON -> R.drawable.pill_soon
        BriefPolicy.Band.LATER -> R.drawable.pill_later
        null -> R.drawable.pill_none
    }

    /**
     * The old hairline bar, still used by the band HEADINGS on the full
     * brief screen, where a single coloured rule introduces a group. Rows no
     * longer use it. [BriefTheme] still moves the `later` one, because at
     * heading size it is chrome again.
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
