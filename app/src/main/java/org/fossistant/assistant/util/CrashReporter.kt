package org.fossistant.assistant.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.fossistant.assistant.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voluntary crash reports, no tracking: a crash is saved on the phone, and on the next launch the app asks
 * whether to send it. Sending opens the user's own email app with the report filled in (they see all of it
 * and can edit or discard it); nothing leaves the phone otherwise.
 *
 * Catches Kotlin/Java crashes directly and, on Android 11+, also native crashes (LiteRT, llama.cpp,
 * whisper.cpp, stable-diffusion.cpp) and freezes ("app not responding") from Android's exit records.
 * The address comes from the build: `-PcrashReportEmail=you@example.com`; without one, Send shares the
 * report through any app.
 */
object CrashReporter {

    private const val FILE_NAME = "pending_crash_report.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT_SEEN = "last_exit_seen_ms"
    private const val LOG_LINES = 60

    val email: String get() = BuildConfig.CRASH_REPORT_EMAIL

    /** Called from the uncaught-exception handler: writes the report synchronously before the process dies. */
    fun recordJavaCrash(context: Context, thread: Thread, throwable: Throwable) {
        runCatching {
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val report = buildString {
                appendLine(header(context, "App crash", System.currentTimeMillis()))
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine(trace.take(12_000))
                appendRecentLogs()
            }
            File(context.filesDir, FILE_NAME).writeText(report)
        }
    }

    /** A crash (or native crash / freeze) since the last launch that the user hasn't decided about yet. */
    fun pendingReport(context: Context): String? {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.let { file ->
            return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return nativeExitReport(context)
    }

    /** Forgets the pending report (sent or declined) so the question isn't asked again. */
    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_EXIT_SEEN, System.currentTimeMillis()).apply()
    }

    /** The email (or, without a configured address, share) intent for [report]. */
    fun sendIntent(report: String): Intent {
        val subject = "Fossistant crash report (${BuildConfig.VERSION_NAME})"
        // Email apps cut very long mailto bodies; the stack trace and recent log fit well under this.
        val body = report.take(15_000)
        return if (email.isNotBlank()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        } else {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body),
                "Send crash report"
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun nativeExitReport(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_SEEN, 0L)
        if (lastSeen == 0L) {
            // First run with the reporter: don't ask about crashes from before it existed.
            prefs.edit().putLong(KEY_LAST_EXIT_SEEN, System.currentTimeMillis()).apply()
            return null
        }
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.timestamp > lastSeen && (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR) }
            ?: return null
        val kind = if (exit.reason == ApplicationExitInfo.REASON_ANR) "App not responding" else "Native crash"
        return buildString {
            appendLine(header(context, kind, exit.timestamp))
            appendLine("Process: ${exit.processName} (status ${exit.status}, importance ${exit.importance})")
            exit.description?.let { appendLine("Description: $it") }
            appendLine("Memory at exit: PSS ${exit.pss / 1024} MB, RSS ${exit.rss / 1024} MB")
            appendRecentLogs()
        }
    }

    private fun header(context: Context, kind: String, timeMs: Long): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        val ram = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        }.getOrDefault(0L)
        return buildString {
            appendLine("$kind at $time")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${ram} MB RAM")
        }
    }

    private fun StringBuilder.appendRecentLogs() {
        val lines = runCatching { AppLogger.getRecentLogs(LOG_LINES) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        appendLine()
        appendLine("Last $LOG_LINES log lines:")
        lines.forEach { appendLine(it.take(400)) }
    }
}
