package org.fossistant.assistant.ui.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.ui.AssistantUiState
import org.fossistant.assistant.ui.AssistantViewModel
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Pure geometry and JPEG region-cropping helpers for Circle / Draw to Search.
 */
object CircleToSearchCropper {

    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)

        fun isValid(minFraction: Float = 0.03f): Boolean =
            left in 0f..1f &&
                top in 0f..1f &&
                right in 0f..1f &&
                bottom in 0f..1f &&
                width >= minFraction &&
                height >= minFraction
    }

    /**
     * Computes a normalized [0f..1f] bounding rectangle around a user's drawn stroke or highlight.
     * Returns null when there are too few points or the gesture is an accidental tiny tap.
     */
    fun boundingRectFromPoints(
        points: List<Pair<Float, Float>>,
        canvasWidth: Float,
        canvasHeight: Float,
        paddingFraction: Float = 0.04f,
        minFraction: Float = 0.03f
    ): NormalizedRect? {
        if (points.size < 2 || canvasWidth <= 0f || canvasHeight <= 0f) return null

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((px, py) in points) {
            if (!px.isFinite() || !py.isFinite()) continue
            val cx = px.coerceIn(0f, canvasWidth)
            val cy = py.coerceIn(0f, canvasHeight)
            if (cx < minX) minX = cx
            if (cy < minY) minY = cy
            if (cx > maxX) maxX = cx
            if (cy > maxY) maxY = cy
        }
        if (!minX.isFinite() || !maxX.isFinite()) return null

        val rawLeft = (minX / canvasWidth).coerceIn(0f, 1f)
        val rawTop = (minY / canvasHeight).coerceIn(0f, 1f)
        val rawRight = (maxX / canvasWidth).coerceIn(0f, 1f)
        val rawBottom = (maxY / canvasHeight).coerceIn(0f, 1f)

        // Require a deliberate circle or highlight stroke along at least one axis before padding.
        val rawSpan = maxOf(rawRight - rawLeft, rawBottom - rawTop)
        if (rawSpan < minFraction) return null

        val pad = paddingFraction.coerceAtLeast(0f)
        val candidate = NormalizedRect(
            left = (rawLeft - pad).coerceIn(0f, 1f),
            top = (rawTop - pad).coerceIn(0f, 1f),
            right = (rawRight + pad).coerceIn(0f, 1f),
            bottom = (rawBottom + pad).coerceIn(0f, 1f)
        )
        return candidate.takeIf { it.isValid(minFraction) }
    }

    /**
     * Converts a normalized selection into `[x, y, width, height]` bitmap pixel coordinates,
     * expanding narrow highlights around their center to satisfy [minSidePx] within bitmap bounds.
     */
    fun pixelCropRect(
        norm: NormalizedRect,
        imageWidth: Int,
        imageHeight: Int,
        minSidePx: Int = 48
    ): IntArray? {
        if (imageWidth <= 0 || imageHeight <= 0 || !norm.isValid()) return null

        var left = (norm.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        var top = (norm.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        var right = (norm.right * imageWidth).toInt().coerceIn(left + 1, imageWidth)
        var bottom = (norm.bottom * imageHeight).toInt().coerceIn(top + 1, imageHeight)

        val targetMinW = minSidePx.coerceIn(1, imageWidth)
        val targetMinH = minSidePx.coerceIn(1, imageHeight)

        if (right - left < targetMinW) {
            val centerX = (left + right) / 2
            left = (centerX - targetMinW / 2).coerceIn(0, imageWidth - targetMinW)
            right = left + targetMinW
        }
        if (bottom - top < targetMinH) {
            val centerY = (top + bottom) / 2
            top = (centerY - targetMinH / 2).coerceIn(0, imageHeight - targetMinH)
            bottom = top + targetMinH
        }

        return intArrayOf(left, top, right - left, bottom - top)
    }

    /**
     * Decodes [sourceJpeg], crops the normalized selection, and re-encodes the region as JPEG.
     */
    fun cropScreenshotJpeg(
        sourceJpeg: ByteArray,
        norm: NormalizedRect,
        quality: Int = 92
    ): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size) ?: return null
        return try {
            val rect = pixelCropRect(norm, bitmap.width, bitmap.height) ?: return null
            val cropped = Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2], rect[3])
            try {
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                out.toByteArray()
            } finally {
                if (cropped !== bitmap) cropped.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

private data class QuickRegionAction(
    val label: String,
    val icon: ImageVector,
    val prompt: String
)

/**
 * Full-screen interactive Circle / Draw to Search overlay over a captured screenshot.
 * Lets users circle or highlight any region and run visual search, product lookup,
 * translation, text extraction, or a custom question using only the cropped region.
 */
@Composable
fun CircleToSearchOverlay(
    screenshotJpeg: ByteArray,
    moodTheme: BlobMoodTheme,
    targetLanguage: String = "English",
    onDismiss: () -> Unit,
    onQueryCroppedRegion: (croppedJpeg: ByteArray, prompt: String) -> Unit
) {
    var bitmapFailed by remember(screenshotJpeg) { mutableStateOf(false) }
    val imageBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, screenshotJpeg) {
        val decoded = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(screenshotJpeg, 0, screenshotJpeg.size)?.asImageBitmap()
        }
        bitmapFailed = decoded == null
        value = decoded
    }
    if (bitmapFailed) {
        LaunchedEffect(screenshotJpeg) { onDismiss() }
        return
    }
    val loadedBitmap = imageBitmap
    if (loadedBitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        )
        return
    }

    val scope = rememberCoroutineScope()
    val strokePoints = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedRect by remember { mutableStateOf<CircleToSearchCropper.NormalizedRect?>(null) }
    var customQuery by remember { mutableStateOf("") }
    var cropError by remember { mutableStateOf<String?>(null) }
    var isCropping by remember { mutableStateOf(false) }

    val effectiveLanguage = if (targetLanguage.isBlank() || targetLanguage.equals("Automatic", ignoreCase = true)) {
        "English"
    } else {
        targetLanguage
    }

    val quickActions = remember(effectiveLanguage) {
        listOf(
            QuickRegionAction(
                label = "Identify",
                icon = Icons.Default.Search,
                prompt = "Identify what is in this circled part of my screen and explain or look up key details."
            ),
            QuickRegionAction(
                label = "Product Lookup",
                icon = Icons.Default.ShoppingBag,
                prompt = "Identify the product, brand, or item shown in this selected region and look up details or options using search_web if helpful."
            ),
            QuickRegionAction(
                label = "Translate",
                icon = Icons.Default.Translate,
                prompt = "Translate all text in this selected screen region into $effectiveLanguage and explain it briefly."
            ),
            QuickRegionAction(
                label = "Extract Text",
                icon = Icons.Default.TextFields,
                prompt = "Transcribe the exact text visible inside this selected region of the screen."
            )
        )
    }

    fun submitRegionQuery(prompt: String) {
        if (isCropping) return
        val rect = selectedRect ?: return
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        isCropping = true
        scope.launch {
            val cropped = withContext(Dispatchers.Default) {
                CircleToSearchCropper.cropScreenshotJpeg(screenshotJpeg, rect)
            }
            isCropping = false
            if (cropped != null) {
                onQueryCroppedRegion(cropped, cleanPrompt)
            } else {
                cropError = "Couldn't crop the selected region. Please draw again."
            }
        }
    }

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // Fit the screenshot without aspect distortion so canvas coords map 1:1 to bitmap pixels.
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val scale = min(
                constraints.maxWidth.toFloat() / loadedBitmap.width.toFloat(),
                constraints.maxHeight.toFloat() / loadedBitmap.height.toFloat()
            ).coerceAtLeast(0.01f)
            val displayWidthDp = with(density) { (loadedBitmap.width * scale).toDp() }
            val displayHeightDp = with(density) { (loadedBitmap.height * scale).toDp() }

            Box(
                modifier = Modifier
                    .size(displayWidthDp, displayHeightDp)
                    .clipToBounds()
                    .onSizeChanged { canvasSize = it }
            ) {
                Image(
                    bitmap = loadedBitmap,
                    contentDescription = "Screen capture for Circle to Search",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(screenshotJpeg) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    selectedRect = null
                                    cropError = null
                                    strokePoints.clear()
                                    strokePoints.add(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    strokePoints.add(change.position)
                                },
                                onDragEnd = {
                                    val computed = CircleToSearchCropper.boundingRectFromPoints(
                                        points = strokePoints.map { it.x to it.y },
                                        canvasWidth = canvasSize.width.toFloat(),
                                        canvasHeight = canvasSize.height.toFloat()
                                    )
                                    if (computed != null) {
                                        selectedRect = computed
                                    } else {
                                        strokePoints.clear()
                                    }
                                },
                                onDragCancel = {
                                    strokePoints.clear()
                                }
                            )
                        }
                ) {
                    val activeRect = selectedRect
                    val cornerRadiusPx = 16.dp.toPx()

                    if (activeRect != null) {
                        val boxRect = Rect(
                            left = activeRect.left * size.width,
                            top = activeRect.top * size.height,
                            right = activeRect.right * size.width,
                            bottom = activeRect.bottom * size.height
                        )
                        val cutout = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = boxRect,
                                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                                )
                            )
                        }
                        clipPath(cutout, clipOp = ClipOp.Difference) {
                            drawRect(Color.Black.copy(alpha = 0.60f))
                        }

                        val borderBrush = Brush.linearGradient(
                            colors = listOf(
                                moodTheme.primaryAccent,
                                moodTheme.secondaryAccent,
                                Color.White
                            ),
                            start = boxRect.topLeft,
                            end = boxRect.bottomRight
                        )
                        drawRoundRect(
                            brush = borderBrush,
                            topLeft = boxRect.topLeft,
                            size = Size(boxRect.width, boxRect.height),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    } else {
                        drawRect(Color.Black.copy(alpha = 0.34f))
                    }

                    if (strokePoints.size >= 2) {
                        val path = Path().apply {
                            moveTo(strokePoints.first().x, strokePoints.first().y)
                            for (i in 1 until strokePoints.size) {
                                val prev = strokePoints[i - 1]
                                val curr = strokePoints[i]
                                quadraticTo(
                                    prev.x,
                                    prev.y,
                                    (prev.x + curr.x) / 2f,
                                    (prev.y + curr.y) / 2f
                                )
                            }
                            lineTo(strokePoints.last().x, strokePoints.last().y)
                        }
                        val strokeBrush = Brush.linearGradient(
                            colors = listOf(
                                moodTheme.primaryAccent,
                                moodTheme.secondaryAccent,
                                Color.White
                            )
                        )
                        drawPath(
                            path = path,
                            brush = strokeBrush,
                            alpha = 0.30f,
                            style = Stroke(
                                width = 16.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        drawPath(
                            path = path,
                            brush = strokeBrush,
                            alpha = 0.96f,
                            style = Stroke(
                                width = 4.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }

        // Top floating header bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xD90E1320),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            moodTheme.primaryAccent.copy(alpha = 0.65f),
                            moodTheme.secondaryAccent.copy(alpha = 0.55f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = moodTheme.secondaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (selectedRect == null) {
                            "Circle or highlight anything on screen"
                        } else {
                            "Region selected — pick an action"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedRect != null) {
                        TextButton(
                            onClick = {
                                selectedRect = null
                                cropError = null
                                strokePoints.clear()
                            },
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "Redraw",
                                color = moodTheme.primaryAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xD90E1320))
                    .border(1.dp, Color(0x44FFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Circle to Search",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Bottom query & action bar once a region is circled or highlighted
        AnimatedVisibility(
            visible = selectedRect != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xEB0E1320),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            moodTheme.primaryAccent.copy(alpha = 0.6f),
                            moodTheme.secondaryAccent.copy(alpha = 0.5f)
                        )
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cropError?.let { err ->
                        Text(
                            text = err,
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickActions.forEach { action ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFF1A2236),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    moodTheme.primaryAccent.copy(alpha = 0.40f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { submitRegionQuery(action.prompt) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = null,
                                        tint = moodTheme.secondaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = action.label,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customQuery,
                            onValueChange = { customQuery = it },
                            placeholder = {
                                Text(
                                    text = "Ask about this circled area...",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 14.sp
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { submitRegionQuery(customQuery) }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = moodTheme.primaryAccent,
                                unfocusedBorderColor = Color(0x44FFFFFF),
                                cursorColor = moodTheme.secondaryAccent,
                                focusedContainerColor = Color(0xFF141B2B),
                                unfocusedContainerColor = Color(0xFF141B2B)
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        )

                        val canSend = customQuery.isNotBlank()
                        IconButton(
                            onClick = { submitRegionQuery(customQuery) },
                            enabled = canSend,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) {
                                        Brush.linearGradient(
                                            listOf(moodTheme.primaryAccent, moodTheme.secondaryAccent)
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color(0xFF1E293B), Color(0xFF1E293B))
                                        )
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Circle to Search query",
                                tint = if (canSend) Color.White else Color(0xFF64748B),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared screen-action dialog used by both [AssistOverlayActivity] and
 * [org.fossistant.assistant.service.FossistantVoiceInteractionSession].
 */
@Composable
internal fun ScreenShareChoiceDialog(
    hasAssistScreenshot: Boolean,
    canCircleToSearch: Boolean = hasAssistScreenshot,
    onCircleToSearch: () -> Unit = {},
    onAskAboutScreen: () -> Unit,
    onShareLive: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = { Text("Your screen", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (canCircleToSearch) {
                        "Circle a specific part of the screen for a targeted search, ask about the whole screen, or share live."
                    } else {
                        "Share your screen live: I shrink to a bubble, you use the phone, and I see the screen every time you ask something."
                    },
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
                if (canCircleToSearch) {
                    Button(
                        onClick = onCircleToSearch,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E2768),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Circle / Draw to Search")
                    }
                }
                if (hasAssistScreenshot) {
                    Button(
                        onClick = onAskAboutScreen,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A2236),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ask about full screen")
                    }
                }
                Button(
                    onClick = onShareLive,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A2236),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share live")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Coordinates screen-choice dialog, Accessibility screenshot capture (temporarily hiding the
 * overlay so Fossistant does not appear in its own capture), and [CircleToSearchOverlay].
 */
@Composable
internal fun ScreenSearchAndCircleHost(
    viewModel: AssistantViewModel,
    uiState: AssistantUiState,
    onShareLive: () -> Unit,
    onCircleOverlayActiveChange: (Boolean) -> Unit = {},
    overlayContent: @Composable (openScreenTools: () -> Unit) -> Unit
) {
    var showScreenChoice by remember { mutableStateOf(false) }
    var isCapturingScreenshot by remember { mutableStateOf(false) }
    var circleSearchBytes by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    val pauseVoice = isCapturingScreenshot || circleSearchBytes != null
    LaunchedEffect(pauseVoice) {
        onCircleOverlayActiveChange(pauseVoice)
    }

    if (showScreenChoice) {
        ScreenShareChoiceDialog(
            hasAssistScreenshot = viewModel.hasAssistScreenshot(),
            canCircleToSearch = viewModel.canUseCircleToSearch(),
            onCircleToSearch = {
                showScreenChoice = false
                viewModel.stopVoiceAndTts()
                viewModel.prewarmVisionEngine()
                val needsLiveCapture = !viewModel.hasAssistScreenshot()
                if (needsLiveCapture) isCapturingScreenshot = true
                viewModel.ensureScreenshotForCircleToSearch { bytes ->
                    isCapturingScreenshot = false
                    if (bytes != null) {
                        circleSearchBytes = bytes
                    } else {
                        viewModel.reportError(
                            "Screenshot unavailable. Enable Fossistant Accessibility Service or re-open via long-press Home."
                        )
                    }
                }
            },
            onAskAboutScreen = {
                showScreenChoice = false
                scope.launch {
                    if (viewModel.attachAssistScreenshotAsync()) {
                        viewModel.sendPrompt(
                            "What's on my screen? Help me with it.",
                            stayInVoiceMode = true,
                            forceSpeakResponse = true
                        )
                    } else {
                        viewModel.reportError(
                            "No screenshot from the assist gesture. Turn on \"Use screenshot\" in Settings > Apps > Default apps > Digital assistant, or use live sharing."
                        )
                    }
                }
            },
            onShareLive = {
                showScreenChoice = false
                viewModel.prewarmVisionEngine()
                onShareLive()
            },
            onDismiss = { showScreenChoice = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (isCapturingScreenshot) 0f else 1f }
        ) {
            overlayContent { showScreenChoice = true }
        }

        circleSearchBytes?.let { screenshot ->
            CircleToSearchOverlay(
                screenshotJpeg = screenshot,
                moodTheme = uiState.moodTheme,
                targetLanguage = uiState.selectedLanguage.englishName,
                onDismiss = { circleSearchBytes = null },
                onQueryCroppedRegion = { croppedJpeg, prompt ->
                    circleSearchBytes = null
                    scope.launch {
                        if (viewModel.attachCroppedScreenshotRegionAsync(croppedJpeg)) {
                            viewModel.sendPrompt(
                                rawPrompt = prompt,
                                stayInVoiceMode = true,
                                forceSpeakResponse = true
                            )
                        } else {
                            viewModel.reportError("Couldn't attach the circled region. Please try again.")
                        }
                    }
                }
            )
        }
    }
}
