package org.fossistant.assistant.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-performance, crash-resilient in-app circular log buffer.
 *
 * Requirements satisfied:
 * 1. Strict FIFO ring buffer of up to 10,000 lines (progressively discards oldest lines).
 * 2. Survives application crashes: backed by persistent storage on disk (`filesDir/logs/fossistant_app.log`).
 * 3. Includes fatal uncaught exception interception with full stack traces.
 * 4. Easily viewable and copyable from Settings -> Logs submenu.
 * 5. Multi-tiered diagnostic logging for all inference engines, tools, audio VAD, and UI flows.
 * 6. Disk writes happen on a background thread so logging never blocks the UI thread.
 * 7. Conversation text goes through [userText]: kept in debug builds, reduced to its length in release builds.
 */
object AppLogger {

    private const val MAX_LOG_LINES = 10_000
    private const val RECOMPACT_THRESHOLD = 10_500
    private const val LOG_SUBDIR = "logs"
    private const val LOG_FILENAME = "fossistant_app.log"

    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<String>(MAX_LOG_LINES)
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private var totalLinesWrittenSinceCompact = 0

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val diskWriter = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLoggerDisk").apply { isDaemon = true }
    }

    /**
     * Wraps conversation content (prompts, transcripts, search queries, spoken text) before logging it:
     * debug builds keep the text for troubleshooting, release builds only record its length.
     */
    fun userText(text: String?, maxChars: Int = 200): String =
        if (org.fossistant.assistant.BuildConfig.DEBUG) text.orEmpty().take(maxChars) else "<${text?.length ?: 0} chars>"

    fun init(context: Context) {
        lock.withLock {
            try {
                val dir = File(context.filesDir, LOG_SUBDIR).apply { mkdirs() }
                logFile = File(dir, LOG_FILENAME)
                loadPersistedLogsLocked()
                fileWriter = FileWriter(logFile, true)
                i("AppLogger", "=== App Session Initialized (Retained ${buffer.size} lines from previous runs) ===")
            } catch (t: Throwable) {
                Log.e("AppLogger", "Failed to initialize persistent log file", t)
            }
        }
    }

    private fun loadPersistedLogsLocked() {
        val file = logFile ?: return
        if (!file.exists() || file.length() == 0L) return

        try {
            val lines = file.readLines()
            buffer.clear()
            val startIdx = if (lines.size > MAX_LOG_LINES) lines.size - MAX_LOG_LINES else 0
            for (i in startIdx until lines.size) {
                buffer.addLast(lines[i])
            }
            totalLinesWrittenSinceCompact = buffer.size
        } catch (t: Throwable) {
            Log.e("AppLogger", "Failed reading persisted logs", t)
        }
    }

    fun v(tag: String, msg: String) = log("V", tag, msg, null)
    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    fun log(level: String, tag: String, msg: String, throwable: Throwable? = null) {
        // Always pipe to standard Android LogCat first
        when (level) {
            "V" -> Log.v(tag, msg, throwable)
            "D" -> Log.d(tag, msg, throwable)
            "I" -> Log.i(tag, msg, throwable)
            "W" -> Log.w(tag, msg, throwable)
            "E" -> Log.e(tag, msg, throwable)
        }

        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val line = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "[$timestamp] [$level] [$tag] $msg\n$sw"
        } else {
            "[$timestamp] [$level] [$tag] $msg"
        }

        val splitLines = line.lines()
        lock.withLock {
            for (l in splitLines) {
                if (buffer.size >= MAX_LOG_LINES) {
                    buffer.removeFirst()
                }
                buffer.addLast(l)
            }
        }
        try {
            diskWriter.execute {
                lock.withLock {
                    try {
                        fileWriter?.let { writer ->
                            writer.write(line + "\n")
                            writer.flush()
                        }
                        totalLinesWrittenSinceCompact += splitLines.size
                        if (totalLinesWrittenSinceCompact >= RECOMPACT_THRESHOLD) {
                            recompactLogFileLocked()
                        }
                    } catch (t: Throwable) {
                        Log.e("AppLogger", "Error writing log entry to disk", t)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down after a fatal crash: the crash handler persists the buffer itself.
        }
    }

    private fun recompactLogFileLocked() {
        val file = logFile ?: return
        try {
            fileWriter?.close()
            val tempFile = File(file.parentFile, "$LOG_FILENAME.tmp")
            tempFile.printWriter().use { pw ->
                for (line in buffer) {
                    pw.println(line)
                }
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
            fileWriter = FileWriter(file, true)
            totalLinesWrittenSinceCompact = buffer.size
        } catch (t: Throwable) {
            Log.e("AppLogger", "Error re-compacting log file", t)
            runCatching { fileWriter = FileWriter(file, true) }
        }
    }

    /**
     * Intercepts fatal unhandled crash, persists entire stacktrace immediately before JVM halts.
     */
    fun logFatalCrash(thread: Thread, throwable: Throwable) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val crashBanner = buildString {
            append("\n====================================================================\n")
            append("FATAL UNCAUGHT EXCEPTION CRASH DETECTED at $timestamp\n")
            append("Thread: ${thread.name} (id: ${thread.id})\n")
            append("OS: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})\n")
            append("Stacktrace:\n")
            append(sw.toString())
            append("====================================================================\n")
        }

        Log.e("AppLogger", crashBanner)

        // Let queued log lines reach the file before the crash banner.
        runCatching {
            diskWriter.shutdown()
            diskWriter.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        lock.withLock {
            for (line in crashBanner.lines()) {
                if (buffer.size >= MAX_LOG_LINES) {
                    buffer.removeFirst()
                }
                buffer.addLast(line)
            }
            try {
                val file = logFile
                if (file != null) {
                    fileWriter?.close()
                    FileWriter(file, true).use { writer ->
                        writer.write(crashBanner)
                        writer.flush()
                    }
                }
            } catch (t: Throwable) {
                Log.e("AppLogger", "Failed flushing crash log to disk", t)
            }
        }
    }

    /**
     * Returns full circular log buffer formatted for clipboard copying.
     */
    fun getAllLogsAsText(): String {
        lock.withLock {
            val sb = java.lang.StringBuilder(buffer.size * 90)
            for (line in buffer) {
                sb.append(line).append('\n')
            }
            return sb.toString()
        }
    }

    /**
     * Returns the latest N lines for UI display in Settings monospace viewer.
     */
    fun getRecentLogs(maxCount: Int = 1000): List<String> {
        lock.withLock {
            val count = minOf(maxCount, buffer.size)
            val dropCount = buffer.size - count
            return buffer.drop(dropCount)
        }
    }

    fun getLineCount(): Int {
        lock.withLock {
            return buffer.size
        }
    }

    fun clear() {
        lock.withLock {
            buffer.clear()
            try {
                fileWriter?.close()
                logFile?.delete()
                val file = logFile
                if (file != null) {
                    fileWriter = FileWriter(file, false)
                }
                totalLinesWrittenSinceCompact = 0
            } catch (t: Throwable) {
                Log.e("AppLogger", "Failed clearing log file", t)
            }
            i("AppLogger", "Log buffer cleared by user.")
        }
    }
}
