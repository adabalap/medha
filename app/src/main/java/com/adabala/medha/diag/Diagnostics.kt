package com.adabala.medha.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A bounded, in-memory log ring buffer plus a crash-safe dump-to-file path.
 *
 * Why this exists: `adb logcat` is not durable, and it is not an answer once
 * the app leaves your desk — when it dies on your phone at 2am you currently
 * have nothing (docs/PRODUCTION-READINESS.md P0 #2). This captures the same
 * log lines the app already produces via [d]/[i]/[w]/[e] (drop-in replacements
 * for [Log]'s own methods — behaviour and logcat output are unchanged), keeps
 * the most recent [CAPACITY] of them in memory, and can write them to a file.
 * [com.adabala.medha.MedhaApplication] does that automatically the moment an
 * uncaught exception is about to kill the process; the drawer's "Diagnostics"
 * action can also do it on demand.
 *
 * Deliberately NOT a telemetry pipeline. Nothing here ever leaves the device
 * on its own — a dump is a local file under `filesDir/diagnostics/` that the
 * person explicitly shares, same as they'd attach a manually-copied logcat
 * capture, except it's actually still there after the crash that mattered.
 */
object Diagnostics {
    private const val CAPACITY = 500
    private val lock = Any()
    private val buffer = ArrayDeque<String>(CAPACITY)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun record(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(stamp.format(Date())).append(' ').append(level).append('/').append(tag)
            append(": ").append(msg)
            if (t != null) append(" -- ").append(Log.getStackTraceString(t).trim())
        }
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        record("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        record("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        record("W", tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        record("E", tag, msg, t)
    }

    /** A stable, ordered snapshot — oldest first — for writing to a file. */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    /**
     * Writes [header] plus the current buffer to a timestamped file under
     * `filesDir/diagnostics/`. Deliberately synchronous and defensive
     * (`runCatching` at every step that can fail): this is most likely to run
     * from an uncaught-exception handler a few milliseconds before the
     * process dies, so it cannot afford to throw, retry, or block.
     */
    fun dumpToFile(context: Context, header: String): File? = runCatching {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        // Cap accumulation — the same unbounded-local-growth concern
        // PRODUCTION-READINESS.md #9 raises about RAG/chat history, applied
        // to this feature so it can't quietly become another instance of it.
        pruneOldDumps(dir)
        val file = File(dir, "diag-${System.currentTimeMillis()}.txt")
        val content = buildString {
            append(header)
            append("\n\n-- recent log lines (oldest first) --\n")
            snapshot().forEach { line -> append(line); append('\n') }
        }
        file.writeText(content)
        file
    }.onFailure { Log.e(TAG, "failed to write diagnostics dump", it) }.getOrNull()

    private fun pruneOldDumps(dir: File, keep: Int = 10) {
        val files = dir.listFiles { f -> f.name.startsWith("diag-") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    /** Existing dump files, most recent first — for the "Diagnostics" list. */
    fun listDumps(context: Context): List<File> {
        val dir = File(context.filesDir, "diagnostics")
        return (dir.listFiles { f -> f.name.startsWith("diag-") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    private const val TAG = "Diagnostics"
}
