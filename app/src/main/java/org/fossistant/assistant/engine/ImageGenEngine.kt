package org.fossistant.assistant.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.model.ImageGenFile
import org.fossistant.assistant.model.ImageGenFiles
import org.fossistant.assistant.model.ImageGenModelChoice
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * On-device image generation with stable-diffusion.cpp (`libsd_jni.so`, CPU). Model files are downloaded on the
 * phone the first time a model is picked (not shipped in the APK), checked against their SHA-256, and kept in
 * `files/imagegen`. The loaded model is freed after a short idle time so it doesn't sit next to the chat model.
 */
class ImageGenEngine(private val context: Context) {

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val error: String? = null
    ) {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    fun interface StepListener {
        fun onStep(step: Int, steps: Int)
    }

    private val modelDir = File(context.filesDir, "imagegen").apply { mkdirs() }
    private val outputDir = File(context.filesDir, "generated_images").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloads = MutableStateFlow<Map<ImageGenModelChoice, DownloadProgress>>(emptyMap())
    /** Downloads in progress (or failed, with [DownloadProgress.error]), by model. */
    val downloads: StateFlow<Map<ImageGenModelChoice, DownloadProgress>> = _downloads
    private val downloadJobs = mutableMapOf<ImageGenModelChoice, Job>()

    private val generateLock = Mutex()
    private var handle = 0L
    private var loadedWithTinyVae: Boolean? = null
    private var idleFreeJob: Job? = null

    init {
        // Files from models the app no longer offers (the first SD 1.5 Q4_0 and its LCM-LoRA) free their space.
        val wanted = ImageGenFiles.all.flatMap { listOf(it.fileName, it.fileName + ".part") }.toSet()
        scope.launch { modelDir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() } }
    }

    fun isReady(choice: ImageGenModelChoice): Boolean = choice.files.all { fileFor(it).length() == it.sizeBytes }

    /** Bytes still to download for [choice] (files shared with another model count once they're on the phone). */
    fun missingBytes(choice: ImageGenModelChoice): Long =
        choice.files.filter { fileFor(it).length() != it.sizeBytes }.sumOf { it.sizeBytes }

    /** Starts (or resumes) downloading every file [choice] needs; progress is published on [downloads]. */
    fun startDownload(choice: ImageGenModelChoice) {
        if (downloadJobs[choice]?.isActive == true || isReady(choice)) return
        downloadJobs[choice] = scope.launch {
            val missing = choice.files.filter { fileFor(it).length() != it.sizeBytes }
            val total = missing.sumOf { it.sizeBytes }
            var done = 0L
            _downloads.update { it + (choice to DownloadProgress(0L, total)) }
            val result = runCatching {
                for (file in missing) {
                    downloadFile(file) { bytes -> _downloads.update { it + (choice to DownloadProgress(done + bytes, total)) } }
                    done += file.sizeBytes
                }
            }
            _downloads.update { current ->
                val error = result.exceptionOrNull()
                if (error == null || error is kotlinx.coroutines.CancellationException) current - choice
                else current + (choice to DownloadProgress(done, total, error.message ?: "Download failed"))
            }
        }
    }

    fun cancelDownload(choice: ImageGenModelChoice) {
        downloadJobs.remove(choice)?.cancel()
        _downloads.update { it - choice }
    }

    /**
     * Generates one 512-px image for [prompt] and saves it as a PNG. With [initImage] it edits that picture
     * instead (img2img): [strength] 0..1 is how far it may move away from it.
     */
    suspend fun generate(
        choice: ImageGenModelChoice,
        prompt: String,
        initImage: Bitmap? = null,
        strength: Float = 0.6f,
        seed: Long = System.nanoTime() and 0x7fffffffL,
        onStep: StepListener? = null
    ): File = generateLock.withLock {
        idleFreeJob?.cancel()
        withContext(Dispatchers.Default) {
            require(isReady(choice)) { "${choice.displayName} isn't downloaded yet" }
            ensureLoaded(choice)
            val (width, height) = initImage?.let { sizeFor(it.width, it.height) } ?: (512 to 512)
            val initRgb = initImage?.let { toRgb(Bitmap.createScaledBitmap(it, width, height, true)) }
            val effectivePrompt = cleanAndEnrichPrompt(prompt)
            val rgb = nativeGenerate(
                handle, effectivePrompt, NEGATIVE_PROMPT, width, height,
                choice.steps, choice.cfgScale, seed, choice.sampler, choice.scheduler,
                null, 1f,
                initRgb, strength, null, onStep
            )
            coroutineContext.ensureActive()
            if (rgb == null || rgb.size != width * height * 3) {
                throw IllegalStateException("Image generation failed: " + nativeRecentLog().lines().lastOrNull { it.isNotBlank() }.orEmpty())
            }
            val bitmap = fromRgb(rgb, width, height)
            val out = File(outputDir, "image_${System.currentTimeMillis()}.png")
            withContext(Dispatchers.IO) {
                FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            out
        }
    }.also { scheduleIdleFree() }

    fun cancelGeneration() {
        if (libraryLoaded) nativeCancel()
    }

    fun release() {
        idleFreeJob?.cancel()
        scope.launch { generateLock.withLock { freeHandle() } }
    }

    private fun ensureLoaded(choice: ImageGenModelChoice) {
        val wantTiny = choice.tinyVaeFile != null
        if (handle != 0L && loadedWithTinyVae == wantTiny) return
        freeHandle()
        loadLibrary()
        val model = fileFor(choice.files.first()).absolutePath
        val taesd = choice.tinyVaeFile?.let { File(modelDir, it).absolutePath }
        // Performance + mid cores on 8-core mobile chips for faster CPU GEMM.
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(4, 6)
        handle = nativeLoad(model, taesd, threads)
        if (handle == 0L) {
            throw IllegalStateException("Couldn't load ${choice.displayName}: " + nativeRecentLog().lines().lastOrNull { it.isNotBlank() }.orEmpty())
        }
        loadedWithTinyVae = wantTiny
    }

    private fun freeHandle() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedWithTinyVae = null
    }

    private fun scheduleIdleFree() {
        idleFreeJob?.cancel()
        idleFreeJob = scope.launch {
            delay(IDLE_FREE_MS)
            generateLock.withLock { freeHandle() }
        }
    }

    private suspend fun downloadFile(file: ImageGenFile, onBytes: (Long) -> Unit) {
        val target = fileFor(file)
        val part = File(modelDir, file.fileName + ".part")
        if (part.length() > file.sizeBytes) part.delete()
        val request = Request.Builder().url(file.url).apply {
            if (part.length() > 0) header("Range", "bytes=${part.length()}-")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for ${file.fileName}")
            val append = response.code == 206
            var written = if (append) part.length() else 0L
            val body = response.body ?: throw IllegalStateException("Empty response for ${file.fileName}")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReport > 4L * 1024 * 1024) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                }
            }
        }
        if (part.length() != file.sizeBytes) throw IllegalStateException("${file.fileName} is incomplete; try again")
        if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException("${file.fileName} was corrupted in transit; try again")
        }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save ${file.fileName}")
        onBytes(file.sizeBytes)
    }

    private fun fileFor(file: ImageGenFile) = File(modelDir, file.fileName)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ImageGenEngine"
        private const val IDLE_FREE_MS = 90_000L
        private const val NEGATIVE_PROMPT = "blurry, lowres, deformed, bad anatomy, extra fingers, watermark, text, signature"

        @Volatile private var libraryLoaded = false

        private fun loadLibrary() {
            if (libraryLoaded) return
            synchronized(this) {
                if (libraryLoaded) return
                try {
                    System.loadLibrary("sd_jni")
                    libraryLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "libsd_jni.so missing", e)
                    throw IllegalStateException("Image generation isn't available in this build")
                }
            }
        }

        /** Output size for an edited picture: its aspect ratio, longest side 512, both sides multiples of 64. */
        internal fun sizeFor(width: Int, height: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return 512 to 512
            val scale = 512f / maxOf(width, height)
            fun snap(v: Int) = ((v * scale / 64f).toInt() * 64).coerceIn(256, 512)
            return snap(width) to snap(height)
        }

        /**
         * Strips conversational request prefixes ("image of", "generate a picture of", etc.) and adds
         * subtle quality tokens to short prompts so DreamShaper 8 LCM produces sharp, detailed images.
         */
        internal fun cleanAndEnrichPrompt(rawPrompt: String): String {
            val stripped = rawPrompt.trim()
                .replace(
                    Regex("^(?:please\\s+)?(?:can you\\s+)?(?:create|generate|make|draw|paint|show me)?\\s*(?:an?\\s+)?(?:image|picture|photo|illustration|drawing)\\s+(?:of\\s+)?", RegexOption.IGNORE_CASE),
                    ""
                )
                .trim()
                .ifEmpty { rawPrompt.trim() }
            val wordCount = stripped.split(Regex("\\s+")).size
            val lower = stripped.lowercase()
            val alreadyStyled = listOf("sharp", "detailed", "8k", "4k", "photorealistic", "cinematic", "masterpiece").any { lower.contains(it) }
            return if (wordCount <= 14 && !alreadyStyled) {
                "$stripped, detailed, sharp focus, natural lighting, high quality"
            } else {
                stripped
            }
        }

        internal fun toRgb(bitmap: Bitmap): ByteArray {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val out = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i * 3] = (p shr 16 and 0xff).toByte()
                out[i * 3 + 1] = (p shr 8 and 0xff).toByte()
                out[i * 3 + 2] = (p and 0xff).toByte()
            }
            return out
        }

        internal fun fromRgb(rgb: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height) { i ->
                val r = rgb[i * 3].toInt() and 0xff
                val g = rgb[i * 3 + 1].toInt() and 0xff
                val b = rgb[i * 3 + 2].toInt() and 0xff
                (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private external fun nativeLoad(modelPath: String, taesdPath: String?, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, negativePrompt: String, width: Int, height: Int,
        steps: Int, cfg: Float, seed: Long, sampler: String, scheduler: String,
        loraPath: String?, loraStrength: Float,
        initRgb: ByteArray?, strength: Float, maskGray: ByteArray?,
        listener: StepListener?
    ): ByteArray?
    private external fun nativeCancel()
    private external fun nativeFree(handle: Long)
    private external fun nativeRecentLog(): String
}
