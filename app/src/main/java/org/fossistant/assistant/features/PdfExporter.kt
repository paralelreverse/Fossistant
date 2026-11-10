package org.fossistant.assistant.features

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.DateFormat
import java.util.Date

/**
 * Turns text (light Markdown: `#` headings, `-`/`*` bullets, `1.` lists, `**bold**`) into an A4 PDF with
 * Android's built-in [PdfDocument] and saves it to Downloads/Fossistant. No third-party library.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595  // A4 in PostScript points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 54

    data class Saved(val uri: Uri, val displayPath: String)

    fun export(context: Context, title: String, text: String): Result<Saved> = runCatching {
        val cleanTitle = title.trim().ifBlank { "Fossistant document" }
        val document = PdfDocument()
        val writer = PageWriter(document)
        writer.paragraph(cleanTitle, Style.TITLE)
        writer.paragraph(DateFormat.getDateInstance(DateFormat.LONG).format(Date()), Style.META)
        writer.space(10f)
        for (block in parseBlocks(text)) {
            writer.paragraph(block.text, block.style)
        }
        writer.finish()

        val fileName = cleanTitle.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "document" } + ".pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fossistant")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { document.writeTo(it) } ?: error("Couldn't write the file")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        } finally {
            document.close()
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        Saved(uri, "Downloads/Fossistant/$fileName")
    }

    /** Opens the saved PDF in the user's PDF viewer. */
    fun open(context: Context, uri: Uri) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Open PDF"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    enum class Style { TITLE, META, HEADING, SUBHEADING, BODY, BULLET }

    data class Block(val text: String, val style: Style)

    /** Splits text into paragraphs, headings and list items. Internal so unit tests can check it. */
    internal fun parseBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks.add(Block(stripInline(paragraph.toString().trim()), Style.BODY))
            paragraph.clear()
        }
        for (rawLine in text.replace("\r", "").lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("### ") || trimmed.startsWith("## ") -> {
                    flush(); blocks.add(Block(stripInline(trimmed.trimStart('#').trim()), Style.SUBHEADING))
                }
                trimmed.startsWith("# ") -> {
                    flush(); blocks.add(Block(stripInline(trimmed.removePrefix("# ").trim()), Style.HEADING))
                }
                Regex("^[-*•] ").containsMatchIn(trimmed) -> {
                    flush(); blocks.add(Block("•  " + stripInline(trimmed.substring(2).trim()), Style.BULLET))
                }
                Regex("^\\d{1,3}[.)] ").containsMatchIn(trimmed) -> {
                    flush(); blocks.add(Block(stripInline(trimmed), Style.BULLET))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
        flush()
        return blocks
    }

    private fun stripInline(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")

    private class PageWriter(private val document: PdfDocument) {
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private val contentWidth = PAGE_WIDTH - 2 * MARGIN

        private fun paintFor(style: Style) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 24, 39)
            when (style) {
                Style.TITLE -> { textSize = 22f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                Style.META -> { textSize = 9.5f; color = Color.rgb(100, 116, 139) }
                Style.HEADING -> { textSize = 15f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                Style.SUBHEADING -> { textSize = 12.5f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                Style.BODY, Style.BULLET -> { textSize = 11f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
            }
        }

        private fun newPage() {
            page?.let { finishPage(it) }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN.toFloat()
        }

        private fun finishPage(current: PdfDocument.Page) {
            val footer = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(148, 163, 184) }
            val label = "$pageNumber"
            current.canvas.drawText(label, (PAGE_WIDTH - footer.measureText(label)) / 2f, PAGE_HEIGHT - MARGIN / 2f, footer)
            document.finishPage(current)
        }

        fun space(points: Float) {
            y += points
        }

        fun paragraph(text: String, style: Style) {
            if (page == null) newPage()
            val paint = paintFor(style)
            val indent = if (style == Style.BULLET) 14 else 0
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth - indent)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.12f)
                .build()
            val before = when (style) { Style.HEADING -> 12f; Style.SUBHEADING -> 8f; else -> 0f }
            y += before
            // Draw line by line so long paragraphs continue on the next page.
            for (line in 0 until layout.lineCount) {
                val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (y + lineHeight > PAGE_HEIGHT - MARGIN) newPage()
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate((MARGIN + indent).toFloat(), y - layout.getLineTop(line))
                canvas.clipRect(0, layout.getLineTop(line), contentWidth, layout.getLineBottom(line))
                layout.draw(canvas)
                canvas.restore()
                y += lineHeight
            }
            y += when (style) { Style.TITLE -> 4f; Style.BULLET -> 3f; else -> 7f }
        }

        fun finish() {
            page?.let { finishPage(it) }
            page = null
        }
    }
}
