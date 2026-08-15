package com.adabala.medha

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.adabala.medha.auth.ClientRegistry
import com.adabala.medha.connectors.SmsConnector
import com.adabala.medha.sched.InferenceScheduler
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import com.adabala.medha.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /**
     * Every control's enabled state derives from this, so the UI can never offer
     * an action that is meaningless right now — e.g. Start while already
     * running, or Open demo before the model is loaded.
     */
    private enum class UiState { IDLE, STARTING, LOADING, RUNNING, ERROR }

    private lateinit var binding: ActivityMainBinding
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false

    private var uiState = UiState.IDLE
    /** Set when the user taps Start; cleared once the service actually answers. */
    private var startRequestedAt = 0L
    /** Last /system payload, reused by the Thermal & hardware dialog. */
    private var lastSystemJson: String? = null
    private var lastSchedulerJson: String? = null

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val registry by lazy { ClientRegistry.get(this) }
    private val sms by lazy { SmsConnector(this) }

    private val smsPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = res[Manifest.permission.READ_SMS] == true
            toast(getString(if (granted) R.string.sms_granted else R.string.sms_denied))
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // SAF: user browses to a .litertlm file. We copy it into app storage so the
    // native engine can open it by a stable filesystem path.
    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            if (polling) pollHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDrawer()
        setupScheduler()

        binding.editPort.setText(
            prefs.getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT)
        )
        binding.modelPathText.text = prefs.getString(InferenceService.KEY_MODEL_PATH, null)
            ?: getString(R.string.no_model_selected)

        when (prefs.getString(InferenceService.KEY_BACKEND, "GPU")) {
            "CPU" -> binding.backendToggle.check(binding.backendCpu.id)
            "NPU" -> binding.backendToggle.check(binding.backendNpu.id)
            else -> binding.backendToggle.check(binding.backendGpu.id)
        }

        renderToken()

        binding.btnPickModel.setOnClickListener {
            // .litertlm has no registered MIME type; we filter by name on import.
            pickModel.launch(arrayOf("*/*"))
        }

        binding.backendToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val v = when (checkedId) {
                binding.backendCpu.id -> "CPU"
                binding.backendNpu.id -> "NPU"
                else -> "GPU"
            }
            prefs.edit().putString(InferenceService.KEY_BACKEND, v).apply()
        }

        binding.btnStart.setOnClickListener { startMedha() }
        binding.btnStop.setOnClickListener { stopMedha() }
        binding.btnCopyToken.setOnClickListener { copyToken() }

        applyState(UiState.IDLE)
    }

    // ----------------------------- drawer -----------------------------

    private fun setupDrawer() {
        // fitsSystemWindows on NavigationView REPLACES the header's padding with
        // the window insets instead of adding to it, which drew the title under
        // the status bar. Apply the top inset ourselves and keep the layout's
        // own padding intact.
        val header = binding.navView.getHeaderView(0)
        val basePad = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, basePad + top, v.paddingRight, v.paddingBottom)
            insets
        }
        binding.toolbar.contentDescription = getString(R.string.app_name)
        binding.toolbar.setNavigationContentDescription(R.string.open_drawer)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.nav_open_pwa -> openDemo()
                R.id.nav_clients -> showClientsDialog()
                R.id.nav_sms -> showSmsDialog()
                R.id.nav_scheduler -> showSchedulerDialog()
                R.id.nav_thermal -> showThermalDialog()
                R.id.nav_copy_token -> copyToken()
                R.id.nav_regen_token -> confirmRegenerateToken()
                R.id.nav_battery -> requestBatteryExemption()
                R.id.nav_stop -> stopMedha()
                R.id.nav_endpoints -> showEndpointsDialog()
                R.id.nav_about -> showAboutDialog()
                else -> return@setNavigationItemSelectedListener false
            }
            true
        }

        // Back closes the drawer before leaving the screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // -------------------------- state machine --------------------------

    /**
     * Single place that decides what is clickable. Called on every poll, so the
     * UI tracks the service even when it is started or stopped from elsewhere
     * (the notification's Stop action, or the system killing it).
     */
    private fun applyState(state: UiState) {
        uiState = state
        val hasModel = prefs.getString(InferenceService.KEY_MODEL_PATH, null) != null
        val busy = state == UiState.STARTING || state == UiState.LOADING
        val live = state == UiState.RUNNING || busy

        binding.btnStart.isEnabled = hasModel && (state == UiState.IDLE || state == UiState.ERROR)
        binding.btnStart.text = when (state) {
            UiState.STARTING -> getString(R.string.btn_starting)
            UiState.LOADING -> getString(R.string.btn_loading)
            UiState.RUNNING -> getString(R.string.btn_running)
            UiState.ERROR -> getString(R.string.btn_retry)
            UiState.IDLE -> getString(R.string.btn_start)
        }

        binding.btnStop.isEnabled = live

        // Config that only takes effect on (re)start is locked while live, so a
        // change cannot silently disagree with what the service is actually
        // running. Stop first, then edit.
        binding.btnPickModel.isEnabled = !live
        binding.editPort.isEnabled = !live
        binding.backendToggle.isEnabled = !live
        binding.backendGpu.isEnabled = !live
        binding.backendCpu.isEnabled = !live
        binding.backendNpu.isEnabled = !live

        binding.btnCopyToken.isEnabled = apiToken() != null

        binding.navView.menu.findItem(R.id.nav_open_pwa)?.isEnabled = state == UiState.RUNNING
        binding.navView.menu.findItem(R.id.nav_stop)?.isEnabled = live
        binding.navView.menu.findItem(R.id.nav_copy_token)?.isEnabled = apiToken() != null
        binding.navView.menu.findItem(R.id.nav_regen_token)?.isEnabled = apiToken() != null
        binding.navView.menu.findItem(R.id.nav_thermal)?.isEnabled = lastSystemJson != null

        val sub = when (state) {
            UiState.IDLE -> getString(R.string.state_stopped)
            UiState.STARTING -> getString(R.string.state_starting)
            UiState.LOADING -> getString(R.string.state_loading)
            UiState.RUNNING -> getString(R.string.state_running, currentPort())
            UiState.ERROR -> getString(R.string.state_error)
        }
        binding.toolbarTitle.toolbarSubtitle.text = sub
        runCatching {
            binding.navView.getHeaderView(0)
                .findViewById<android.widget.TextView>(R.id.navHeaderStatus)?.text = sub
        }
        binding.loadProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.configLockedHint.visibility = if (live) View.VISIBLE else View.GONE
    }

    // --------------------------- scheduler UI ---------------------------

    private fun setupScheduler() {
        val p = prefs
        binding.pauseSlider.value =
            p.getFloat(InferenceService.KEY_THERMAL_PAUSE, 0.85f).coerceIn(0.30f, 1.20f)
        binding.resumeSlider.value =
            p.getFloat(InferenceService.KEY_THERMAL_RESUME, 0.70f).coerceIn(0.20f, 1.10f)
        binding.switchCharging.isChecked = p.getBoolean(InferenceService.KEY_BATCH_CHARGING, false)
        renderSliderLabels()

        binding.pauseSlider.addOnChangeListener { _, v, _ ->
            // Keep resume strictly below pause in the UI too, so the user never
            // sees a configuration the service will silently rewrite.
            if (binding.resumeSlider.value >= v - 0.05f) {
                binding.resumeSlider.value = (v - 0.05f).coerceAtLeast(0.20f)
            }
            persistScheduler(); renderSliderLabels()
        }
        binding.resumeSlider.addOnChangeListener { _, v, _ ->
            if (v >= binding.pauseSlider.value - 0.05f) {
                binding.resumeSlider.value =
                    (binding.pauseSlider.value - 0.05f).coerceAtLeast(0.20f)
            }
            persistScheduler(); renderSliderLabels()
        }
        binding.switchCharging.setOnCheckedChangeListener { _, _ -> persistScheduler() }
    }

    private fun renderSliderLabels() {
        binding.pauseLabel.text = getString(R.string.sched_pause) +
            "  " + String.format(Locale.US, "%.2f", binding.pauseSlider.value)
        binding.resumeLabel.text = getString(R.string.sched_resume) +
            "  " + String.format(Locale.US, "%.2f", binding.resumeSlider.value)
    }

    private fun persistScheduler() {
        prefs.edit()
            .putFloat(InferenceService.KEY_THERMAL_PAUSE, binding.pauseSlider.value)
            .putFloat(InferenceService.KEY_THERMAL_RESUME, binding.resumeSlider.value)
            .putBoolean(InferenceService.KEY_BATCH_CHARGING, binding.switchCharging.isChecked)
            .apply()
        // The running service re-reads config on each start command.
        if (uiState == UiState.RUNNING || uiState == UiState.LOADING) {
            ContextCompat.startForegroundService(this, Intent(this, InferenceService::class.java))
        }
    }

    // ---------------------------- actions ----------------------------

    private fun startMedha() {
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        // Validated up front: an out-of-range port previously produced a service
        // that started, failed to bind, and reported nothing useful.
        if (port == null || port !in 1024..65535) {
            toast(getString(R.string.err_port))
            return
        }
        if (prefs.getString(InferenceService.KEY_MODEL_PATH, null) == null) {
            toast(getString(R.string.err_no_model))
            return
        }
        prefs.edit().putString(InferenceService.KEY_PORT, port.toString()).apply()
        ensureNotifPerm()
        ContextCompat.startForegroundService(this, Intent(this, InferenceService::class.java))
        startRequestedAt = System.currentTimeMillis()
        applyState(UiState.STARTING)
    }

    private fun stopMedha() {
        stopService(Intent(this, InferenceService::class.java))
        startRequestedAt = 0L
        lastSystemJson = null
        applyState(UiState.IDLE)
        toast(getString(R.string.toast_stopped))
    }

    private fun openDemo() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${currentPort()}/"))
            )
        }.onFailure { toast(getString(R.string.err_no_browser)) }
    }

    // ---------------------------- dialogs ----------------------------

    /**
     * Client manager.
     *
     * The previous version was a read-only wall of text: no way to copy a
     * token, rotate one, or delete a client created by mistake. A token is
     * shown in full exactly once at creation, so "no copy" meant a typo in the
     * client id was unrecoverable and the token was gone for good.
     */
    private fun showClientsDialog() {
        val clients = registry.all()
        if (clients.isEmpty()) {
            showAddClientDialog()
            return
        }
        val labels = clients.map { c ->
            val caps = if (c.isAdmin) "full access" else c.capabilities.sorted().joinToString(", ")
            "${c.id}   [${c.namespace}:*]\n${c.token.take(10)}…${c.token.takeLast(4)}\n$caps"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clients_title)
            .setIcon(R.drawable.ic_client)
            .setItems(labels) { _, i -> showClientActions(clients[i]) }
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.client_add) { _, _ -> showAddClientDialog() }
            .show()
    }

    private fun showClientActions(client: ClientRegistry.Client) {
        val actions = mutableListOf(
            getString(R.string.copy_token),
            getString(R.string.rotate),
            getString(R.string.client_edit)
        )
        // The last admin must keep existing, or the owner is locked out of
        // their own service with no recovery short of clearing app data.
        val deletable = !client.isAdmin || registry.all().count { it.isAdmin } > 1
        if (deletable) actions.add(getString(R.string.revoke))

        MaterialAlertDialogBuilder(this)
            .setTitle(client.id)
            .setItems(actions.toTypedArray()) { _, i ->
                when (actions[i]) {
                    getString(R.string.copy_token) -> {
                        copyText("Medha token: ${client.id}", client.token)
                        showTokenDialog(client)
                    }
                    getString(R.string.rotate) -> confirmRotate(client)
                    getString(R.string.client_edit) -> showCapabilitiesDialog(client)
                    getString(R.string.revoke) -> confirmRevoke(client)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Shows the token in full and selectable, since clipboards can fail. */
    private fun showTokenDialog(client: ClientRegistry.Client) {
        MaterialAlertDialogBuilder(this)
            .setTitle(client.id)
            .setMessage(
                "${client.token}\n\nCopied to the clipboard.\n" +
                    "Namespace: ${client.namespace}:*\n" +
                    "Capabilities: ${if (client.isAdmin) "full access"
                        else client.capabilities.sorted().joinToString(", ")}"
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmRotate(client: ClientRegistry.Client) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rotate_title, client.id))
            .setMessage(R.string.rotate_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rotate) { _, _ ->
                val fresh = registry.rotate(client.id)
                if (fresh == null) {
                    toast(getString(R.string.caps_failed))
                } else {
                    copyText("Medha token: ${fresh.id}", fresh.token)
                    restartServiceIfLive()
                    showTokenDialog(fresh)
                }
            }
            .show()
    }

    private fun confirmRevoke(client: ClientRegistry.Client) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.revoke_title, client.id))
            .setMessage(R.string.revoke_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.revoke) { _, _ ->
                if (registry.revoke(client.id)) {
                    restartServiceIfLive()
                    toast(getString(R.string.revoked, client.id))
                } else {
                    toast(getString(R.string.revoke_refused))
                }
            }
            .show()
    }

    private fun copyText(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    /**
     * Capabilities offered in the UI, in the order they are shown.
     *
     * SMS and notify are deliberately absent from the default set: most
     * consumers should not have them, and a grant that broad should be a
     * decision rather than a default.
     */
    private val grantable = listOf(
        ClientRegistry.Cap.GENERATE to "Run the model",
        ClientRegistry.Cap.MEMORY to "Chat memory / sessions",
        ClientRegistry.Cap.RAG to "Knowledge (RAG)",
        ClientRegistry.Cap.STORE to "Store its own data",
        ClientRegistry.Cap.SMS_READ to "Read SMS",
        ClientRegistry.Cap.SMS_SEND to "Send SMS",
        ClientRegistry.Cap.NOTIFY to "Post notifications"
    )

    private fun showCapabilitiesDialog(client: ClientRegistry.Client) {
        val checked = grantable.map { it.first in client.capabilities }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(client.id)
            .setMultiChoiceItems(
                grantable.map { it.second }.toTypedArray(), checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val caps = grantable.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                val updated = registry.setCapabilities(client.id, caps)
                if (updated != null) {
                    // The server captured the client list at construction, so it
                    // has to be rebuilt for the change to take effect.
                    restartServiceIfLive()
                    toast(getString(R.string.caps_saved, updated.id))
                } else {
                    toast(getString(R.string.caps_failed))
                }
            }
            .show()
    }

    /** Rebuilds the server so a credential or capability change takes effect. */
    private fun restartServiceIfLive() {
        if (uiState == UiState.RUNNING || uiState == UiState.LOADING) {
            ContextCompat.startForegroundService(this, Intent(this, InferenceService::class.java))
        }
    }

    private fun showAddClientDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.client_id_hint)
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(input)
        }
        // Defaults on: everything a normal consumer needs. SMS and notify are
        // off, because they are the ones worth an explicit decision.
        val checked = grantable.map { it.first in ClientRegistry.Cap.DEFAULT }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.client_new_title)
            .setView(wrap)
            .setMultiChoiceItems(
                grantable.map { it.second }.toTypedArray(), checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val id = input.text.toString().trim().lowercase()
                val caps = grantable.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                runCatching {
                    // Namespace == id keeps the mental model simple: one client,
                    // one prefix, no separate thing to remember.
                    registry.create(id, id, id, caps)
                }.onSuccess { c ->
                    copyText("Medha token: ${c.id}", c.token)
                    restartServiceIfLive()
                    showTokenDialog(c)
                }.onFailure { e -> toast(e.message ?: "could not create client") }
            }
            .show()
    }

    private fun showSmsDialog() {
        val st = sms.status()
        if (!st.supported) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sms_title)
                .setIcon(R.drawable.ic_sms)
                .setMessage(getString(R.string.sms_unsupported))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val body = buildString {
            append("Read permission: ").append(if (st.canRead) "granted" else "NOT granted").append("\n")
            append("Send permission: ").append(if (st.canSend) "granted" else "not granted").append("\n")
            append("Default SMS app: ").append(if (st.isDefaultSmsApp) "yes" else "no").append("\n")
            if (st.canRead) append("Messages visible: ").append(st.totalMessages).append("\n")
            append("\nPWAs reach SMS through Medha at /connectors/sms/*, because a\n")
            append("browser page cannot read SMS at all. A client needs the\n")
            append("'sms.read' capability; sending additionally requires being the\n")
            append("default SMS app.")
        }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sms_title)
            .setIcon(R.drawable.ic_sms)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
        if (!st.canRead) {
            b.setNeutralButton(R.string.sms_grant) { _, _ ->
                smsPerm.launch(SmsConnector.READ_PERMISSIONS)
            }
        }
        b.show()
    }

    private fun showSchedulerDialog() {
        val js = lastSchedulerJson
        val body = if (js == null) getString(R.string.err_not_running) else runCatching {
            val s = JSONObject(js)
            buildString {
                append("Queue: ").append(s.optInt("queueDepth")).append(" / ")
                append(s.optInt("maxQueueDepth")).append("\n")
                append("Interactive waiting: ").append(s.optInt("interactiveWaiting")).append("\n")
                append("Batch paused: ").append(s.optBoolean("batchPaused")).append("\n")
                val why = s.optString("gateReason", "")
                if (why.isNotEmpty()) append("Reason: ").append(why).append("\n")
                append("\nThermal headroom: ")
                val hr = s.optDouble("thermalHeadroom", -1.0)
                append(if (hr < 0) "not reported" else String.format(Locale.US, "%.2f", hr))
                append("\nPause at: ").append(String.format(Locale.US, "%.2f", s.optDouble("thermalPauseAt")))
                append("\nResume at: ").append(String.format(Locale.US, "%.2f", s.optDouble("thermalResumeAt")))
                append("\n\nCharging: ").append(s.optBoolean("charging"))
                append("\nBattery: ").append(s.optInt("batteryPercent")).append("%")
            }
        }.getOrDefault("Could not read scheduler state.")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nav_scheduler)
            .setIcon(R.drawable.ic_tune)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAboutDialog() {
        val body = buildString {
            append(getString(R.string.about_body))
            append("\n\n")
            append("Version ").append(BuildInfo.VERSION).append("\n")
            append("Package ").append(packageName).append("\n")
            lastSystemJson?.let {
                runCatching {
                    val s = JSONObject(it)
                    append("Model ").append(
                        s.optString("model").substringAfterLast('/').ifEmpty { "—" }
                    ).append("\n")
                    append("Backend ").append(s.optString("backendConfigured", "—"))
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setIcon(R.drawable.ic_info)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showEndpointsDialog() {
        val port = currentPort()
        val body = """
            Base: http://127.0.0.1:$port
            Auth: Authorization: Bearer <token>

            GET   /health              (no auth)
            GET   /system
            GET   /metrics

            POST  /generate
            POST  /generate/stream     SSE
            POST  /chat                {sessionId, message}

            GET   /sessions
            GET   /sessions/{id}/messages
            DEL   /sessions/{id}

            GET   /v1/models
            POST  /v1/chat/completions OpenAI-compatible
            POST  /v1/embeddings       501 (no embedder)

            POST  /rag/ingest
            POST  /rag/query
            GET   /rag/collections
            DEL   /rag/collections/{name}
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.endpoints_title)
            .setIcon(R.drawable.ic_book)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy_token) { _, _ -> copyToken() }
            .show()
    }

    private fun showThermalDialog() {
        val js = lastSystemJson
        if (js == null) {
            toast(getString(R.string.err_not_running))
            return
        }
        val body = runCatching {
            val s = JSONObject(js)
            val headroom = s.optDouble("thermalHeadroom", -1.0)
            buildString {
                append("Thermal status: ").append(s.optString("thermal", "unknown")).append("\n")
                append("Headroom: ")
                if (headroom < 0) {
                    // Explicitly distinguished from "cool": the API needs
                    // Android 11+, a warm-up period after boot, and rejects
                    // calls under ~1s apart.
                    append("not reported on this device")
                } else {
                    append(String.format(Locale.US, "%.2f", headroom))
                    append(
                        when {
                            headroom < 0.7 -> "  (headroom available)"
                            headroom < 0.95 -> "  (approaching throttle)"
                            else -> "  (throttling — expect slower tokens/sec)"
                        }
                    )
                }
                append("\n\n")
                append("SoC: ").append(s.optString("soc", "unknown")).append("\n")
                append("CPU cores: ").append(s.optString("cpuCores", "unknown")).append("\n")
                append("CPU max clock: ").append(s.optString("cpuMaxMhz", "unknown")).append(" MHz\n")
                append("ABIs: ").append(s.optString("abis", "unknown")).append("\n\n")
                append("RAM: ").append(s.optLong("memUsedMb")).append(" / ")
                append(s.optLong("memTotalMb")).append(" MB used\n")
                append("App heap: ").append(s.optLong("appHeapMb")).append(" / ")
                append(s.optLong("appHeapMaxMb")).append(" MB\n")
                append("Free storage: ").append(s.optLong("freeStorageMb")).append(" MB\n")
                append("DB size: ").append(s.optLong("dbSizeBytes") / 1024).append(" KB\n\n")
                append("Note: Android exposes no per-core temperature to normal apps. ")
                append("Status and headroom are the only readings available without root.")
            }
        }.getOrDefault("Could not read system info.")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nav_thermal)
            .setIcon(R.drawable.ic_thermal)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmRegenerateToken() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.regen_title)
            .setIcon(R.drawable.ic_refresh)
            .setMessage(R.string.regen_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.regenerate) { _, _ -> regenerateToken() }
            .show()
    }

    private fun regenerateToken() {
        registry.rotate("admin")
        val wasLive = uiState == UiState.RUNNING || uiState == UiState.LOADING
        stopService(Intent(this, InferenceService::class.java))
        renderToken()
        if (wasLive) {
            // The server captured the old token at construction time, so it has
            // to be rebuilt for the new one to take effect.
            pollHandler.postDelayed({
                ContextCompat.startForegroundService(
                    this, Intent(this, InferenceService::class.java)
                )
                startRequestedAt = System.currentTimeMillis()
                applyState(UiState.STARTING)
            }, RESTART_DELAY_MS)
        } else {
            applyState(UiState.IDLE)
        }
        copyToken()
        toast(getString(R.string.toast_token_regenerated))
    }

    // ---------------------------- lifecycle ----------------------------

    override fun onResume() {
        super.onResume()
        polling = true
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        io.cancel()
        pollHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ----------------------------- helpers -----------------------------

    private fun currentPort(): Int =
        prefs.getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT)
            ?.toIntOrNull() ?: 8080

    private fun apiToken(): String? = registry.admin()?.token

    private fun renderToken() {
        val t = apiToken()
        binding.tokenText.text =
            if (t == null) getString(R.string.token_pending) else "${t.take(8)}…${t.takeLast(4)}"
        binding.btnCopyToken.isEnabled = t != null
    }

    private fun copyToken() {
        val t = apiToken() ?: run { toast(getString(R.string.err_no_token)); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Medha API token", t))
        // Android 13+ shows its own copy confirmation; avoid a double toast.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(getString(R.string.toast_token_copied))
        }
    }

    // -------------------------- model import --------------------------

    private fun importModel(uri: Uri) {
        binding.modelPathText.text = getString(R.string.importing_model)
        io.launch {
            val name = queryName(uri) ?: "model.litertlm"
            if (!name.endsWith(".litertlm") && !name.endsWith(".task")) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.err_wrong_type))
                    binding.modelPathText.text = getString(R.string.no_model_selected)
                }
                return@launch
            }

            val incoming = querySize(uri)
            val free = SystemInfo.freeStorageMb(this@MainActivity)
            // Copying a 3 GB model onto a device with 1 GB free used to fail
            // partway and leave a truncated file that the engine then tried to
            // open, producing an opaque native error.
            if (incoming > 0 && free > 0 && incoming / (1024 * 1024) > free - 256) {
                withContext(Dispatchers.Main) {
                    binding.modelPathText.text = getString(R.string.no_model_selected)
                    toast("Not enough free space: need ~${incoming / (1024 * 1024)} MB, have $free MB")
                }
                return@launch
            }

            val dir = File(filesDir, "models").apply { mkdirs() }
            val dest = File(dir, name)
            val tmp = File(dir, "$name.part")

            runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 20) }
                }
                // Atomic swap: a partially written model never becomes the
                // configured model path.
                if (dest.exists()) dest.delete()
                check(tmp.renameTo(dest)) { "could not finalise model file" }
            }.onSuccess {
                prefs.edit()
                    .putString(InferenceService.KEY_MODEL_PATH, dest.absolutePath)
                    .putString(InferenceService.KEY_MODEL_URI, uri.toString())
                    .apply()
                withContext(Dispatchers.Main) {
                    binding.modelPathText.text = dest.absolutePath
                    toast("Model ready (${dest.length() / (1024 * 1024)} MB)")
                    applyState(uiState)
                }
            }.onFailure { e ->
                runCatching { tmp.delete() }
                withContext(Dispatchers.Main) {
                    binding.modelPathText.text = "Import failed: ${e.message}"
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
        } ?: -1L

    // --------------------------- permissions ---------------------------

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                // Some OEM builds hide this screen entirely.
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        } else {
            toast(getString(R.string.toast_battery_ok))
        }
    }

    // ------------------------ live dashboard ------------------------

    private fun refresh() {
        val port = currentPort()
        val token = apiToken()
        io.launch {
            val health = fetch("http://127.0.0.1:$port/health", token)
            // Skip the two authenticated calls entirely when the service is down.
            val system = if (health != null) fetch("http://127.0.0.1:$port/system", token) else null
            val metrics = if (health != null) fetch("http://127.0.0.1:$port/metrics", token) else null
            val sched = if (health != null) fetch("http://127.0.0.1:$port/scheduler", token) else null
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    lastSystemJson = system
                    lastSchedulerJson = sched
                    renderSchedulerCard(sched)
                    render(health, system, metrics)
                    renderToken()
                }
            }
        }
    }

    private fun renderSchedulerCard(sched: String?) {
        if (sched == null) {
            binding.schedState.text = getString(R.string.sched_idle)
            binding.statQueue.text = "—"
            binding.statThermal.text = "—"
            return
        }
        runCatching {
            val s = JSONObject(sched)
            val hr = s.optDouble("thermalHeadroom", -1.0)
            binding.statQueue.text = s.optInt("queueDepth").toString()
            binding.statThermal.text =
                if (hr < 0) "n/a" else String.format(Locale.US, "%.2f", hr)
            binding.schedState.text = buildString {
                append("queue ").append(s.optInt("queueDepth")).append("/")
                append(s.optInt("maxQueueDepth"))
                append("   batch ").append(if (s.optBoolean("batchPaused")) "PAUSED" else "ready")
                append("\nbattery ").append(s.optInt("batteryPercent")).append("%")
                append(if (s.optBoolean("charging")) " (charging)" else "")
                val why = s.optString("gateReason", "")
                if (why.isNotEmpty()) append("\n").append(why)
            }
        }
    }

    private fun render(health: String?, system: String?, metrics: String?) {
        if (health == null) {
            // Give a freshly started service a grace period before calling it
            // dead; the model load can take a while on a cold cache.
            val starting = startRequestedAt > 0 &&
                System.currentTimeMillis() - startRequestedAt < START_GRACE_MS
            binding.statusText.setTextColor(Color.parseColor(COLOR_IDLE))
            binding.statusText.text =
                if (starting) "● Starting…" else "● Not running"
            binding.statusDetail.text =
                if (starting) "Waiting for the service" else "Tap Start to load the model"
            binding.systemText.text = "Backend: —\nMemory: —\nDevice: —"
            binding.metricsText.text = "Requests: 0\nAvg speed: —\nStored: —"
            if (!starting) startRequestedAt = 0L
            applyState(if (starting) UiState.STARTING else UiState.IDLE)
            return
        }

        startRequestedAt = 0L
        var next = UiState.LOADING

        runCatching {
            val h = JSONObject(health)
            val loaded = h.optBoolean("modelLoaded", false)
            val err = h.optString("error", "")
            val backend = h.optString("backend", "?")
            val busy = h.optBoolean("busy", false)
            next = when {
                loaded -> UiState.RUNNING
                err.isNotEmpty() -> UiState.ERROR
                else -> UiState.LOADING
            }
            when (next) {
                UiState.RUNNING -> {
                    binding.statusText.setTextColor(Color.parseColor(COLOR_OK))
                    binding.statusText.text =
                        if (busy) "● Running · generating" else "● Running · model loaded"
                }
                UiState.ERROR -> {
                    binding.statusText.setTextColor(Color.parseColor(COLOR_ERR))
                    binding.statusText.text = "● Load failed"
                }
                else -> {
                    binding.statusText.setTextColor(Color.parseColor(COLOR_WARN))
                    binding.statusText.text = "● Loading model…"
                }
            }
            binding.statusDetail.text = if (err.isNotEmpty()) err else "Backend: $backend"
        }

        system?.let {
            runCatching {
                val s = JSONObject(it)
                binding.systemText.text = buildString {
                    append("Backend: ").append(s.optString("backendConfigured"))
                    append(if (s.optBoolean("backendVerified")) " (init ok)" else " (configured)")
                    append("\nMemory: ").append(s.optLong("memUsedMb")).append(" / ")
                    append(s.optLong("memTotalMb")).append(" MB used")
                    append("\nApp heap: ").append(s.optLong("appHeapMb")).append(" / ")
                    append(s.optLong("appHeapMaxMb")).append(" MB")
                    if (s.optBoolean("lowMemory")) append("  ⚠ low")
                    append("\nThermal: ").append(s.optString("thermal", "unknown"))
                    val hr = s.optDouble("thermalHeadroom", -1.0)
                    if (hr >= 0) {
                        append("  headroom ")
                        append(String.format(Locale.US, "%.2f", hr))
                        if (hr >= 0.95) append(" ⚠")
                    }
                    append("\nDevice: ").append(s.optString("manufacturer")).append(" ")
                    append(s.optString("model"))
                    append("\nSoC: ").append(s.optString("soc"))
                    append("\nLoad time: ").append(s.optLong("loadMs")).append(" ms")
                }
            }
        }

        metrics?.let {
            runCatching {
                val m = JSONObject(it)
                binding.metricsText.text = buildString {
                    append("Requests: ").append(m.optLong("totalRequests"))
                    val fails = m.optLong("totalFailures")
                    if (fails > 0) append("  (").append(fails).append(" failed)")
                    append("\nAvg speed: ")
                    append(String.format(Locale.US, "%.1f", m.optDouble("avgTokensPerSec", 0.0)))
                    append(" tok/s  ·  last ")
                    append(String.format(Locale.US, "%.1f", m.optDouble("lastTokensPerSec", 0.0)))
                    binding.statSpeed.text =
                        String.format(Locale.US, "%.0f", m.optDouble("lastTokensPerSec", 0.0))
                    append("\nStored: ").append(m.optInt("db_conversations")).append(" chats, ")
                    append(m.optInt("db_messages")).append(" msgs, ")
                    append(m.optInt("db_chunks")).append(" chunks")
                }
            }
        }

        applyState(next)
    }

    private fun fetch(urlStr: String, token: String?): String? = runCatching {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 1500
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val POLL_MS = 2000L
        private const val START_GRACE_MS = 25_000L
        private const val RESTART_DELAY_MS = 900L
        private const val COLOR_OK = "#B7F5C8"
        private const val COLOR_WARN = "#F5E4B7"
        private const val COLOR_ERR = "#F5C0B7"
        private const val COLOR_IDLE = "#B9A9EC"
    }
}
