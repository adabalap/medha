package com.example.hellomedha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * The whole integration, end to end, in one screen:
 *
 *   Connect  ->  consent dialog  ->  token  ->  streamed answer
 *
 * Nothing here depends on Medha's own code. It talks to a package name and an
 * HTTP endpoint, which is exactly the position a real third-party app is in.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var prompt: EditText
    private lateinit var connectBtn: Button
    private lateinit var askBtn: Button

    private var client: MedhaClient? = null

    private val handshake = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val token = data.getStringExtra(EXTRA_TOKEN).orEmpty()
            val baseUrl = data.getStringExtra(EXTRA_BASE_URL).orEmpty()
            val granted = data.getStringArrayExtra(EXTRA_GRANTED).orEmpty()
            val namespace = data.getStringExtra(EXTRA_NAMESPACE).orEmpty()

            // Persist so we never prompt again unnecessarily. Re-requesting
            // the same capabilities is silent on Medha's side, but not asking
            // at all is better still.
            prefs().edit()
                .putString(PREF_TOKEN, token)
                .putString(PREF_BASE_URL, baseUrl)
                .apply()

            client = MedhaClient(baseUrl, token)
            status.text = getString(
                R.string.status_connected, granted.joinToString(", "), namespace
            )
            askBtn.isEnabled = true
        } else {
            status.text = when (data?.getStringExtra(EXTRA_ERROR)) {
                "denied" -> getString(R.string.status_denied)
                "unidentified_caller" -> getString(R.string.status_bad_launch)
                "invalid_request" -> getString(R.string.status_bad_request)
                else -> getString(R.string.status_cancelled)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        prompt = findViewById(R.id.prompt)
        connectBtn = findViewById(R.id.connect)
        askBtn = findViewById(R.id.ask)

        restoreExistingGrant()

        connectBtn.setOnClickListener { requestAccess() }
        askBtn.setOnClickListener { ask() }
    }

    /** A stored token from a previous run means no prompt at all. */
    private fun restoreExistingGrant() {
        val token = prefs().getString(PREF_TOKEN, null)
        val baseUrl = prefs().getString(PREF_BASE_URL, null)
        if (token.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            status.text = getString(R.string.status_not_connected)
            askBtn.isEnabled = false
            return
        }
        client = MedhaClient(baseUrl, token)
        status.text = getString(R.string.status_restored, baseUrl)
        askBtn.isEnabled = true
    }

    private fun requestAccess() {
        val intent = Intent(ACTION_REQUEST_ACCESS).apply {
            // Explicit package: without this, an implicit action could in
            // principle be answered by some other app that declared the same
            // filter, and we would hand our trust to it.
            setPackage(MEDHA_PACKAGE)
            putExtra(EXTRA_CAPABILITIES, arrayOf("generate", "rag"))
            putExtra(EXTRA_REASON, getString(R.string.access_reason))
        }
        if (intent.resolveActivity(packageManager) == null) {
            status.text = getString(R.string.status_not_installed)
            return
        }
        // Must be a for-result launch: Medha identifies us by the calling
        // package, which the platform only populates for this launch type.
        handshake.launch(intent)
    }

    private fun ask() {
        val c = client ?: return
        val question = prompt.text.toString().trim()
        if (question.isEmpty()) return

        askBtn.isEnabled = false
        output.text = ""
        val sb = StringBuilder()

        // A plain thread, not coroutines, to keep the dependency list at zero.
        // Real apps should use whatever they already use.
        thread {
            try {
                if (!c.isReady()) {
                    runOnUiThread { output.text = getString(R.string.error_no_model) }
                    return@thread
                }
                c.chatStream(listOf("user" to question)) { delta ->
                    sb.append(delta)
                    runOnUiThread { output.text = sb.toString() }
                }
            } catch (e: MedhaClient.ApiException) {
                val msg = when {
                    e.isUnauthorized -> {
                        // Revoked or rotated. Drop the dead token so the next
                        // Connect actually re-prompts instead of silently
                        // failing again with the same credential.
                        prefs().edit().remove(PREF_TOKEN).remove(PREF_BASE_URL).apply()
                        client = null
                        runOnUiThread { askBtn.isEnabled = false }
                        getString(R.string.error_revoked)
                    }
                    e.isForbidden -> getString(R.string.error_forbidden, e.message.orEmpty())
                    e.retryAfterSeconds != null ->
                        getString(R.string.error_busy, e.retryAfterSeconds)
                    e.isTransient -> getString(R.string.error_transient, e.message.orEmpty())
                    else -> getString(R.string.error_generic, e.status, e.message.orEmpty())
                }
                runOnUiThread { output.text = msg }
            } catch (e: Exception) {
                runOnUiThread {
                    output.text = getString(R.string.error_unreachable, e.message.orEmpty())
                }
            } finally {
                runOnUiThread { if (client != null) askBtn.isEnabled = true }
            }
        }
    }

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
    }
}
