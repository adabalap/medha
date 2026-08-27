package com.example.hellomedha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.hellomedha.databinding.ActivityMainBinding
import kotlin.concurrent.thread

/**
 * The whole integration, end to end:
 *
 *   Connect -> consent dialog -> scoped token -> streamed answer
 *
 * Nothing here depends on Medha's own code. It resolves an intent action and
 * talks to an HTTP endpoint, which is exactly the position a real third-party
 * app is in.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var client: MedhaClient? = null
    private val turns = mutableListOf<Pair<String, String>>()

    private val handshake = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val token = data.getStringExtra(EXTRA_TOKEN).orEmpty()
            val baseUrl = data.getStringExtra(EXTRA_BASE_URL).orEmpty()
            val granted = data.getStringArrayExtra(EXTRA_GRANTED).orEmpty()
            val namespace = data.getStringExtra(EXTRA_NAMESPACE).orEmpty()

            // Persist so we never prompt again unnecessarily. Note baseUrl
            // carries whatever port Medha is actually configured for -- it is
            // never assumed or hardcoded on this side.
            prefs().edit()
                .putString(PREF_TOKEN, token)
                .putString(PREF_BASE_URL, baseUrl)
                .putString(PREF_GRANTED, granted.joinToString(","))
                .putString(PREF_NS, namespace)
                .apply()
            connect(baseUrl, token, granted.toList(), namespace)
        } else {
            val why = when (data?.getStringExtra(EXTRA_ERROR)) {
                "denied" -> getString(R.string.status_denied)
                "unidentified_caller" -> getString(R.string.status_bad_launch)
                "invalid_request" -> getString(R.string.status_bad_request)
                else -> getString(R.string.status_cancelled)
            }
            b.status.text = why
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.connect.setOnClickListener { requestAccess() }
        b.ask.setOnClickListener { ask() }
        b.ragToggle.setOnCheckedChangeListener { _, on ->
            b.collection.visibility = if (on) View.VISIBLE else View.GONE
        }
        restoreExistingGrant()
    }

    // ------------------------------ connection ------------------------------

    /** A stored token from a previous run means no prompt at all. */
    private fun restoreExistingGrant() {
        val token = prefs().getString(PREF_TOKEN, null)
        val baseUrl = prefs().getString(PREF_BASE_URL, null)
        if (token.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            setDisconnected(getString(R.string.status_not_connected))
            return
        }
        connect(
            baseUrl, token,
            prefs().getString(PREF_GRANTED, "").orEmpty().split(",").filter { it.isNotBlank() },
            prefs().getString(PREF_NS, "").orEmpty()
        )
    }

    private fun connect(baseUrl: String, token: String, granted: List<String>, ns: String) {
        client = MedhaClient(baseUrl, token)
        b.status.text = getString(R.string.status_connected, baseUrl)
        b.grantDetail.text = getString(
            R.string.grant_detail, granted.joinToString(", ").ifBlank { "—" }, ns
        )
        b.grantDetail.visibility = View.VISIBLE
        b.connect.text = getString(R.string.reconnect)
        b.ask.isEnabled = true
        b.ragToggle.isEnabled = granted.contains("rag")
    }

    private fun setDisconnected(message: String) {
        client = null
        b.status.text = message
        b.grantDetail.visibility = View.GONE
        b.connect.text = getString(R.string.connect)
        b.ask.isEnabled = false
    }

    /**
     * Finds whichever Medha variant is actually installed.
     *
     * Medha ships under several package names depending on build variant
     * (`com.adabala.medha` plus optional `.full` and `.debug` suffixes), so
     * targeting one hardcoded name reports "not installed" against a working
     * debug build -- which is exactly what happened the first time this
     * sample ran. Resolving the action finds whatever is really there; the
     * prefix check then makes the intent explicit again so an unrelated app
     * declaring the same filter cannot intercept the request.
     *
     * For production, compare the resolved package's signing certificate
     * against Medha's. A prefix is a sanity check, not authentication.
     */
    private fun resolveMedhaPackage(): String? {
        val matches = packageManager
            .queryIntentActivities(Intent(ACTION_REQUEST_ACCESS), 0)
            .map { it.activityInfo.packageName }
            .filter { it == MEDHA_PACKAGE || it.startsWith("$MEDHA_PACKAGE.") }
            .distinct()
        return matches.firstOrNull { it == MEDHA_PACKAGE } ?: matches.firstOrNull()
    }

    private fun requestAccess() {
        val target = resolveMedhaPackage() ?: run {
            setDisconnected(getString(R.string.status_not_installed))
            return
        }
        handshake.launch(
            Intent(ACTION_REQUEST_ACCESS).apply {
                setPackage(target)
                putExtra(EXTRA_CAPABILITIES, arrayOf("generate", "rag"))
                putExtra(EXTRA_REASON, getString(R.string.access_reason))
            }
        )
    }

    // -------------------------------- chat ---------------------------------

    private fun bubble(text: String, mine: Boolean, error: Boolean = false): TextView {
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (mine) R.color.on_teal else R.color.text_primary
                )
            )
            textSize = 14.5f
            setPadding(dp(13), dp(9), dp(13), dp(9))
            setBackgroundResource(if (mine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            if (error) setBackgroundColor(0x33CC4433)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
            gravity = if (mine) Gravity.END else Gravity.START
            marginStart = if (mine) dp(48) else 0
            marginEnd = if (mine) 0 else dp(48)
        }
        tv.layoutParams = lp
        b.thread.addView(tv)
        b.empty.visibility = View.GONE
        b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun ask() {
        val c = client ?: return
        val question = b.prompt.text.toString().trim()
        if (question.isEmpty()) return
        b.prompt.setText("")
        b.ask.isEnabled = false

        bubble(question, mine = true)
        turns.add("user" to question)
        val pending = bubble("…", mine = false)

        val collection = if (b.ragToggle.isChecked) {
            b.collection.text.toString().trim().ifBlank { "demo" }
        } else {
            null
        }

        thread {
            val sb = StringBuilder()
            try {
                // Check readiness first and report what is actually wrong.
                // A generic "no model loaded" sends people to fix the wrong
                // thing when the real cause is an unreachable server.
                when (val r = c.readiness()) {
                    is MedhaClient.Readiness.Ready -> Unit
                    is MedhaClient.Readiness.NoModel -> {
                        val msg = if (r.lastError != null) {
                            getString(R.string.error_model_failed, r.lastError)
                        } else {
                            getString(R.string.error_no_model)
                        }
                        runOnUiThread { pending.text = msg }
                        turns.removeAt(turns.lastIndex)
                        return@thread
                    }
                    is MedhaClient.Readiness.Unreachable -> {
                        runOnUiThread {
                            pending.text = getString(R.string.error_unreachable, r.reason)
                        }
                        turns.removeAt(turns.lastIndex)
                        return@thread
                    }
                }
                c.chatStream(turns.toList(), collection) { delta ->
                    sb.append(delta)
                    runOnUiThread {
                        pending.text = sb.toString()
                        b.scroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
                val answer = sb.toString().ifBlank { getString(R.string.empty_reply) }
                runOnUiThread { pending.text = answer }
                turns.add("assistant" to answer)
            } catch (e: MedhaClient.ApiException) {
                // Drop the failed turn so it does not poison the next request's
                // context -- resending a question the model never answered
                // makes the transcript progressively less coherent.
                turns.removeAt(turns.lastIndex)
                val msg = when {
                    e.isUnauthorized -> {
                        prefs().edit().clear().apply()
                        runOnUiThread { setDisconnected(getString(R.string.status_revoked)) }
                        getString(R.string.error_revoked)
                    }
                    e.isForbidden -> getString(R.string.error_forbidden, e.message.orEmpty())
                    e.retryAfterSeconds != null -> getString(R.string.error_busy, e.retryAfterSeconds)
                    e.isTransient -> getString(R.string.error_transient, e.message.orEmpty())
                    else -> getString(R.string.error_generic, e.status, e.message.orEmpty())
                }
                runOnUiThread { pending.text = msg }
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex)
                runOnUiThread {
                    pending.text = getString(R.string.error_unreachable, e.message.orEmpty())
                }
            } finally {
                runOnUiThread { b.ask.isEnabled = client != null }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences("hello-medha", Context.MODE_PRIVATE)

    private companion object {
        const val MEDHA_PACKAGE = "com.adabala.medha"
        const val ACTION_REQUEST_ACCESS = "com.adabala.medha.action.REQUEST_ACCESS"
        const val EXTRA_CAPABILITIES = "com.adabala.medha.extra.CAPABILITIES"
        const val EXTRA_REASON = "com.adabala.medha.extra.REASON"
        const val EXTRA_TOKEN = "com.adabala.medha.extra.TOKEN"
        const val EXTRA_BASE_URL = "com.adabala.medha.extra.BASE_URL"
        const val EXTRA_GRANTED = "com.adabala.medha.extra.GRANTED_CAPABILITIES"
        const val EXTRA_NAMESPACE = "com.adabala.medha.extra.NAMESPACE"
        const val EXTRA_ERROR = "com.adabala.medha.extra.ERROR"

        const val PREF_TOKEN = "medha_token"
        const val PREF_BASE_URL = "medha_base_url"
        const val PREF_GRANTED = "medha_granted"
        const val PREF_NS = "medha_namespace"
    }
}
