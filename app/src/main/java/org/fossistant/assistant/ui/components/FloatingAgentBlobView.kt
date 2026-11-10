package org.fossistant.assistant.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot

/**
 * Floating blob shown on top of other apps during Agent Mode and live screen sharing. It uses the same
 * [BlobPhysics] and [BlobRenderer] as the in-app [CosmicAiBlob], in the user's theme colors, so it looks and
 * moves the same.
 *
 * Tapping the blob stops Agent Mode / screen sharing; dragging moves it.
 */
@SuppressLint("ViewConstructor")
class FloatingAgentBlobView(
    context: Context,
    private val onStop: () -> Unit
) : View(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val physics = BlobPhysics().apply {
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    private val renderer = BlobRenderer().apply {
        val theme = org.fossistant.assistant.FossistantApp.currentMoodTheme
        setPalette(
            BlobPalette(
                primary = theme.primaryAccent.toArgb(),
                secondary = theme.secondaryAccent.toArgb(),
                tertiary = theme.tertiaryAccent.toArgb(),
                core = theme.coreNebulaColor.toArgb()
            )
        )
    }
    private var lastFrameNanos = 0L
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            physics.step(if (lastFrameNanos == 0L) 0.016f else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
            lastFrameNanos = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Updates the activity state (reasoning / talking / listening / idle) and audio levels. Safe to call from
     * any thread; the next frame picks the values up.
     */
    fun updateState(
        state: BlobActivityState,
        voiceRmsLevel: Float = 0f,
        ttsAudioLevel: Float = 0f
    ) {
        physics.state = state
        physics.micLevel = if (voiceRmsLevel > 1.5f) ((voiceRmsLevel - 24f) / 46f).coerceIn(0f, 1f) else voiceRmsLevel.coerceIn(0f, 1f)
        physics.ttsLevel = ttsAudioLevel.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), physics, 0.3f, resources.displayMetrics.density)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val params = layoutParams as? WindowManager.LayoutParams

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (params != null) {
                    initialX = params.x
                    initialY = params.y
                }
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                    if (params != null && wm != null) {
                        // Gravity is TOP or END. For END gravity, moving right decreases x, left increases x.
                        params.x = (initialX - dx).toInt().coerceAtLeast(0)
                        params.y = (initialY + dy).toInt().coerceAtLeast(0)
                        try {
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) {}
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    performClick()
                    onStop()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
