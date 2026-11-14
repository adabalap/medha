package com.adabala.medha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.adabala.medha.databinding.ActivityBriefBinding
import com.adabala.medha.notify.BriefJson
import com.adabala.medha.notify.BriefIcons
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefRender
import com.adabala.medha.notify.NotificationHub
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The whole brief, grouped by the app that wrote it.
 *
 * Reached by tapping the home-screen widget. That gesture is load-bearing:
 * the widget draws [BriefPolicy.PAGE_ROWS] rows of up to
 * [BriefPolicy.MAX_ROWS] and rotates through the rest, so this is the only
 * place the overflow can be read without waiting for it to come around. It
 * is the same behaviour the system's own brief has when tapped.
 *
 * Laid out to match that: a headline, a prose line, then one rounded card
 * per app introduced by a sentence. The earlier version was a toolbar over a
 * bare list, which on a nearly-empty brief read as a broken screen rather
 * than as "nothing to report".
 *
 * Rows are built in code rather than from an adapter. The content is bounded
 * in [BriefPolicy] — twelve rows across at most eight sections — so view
 * recycling buys nothing and an adapter would be more moving parts than
 * twelve text rows justify.
 */
class BriefActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBriefBinding
    private val hub by lazy { NotificationHub(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material You, on this screen too. Must run before any view is
        // inflated: it swaps the theme's colour roles for the ones derived
        // from the wallpaper, and anything already inflated keeps the old
        // ones. A no-op below API 31, where there is no system palette.
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityBriefBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // finish(), not navigateUp(): this is opened from a home-screen
        // widget, so there is no parent in the back stack to go up to. Back
        // and the close button both return to the launcher, which is what
        // the system brief does.
        binding.briefToolbar.setNavigationOnClickListener { finish() }
        binding.briefToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.brief_refresh -> { render(); true }
                R.id.brief_clear_all -> { confirmClearAll(); true }
                else -> false
            }
        }
    }

    /**
     * Re-read on every resume rather than observed.
     *
     * The brief is written over HTTP by clients outside this activity and
     * ages out on read, so what was correct when the screen was created is
     * not necessarily correct when the user comes back to it. A listener
     * would be more machinery for a screen whose whole lifetime is a few
     * seconds of reading.
     */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val sections = hub.brief()
        val container = binding.briefContainer
        container.removeAllViews()

        if (sections.isEmpty()) {
            binding.briefEmpty.visibility = View.VISIBLE
            binding.briefSubtitle.visibility = View.GONE
            binding.briefOverdue.visibility = View.GONE
            return
        }

        binding.briefEmpty.visibility = View.GONE
        binding.briefSubtitle.visibility = View.VISIBLE

        // The same ordering the widget draws, from the same function, with
        // the capacity opened up. This screen is what a widget tap opens
        // into, so a row must not move between the two: EVERYTHING is the
        // widget's list without the cut.
        val face = BriefRender.face(
            sections, now, BriefPolicy.deviceOffset(now), EVERYTHING
        )

        binding.briefSubtitle.text = resources.getQuantityString(
            R.plurals.brief_subtitle, face.contributors, face.contributors, face.total
        )

        if (face.overdue > 0) {
            binding.briefOverdue.visibility = View.VISIBLE
            binding.briefOverdue.text = getString(R.string.widget_overdue, face.overdue)
        } else {
            binding.briefOverdue.visibility = View.GONE
        }

        // Grouped by band, in band order. groupBy preserves encounter order
        // and the list is already banded, so the groups come out urgent
        // first without a second sort.
        face.lines.groupBy { it.band }.forEach { (band, lines) ->
            container.addView(bandHeader(band, lines.size, container))
            container.addView(bandCard(lines, face.contributors > 1, container))
        }
    }

    private fun bandHeader(band: BriefPolicy.Band?, count: Int, parent: ViewGroup): View =
        layoutInflater.inflate(R.layout.item_brief_band, parent, false).apply {
            findViewById<TextView>(R.id.bandLabel).also {
                it.setText(BriefIcons.label(band))
                // The one colour on this screen that does not follow the
                // system palette, for the same reason it does not in the
                // widget: a wallpaper must not be able to make "overdue"
                // look calm.
                it.setTextColor(getColor(BriefIcons.labelColor(band)))
            }
            findViewById<TextView>(R.id.bandCount).text =
                getString(R.string.band_count, count)
        }

    /** One band's lines, in a rounded card. */
    private fun bandCard(
        lines: List<BriefRender.Line>,
        attribute: Boolean,
        parent: ViewGroup
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_brief_card)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        lines.forEach { addView(row(it, attribute, parent)) }
    }

    private fun row(line: BriefRender.Line, attribute: Boolean, parent: ViewGroup): View =
        layoutInflater.inflate(R.layout.item_brief_row, parent, false).apply {
            findViewById<ImageView>(R.id.rowTile).setImageResource(BriefIcons.tile(line.band))
            findViewById<ImageView>(R.id.rowPill).setImageResource(BriefIcons.pill(line.band))
            findViewById<ImageView>(R.id.rowIcon).setImageResource(BriefIcons.icon(line.kind))
            findViewById<TextView>(R.id.rowTitle).text = line.title
            findViewById<TextView>(R.id.rowText).also {
                it.text = line.text
                it.visibility = if (line.text.isBlank()) View.GONE else View.VISIBLE
            }
            findViewById<TextView>(R.id.rowMark).also {
                it.text = line.mark
                it.setTextColor(getColor(BriefIcons.labelColor(line.band)))
            }
            // Attribution only when a second app is writing. With one writer
            // the client id on every line is noise the user already knows.
            findViewById<TextView>(R.id.rowFrom).also {
                it.visibility = if (attribute) View.VISIBLE else View.GONE
                if (attribute) it.text = getString(R.string.brief_from, line.clientId)
            }
            setOnClickListener { openItem(line) }
        }

    /**
     * What a tap on a line does.
     *
     * If the posting app supplied a link, open it. Otherwise show the item
     * in full — Medha cannot launch the app that wrote it, because it stores
     * a hashed namespace rather than the caller's package and will not guess
     * from a client id. For a paired web app it does know the origin, so
     * that is offered.
     *
     * The link has already been filtered to http(s) on write and again on
     * read; it is checked once more here because this is the line that
     * actually starts an activity.
     */
    private fun openItem(line: BriefRender.Line) {
        val link = line.link?.takeIf { BriefJson.isSafeLink(it) }
        if (link != null) {
            if (launch(link)) return
            // Fall through to the sheet rather than failing silently: a
            // device with no browser is rare but a dead tap is worse.
        }
        showDetail(line, link)
    }

    private fun launch(url: String): Boolean = runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        )
        true
    }.getOrElse { e ->
        if (e is ActivityNotFoundException) false else false
    }

    private fun showDetail(line: BriefRender.Line, link: String?) {
        val body = buildString {
            if (line.text.isNotBlank()) append(line.text).append("\n\n")
            // The countdown, not the post age: when a thing is due is what
            // the sheet is being opened to find out.
            append(getString(R.string.brief_item_meta, line.clientId, line.mark))
            if (link != null) append("\n").append(link)
        }

        val b = MaterialAlertDialogBuilder(this)
            .setTitle(line.title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)

        // Only a paired web app has an origin Medha can offer to open.
        val origin = webOriginFor(line.clientId)
        if (link != null) {
            b.setNeutralButton(R.string.brief_item_open) { _, _ -> launch(link) }
        } else if (origin != null) {
            b.setNeutralButton(R.string.brief_item_open_app) { _, _ -> launch(origin) }
        }
        b.show()
    }

    private fun webOriginFor(clientId: String): String? =
        runCatching {
            com.adabala.medha.auth.ClientRegistry.get(this)
                .unordered()
                .firstOrNull { it.id == clientId }
                ?.webOrigin
                ?.takeIf { BriefJson.isSafeLink(it) }
        }.getOrNull()

    /**
     * Clears every section, not just one.
     *
     * This is the user's home screen and the brief is the one surface other
     * apps can put content on it, so they get a way to empty it without
     * working out which app wrote what and revoking it. Confirmed first,
     * because it discards other apps' content and they cannot tell.
     */
    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.brief_clear_all)
            .setMessage(R.string.brief_clear_all_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.brief_clear_all_confirm) { _, _ ->
                hub.clearAllBrief()
                render()
            }
            .show()
    }

    private companion object {
        /**
         * Capacity for "show me all of it".
         *
         * The store is bounded at eight sections of five, so this is the real
         * ceiling rather than a generous guess, and the screen never silently
         * drops a row the way a smaller number would.
         */
        const val EVERYTHING =
            BriefPolicy.MAX_SECTIONS * BriefPolicy.MAX_ITEMS_PER_SECTION
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
