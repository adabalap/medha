package com.example.litertservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.example.litertservice.data.MedhaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Medha foreground service. Loads the model once and keeps the HTTP server + DB
 * alive until explicitly stopped, so any PWA on the device can consume it.
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: LlmEngine
    private var server: LocalServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        startForeground(NOTIF_ID, buildNotification("Starting Medha…"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val modelPath = prefs.getString(KEY_MODEL_PATH, DEFAULT_MODEL_PATH)!!
        val port = prefs.getString(KEY_PORT, DEFAULT_PORT)!!.toIntOrNull() ?: 8080
        val backend = when (prefs.getString(KEY_BACKEND, "GPU")) {
            "CPU" -> LlmEngine.ConfiguredBackend.CPU
            "NPU" -> LlmEngine.ConfiguredBackend.NPU
            else -> LlmEngine.ConfiguredBackend.GPU
        }

        val db = MedhaDatabase.get(applicationContext)
        if (server == null) {
            server = LocalServer(applicationContext, engine, port, db).also { it.start() }
        }

        scope.launch {
            try {
                updateNotification("Loading model…")
                engine.load(modelPath, backend)
                updateNotification("Medha ready · 127.0.0.1:$port · ${engine.configuredBackend.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "Model load failed", t)
                engine.recordError(t.message)
                updateNotification("Load failed: ${t.message?.take(48)}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        engine.close()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Medha::Inference").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety cap: 12h
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Medha", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Medha మేధా")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "medha"
        private const val NOTIF_ID = 1

        const val KEY_MODEL_PATH = "model_path"
        const val KEY_PORT = "server_port"
        const val KEY_BACKEND = "backend"
        const val KEY_MODEL_URI = "model_uri"

        val DEFAULT_MODEL_PATH =
            android.os.Environment.getExternalStorageDirectory().absolutePath +
                "/Download/models/gemma-4-E2B-it.litertlm"
        const val DEFAULT_PORT = "8080"
    }
}
