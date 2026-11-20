package com.adabala.medha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.adabala.medha.databinding.ActivityBriefBinding
import com.adabala.medha.notify.BriefJson
import com.adabala.medha.notify.BriefDismiss
import com.adabala.medha.notify.BriefIcons
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.BriefRender
import com.adabala.medha.notify.BriefTriage
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
        paintHeadline()

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

    /**
     * Sweeps the headline through the brief's own palette.
     *
     * The colours are the kind_* ones the rows already use, not a set picked
     * for this one view, so the headline reads as the top of the same screen
     * rather than decoration bolted onto it. They carry values-night
     * variants, so dark mode re-resolves them when the activity is recreated
     * and nothing here has to know which mode it is in.
     *
     * Only the BRAND is painted. It sits in a capsule beside the tagline,
     * which stays in secondary text: a gradient across the whole sentence
     * made the capsule noisy and left nothing for "పాదరసం" to stand out
     * against.
     *
     * doOnLayout, not post: a shader needs the view's width, and at the end
     * of onCreate that is still zero. The brand is wrap_content and capped
     * at one line, so its width is exactly the text and the sweep lands on
     * the glyphs rather than trailing off into empty space.
     */
    private fun paintHeadline() {
        binding.briefHeadline.doOnLayout { v ->
            val tv = v as android.widget.TextView
            val w = tv.width.toFloat()
            if (w <= 0f) return@doOnLayout
            tv.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    ContextCompat.getColor(this, R.color.kind_date),
                    ContextCompat.getColor(this, R.color.kind_bill),
                    ContextCompat.getColor(this, R.color.kind_event)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            tv.invalidate()
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val sections = hub.brief()
        val container = binding.briefContainer
        // Once per render, not once per row.
        refreshNameCache()
        container.removeAllViews()

        if (sections.isEmpty()) {
            binding.briefEmpty.visibility = View.VISIBLE
            binding.briefSubtitle.visibility = View.GONE
            binding.briefOverdue.visibility = View.GONE
            return
        }

        binding.briefEmpty.visibility = View.GONE
        binding.briefSubtitle.visibility = View.VISIBLE

        val offset = BriefPolicy.deviceOffset(now)

        // TWO lanes, drawn differently on purpose.
        //
        // The agenda is what needs doing, and it stays ONE ranked list
        // banded by urgency -- the same ordering the widget draws, from the
        // same function, with the capacity opened up. This screen is what a
        // widget tap opens into, so a row must not move between the two.
        //
        // Open items are the leftovers: things an app mentioned that nobody
        // committed to and that carry no declared time. There the question
        // is not "what do I do" but "what is all this", so they are bucketed
        // by the app they came from and each bucket can be cleared in one
        // action. Bucketing the AGENDA that way would turn it back into an
        // inbox -- see BriefRender.byOrigin.
        val agenda = BriefTriage.only(sections, BriefTriage.Lane.AGENDA)
        val open = BriefTriage.only(sections, BriefTriage.Lane.OPEN)

        val face = BriefRender.face(
            agenda, now, offset, EVERYTHING
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
        val origins = face.lines.map { originOf(it) }.distinct().size
        face.lines.groupBy { it.band }.forEach { (band, lines) ->
            container.addView(bandHeader(band, lines.size, container))
            // Attribute when more than one ORIGIN is on screen, not more
            // than one client. Every notification-sourced row shares the
            // single client "notifications" while actually coming from
            // WhatsApp, Teams and Outlook, so counting clients hid exactly
            // the attribution worth showing.
            container.addView(bandCard(lines, origins > 1, container))
        }

        renderOpenItems(open, now, offset, container)
    }

    /**
     * The leftovers, bucketed by the app that produced them.
     *
     * Everything here failed both ways into the agenda: nobody committed to
     * it and it carries no declared time. That is almost always one app
     * being noisy, which is why the buckets exist and why each one can be
     * cleared in a single action -- the alternative is dismissing forty
     * rows one at a time, which nobody does, so the list just grows until
     * the screen is useless.
     *
     * Drawn BELOW the agenda and under its own heading, never mixed into
     * it. The ranked list at the top is the thing this screen is for.
     */
    private fun renderOpenItems(
        open: List<BriefPolicy.Section>,
        now: Long,
        offset: Long,
        container: ViewGroup
    ) {
        if (open.isEmpty()) return
        val lines = BriefRender.face(open, now, offset, EVERYTHING).lines
        if (lines.isEmpty()) return

        container.addView(openHeader(lines.size, container))
        BriefRender.byOrigin(lines).forEach { (key, group) ->
            container.addView(originHeader(key, group, container))
            // Already under the app's own heading, so repeating it on every
            // row is noise.
            container.addView(bandCard(group, false, container))
        }
    }

    private fun openHeader(count: Int, parent: ViewGroup): View =
        layoutInflater.inflate(R.layout.item_brief_band, parent, false).apply {
            findViewById<TextView>(R.id.bandLabel).also {
                it.setText(R.string.brief_open_items)
                it.setTextColor(getColor(R.color.band_later))
            }
            findViewById<TextView>(R.id.bandCount).text =
                getString(R.string.band_count, count)
            // No dismiss on this one: "clear everything nobody asked for"
            // across every app at once is a bigger action than it looks,
            // and the per-app buttons below say what they will take.
            findViewById<View>(R.id.bandDismiss).visibility = View.GONE
        }

    private fun originHeader(
        key: String,
        group: List<BriefRender.Line>,
        parent: ViewGroup
    ): View = layoutInflater.inflate(R.layout.item_brief_band, parent, false).apply {
        val shown = group.firstOrNull()?.let { originOf(it) } ?: key
        findViewById<TextView>(R.id.bandLabel).also {
            it.text = shown
            it.setTextColor(getColor(R.color.band_later))
        }
        findViewById<TextView>(R.id.bandCount).text =
            getString(R.string.band_count, group.size)
        findViewById<View>(R.id.bandDismiss).setOnClickListener {
            confirmDismissOrigin(key, shown, group.size)
        }
    }

    /**
     * Dismissing everything one app produced.
     *
     * Confirmed, like the band version and for the same reason: it removes
     * several things at once and nothing shows what a mistake cost until
     * the next render.
     *
     * Matched on BriefRender.originKey rather than the displayed name, so
     * two apps whose labels happen to read the same are still two buckets,
     * and a label that changes between renders cannot make the button miss.
     */
    private fun confirmDismissOrigin(key: String, shown: String, count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dismiss_group_title, shown))
            .setMessage(getString(R.string.dismiss_group_body, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dismiss) { _, _ ->
                val keys = BriefDismiss.keysOf(hub.brief()) { section, item ->
                    BriefTriage.laneOf(item) == BriefTriage.Lane.OPEN &&
                        (item.source?.takeIf { it.isNotBlank() } ?: section.clientId) == key
                }
                hub.dismiss(keys)
                toast(getString(R.string.dismissed_many, keys.size))
                render()
            }
            .show()
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
            findViewById<View>(R.id.bandDismiss).setOnClickListener {
                confirmDismissBand(band, count)
            }
        }

    /**
     * Dismissing a whole band.
     *
     * Confirmed, because it removes several things at once and the user
     * cannot see what a mistake cost until the next render. Not confirmed
     * for a single row: one item is cheap to lose and the sheet already
     * showed exactly what it was.
     */
    private fun confirmDismissBand(band: BriefPolicy.Band?, count: Int) {
        val label = getString(BriefIcons.label(band))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dismiss_group_title, label))
            .setMessage(getString(R.string.dismiss_group_body, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dismiss) { _, _ ->
                val now = System.currentTimeMillis()
                val offset = BriefPolicy.deviceOffset(now)
                val keys = BriefDismiss.keysOf(hub.brief()) { _, item ->
                    BriefPolicy.bandOf(item.dueAt, now, offset) == band
                }
                hub.dismiss(keys)
                toast(getString(R.string.dismissed_many, keys.size))
                render()
            }
            .show()
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
            findViewById<ImageView>(R.id.rowTile).setImageResource(BriefIcons.tile(line.kind))
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
                if (attribute) it.text = getString(R.string.brief_from, originOf(line))
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
            append(getString(R.string.brief_item_meta, originOf(line), line.mark))
            if (link != null) append("\n").append(link)
        }

        val b = MaterialAlertDialogBuilder(this)
            .setTitle(line.title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            // Not confirmed: one item is cheap to lose, the sheet showed
            // exactly what it was, and a confirmation on every single
            // dismissal is how a feature becomes one nobody uses.
            .setNegativeButton(R.string.dismiss) { _, _ ->
                // line.id first: BriefDismiss.apply() keys on the id when the
                // client sent one, so computing the title-based key here
                // would store a mark that matches nothing — Dismiss would
                // animate, the row would return on the next render, and
                // there would be no error anywhere.
                hub.dismiss(
                    listOf(BriefDismiss.key(line.clientId, line.id, line.title, line.dueAt))
                )
                toast(getString(R.string.dismissed_one))
                render()
            }

        // Only a paired web app has an origin Medha can offer to open.
        val origin = webOriginFor(line.clientId)
        if (link != null) {
            b.setNeutralButton(R.string.brief_item_open) { _, _ -> launch(link) }
        } else if (origin != null) {
            b.setNeutralButton(R.string.brief_item_open_app) { _, _ -> launch(origin) }
        }
        b.show()
    }

    /**
     * What to call a row's origin, in a person's words.
     *
     * Three sources, in order: the app a notification came from, the
     * posting client's registered NAME, and finally its id. Only the first
     * two are names; the third is a fallback that should be rare.
     *
     * Every row used to show the id, so the screen said "from admin" and
     * "from notifications" — a registry key and an internal bucket, and
     * neither is something the reader can do anything with. "admin" is
     * Quicksilver; "notifications" was WhatsApp, Teams and Outlook all at
     * once, which is the part a per-item source fixes rather than a
     * rename.
     *
     * The registry is read once per render and cached for that render:
     * resolving per row would re-read it for every line on the screen.
     */
    private var nameCache: Map<String, String> = emptyMap()

    private fun refreshNameCache() {
        nameCache = runCatching {
            com.adabala.medha.auth.ClientRegistry.get(this)
                .unordered()
                .associate { it.id to it.name.trim().ifBlank { it.id } }
        }.getOrDefault(emptyMap())
    }

    private fun originOf(line: BriefRender.Line): String =
        line.source?.takeIf { it.isNotBlank() }
            ?: nameCache[line.clientId]
            ?: line.clientId

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

    // Deliberately a second copy of MainActivity's helper rather than a shared
    // extension. A one-line wrapper is not worth a new file, and moving
    // MainActivity's would mean editing a 1500-line Activity to fix a compile
    // error in this one -- which is exactly how v0.17.0 lost the add-client
    // flow.
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
