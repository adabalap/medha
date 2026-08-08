package com.adabala.medha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.adabala.medha.data.MedhaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Medha foreground service. Loads the model once and keeps the HTTP server + DB
 * alive until explicitly stopped, so any PWA on the device can consume it.
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: LlmEngine
    private var server: LocalServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentPort: Int = -1
    private var loadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        startForeground(NOTIF_ID, buildNotification("Starting Medha…"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val modelPath = prefs.getString(KEY_MODEL_PATH, null)
        val port = prefs.getString(KEY_PORT, DEFAULT_PORT)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: DEFAULT_PORT.toInt()
        val backend = when (prefs.getString(KEY_BACKEND, "GPU")) {
            "CPU" -> LlmEngine.ConfiguredBackend.CPU
            "NPU" -> LlmEngine.ConfiguredBackend.NPU
            else -> LlmEngine.ConfiguredBackend.GPU
        }

        if (modelPath.isNullOrBlank()) {
            updateNotification("No model selected — open Medha and pick one")
            return START_STICKY
        }

        val token = ensureApiToken(prefs)
        val requireAuth = prefs.getBoolean(KEY_REQUIRE_AUTH, true)
        val db = MedhaDatabase.get(applicationContext)

        // Restart the server if the port changed. Previously the server was
        // created once and never rebound, so editing the port in the UI and
        // hitting Start silently kept serving on the old one.
        if (server == null || currentPort != port) {
            server?.stop()
            server = LocalServer(applicationContext, engine, port, db, token, requireAuth)
                .also { runCatching { it.start() }.onFailure { e -> onServerFailed(e, port) } }
            currentPort = port
        }

        // Idempotent: LlmEngine.load() no-ops when the same model and backend are
        // already live, so START_STICKY redelivery cannot tear down a running
        // engine underneath an in-flight request.
        if (loadJob?.isActive != true) {
            loadJob = scope.launch {
                try {
                    if (!engine.isLoaded) updateNotification("Loading model…")
                    engine.load(modelPath, backend)
                    updateNotification(readyText(port))
                } catch (t: Throwable) {
                    Log.e(TAG, "Model load failed", t)
                    engine.recordError(t.message)
                    updateNotification("Load failed: ${t.message?.take(64)}")
                }
            }
        }
        return START_STICKY
    }

    private fun onServerFailed(e: Throwable, port: Int) {
        Log.e(TAG, "Server failed to bind on port $port", e)
        engine.recordError("port $port unavailable: ${e.message}")
        updateNotification("Port $port unavailable — pick another")
    }

    private fun readyText(port: Int): String =
        "Ready · 127.0.0.1:$port · ${engine.configuredBackend.name}"

    /**
     * Trims the engine when Android signals memory pressure. A multi-GB model
     * plus a foreground service is exactly the profile the low-memory killer
     * targets; releasing on TRIM_MEMORY_COMPLETE lets us come back cleanly
     * instead of being killed mid-request.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.w(TAG, "onTrimMemory($level): releasing engine")
            engine.close()
            engine.recordError("engine released under memory pressure; restart to reload")
            updateNotification("Released under memory pressure")
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        server?.stop()
        server = null
        engine.close()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------- token ---------------------------

    /**
     * Provisions a per-install bearer token on first run. 160 bits from
     * SecureRandom; stored in default prefs, which live inside the app sandbox.
     */
    private fun ensureApiToken(prefs: SharedPreferences): String {
        prefs.getString(KEY_API_TOKEN, null)?.takeIf { it.length >= 16 }?.let { return it }
        val token = generateToken()
        prefs.edit().putString(KEY_API_TOKEN, token).apply()
        return token
    }

    // ------------------------- wake lock -------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Medha::Inference").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ----------------------- notification ------------------------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Medha", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, InferenceService::class.java).setAction(ACTION_STOP), flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Medha మేధా")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "medha"
        private const val NOTIF_ID = 1
        private const val WAKELOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // safety cap: 12h

        const val ACTION_STOP = "com.adabala.medha.STOP"

        const val KEY_MODEL_PATH = "model_path"
        const val KEY_PORT = "server_port"
        const val KEY_BACKEND = "backend"
        const val KEY_MODEL_URI = "model_uri"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_REQUIRE_AUTH = "require_auth"

        const val DEFAULT_PORT = "8080"

        /** 160 bits of SecureRandom, hex encoded. Shared with the settings UI. */
        fun generateToken(): String {
            val bytes = ByteArray(20)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
