package org.fossistant.assistant.util

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Which model files this APK carries in `assets/models`. The APKs bundle different models (full: Gemma and
 * Whisper; slim: none) and share one app id, so the app checks the installed APK instead of assuming.
 */
object BundledModels {

    @Volatile
    private var cached: Map<String, Long>? = null

    /** File names under `assets/models` in the installed APK with their sizes (empty on the JVM or when unreadable). */
    private fun entries(context: Context?): Map<String, Long> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val apk = context?.applicationInfo?.sourceDir?.let(::File)?.takeIf { it.exists() } ?: return emptyMap()
            val sizes = runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith(PREFIX) && it.name.length > PREFIX.length }
                        .associate { it.name.removePrefix(PREFIX) to it.size }
                }
            }.getOrDefault(emptyMap())
            cached = sizes
            return sizes
        }
    }

    /** File names under `assets/models` in the installed APK (empty on the JVM or when unreadable). */
    fun fileNames(context: Context?): Set<String> = entries(context).keys

    /**
     * Size of [fileName] as this APK carries it (parts added up), or null when it isn't in the APK. Used to notice
     * that an APK ships a newer build of a model under the same name, so the unpacked copy gets replaced.
     */
    fun size(context: Context?, fileName: String): Long? {
        val sizes = entries(context)
        sizes[fileName]?.let { return it }
        val parts = partNames(fileName, sizes.keys)
        return if (parts.isEmpty()) null else parts.sumOf { sizes[it] ?: 0L }
    }

    fun contains(context: Context?, fileName: String): Boolean {
        val names = fileNames(context)
        return fileName in names || partNames(fileName, names).isNotEmpty()
    }

    /**
     * A model over 2 GB can't be packaged as one asset (the Android build tools read assets into a single array),
     * so it ships split as `name.part1.gguf`, `name.part2.gguf`, ... These are its parts in order, or empty.
     */
    fun partNames(fileName: String, names: Set<String>): List<String> {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return emptyList()
        val base = fileName.substring(0, dot)
        val ext = fileName.substring(dot)
        return generateSequence(1) { it + 1 }
            .map { "$base.part$it$ext" }
            .takeWhile { it in names }
            .toList()
    }

    private const val PREFIX = "assets/models/"
}
