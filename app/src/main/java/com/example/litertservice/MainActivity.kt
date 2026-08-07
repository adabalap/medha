package com.example.litertservice

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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.litertservice.databinding.ActivityMainBinding
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

    private lateinit var binding: ActivityMainBinding
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

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

        binding.editPort.setText(prefs.getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT))
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

        binding.btnStart.setOnClickListener { onStart() }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, InferenceService::class.java))
            toast("Stopped")
        }

        binding.btnOpenPwa.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${currentPort()}/")))
        }

        binding.btnCopyToken.setOnClickListener { copyToken() }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    private fun onStart() {
        val portText = binding.editPort.text?.toString()?.trim().orEmpty()
        val port = portText.toIntOrNull()
        // Validated up front: an out-of-range port previously produced a service
        // that started, failed to bind, and reported nothing useful.
        if (port == null || port !in 1024..65535) {
            toast("Port must be a number between 1024 and 65535")
            return
        }
        if (prefs.getString(InferenceService.KEY_MODEL_PATH, null) == null) {
            toast("Pick a model first")
            return
        }
        prefs.edit().putString(InferenceService.KEY_PORT, port.toString()).apply()
        ensureNotifPerm()
        ContextCompat.startForegroundService(this, Intent(this, InferenceService::class.java))
        toast("Starting Medha…")
    }

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
        super.onDestroy()
    }

    private fun currentPort(): Int =
        prefs.getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT)
            ?.toIntOrNull() ?: 8080

    private fun apiToken(): String? = prefs.getString(InferenceService.KEY_API_TOKEN, null)

    private fun renderToken() {
        val t = apiToken()
        binding.tokenText.text = if (t == null) {
            getString(R.string.token_pending)
        } else {
            "${t.take(8)}…${t.takeLast(4)}"
        }
        binding.btnCopyToken.isEnabled = t != null
    }

    private fun copyToken() {
        val t = apiToken() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Medha API token", t))
        // Android 13+ shows its own copy confirmation; avoid a double toast.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Token copied")
    }

    // ------------------------- model import -------------------------

    private fun importModel(uri: Uri) {
        binding.modelPathText.text = getString(R.string.importing_model)
        io.launch {
            val name = queryName(uri) ?: "model.litertlm"
            if (!name.endsWith(".litertlm") && !name.endsWith(".task")) {
                withContext(Dispatchers.Main) {
                    toast("Pick a .litertlm or .task file")
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
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        } else {
            toast("Already allowed")
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
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    render(health, system, metrics)
                    renderToken()
                }
            }
        }
    }

    private fun render(health: String?, system: String?, metrics: String?) {
        if (health == null) {
            binding.statusText.setTextColor(Color.parseColor(COLOR_IDLE))
            binding.statusText.text = "● Not running"
            binding.statusDetail.text = "Tap Start to load the model"
            binding.systemText.text = "Backend: —\nMemory: —\nDevice: —"
            binding.metricsText.text = "Requests: 0\nAvg speed: —\nStored: —"
            return
        }

        runCatching {
            val h = JSONObject(health)
            val loaded = h.optBoolean("modelLoaded", false)
            val err = h.optString("error", "")
            val backend = h.optString("backend", "?")
            val busy = h.optBoolean("busy", false)
            when {
                loaded && busy -> {
                    binding.statusText.setTextColor(Color.parseColor(COLOR_OK))
                    binding.statusText.text = "● Running · generating"
                }
                loaded -> {
                    binding.statusText.setTextColor(Color.parseColor(COLOR_OK))
                    binding.statusText.text = "● Running · model loaded"
                }
                err.isNotEmpty() -> {
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
                    val thermal = s.optString("thermal", "unknown")
                    if (thermal != "none" && thermal != "unknown") append("\nThermal: ").append(thermal).append(" ⚠")
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
                    append("\nStored: ").append(m.optInt("db_conversations")).append(" chats, ")
                    append(m.optInt("db_messages")).append(" msgs, ")
                    append(m.optInt("db_chunks")).append(" chunks")
                }
            }
        }
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
        private const val COLOR_OK = "#B7F5C8"
        private const val COLOR_WARN = "#F5E4B7"
        private const val COLOR_ERR = "#F5C0B7"
        private const val COLOR_IDLE = "#B9A9EC"
    }
}
