package org.fossistant.assistant.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.model.ToolVisual
import org.fossistant.assistant.model.VisualKind
import org.fossistant.assistant.model.VisualPhoto
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Charts, infographics and search photos that came with an answer ([ToolExecutionResult.visual]), drawn in the chat
 * bubble under the tool chips. An answer shows one photo strip at most, even after several searches.
 *
 * Series colors are a fixed-order palette checked for color-blind separation and 3:1 contrast on the dark bubbles;
 * text always uses the ink colors, never a series color.
 */
@Composable
fun ToolVisuals(tools: List<ToolExecutionResult>, accent: Color, modifier: Modifier = Modifier) {
    val visuals = remember(tools) { visualsToShow(tools) }
    if (visuals.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        visuals.forEach { visual ->
            when (visual.kind) {
                VisualKind.BAR_CHART -> ChartPanel(visual) { BarChart(visual) }
                VisualKind.LINE_CHART -> ChartPanel(visual) { LineChart(visual) }
                VisualKind.PIE_CHART -> ChartPanel(visual) { DonutChart(visual) }
                VisualKind.BREAKDOWN -> BreakdownDiagram(visual, accent)
                VisualKind.FLOW -> FlowDiagram(visual)
                VisualKind.PHOTOS -> PhotoStrip(visual.photos)
            }
        }
    }
}

/** Whether [tools] carry something [ToolVisuals] draws (the bubble gets wider for it). */
fun hasToolVisuals(tools: List<ToolExecutionResult>): Boolean = visualsToShow(tools).isNotEmpty()

/** The visuals of [tools] in order, keeping only the first photo strip. */
internal fun visualsToShow(tools: List<ToolExecutionResult>): List<ToolVisual> {
    var photosShown = false
    return tools.mapNotNull { it.visual }.filter { visual ->
        when {
            visual.kind != VisualKind.PHOTOS -> true
            photosShown || visual.photos.isEmpty() -> false
            else -> {
                photosShown = true
                true
            }
        }
    }
}

@Composable
private fun ChartPanel(visual: ToolVisual, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CHART_SURFACE)
            .border(1.dp, HAIRLINE, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = describe(visual) }
    ) {
        if (visual.title.isNotBlank()) {
            Text(visual.title, color = INK_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Horizontal bars from one zero baseline, the label on the left and the value at the tip. */
@Composable
private fun BarChart(visual: ToolVisual) {
    val values = visual.values
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(BAR_ROW * values.size)) {
        val gap = 6.dp.toPx()
        val row = BAR_ROW.toPx()
        val thickness = BAR_THICKNESS.toPx()
        val valueLayouts = values.map { measurer.measure(formatValue(it, visual.unit), VALUE_STYLE, maxLines = 1) }
        val labelWidth = values.indices
            .maxOf { measurer.measure(visual.labels.getOrElse(it) { "" }, LABEL_STYLE, maxLines = 1).size.width }
            .toFloat()
            .coerceAtMost(size.width * 0.38f)
        // Room for the value labels outside the bar ends, on the side each bar grows to.
        val negativeRoom = values.indices.filter { values[it] < 0 }.maxOfOrNull { valueLayouts[it].size.width + gap } ?: 0f
        val positiveRoom = values.indices.filter { values[it] >= 0 }.maxOfOrNull { valueLayouts[it].size.width + gap } ?: 0f
        val plotLeft = labelWidth + gap + negativeRoom
        val plotWidth = (size.width - plotLeft - positiveRoom).coerceAtLeast(size.width * 0.2f)
        val low = minOf(0.0, values.min())
        val span = (maxOf(0.0, values.max()) - low).takeIf { it > 0 } ?: 1.0
        val zeroX = plotLeft + (-low / span * plotWidth).toFloat()
        values.forEachIndexed { i, value ->
            val centerY = i * row + row / 2
            val label = measurer.measure(
                visual.labels.getOrElse(i) { "" }, LABEL_STYLE, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = labelWidth.toInt().coerceAtLeast(1))
            )
            drawText(label, topLeft = Offset(0f, centerY - label.size.height / 2f))
            val length = (abs(value) / span * plotWidth).toFloat()
            val start = if (value >= 0) zeroX else zeroX - length
            drawBar(start, centerY - thickness / 2, length, thickness, growsRight = value >= 0)
            val text = valueLayouts[i]
            val x = if (value >= 0) zeroX + length + gap else zeroX - length - gap - text.size.width
            drawText(text, topLeft = Offset(x, centerY - text.size.height / 2f))
        }
        drawLine(AXIS, Offset(zeroX, 0f), Offset(zeroX, size.height), strokeWidth = 1.dp.toPx())
    }
}

/** A bar with a 4 dp rounded data end and a square end at the baseline. */
private fun DrawScope.drawBar(start: Float, top: Float, length: Float, thickness: Float, growsRight: Boolean) {
    if (length <= 0f) return
    val r = minOf(4.dp.toPx(), length / 2, thickness / 2)
    val round = CornerRadius(r, r)
    val rect = RoundRect(
        left = start, top = top, right = start + length, bottom = top + thickness,
        topLeftCornerRadius = if (growsRight) CornerRadius.Zero else round,
        topRightCornerRadius = if (growsRight) round else CornerRadius.Zero,
        bottomRightCornerRadius = if (growsRight) round else CornerRadius.Zero,
        bottomLeftCornerRadius = if (growsRight) CornerRadius.Zero else round
    )
    drawPath(Path().apply { addRoundRect(rect) }, SERIES[0])
}

/** A 2 dp line over a light wash with clean y ticks; tapping a point shows its label and value. */
@Composable
private fun LineChart(visual: ToolVisual) {
    val values = visual.values
    if (values.size < 2) return
    val measurer = rememberTextMeasurer()
    var selected by remember(visual) { mutableStateOf<Int?>(null) }
    // Left edge and width of the plot, written while drawing and read by taps.
    val plot = remember { FloatArray(2) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .pointerInput(visual) {
                detectTapGestures { tap ->
                    if (plot[1] <= 0f) return@detectTapGestures
                    val index = ((tap.x - plot[0]) / plot[1] * values.lastIndex).roundToInt().coerceIn(0, values.lastIndex)
                    selected = if (selected == index) null else index
                }
            }
    ) {
        val gap = 6.dp.toPx()
        val ticks = niceTicks(values.min(), values.max())
        val tickLayouts = ticks.map { measurer.measure(formatNumber(it), AXIS_STYLE, maxLines = 1) }
        val left = tickLayouts.maxOf { it.size.width } + gap
        val right = size.width - 6.dp.toPx()
        val top = 22.dp.toPx()
        val xLabelConstraints = Constraints(maxWidth = ((right - left) / 2.6f).toInt().coerceAtLeast(1))
        fun xLabel(i: Int) = measurer.measure(
            visual.labels.getOrElse(i) { "" }, AXIS_STYLE, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = xLabelConstraints
        )
        val firstLabel = xLabel(0)
        val lastLabel = xLabel(values.lastIndex)
        val bottom = size.height - firstLabel.size.height - 4.dp.toPx()
        plot[0] = left
        plot[1] = right - left
        val lo = ticks.first()
        val hi = ticks.last()
        fun xAt(i: Int) = left + (right - left) * i / values.lastIndex
        fun yAt(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        ticks.forEachIndexed { k, tick ->
            val y = yAt(tick)
            drawLine(if (k == 0) AXIS else GRID, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = tickLayouts[k]
            drawText(layout, topLeft = Offset(left - gap - layout.size.width, y - layout.size.height / 2f))
        }
        val labelY = bottom + 4.dp.toPx()
        drawText(firstLabel, topLeft = Offset(left, labelY))
        drawText(lastLabel, topLeft = Offset(right - lastLabel.size.width, labelY))
        if (values.size >= 5) {
            val middle = values.size / 2
            val layout = xLabel(middle)
            val x = xAt(middle) - layout.size.width / 2f
            if (x > left + firstLabel.size.width + gap && x + layout.size.width < right - lastLabel.size.width - gap) {
                drawText(layout, topLeft = Offset(x, labelY))
            }
        }

        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(xAt(i), yAt(v)) else lineTo(xAt(i), yAt(v)) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xAt(values.lastIndex), bottom)
            lineTo(xAt(0), bottom)
            close()
        }
        drawPath(area, SERIES[0].copy(alpha = 0.10f))
        drawPath(line, SERIES[0], style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // The tapped point, or else the last one, gets a dot and its value.
        val picked = selected
        val index = picked ?: values.lastIndex
        val point = Offset(xAt(index), yAt(values[index]))
        if (picked != null) {
            drawLine(INK_MUTED.copy(alpha = 0.5f), Offset(point.x, top), Offset(point.x, bottom), strokeWidth = 1.dp.toPx())
        }
        drawCircle(CHART_SURFACE, radius = 6.dp.toPx(), center = point)
        drawCircle(SERIES[0], radius = 4.dp.toPx(), center = point)
        val text = if (picked != null) {
            "${visual.labels.getOrElse(index) { "" }}: ${formatValue(values[index], visual.unit)}"
        } else {
            formatValue(values[index], visual.unit)
        }
        val layout = measurer.measure(text, VALUE_STYLE, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (right - left).toInt().coerceAtLeast(1)))
        val pad = if (picked != null) 6.dp.toPx() else 0f
        val textX = (point.x - layout.size.width / 2f).coerceAtMost(right - layout.size.width - pad).coerceAtLeast(left + pad)
        val textY = (point.y - 8.dp.toPx() - layout.size.height).coerceAtLeast(3.dp.toPx())
        if (picked != null) {
            drawRoundRect(
                TOOLTIP, topLeft = Offset(textX - pad, textY - 3.dp.toPx()),
                size = Size(layout.size.width + 2 * pad, layout.size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }
        drawText(layout, topLeft = Offset(textX, textY))
    }
}

/** A donut with 2 dp gaps between slices and a legend with each slice's share. */
@Composable
private fun DonutChart(visual: ToolVisual) {
    val values = visual.values
    val total = values.sum()
    if (values.isEmpty() || total <= 0) return
    val sharesArePercent = visual.unit.trim() == "%"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(DONUT), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                val diameter = size.minDimension - stroke
                val gapDegrees = if (values.size > 1) 2.dp.toPx() / (Math.PI.toFloat() * diameter) * 360f else 0f
                var start = -90f
                values.forEachIndexed { i, value ->
                    val sweep = (value / total * 360.0).toFloat()
                    drawArc(
                        color = SERIES[i % SERIES.size],
                        startAngle = start + gapDegrees / 2,
                        sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.6f),
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke)
                    )
                    start += sweep
                }
            }
            if (!sharesArePercent) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 64.dp)) {
                    Text(
                        formatValue(total, visual.unit), color = INK_PRIMARY, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("total", color = INK_MUTED, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            values.forEachIndexed { i, value ->
                val share = (value / total * 100).let { if (it > 0 && it < 1) "<1%" else "${it.roundToInt()}%" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SERIES[i % SERIES.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        visual.labels.getOrElse(i) { "" }, color = INK_SECONDARY, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (sharesArePercent) formatValue(value, "%") else "${formatValue(value, visual.unit)} · $share",
                        color = INK_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1
                    )
                }
            }
        }
    }
}

/** The topic in a box, with arrows branching down to one box per part. */
@Composable
private fun BreakdownDiagram(visual: ToolVisual, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        DiagramBox(
            heading = visual.title.ifBlank { "Breakdown" },
            text = "",
            fill = accent.copy(alpha = 0.16f),
            border = accent.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        )
        visual.boxes.forEachIndexed { i, box ->
            val last = i == visual.boxes.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Canvas(Modifier.width(30.dp).fillMaxHeight()) {
                    val x = 14.dp.toPx()
                    val boxTop = BOX_GAP.toPx()
                    val midY = boxTop + (size.height - boxTop) / 2
                    val stroke = 1.5.dp.toPx()
                    drawLine(ARROW, Offset(x, 0f), Offset(x, if (last) midY else size.height), strokeWidth = stroke)
                    drawLine(ARROW, Offset(x, midY), Offset(size.width - 5.dp.toPx(), midY), strokeWidth = stroke)
                    drawArrowHead(tip = Offset(size.width - 1.dp.toPx(), midY), direction = Offset(1f, 0f))
                }
                DiagramBox(
                    heading = box.heading,
                    text = box.text,
                    marker = SERIES[i % SERIES.size],
                    modifier = Modifier.weight(1f).padding(top = BOX_GAP)
                )
            }
        }
    }
}

/** Numbered steps from top to bottom with arrows between them. */
@Composable
private fun FlowDiagram(visual: ToolVisual) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (visual.title.isNotBlank()) {
            Text(
                visual.title, color = INK_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
        }
        visual.boxes.forEachIndexed { i, box ->
            if (i > 0) {
                Canvas(Modifier.width(16.dp).height(20.dp)) {
                    val x = size.width / 2
                    drawLine(ARROW, Offset(x, 2.dp.toPx()), Offset(x, size.height - 5.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    drawArrowHead(tip = Offset(x, size.height - 1.dp.toPx()), direction = Offset(0f, 1f))
                }
            }
            DiagramBox(heading = box.heading, text = box.text, step = i + 1, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiagramBox(
    heading: String,
    text: String,
    modifier: Modifier = Modifier,
    marker: Color? = null,
    step: Int? = null,
    fill: Color = BOX_FILL,
    border: Color = BOX_BORDER
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (step != null) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(SERIES[0]), contentAlignment = Alignment.Center) {
                Text("$step", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        } else if (marker != null) {
            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(marker))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(heading, color = INK_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            if (text.isNotBlank()) {
                Text(text, color = INK_SECONDARY, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

private fun DrawScope.drawArrowHead(tip: Offset, direction: Offset) {
    val length = 6.dp.toPx()
    val halfWidth = 4.dp.toPx()
    val base = tip - direction * length
    val normal = Offset(-direction.y, direction.x) * halfWidth
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + normal.x, base.y + normal.y)
        lineTo(base.x - normal.x, base.y - normal.y)
        close()
    }
    drawPath(head, ARROW)
}

/** Search photos in a row; tapping one opens the page it comes from. */
@Composable
private fun PhotoStrip(photos: List<VisualPhoto>) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        photos.forEach { photo ->
            Column(Modifier.width(128.dp)) {
                RemoteImage(
                    url = photo.imageUrl,
                    description = photo.title,
                    modifier = Modifier
                        .size(width = 128.dp, height = 92.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BOX_FILL)
                        .clickable(enabled = isWebUrl(photo.pageUrl)) { runCatching { uriHandler.openUri(photo.pageUrl) } }
                )
                if (photo.source.isNotBlank()) {
                    Text(
                        photo.source, color = INK_MUTED, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, description: String, modifier: Modifier) {
    val image by produceState(initialValue = RemoteImages.cached(url), url) {
        if (value == null) value = RemoteImages.load(url)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description.ifBlank { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Photo, contentDescription = null, tint = Color(0x4DFFFFFF), modifier = Modifier.size(22.dp))
        }
    }
}

/** Downloads and caches the few small photos shown with search answers (no image library needed for that). */
private object RemoteImages {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_SIDE_PX = 512
    private const val USER_AGENT = "Mozilla/5.0 (Android 15; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!isWebUrl(url)) return@withContext null
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || body.contentLength() > MAX_BYTES) return@use null
                val source = body.source()
                if (source.request(MAX_BYTES + 1)) return@use null
                decode(source.readByteArray())
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

private fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

/** What TalkBack reads for a chart: its kind, title and every value. */
private fun describe(visual: ToolVisual): String {
    val kind = when (visual.kind) {
        VisualKind.LINE_CHART -> "Line chart"
        VisualKind.PIE_CHART -> "Pie chart"
        else -> "Bar chart"
    }
    val points = visual.values.indices.joinToString("; ") { i ->
        "${visual.labels.getOrElse(i) { "" }} ${formatValue(visual.values[i], visual.unit)}"
    }
    return "$kind: ${visual.title}. $points"
}

/** 3 to 6 round y-axis values (steps of 1, 2, 2.5 or 5 times a power of ten) covering [minValue]..[maxValue]. */
internal fun niceTicks(minValue: Double, maxValue: Double, target: Int = 4): List<Double> {
    var lo = minValue
    var hi = maxValue
    if (hi - lo < 1e-12) {
        val pad = if (hi == 0.0) 1.0 else abs(hi) * 0.1
        lo -= pad
        hi += pad
    }
    val rawStep = (hi - lo) / target
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rawStep * (1 - 1e-9) }
    val first = floor(lo / step + 1e-9) * step
    val last = ceil(hi / step - 1e-9) * step
    val count = ((last - first) / step).roundToInt()
    return (0..count).map { k -> (first + k * step).let { if (abs(it) < step * 1e-9) 0.0 else it } }
}

/** A value with its unit: "$1,200", "45%", "20°C", "3.4M km". */
internal fun formatValue(value: Double, unit: String): String {
    val number = formatNumber(value)
    val u = unit.trim()
    return when {
        u.isEmpty() -> number
        u in PREFIX_UNITS -> if (number.startsWith("-")) "-$u${number.drop(1)}" else "$u$number"
        u == "%" || u.startsWith("°") -> "$number$u"
        else -> "$number $u"
    }
}

/** Compact from 10,000 up (12.5K, 3.4M, 1.2B), otherwise up to 3 significant decimals, in the phone's number format. */
internal fun formatNumber(value: Double): String {
    val size = abs(value)
    val (scaled, suffix) = when {
        size >= 1e12 -> value / 1e12 to "T"
        size >= 1e9 -> value / 1e9 to "B"
        size >= 1e6 -> value / 1e6 to "M"
        size >= 1e4 -> value / 1e3 to "K"
        else -> value to ""
    }
    val format = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = when {
            suffix.isNotEmpty() -> if (abs(scaled) >= 100) 0 else 1
            size >= 100 -> 1
            size >= 1 -> 2
            else -> 3
        }
    }
    return format.format(scaled) + suffix
}

private val PREFIX_UNITS = setOf("$", "€", "£", "¥", "₹")

/** Categorical series colors (dark steps) in their fixed order. */
private val SERIES = listOf(
    Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
    Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767)
)
private val INK_PRIMARY = Color(0xFFF8FAFC)
private val INK_SECONDARY = Color(0xFFCBD5E1)
private val INK_MUTED = Color(0xFF94A3B8)
private val CHART_SURFACE = Color(0xFF10151C)
private val HAIRLINE = Color(0x1AFFFFFF)
private val GRID = Color(0x14FFFFFF)
private val AXIS = Color(0x33FFFFFF)
private val ARROW = Color(0xCC94A3B8)
private val BOX_FILL = Color(0x12FFFFFF)
private val BOX_BORDER = Color(0x24FFFFFF)
private val TOOLTIP = Color(0xFF232B36)

private val LABEL_STYLE = TextStyle(color = INK_SECONDARY, fontSize = 11.sp)
private val VALUE_STYLE = TextStyle(color = INK_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val AXIS_STYLE = TextStyle(color = INK_MUTED, fontSize = 10.sp)

private val BAR_ROW = 24.dp
private val BAR_THICKNESS = 12.dp
private val DONUT = 104.dp
private val BOX_GAP = 6.dp
