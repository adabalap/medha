package com.adabala.medha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.adabala.medha.diag.Diagnostics
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.adabala.medha.data.MedhaDatabase
import com.adabala.medha.auth.ClientRegistry
import com.adabala.medha.auth.PairingManager
import com.adabala.medha.notify.NotificationHub
import com.adabala.medha.sched.InferenceScheduler
import com.adabala.medha.net.PortProbe
import com.adabala.medha.sched.SchedulerConfig
import com.adabala.medha.rag.AiEdgeEmbedder
import com.adabala.medha.rag.Embedder
import com.adabala.medha.rag.NoEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var embedder: Embedder = NoEmbedder
    private lateinit var scheduler: InferenceScheduler
    private lateinit var registry: ClientRegistry
    private lateinit var notifier: NotificationHub
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentPort: Int = -1
    private var loadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        registry = ClientRegistry.get(applicationContext)
        notifier = NotificationHub(applicationContext)
        scheduler = InferenceScheduler(applicationContext, loadSchedulerConfig())
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

        // Scheduler settings are re-read on every start so the UI sliders take
        // effect without needing a full service restart.
        scheduler.config = loadSchedulerConfig()
        val requireAuth = prefs.getBoolean(KEY_REQUIRE_AUTH, true)
        val db = MedhaDatabase.get(applicationContext)

        // Optional and non-fatal: with no model files or no SDK on the
        // classpath this returns NoEmbedder and RAG stays lexical.
        if (!embedder.isReady) {
            embedder = AiEdgeEmbedder.createOrNull(applicationContext)
        }

        // Restart the server if the port changed. Previously the server was
        // created once and never rebound, so editing the port in the UI and
        // hitting Start silently kept serving on the old one.
        if (server == null || currentPort != port) {
            server?.stop()
            server = LocalServer(
                applicationContext, engine, port, db, requireAuth, embedder,
                registry, scheduler, notifier, PairingManager.shared()
            )
                .also {
                    runCatching { it.start() }
                        .onSuccess { clearServerError() }
                        .onFailure { e -> onServerFailed(e, port) }
                }
            currentPort = port
        }

        // Idempotent: LlmEngine.load() no-ops when the same model and backend are
        // already live, so START_STICKY redelivery cannot tear down a running
        // engine underneath an in-flight request.
        if (loadJob?.isActive != true) {
            loadJob = scope.launch {
                try {
                    if (!engine.isLoaded) updateNotification("Loading model…")
                    // The hint is keyed by model path and backend: a
                    // different model, or the same model on another
                    // accelerator, is a different question and must be
                    // rediscovered rather than inheriting an answer.
                    val hintKey = rungHintKey(modelPath, backend.name)
                    val whyKey = hintKey + WHY_SUFFIX
                    engine.load(
                        modelPath,
                        backend,
                        enableMultimodal = prefs.getBoolean(KEY_MULTIMODAL, true),
                        rungHint = prefs.getString(hintKey, null),
                        // Handed back so a cached start reports the same
                        // thing a cold one does. Without it the rung that
                        // would have failed is never tried, nothing is
                        // recorded, and /health silently drops a
                        // diagnostic it showed on the previous boot.
                        constraintHint = prefs.getString(whyKey, null)
                    )
                    // Persist only on success, and only a rung worth
                    // caching -- storing "text-only" would put it first
                    // next time, where it succeeds immediately and the
                    // media rungs never run again.
                    engine.cacheableRung?.let { rung ->
                        prefs.edit()
                            .putString(hintKey, rung)
                            .putString(whyKey, engine.multimodalError)
                            .apply()
                    }
                    updateNotification(readyText(port))
                } catch (t: Throwable) {
                    Diagnostics.e(TAG, "Model load failed", t)
                    engine.recordError(t.message)
                    updateNotification("Load failed: ${t.message?.take(64)}")
                }
            }
        }
        return START_STICKY
    }

    private fun onServerFailed(e: Throwable, port: Int) {
        Diagnostics.e(TAG, "Server failed to bind on port $port", e)

        // The sentence worth showing is on the BindException, which by the
        // time it arrives here may be wrapped in the JobCancellationException
        // that Ktor's scope raises as it tears down.
        var c: Throwable? = e
        var bind: java.net.BindException? = null
        while (c != null && bind == null) {
            if (c is java.net.BindException) bind = c
            c = c.cause
        }
        val msg = bind?.message?.takeIf { it.isNotBlank() }
            ?: PortProbe.explain(PortProbe.LOOPBACK, port)

        engine.recordError(msg)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(KEY_LAST_SERVER_ERROR, msg)
            .putLong(KEY_LAST_SERVER_ERROR_AT, System.currentTimeMillis())
            .apply()
        updateNotification(msg.take(96))
    }

    /** Clears the stored reason once a start succeeds, so it cannot outlive it. */
    private fun clearServerError() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .remove(KEY_LAST_SERVER_ERROR)
            .remove(KEY_LAST_SERVER_ERROR_AT)
            .apply()
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
            Diagnostics.w(TAG, "onTrimMemory($level): releasing engine and embedder")
            engine.close()
            // The embedding model is a second resident model; release it too or
            // the trim accomplishes much less than it appears to.
            runCatching { embedder.close() }
            embedder = NoEmbedder
            engine.recordError("engine released under memory pressure; restart to reload")
            updateNotification("Released under memory pressure")
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        server?.stop()
        server = null
        engine.close()
        runCatching { embedder.close() }
        embedder = NoEmbedder
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadSchedulerConfig(): SchedulerConfig {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        return SchedulerConfig(
            maxQueueDepth = p.getInt(KEY_QUEUE_DEPTH, 8),
            thermalPauseAt = p.getFloat(KEY_THERMAL_PAUSE, 0.85f),
            thermalResumeAt = p.getFloat(KEY_THERMAL_RESUME, 0.70f),
            batchRequiresCharging = p.getBoolean(KEY_BATCH_CHARGING, false),
            batchMinBatteryPercent = p.getInt(KEY_BATCH_MIN_BATTERY, 20)
        ).validated()
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

        // Why the server is not running, written where the UI can read it
        // WITHOUT the server.
        //
        // engine.recordError() puts the reason behind /health, which is
        // served by the thing that failed to start -- so when the port was
        // taken, the Home screen polled, got nothing, and said "Not running
        // · Tap Start" forever. Tapping Start then failed the same way, in
        // silence. The one fact that explains everything lived in a place
        // that could only be reached if the problem did not exist.
        const val KEY_LAST_SERVER_ERROR = "last_server_error"

        // When it was written. Home gives a freshly started service 25
        // seconds of grace before calling it dead, which is right for a cold
        // model load and wrong for a bind that failed in microseconds: the
        // screen said "Starting…" for 25 seconds about an attempt that was
        // already over. A failure stamped after the start request ends the
        // grace period immediately.
        const val KEY_LAST_SERVER_ERROR_AT = "last_server_error_at"
        const val KEY_BACKEND = "backend"
        const val KEY_MODEL_URI = "model_uri"
        /** Legacy single token. Read once by ClientRegistry to seed the admin client. */
        const val KEY_API_TOKEN = "api_token"
        const val KEY_REQUIRE_AUTH = "require_auth"
        const val KEY_THERMAL_PAUSE = "thermal_pause"
        const val KEY_THERMAL_RESUME = "thermal_resume"
        const val KEY_BATCH_CHARGING = "batch_charging"
        const val KEY_BATCH_MIN_BATTERY = "batch_min_battery"
        const val KEY_QUEUE_DEPTH = "queue_depth"

        /**
         * Whether to initialize the engine with vision and audio backends.
         *
         * On by default: with a model that carries the encoders this is the
         * difference between Medha transcribing audio itself and a client
         * shipping its own ASR stack beside it.
         *
         * The escape hatch exists because enabling it is not free on a
         * model that lacks them. LlmEngine's init ladder tries the media
         * backends, then media-on-CPU, then text-only, and each failed rung
         * costs a full model load — about 8s on a flagship, so a cold start
         * can take 25s before settling on text-only. Once the probe or a
         * load has shown this model has no encoders, turning this off makes
         * every subsequent start fast again.
         */
        const val KEY_MULTIMODAL = "multimodal_enabled"

        /**
         * Prefix for the cached init-ladder rung.
         *
         * Keyed by model path and backend because the answer belongs to
         * that pair: the first device to run this rejected audio-on-GPU
         * for one model, which says nothing about the next model or about
         * the same model on CPU. A single global key would carry a stale
         * answer across a model swap, and the ladder would spend a rung
         * discovering that — the exact cost the cache exists to avoid.
         */
        const val KEY_RUNG_PREFIX = "multimodal_rung:"

        fun rungHintKey(modelPath: String, backend: String): String =
            "$KEY_RUNG_PREFIX$backend:$modelPath"

        /** Suffix for the reason stored beside a cached rung. */
        const val WHY_SUFFIX = "#why"

        /**
         * 8001, not 8080.
         *
         * Every runnable sample in this repo — samples/medha-brief.js,
         * hg-agent-brief.py, medha_brief.py, hg-agent-transcribe.py and
         * the notify PWA — is written against http://127.0.0.1:8001, so a
         * stock install could not talk to its own examples. 8080 is also
         * the most contested number on any device: a real phone had
         * something else already listening, and Medha died on the
         * BindException rather than saying so.
         *
         * Only affects installs that never chose a port. A port already
         * saved in preferences wins, so nobody who set one moves.
         */
        const val DEFAULT_PORT = "8001"

    }
}
