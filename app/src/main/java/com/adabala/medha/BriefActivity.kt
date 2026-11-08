package com.adabala.medha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.adabala.medha.databinding.ActivityBriefBinding
import com.adabala.medha.notify.BriefJson
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.NotificationHub
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
            return
        }

        binding.briefEmpty.visibility = View.GONE
        binding.briefSubtitle.visibility = View.VISIBLE

        val rowCount = sections.sumOf { it.items.size }
        binding.briefSubtitle.text = resources.getQuantityString(
            R.plurals.brief_subtitle, sections.size, sections.size, rowCount
        )

        sections.forEach { section ->
            // A sentence introducing the card, the way the system brief
            // writes "1 event left for today." above its own. A bare label
            // would read as a form field.
            container.addView(
                leadIn(
                    getString(
                        R.string.brief_section_lead,
                        section.clientId,
                        BriefPolicy.age(section.updatedAt, now)
                    )
                )
            )
            container.addView(card(section))
        }
    }

    private fun leadIn(text: String): View =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(4, dp(24), 0, dp(8))
        }

    /** One app's items, in a rounded card. */
    private fun card(section: BriefPolicy.Section): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_brief_card)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            section.items.forEach { addView(itemRow(it, section)) }
        }

    private fun itemRow(item: BriefPolicy.Item, section: BriefPolicy.Section): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            // A ripple so the row looks tappable before it is tapped. Every
            // row opens something — its link if it has one, a detail sheet
            // otherwise — so there is no dead row to mislead anyone.
            setBackgroundResource(selectableItemBackground())
            setOnClickListener { openItem(item, section) }

            addView(
                View(this@BriefActivity).apply {
                    background = getDrawable(R.drawable.bg_brief_accent)
                },
                LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = dp(13) }
            )

            addView(
                LinearLayout(this@BriefActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        // No explicit colour: a plain TextView under
                        // Theme.Medha already takes the theme's primary text
                        // colour, and hardcoding one is what breaks in dark
                        // mode. The secondary line below sets one because it
                        // deliberately differs.
                        TextView(this@BriefActivity).apply {
                            text = item.title
                            textSize = 16f
                        }
                    )
                    if (item.text.isNotBlank()) {
                        addView(
                            TextView(this@BriefActivity).apply {
                                text = item.text
                                textSize = 14f
                                setTextColor(getColor(R.color.text_secondary))
                                setLineSpacing(dp(2).toFloat(), 1f)
                            }
                        )
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
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
    private fun openItem(item: BriefPolicy.Item, section: BriefPolicy.Section) {
        val link = item.link?.takeIf { BriefJson.isSafeLink(it) }
        if (link != null) {
            if (launch(link)) return
            // Fall through to the sheet rather than failing silently: a
            // device with no browser is rare but a dead tap is worse.
        }
        showDetail(item, section, link)
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

    private fun showDetail(
        item: BriefPolicy.Item,
        section: BriefPolicy.Section,
        link: String?
    ) {
        val now = System.currentTimeMillis()
        val body = buildString {
            if (item.text.isNotBlank()) append(item.text).append("\n\n")
            append(
                getString(
                    R.string.brief_item_meta,
                    section.clientId,
                    BriefPolicy.age(section.updatedAt, now)
                )
            )
            if (link != null) append("\n").append(link)
        }

        val b = MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)

        // Only a paired web app has an origin Medha can offer to open.
        val origin = webOriginFor(section.clientId)
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

    /** The theme's ripple, resolved once per row. */
    private fun selectableItemBackground(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
