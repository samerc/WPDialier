package com.fancyshark.wpdialer.data

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash journal. There is deliberately no crash *reporting* —
 * the privacy policy promises nothing leaves the phone — so crashes are
 * appended to a local file the user can email from the about screen.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 64 * 1024

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { append(app, thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    // Synchronized: multiple threads can crash near-simultaneously, and an
    // interleaved read-truncate-write would destroy the journal.
    @Synchronized
    private fun append(context: Context, thread: Thread, e: Throwable) {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry =
            "---- $stamp  v$version  Android ${Build.VERSION.RELEASE}  " +
                "${Build.MANUFACTURER} ${Build.MODEL}  thread ${thread.name}\n$trace\n"
        val file = File(context.filesDir, FILE_NAME)
        val existing = if (file.exists()) file.readText() else ""
        // Keep the newest entries when the journal outgrows the cap.
        val combined = (existing + entry).let {
            if (it.length > MAX_BYTES) it.takeLast(MAX_BYTES / 2) else it
        }
        file.writeText(combined)
    }

    /** The journal tail, sized to survive an email intent; null when empty. */
    fun tail(context: Context, maxChars: Int = 6000): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return file.readText().takeLast(maxChars).takeIf { it.isNotBlank() }
    }
}
