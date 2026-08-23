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
            previous?.uncaughtException(thread, throwable)
        }
    }
}
