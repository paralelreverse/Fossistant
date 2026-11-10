package org.fossistant.assistant.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.model.PrimaryLlmModel
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads the models an APK doesn't carry (slim) straight into `files/models`, the folder the engines already
 * read from, so the phone keeps exactly one copy of each model (no copy inside the APK, no second unpacked copy).
 *
 * - Resumable: data goes to `<name>.part` with HTTP Range requests, so a dropped connection continues later.
 * - Pinned: size and SHA-256 must match the build the full APK bundles. For Gemma that is the MTP build; on top
 *   of the hash, LiteRT is asked whether the file really has its MTP drafter, and a file without one is deleted.
 * - Device preparation: LiteRT builds the GPU programs for this phone when the model is first loaded
 *   ([GemmaLiteRtEngine.ensureInitialized]), cached in `files/litertlm_compiled_shaders`.
 */
class ModelDownloadManager(private val context: Context) {

    enum class Phase { IDLE, WAITING_FOR_WIFI, DOWNLOADING, VERIFYING, PREPARING, DONE, ERROR }

    data class Progress(
        val phase: Phase = Phase.IDLE,
        val label: String = "",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val error: String? = null
    ) {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private data class Item(
        val label: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val requireMtp: Boolean
    )

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun isPrimaryReady(model: PrimaryLlmModel): Boolean =
        File(modelsDir, model.filename).let { it.exists() && it.length() > model.minBytes }

    fun isWhisperReady(choice: WhisperModelChoice): Boolean =
        File(modelsDir, choice.filename).let { it.exists() && it.length() > choice.minBytes }

    /** Bytes still to fetch for [model] and [whisper] (already-downloaded parts of a `.part` file count). */
    fun missingBytes(model: PrimaryLlmModel, whisper: WhisperModelChoice): Long =
        itemsFor(model, whisper).sumOf { it.sizeBytes - File(modelsDir, it.fileName + ".part").length().coerceAtMost(it.sizeBytes) }

    fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Downloads whatever of [model] and [whisper] isn't on the phone yet. [wifiOnly] waits instead of using
     * mobile data. [onPrimaryReady] runs once the language model is in place (to load it and build GPU programs).
     */
    fun start(
        model: PrimaryLlmModel,
        whisper: WhisperModelChoice,
        wifiOnly: Boolean,
        onPrimaryReady: suspend () -> Unit = {}
    ) {
        if (isRunning) return
        job = scope.launch {
            val items = itemsFor(model, whisper)
            val total = items.sumOf { it.sizeBytes }
            try {
                if (items.isNotEmpty()) {
                    if (wifiOnly && !isOnUnmeteredNetwork()) {
                        _progress.value = Progress(Phase.WAITING_FOR_WIFI, "Waiting for Wi-Fi", 0L, total)
                        return@launch
                    }
                    val needed = items.sumOf { it.sizeBytes } + SPACE_MARGIN_BYTES
                    if (freeBytes() < needed) {
                        throw IllegalStateException(
                            "Not enough free space: ${formatBytes(needed)} needed, ${formatBytes(freeBytes())} free."
                        )
                    }
                    var done = 0L
                    for (item in items) {
                        download(item) { bytes, speed ->
                            _progress.value = Progress(Phase.DOWNLOADING, item.label, done + bytes, total, speed)
                        }
                        _progress.value = Progress(Phase.VERIFYING, item.label, done + item.sizeBytes, total)
                        verifyAndInstall(item)
                        done += item.sizeBytes
                    }
                }
                if (isPrimaryReady(model)) {
                    _progress.value = Progress(Phase.PREPARING, "Optimizing ${model.displayName} for this phone", total, total)
                    onPrimaryReady()
                }
                _progress.value = Progress(Phase.DONE, "Ready", total, total)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    _progress.value = Progress(Phase.IDLE)
                    throw t
                }
                AppLogger.w(TAG, "Model download failed: ${t.message}", t)
                _progress.value = _progress.value.copy(phase = Phase.ERROR, error = t.message ?: "Download failed")
            }
        }
    }

    /** Pauses; the `.part` files stay so the next [start] resumes. */
    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = Progress(Phase.IDLE)
    }

    private fun itemsFor(model: PrimaryLlmModel, whisper: WhisperModelChoice): List<Item> = buildList {
        if (!isPrimaryReady(model) && model.downloadUrl.isNotBlank()) {
            add(Item(model.displayName, model.filename, model.downloadUrl, model.downloadSizeBytes, model.downloadSha256, model.hasMtpDrafter))
        }
        if (!isWhisperReady(whisper)) {
            add(Item(whisper.displayName, whisper.filename, whisper.downloadUrl, whisper.downloadSizeBytes, whisper.downloadSha256, false))
        }
    }

    private suspend fun download(item: Item, onBytes: (Long, Long) -> Unit) {
        val part = File(modelsDir, item.fileName + ".part")
        if (item.sizeBytes > 0 && part.length() > item.sizeBytes) part.delete()
        if (item.sizeBytes > 0 && part.length() == item.sizeBytes) return
        val request = Request.Builder().url(item.url).apply {
            if (part.length() > 0) header("Range", "bytes=${part.length()}-")
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416 && part.length() == item.sizeBytes) return
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} while downloading ${item.label}")
            val append = response.code == 206
            var written = if (append) part.length() else 0L
            val body = response.body ?: throw IllegalStateException("Empty response for ${item.label}")
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var speed = 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(512 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        windowBytes += read
                        val elapsed = System.nanoTime() - windowStart
                        if (elapsed > 1_000_000_000L) {
                            speed = windowBytes * 1_000_000_000L / elapsed
                            windowStart = System.nanoTime()
                            windowBytes = 0L
                        }
                        if (written - lastReport > 2L * 1024 * 1024) {
                            lastReport = written
                            onBytes(written, speed)
                        }
                    }
                }
            }
        }
    }

    private fun verifyAndInstall(item: Item) {
        val part = File(modelsDir, item.fileName + ".part")
        if (item.sizeBytes > 0 && part.length() != item.sizeBytes) {
            throw IllegalStateException("${item.label} is incomplete (${part.length()} of ${item.sizeBytes} bytes); try again.")
        }
        if (item.sha256.isNotBlank() && !sha256(part).equals(item.sha256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException(
                "${item.label} doesn't match the expected build" +
                    (if (item.requireMtp) " (the MTP version)" else "") + ". The download was deleted; try again."
            )
        }
        if (item.requireMtp && !hasMtpDrafter(part)) {
            part.delete()
            throw IllegalStateException("The downloaded ${item.label} has no MTP drafter, so it was rejected.")
        }
        val target = File(modelsDir, item.fileName)
        if (target.exists()) target.delete()
        // Drop GPU programs built for an older file of the same name.
        File(context.filesDir, "litertlm_compiled_shaders").listFiles { f -> f.name.startsWith("${item.fileName}_") }
            ?.forEach { it.delete() }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save ${item.label}.")
        AppLogger.i(TAG, "Installed ${item.label} (${target.length()} bytes) in ${target.parent}")
    }

    private fun hasMtpDrafter(file: File): Boolean = runCatching {
        com.google.ai.edge.litertlm.ModelInfo.from(file.absolutePath).use { info ->
            (info as? com.google.ai.edge.litertlm.ModelInfo.Llm)?.hasSpeculativeDecodingSupport() == true
        }
    }.getOrElse {
        // The pinned SHA-256 already identifies the MTP build; only an unreadable file without a hash fails here.
        AppLogger.w(TAG, "Couldn't read ${file.name}'s capabilities: ${it.message}")
        true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4 * 1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        /** Headroom on top of the files themselves (GPU program cache, KV cache spill, the rest of the app). */
        const val SPACE_MARGIN_BYTES = 1_000_000_000L

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
            else -> "%.0f KB".format(bytes / 1_000.0)
        }
    }
}
