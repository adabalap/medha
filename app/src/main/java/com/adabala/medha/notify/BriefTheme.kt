package com.adabala.medha.notify

import android.content.Context
import androidx.preference.PreferenceManager
import com.adabala.medha.R

/**
 * Which accent the brief widget draws with.
 *
 * WHAT A CHOICE HERE CHANGES, AND WHAT IT CANNOT
 *
 * The accent, and nothing else. Surfaces and text always follow the system,
 * because a widget that ignores dark mode is broken rather than themed. The
 * three urgency colours follow nothing at all: a wallpaper, or a preference,
 * must not be able to make "overdue" look calm. So a theme picks the mark
 * badge and the `later` bar, which are the two places on the widget where
 * colour is decoration.
 *
 * WHY THESE ARE PRESETS AND NOT A COLOUR WHEEL
 *
 * Not a shortcut: it is the only version that can be correct. A widget is
 * drawn by the launcher, and the launcher resolves resource ids against its
 * own configuration — which is exactly what makes `values-night` work out
 * there. An arbitrary colour would have to be resolved in Medha's process
 * and shipped as an int, which bakes in Medha's idea of light or dark and is
 * then wrong in one of the two themes, invisibly, until someone switches.
 * Presets are resources, so the launcher keeps resolving them and dark mode
 * keeps working.
 *
 * [DYNAMIC] is the default and has no drawables of its own: it uses the
 * stock ones, which resolve through `values-v31` to the wallpaper palette on
 * Android 12+ and to the Medha indigo below it. So on an older phone
 * Dynamic and Indigo genuinely are the same thing, and the picker says so.
 */
enum class BriefTheme(
    val key: String,
    val labelRes: Int,
    val badge: Int,
    val laterBar: Int
) {
    DYNAMIC("dynamic", R.string.theme_dynamic, R.drawable.bg_brief_badge, R.drawable.bar_later),
    INDIGO("indigo", R.string.theme_indigo, R.drawable.badge_indigo, R.drawable.bar_later_indigo),
    TEAL("teal", R.string.theme_teal, R.drawable.badge_teal, R.drawable.bar_later_teal),
    ROSE("rose", R.string.theme_rose, R.drawable.badge_rose, R.drawable.bar_later_rose),
    AMBER("amber", R.string.theme_amber, R.drawable.badge_amber, R.drawable.bar_later_amber);

    companion object {
        /**
         * The DEFAULT preference file, not a named one.
         *
         * Everything else in Medha reads settings through
         * PreferenceManager.getDefaultSharedPreferences; a named file here
         * would have been a second store that the picker writes and the
         * widget never sees, which looks exactly like "the setting does
         * nothing".
         */
        const val KEY = "brief_theme"

        /**
         * Unknown or missing resolves to [DYNAMIC] rather than throwing.
         *
         * values(), not entries: entries needs the 1.9 language level and
         * this file is not worth coupling to a Kotlin version over one
         * lookup that runs once per widget render.
         */
        fun of(key: String?): BriefTheme =
            // Qualified: a bare values() is an enum member the compiler
            // generates, which tools/check_symbols.py cannot see a
            // declaration for. Qualifying costs nothing and keeps the
            // checker strict about genuinely missing functions.
            BriefTheme.values().firstOrNull { it.key == key } ?: DYNAMIC

        /**
         * Read straight from prefs on each render rather than cached.
         *
         * A widget provider is a BroadcastReceiver: the system builds a fresh
         * instance per broadcast, so there is no instance for a cache to live
         * on, and a companion-level one would be a static that outlives the
         * setting it mirrors.
         */
        fun current(context: Context): BriefTheme = of(
            PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null)
        )
    }
}
