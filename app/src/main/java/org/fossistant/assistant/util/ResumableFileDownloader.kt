package org.fossistant.assistant.util

import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Shared resumable HTTP Range downloader and SHA-256 verifier for large model artifacts.
 */
object ResumableFileDownloader {

    suspend fun downloadToPart(
        http: OkHttpClient,
        url: String,
        target: File,
        expectedSizeBytes: Long,
        label: String,
        bufferSizeBytes: Int = 512 * 1024,
        reportStepBytes: Long = 2L * 1024 * 1024,
        onProgress: (downloadedBytes: Long, bytesPerSecond: Long) -> Unit = { _, _ -> }
    ): File {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        if (expectedSizeBytes > 0 && part.length() > expectedSizeBytes) {
            part.delete()
        }
        if (expectedSizeBytes > 0 && part.length() == expectedSizeBytes) {
            onProgress(expectedSizeBytes, 0L)
            return part
        }

        val request = Request.Builder().url(url).apply {
            if (part.length() > 0) header("Range", "bytes=${part.length()}-")
        }.build()

        http.newCall(request).execute().use { response ->
            if (response.code == 416 && expectedSizeBytes > 0 && part.length() == expectedSizeBytes) {
                onProgress(expectedSizeBytes, 0L)
                return part
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} while downloading $label")
            }
            val append = response.code == 206
            var written = if (append) part.length() else 0L
            val body = response.body ?: throw IllegalStateException("Empty response for $label")
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var speed = 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(bufferSizeBytes)
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
                        if (written - lastReport > reportStepBytes) {
                            lastReport = written
                            onProgress(written, speed)
                        }
                    }
                }
            }
            onProgress(written, speed)
        }
        return part
    }

    fun sha256(file: File, bufferSizeBytes: Int = 4 * 1024 * 1024): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(bufferSizeBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun installPartFile(part: File, target: File, label: String) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save $label.")
    }
}
