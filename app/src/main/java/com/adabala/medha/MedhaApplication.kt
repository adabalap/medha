package com.adabala.medha

import android.app.Application
import android.util.Log
import com.adabala.medha.diag.Diagnostics

/**
 * Installs a diagnostics dump ahead of the platform's default
 * uncaught-exception handler, then always chains to it.
 *
 * "Always chains to it" is the important part: this must never suppress the
 * normal crash. Swallowing the exception here would stop the OS's own
 * "app has stopped" flow and any other installed handler (Play Vitals, ANR
 * reporting) from ever running, trading one missing signal for another. This
 * only runs first, writes a local record before the process goes away, and
 * then gets out of the way.
 *
 * There is exactly one exception, documented at the point where it is made:
 * a BindException from Ktor's accept job, which means the server did not
 * start, not that the app is broken. Everything else still chains.
 */
class MedhaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // runCatching, not a bare try/catch that might rethrow something
            // new: an exception thrown while handling a fatal exception can
            // suppress the original crash report entirely on some OEM builds.
            runCatching {
                val header = buildString {
                    append("Medha ").append(BuildConfig.VERSION_NAME).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Fatal:\n").append(Log.getStackTraceString(throwable))
                }
                Diagnostics.dumpToFile(applicationContext, header)
            }

            // The one exception to "always chain", and it is narrow on
            // purpose.
            //
            // Ktor's CIO engine binds inside its accept job, on its own
            // coroutine, after start(wait = false) has returned. A
            // BindException there is not the app failing -- it is the server
            // not starting, which InferenceService has already caught,
            // recorded and put on screen. LocalServer probes the port first
            // so this should not be reachable at all; what is left is the
            // race where another process takes the port in the microseconds
            // between the probe and the real bind.
            //
            // Killing the process for that is strictly worse than not: the
            // Activity, the engine and every other part of the app are fine,
            // and the person is left staring at a launcher instead of the
            // screen that explains what happened. The dump above is still
            // written either way.
            //
            // Nothing else is swallowed. Anything that is not a
            // BindException, anywhere in its cause chain, chains to the
            // platform handler exactly as before, so the OS crash flow and
            // any other installed reporter still run.
            if (!causedByBindFailure(throwable)) {
                previous?.uncaughtException(thread, throwable)
            } else {
                Log.w("Medha", "Server could not bind; app left running", throwable)
            }
        }
    }

    private fun causedByBindFailure(t: Throwable?): Boolean {
        var c = t
        var hops = 0
        // Bounded: a cause chain can be circular, and a loop inside the
        // uncaught handler is a hang rather than a crash report.
        while (c != null && hops++ < 16) {
            if (c is java.net.BindException) return true
            c = c.cause
        }
        return false
    }
}
