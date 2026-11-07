package com.adabala.medha

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.adabala.medha.databinding.ActivityBriefBinding
import com.adabala.medha.notify.BriefPolicy
import com.adabala.medha.notify.NotificationHub
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The whole brief, grouped by the app that wrote it.
 *
 * Reached by tapping the home-screen widget. That gesture is load-bearing
 * rather than decorative: the widget draws [BriefPolicy.PAGE_ROWS] rows of up
 * to [BriefPolicy.MAX_ROWS] and rotates through the rest, so this is the only
 * place the user can read the overflow without waiting for it to come around.
 * It is the same behaviour Samsung's own digest has when tapped, which is
 * what was asked for — and it is why the overflow does not have to be thrown
 * away at write time.
 *
 * Rows are built in code rather than from an adapter. The content is bounded
 * (twelve rows across at most eight sections, enforced in [BriefPolicy]), so
 * view recycling buys nothing and a RecyclerView plus adapter plus view
 * holder would be more moving parts than twelve text rows justify.
 */
class BriefActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBriefBinding
    private val hub by lazy { NotificationHub(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBriefBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
     * The brief is written by HTTP clients in another process-less path and
     * ages out on read, so what was correct when this screen was created is
     * not necessarily correct when the user comes back to it from the
     * launcher. A listener would be more machinery for a screen whose whole
     * lifetime is a few seconds of reading.
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
            binding.briefScroll.visibility = View.GONE
            binding.briefSubtitle.text = ""
            return
        }

        binding.briefEmpty.visibility = View.GONE
        binding.briefScroll.visibility = View.VISIBLE

        val rowCount = sections.sumOf { it.items.size }
        binding.briefSubtitle.text = resources.getQuantityString(
            R.plurals.brief_subtitle, sections.size, sections.size, rowCount
        )

        sections.forEach { section ->
            container.addView(sectionHeader(section.clientId, BriefPolicy.age(section.updatedAt, now)))
            section.items.forEach { item ->
                container.addView(itemRow(item.title, item.text))
            }
        }
    }

    private fun sectionHeader(clientId: String, age: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(2))
            addView(
                TextView(this@BriefActivity).apply {
                    text = clientId
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    // The id is the one thing the user can tie back to an app
                    // they granted access to, so it reads as a label rather
                    // than as body text.
                    isAllCaps = true
                    letterSpacing = 0.06f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(this@BriefActivity).apply {
                    text = age
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                }
            )
        }

    private fun itemRow(title: String, text: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(
                // No explicit colour: a plain TextView under Theme.Medha
                // already picks up the theme's primary text colour, and
                // hardcoding one here would be the thing that breaks in dark
                // mode. The secondary rows below set a colour because they
                // deliberately differ from it.
                TextView(this@BriefActivity).apply {
                    this.text = title
                    textSize = 15f
                }
            )
            if (text.isNotBlank()) {
                addView(
                    TextView(this@BriefActivity).apply {
                        this.text = text
                        textSize = 13.5f
                        setTextColor(getColor(R.color.text_secondary))
                    }
                )
            }
        }

    /**
     * Clears every section, not just one.
     *
     * This is the user's home screen and the brief is the one surface other
     * apps can put content on it. They get a way to empty it without having
     * to work out which app wrote what and revoke it. Confirmed first,
     * because it discards other apps' content and they cannot tell it
     * happened.
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
