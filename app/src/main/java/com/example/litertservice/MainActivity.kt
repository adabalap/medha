package com.example.litertservice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.litertservice.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val io = CoroutineScope(Dispatchers.IO)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false

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
            if (polling) pollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.editPort.setText(prefs.getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT))
        val savedPath = prefs.getString(InferenceService.KEY_MODEL_PATH, null)
        binding.modelPathText.text = savedPath ?: "No model selected"
        when (prefs.getString(InferenceService.KEY_BACKEND, "GPU")) {
            "CPU" -> binding.backendToggle.check(binding.backendCpu.id)
            "NPU" -> binding.backendToggle.check(binding.backendNpu.id)
            else -> binding.backendToggle.check(binding.backendGpu.id)
        }

        binding.btnPickModel.setOnClickListener {
            pickModel.launch(arrayOf("*/*")) // .litertlm has no registered MIME; filter by name on import
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

        binding.btnStart.setOnClickListener {
            prefs.edit().putString(InferenceService.KEY_PORT, binding.editPort.text.toString().trim()).apply()
            if (prefs.getString(InferenceService.KEY_MODEL_PATH, null) == null) {
                Toast.makeText(this, "Pick a model first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ensureNotifPerm()
            ContextCompat.startForegroundService(this, Intent(this, InferenceService::class.java))
            Toast.makeText(this, "Starting Medha…", Toast.LENGTH_SHORT).show()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, InferenceService::class.java))
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenPwa.setOnClickListener {
            val port = currentPort()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/")))
        }

        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume(); polling = true; pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause(); polling = false; pollHandler.removeCallbacks(pollRunnable)
    }

    private fun currentPort(): Int =
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT)!!.toIntOrNull() ?: 8080

    private fun importModel(uri: Uri) {
        binding.modelPathText.text = "Importing model…"
        io.launch {
            val name = queryName(uri) ?: "model.litertlm"
            if (!name.endsWith(".litertlm") && !name.endsWith(".task")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Pick a .litertlm file", Toast.LENGTH_LONG).show()
                    binding.modelPathText.text = "No model selected"
                }
                return@launch
            }
            val dest = File(filesDir, "models").apply { mkdirs() }.let { File(it, name) }
            runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it, 1 shl 20) }
                }
            }.onSuccess {
                PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit()
                    .putString(InferenceService.KEY_MODEL_PATH, dest.absolutePath)
                    .putString(InferenceService.KEY_MODEL_URI, uri.toString())
                    .apply()
                withContext(Dispatchers.Main) {
                    binding.modelPathText.text = dest.absolutePath
                    Toast.makeText(this@MainActivity, "Model ready (${dest.length() / (1024*1024)} MB)", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    binding.modelPathText.text = "Import failed: ${e.message}"
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        } else Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
    }

    // --- live dashboard polling ---
    private fun refresh() {
        val port = currentPort()
        io.launch {
            val health = fetch("http://127.0.0.1:$port/health")
            val system = fetch("http://127.0.0.1:$port/system")
            val metrics = fetch("http://127.0.0.1:$port/metrics")
            withContext(Dispatchers.Main) { render(health, system, metrics) }
        }
    }

    private fun render(health: String?, system: String?, metrics: String?) {
        if (health == null) {
            binding.statusText.setTextColor(Color.parseColor("#B9A9EC"))
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
            when {
                loaded -> { binding.statusText.setTextColor(Color.parseColor("#B7F5C8")); binding.statusText.text = "● Running · model loaded" }
                err.isNotEmpty() -> { binding.statusText.setTextColor(Color.parseColor("#F5C0B7")); binding.statusText.text = "● Load failed" }
                else -> { binding.statusText.setTextColor(Color.parseColor("#F5E4B7")); binding.statusText.text = "● Loading model…" }
            }
            binding.statusDetail.text = if (err.isNotEmpty()) err else "Backend: $backend"
        }
        system?.let {
            runCatching {
                val s = JSONObject(it)
                binding.systemText.text = buildString {
                    append("Backend: ").append(s.optString("backendConfigured")).append(if (s.optBoolean("backendVerified")) " (active)" else " (configured)").append("\n")
                    append("Memory: ").append(s.optLong("memUsedMb")).append(" / ").append(s.optLong("memTotalMb")).append(" MB used\n")
                    append("App heap: ").append(s.optLong("appHeapMb")).append(" MB")
                    if (s.optBoolean("lowMemory")) append("  ⚠ low")
                    append("\nDevice: ").append(s.optString("manufacturer")).append(" ").append(s.optString("model")).append("\n")
                    append("SoC: ").append(s.optString("soc")).append("\n")
                    append("Load time: ").append(s.optLong("loadMs")).append(" ms")
                }
            }
        }
        metrics?.let {
            runCatching {
                val m = JSONObject(it)
                binding.metricsText.text = buildString {
                    append("Requests: ").append(m.optLong("totalRequests")).append("\n")
                    append("Avg speed: ").append(String.format("%.1f", m.optDouble("avgTokensPerSec", 0.0))).append(" tok/s")
                    append("  ·  last ").append(String.format("%.1f", m.optDouble("lastTokensPerSec", 0.0))).append("\n")
                    append("Stored: ").append(m.optInt("db_conversations")).append(" chats, ")
                    append(m.optInt("db_messages")).append(" msgs, ")
                    append(m.optInt("db_chunks")).append(" chunks")
                }
            }
        }
    }

    private fun fetch(urlStr: String): String? = runCatching {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 800; readTimeout = 1200
        }
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    }.getOrNull()
}
