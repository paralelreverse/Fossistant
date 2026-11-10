package org.fossistant.assistant.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Something drawn in the chat with an answer: a chart or an infographic the model made with `show_chart` /
 * `show_diagram`, or photos found with a web search. Kept on the [ToolExecutionResult], so saved chats show it again.
 */
@Serializable
data class ToolVisual(
    val kind: VisualKind,
    val title: String = "",
    /** Unit of chart values ("°C", "USD"); empty when there is none. */
    val unit: String = "",
    val labels: List<String> = emptyList(),
    val values: List<Double> = emptyList(),
    /** Boxes of a breakdown or flow diagram. */
    val boxes: List<VisualBox> = emptyList(),
    val photos: List<VisualPhoto> = emptyList()
)

@Serializable
enum class VisualKind { BAR_CHART, LINE_CHART, PIE_CHART, BREAKDOWN, FLOW, PHOTOS }

@Serializable
data class VisualBox(val heading: String, val text: String = "")

@Serializable
data class VisualPhoto(
    val imageUrl: String,
    /** The page the photo comes from, opened when it is tapped. */
    val pageUrl: String = "",
    val title: String = "",
    val source: String = ""
)

/**
 * Turns the text arguments a model writes for `show_chart` / `show_diagram` into a [ToolVisual]. Small models write
 * lists in many shapes ("a; b", "a, b", JSON arrays, one per line), so all of them are accepted.
 */
object VisualArgs {

    private const val MAX_BARS = 16
    private const val MAX_LINE_POINTS = 60
    private const val MAX_PIE_SLICES = 8
    private const val MAX_BOXES = 8

    fun chart(type: String, title: String, labels: String, values: String, unit: String): Result<ToolVisual> {
        val kind = when (type.trim().lowercase()) {
            "line", "trend", "area", "timeline" -> VisualKind.LINE_CHART
            "pie", "donut", "doughnut", "share", "shares" -> VisualKind.PIE_CHART
            else -> VisualKind.BAR_CHART
        }
        val labelList = splitList(labels)
        val numbers = splitValues(values, labelList.size).map { parseNumber(it) }
        if (numbers.isEmpty() || numbers.any { it == null }) {
            return Result.failure(IllegalArgumentException("values must be numbers separated by ';', e.g. '12; 7.5; 20'"))
        }
        var points = numbers.filterNotNull()
        var names = List(points.size) { i -> labelList.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "#${i + 1}" }
        when (kind) {
            VisualKind.LINE_CHART -> if (points.size < 2) {
                return Result.failure(IllegalArgumentException("a line chart needs at least 2 values"))
            }
            VisualKind.PIE_CHART -> {
                if (points.any { it < 0 } || points.sum() <= 0) {
                    return Result.failure(IllegalArgumentException("a pie chart needs positive values"))
                }
                if (points.size > MAX_PIE_SLICES) {
                    // Biggest slices first, the rest as "Other".
                    val sorted = names.zip(points).sortedByDescending { it.second }
                    val kept = sorted.take(MAX_PIE_SLICES - 1)
                    names = kept.map { it.first } + "Other"
                    points = kept.map { it.second } + sorted.drop(MAX_PIE_SLICES - 1).sumOf { it.second }
                }
            }
            else -> Unit
        }
        val limit = if (kind == VisualKind.LINE_CHART) MAX_LINE_POINTS else MAX_BARS
        return Result.success(
            ToolVisual(
                kind = kind,
                title = title.trim().take(80),
                unit = unit.trim().take(12),
                labels = names.take(limit).map { it.take(40) },
                values = points.take(limit)
            )
        )
    }

    fun diagram(type: String, title: String, items: String): Result<ToolVisual> {
        val kind = when (type.trim().lowercase()) {
            "flow", "steps", "process", "sequence", "timeline", "chain" -> VisualKind.FLOW
            else -> VisualKind.BREAKDOWN
        }
        val boxes = splitList(items, commaSeparates = false).map { item ->
            val cut = item.indexOfAny(charArrayOf(':', '–', '—')).takeIf { it in 1..60 }
                ?: item.indexOf(" - ").takeIf { it in 1..60 }
            if (cut == null) {
                VisualBox(item.take(60))
            } else {
                VisualBox(item.substring(0, cut).trim().take(60), item.substring(cut + 1).trim().trimStart('-', ' ').take(160))
            }
        }.filter { it.heading.isNotBlank() }
        if (boxes.isEmpty()) {
            return Result.failure(IllegalArgumentException("items must be boxes separated by ';', e.g. 'Rent: 40%; Food: 25%'"))
        }
        return Result.success(ToolVisual(kind = kind, title = title.trim().take(80), boxes = boxes.take(MAX_BOXES)))
    }

    /** A JSON array, or items separated by ';', new lines or (when [commaSeparates]) commas. */
    fun splitList(raw: String, commaSeparates: Boolean = true): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) {
            runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull()?.let { array ->
                return array.map { (it as? JsonPrimitive)?.content ?: it.toString() }.map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        val parts = when {
            ';' in text -> text.split(';')
            '\n' in text -> text.split('\n')
            commaSeparates -> text.split(',')
            else -> listOf(text)
        }
        // "- item" and "• item" are list bullets; "-1" is a negative number.
        return parts.map { BULLET.replace(it.trim(), "").trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Values split like [splitList]; when they are only separated by commas, "1,250, 3,400" is read as two numbers if
     * that matches the [expectedCount] of labels.
     */
    fun splitValues(raw: String, expectedCount: Int): List<String> {
        val text = raw.trim()
        if (text.startsWith("[") || ';' in text || '\n' in text) return splitList(text)
        val plain = splitList(text)
        if (expectedCount <= 0 || plain.size == expectedCount) return plain
        val grouped = splitList(THOUSANDS.replace(text) { it.value.replace(",", "") })
        return if (grouped.size == expectedCount) grouped else plain
    }

    /** "12", "7.5", "-3", "$1,200", "12,5" (decimal comma), "45%", "20 °C", "1.2k", "3.4M", "5 billion" -> numbers. */
    fun parseNumber(raw: String): Double? {
        val text = CURRENCY.replace(raw, "").trim().replace(Regex("[\\s  ]"), "")
        if (DECIMAL_COMMA.matches(text)) return text.replace(',', '.').toDoubleOrNull()
        val match = NUMBER.find(text) ?: return null
        val value = match.value.replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (LEADING_WORD.find(text.substring(match.range.last + 1))?.value) {
            "k", "K", "thousand", "Thousand" -> 1e3
            "M", "mn", "Mn", "million", "Million" -> 1e6
            "B", "bn", "Bn", "billion", "Billion" -> 1e9
            "T", "tn", "trillion", "Trillion" -> 1e12
            else -> 1.0
        }
        return value * multiplier
    }

    private val BULLET = Regex("^(?:[-*]\\s+|•\\s*)")
    private val CURRENCY = Regex("[$€£¥₹]")
    private val DECIMAL_COMMA = Regex("-?\\d+,\\d{1,2}")
    private val LEADING_WORD = Regex("^[A-Za-z]+")
    private val NUMBER = Regex("-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|-?\\.\\d+")
    private val THOUSANDS = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?=\\s*(?:,\\s|$))")
}
