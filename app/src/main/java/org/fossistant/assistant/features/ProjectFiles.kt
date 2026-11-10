package org.fossistant.assistant.features

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream
import kotlin.math.ln

/**
 * Project files: text extraction (plain text, Markdown, CSV, HTML, Word .docx, and PDF on Android 15+),
 * chunking, and BM25 keyword search to pick the excerpts that go with each question. Keyword search needs no
 * embedding model, so nothing extra is downloaded or bundled.
 */
object ProjectFiles {

    private const val MAX_FILE_CHARS = 400_000
    private const val CHUNK_CHARS = 900

    /** Reads [uri] into a [ProjectFile], or returns an error message. */
    fun import(context: Context, uri: Uri): Result<ProjectFile> = runCatching {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { name = it }
        }
        val mime = resolver.getType(uri).orEmpty()
        val lower = name.lowercase()
        val text = when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> readPdf(context, uri)
            lower.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(context, uri)
            mime.contains("html") || lower.endsWith(".html") || lower.endsWith(".htm") ->
                Jsoup.parse(readText(context, uri)).text()
            else -> readText(context, uri)
        }.take(MAX_FILE_CHARS)
        require(text.isNotBlank()) { "No text found in $name." }
        ProjectFile(name = name, chunks = chunk(text))
    }

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { readBoundedText(it, MAX_FILE_CHARS) }
            ?: error("Couldn't open the file.")

    private fun readBoundedText(input: java.io.InputStream, maxChars: Int): String {
        val reader = input.bufferedReader()
        val sb = StringBuilder(minOf(maxChars, 16_384))
        val buf = CharArray(8_192)
        while (sb.length < maxChars) {
            val read = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
            if (read <= 0) break
            sb.append(buf, 0, read)
        }
        return sb.toString()
    }

    private fun readPdf(context: Context, uri: Uri): String {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "Reading text from PDFs needs Android 15 or newer."
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Couldn't open the PDF.")
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                buildString {
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            page.textContents.forEach { content -> appendLine(content.text) }
                        }
                        appendLine()
                        if (length > MAX_FILE_CHARS) break
                    }
                }
            }
        }
    }

    /** Word documents are zip files; the text is in word/document.xml. */
    private fun readDocx(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = readBoundedText(zip, MAX_FILE_CHARS * 4)
                        return xml
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<w:tab/>"), "\t")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'")
                    }
                }
            }
        }
        error("That doesn't look like a Word document.")
    }

    /** Splits text at paragraph or sentence boundaries into ~900-character chunks. */
    internal fun chunk(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        val pieces = text.replace("\r", "").split(Regex("\n\\s*\n")).flatMap { paragraph ->
            if (paragraph.length <= CHUNK_CHARS) listOf(paragraph) else paragraph.split(Regex("(?<=[.!?])\\s+"))
        }
        for (piece in pieces) {
            val clean = piece.replace(Regex("[ \t]+"), " ").trim()
            if (clean.isEmpty()) continue
            if (current.length + clean.length + 1 > CHUNK_CHARS && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (clean.length > CHUNK_CHARS) {
                clean.chunked(CHUNK_CHARS).forEach { chunks.add(it) }
            } else {
                current.append(clean).append('\n')
            }
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }

    private fun tokens(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase()).map { it.value }.toList()

    /** The excerpts most relevant to [query] (BM25), up to [maxChars], as (file name, text) pairs. */
    internal fun search(project: Project, query: String, maxChars: Int = 2400): List<Pair<String, String>> {
        val docs = project.files.flatMap { file -> file.chunks.map { file.name to it } }
        if (docs.isEmpty()) return emptyList()
        val queryTerms = tokens(query).distinct()
        val docTokens = docs.map { tokens(it.second) }
        val avgLen = docTokens.map { it.size }.average().coerceAtLeast(1.0)
        val docFreq = HashMap<String, Int>()
        docTokens.forEach { list -> list.toSet().forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 } }
        val k1 = 1.2
        val b = 0.75
        val scored = docs.indices.map { i ->
            val counts = docTokens[i].groupingBy { it }.eachCount()
            val len = docTokens[i].size
            val score = queryTerms.sumOf { term ->
                val tf = counts[term] ?: return@sumOf 0.0
                val df = docFreq[term] ?: 0
                val idf = ln(1 + (docs.size - df + 0.5) / (df + 0.5))
                idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avgLen))
            }
            i to score
        }
        // Small projects: with no keyword overlap, the start of the files is still the best context.
        val ranked = scored.sortedByDescending { it.second }.let { list ->
            if (list.first().second <= 0.0) docs.indices.map { it to 0.0 } else list.filter { it.second > 0.0 }
        }
        val selected = mutableListOf<Pair<String, String>>()
        var used = 0
        for ((index, _) in ranked) {
            val doc = docs[index]
            if (used + doc.second.length > maxChars) {
                if (selected.isEmpty()) selected.add(doc.first to doc.second.take(maxChars))
                break
            }
            selected.add(doc)
            used += doc.second.length
        }
        return selected
    }

    /** The per-turn context for a chat inside [project]. */
    fun turnContext(project: Project, query: String): String = buildString {
        append("You're working in the project \"${project.name}\".")
        if (project.instructions.isNotBlank()) append(" Project instructions: ").append(project.instructions.trim())
        val excerpts = search(project, query)
        if (excerpts.isNotEmpty()) {
            append("\nExcerpts from the project files (mention the file name when you use them):")
            excerpts.forEach { (file, text) -> append("\n[").append(file).append("] ").append(text) }
        }
    }
}
