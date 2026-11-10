package org.fossistant.assistant.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

/**
 * Memory-efficient streaming processor for large text documents, video files, and long audio clips.
 *
 * Designed specifically for 8GB RAM mobile devices:
 * - Never loads an entire multi-megabyte document or video into the Java heap at once.
 * - Uses sliding-window chunking + lightweight lexical BM25 scoring to select the most relevant
 *   chunks for Gemma 4 E2B's context window, or hierarchical map-reduce summarization.
 * - Uses hardware-accelerated sync-frame extraction (`OPTION_CLOSEST_SYNC`) and 512px downscaling
 *   for video frames passed to LiteRT-LM's GPU Vision backend.
 */
class MultimodalChunkProcessor(
    private val context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val DEFAULT_CHUNK_CHAR_SIZE = 3200
        const val DEFAULT_CHUNK_OVERLAP = 320
        // Each image costs roughly 256 tokens of the 4096-token context.
        const val MAX_VIDEO_KEYFRAMES = 4
        const val PHOTO_TARGET_MAX_DIM = 768
        const val VISION_TARGET_MAX_DIM = 512
    }

    /**
     * Splits a large text string or stream into overlapping semantic chunks without duplicating
     * the full string in memory more than necessary.
     */
    fun chunkLargeText(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_CHAR_SIZE,
        overlap: Int = DEFAULT_CHUNK_OVERLAP
    ): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= chunkSize) return listOf(text)

        val safeChunkSize = max(chunkSize, 200)
        val safeOverlap = overlap.coerceIn(0, safeChunkSize / 2)
        val step = safeChunkSize - safeOverlap
        val chunks = ArrayList<String>((text.length / step) + 2)

        var start = 0
        while (start < text.length) {
            var end = min(start + safeChunkSize, text.length)
            // Prefer breaking at paragraph or sentence boundary
            if (end < text.length) {
                val lastParagraph = text.lastIndexOf('\n', end)
                val lastPeriod = text.lastIndexOf(". ", end)
                val boundary = max(lastParagraph, lastPeriod)
                if (boundary > start + (safeChunkSize / 2)) {
                    end = boundary + 1
                }
            }
            chunks.add(text.substring(start, end).trim())
            if (end >= text.length) break
            start = max(start + 1, end - safeOverlap)
        }
        return chunks.filter { it.isNotEmpty() }
    }

    /**
     * Ranks and selects the top-K most relevant text chunks for a user query so Gemma 4 E2B QAT
     * can answer questions over massive documents within a tight KV-cache RAM budget.
     */
    fun selectTopRelevantChunks(
        chunks: List<String>,
        query: String,
        topK: Int = 4
    ): List<String> {
        if (chunks.size <= topK) return chunks
        val queryTerms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        if (queryTerms.isEmpty()) {
            // Evenly sample across beginning, middle, and end for general summarization
            val stride = max(1, chunks.size / topK)
            return (0 until topK).map { idx -> chunks[min(idx * stride, chunks.lastIndex)] }
        }

        return chunks
            .mapIndexed { index, chunk ->
                val lowerChunk = chunk.lowercase()
                var score = 0.0
                for (term in queryTerms) {
                    var pos = lowerChunk.indexOf(term)
                    var occurrences = 0
                    while (pos != -1 && occurrences < 10) {
                        occurrences++
                        pos = lowerChunk.indexOf(term, pos + term.length)
                    }
                    if (occurrences > 0) {
                        // Sublinear TF scaling (BM25-like saturation)
                        score += (1.0 + kotlin.math.ln(occurrences.toDouble()))
                    }
                }
                index to score
            }
            .sortedByDescending { it.second }
            .take(topK)
            .sortedBy { it.first } // Preserve original document reading order
            .map { chunks[it.first] }
    }

    /**
     * Streams a large text file from an Android Content [Uri] in 8KB buffer blocks.
     */
    suspend fun readAndChunkUriText(
        uri: Uri,
        maxTotalChars: Int = 250_000
    ): List<String> = withContext(ioDispatcher) {
        val ctx = context ?: return@withContext emptyList()
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8192).use { reader ->
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    val remaining = maxTotalChars - sb.length
                    if (remaining <= 0) break
                    sb.append(buffer, 0, min(read, remaining))
                }
            }
        }
        chunkLargeText(sb.toString())
    }

    /**
     * Extracts uniformly spaced, hardware-decoded, downscaled JPEG keyframes from a video [Uri]
     * for Gemma 4 E2B's Vision encoder without exhausting native heap.
     */
    suspend fun extractVideoKeyframesJpeg(
        videoUri: Uri,
        maxFrames: Int = MAX_VIDEO_KEYFRAMES
    ): List<ByteArray> = withContext(ioDispatcher) {
        val ctx = context ?: return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<ByteArray>()
        try {
            retriever.setDataSource(ctx, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frameCount = maxFrames.coerceIn(1, 8)
            val intervalUs = if (durationMs > 0) (durationMs * 1000L) / frameCount else 1_000_000L

            for (i in 0 until frameCount) {
                val timeUs = i * intervalUs
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val scaled = scaleDownBitmap(rawBitmap, VISION_TARGET_MAX_DIM)
                if (scaled !== rawBitmap) {
                    rawBitmap.recycle()
                }
                val bos = ByteArrayOutputStream(24_576)
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                scaled.recycle()
                frames.add(bos.toByteArray())
            }
        } catch (_: Throwable) {
            // Gracefully return any frames extracted so far
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
        }
        frames
    }

    /**
     * Re-encodes a photo so its longest side is at most [maxDimension] (full-resolution camera photos are
     * slow to encode and waste memory in the vision encoder). Returns the input if it can't be decoded.
     */
    fun downscaleImageJpeg(bytes: ByteArray, maxDimension: Int = PHOTO_TARGET_MAX_DIM): ByteArray {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes
            if (bounds.outWidth <= maxDimension && bounds.outHeight <= maxDimension) return bytes
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimension && bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return bytes
            val scaled = scaleDownBitmap(decoded, maxDimension)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            bytes
        }
    }

    private fun scaleDownBitmap(src: Bitmap, maxDimension: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDimension && h <= maxDimension) return src
        val ratio = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val targetW = max(1, (w * ratio).toInt())
        val targetH = max(1, (h * ratio).toInt())
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }
}
