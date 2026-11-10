package org.fossistant.assistant.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * On-Device Agentic Accessibility Service enabling Gemma 4 E2B QAT to read the active screen,
 * click UI buttons, scroll lists, and perform multi-step device control tasks locally.
 */
class FossistantAgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        // Bound by the system: a moment when starting the microphone foreground service is allowed.
        VoiceWakeWordService.startIfEnabled(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven updates are only inspected on-demand when an agent tool call executes,
        // avoiding background CPU wakeups and preserving battery life.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideFloatingAgentBlob()
        if (activeInstance === this) {
            activeInstance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideFloatingAgentBlob()
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    /**
     * Collects a compact text & interactive element summary of the active window,
     * including buttons, input fields, scrollable containers, and package name.
     */
    fun dumpCurrentScreenSummary(maxNodes: Int = 80): String {
        val root = rootInActiveWindow ?: return "No active screen window available."
        val sb = StringBuilder()
        sb.appendLine("Active App: ${root.packageName ?: "unknown"}")
        var count = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || count >= maxNodes) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val hint = node.hintText?.toString()?.trim()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val label = text ?: desc ?: hint
            if (!label.isNullOrEmpty() || isEditable || isClickable) {
                count++
                val tag = when {
                    isEditable -> "[InputField/Editable]"
                    isClickable -> "[Button/Clickable]"
                    isScrollable -> "[ScrollableList]"
                    else -> "[Text]"
                }
                sb.appendLine("$tag ${label ?: "(unlabeled)"}")
            }
            for (i in 0 until node.childCount) {
                if (count >= maxNodes) break
                visit(node.getChild(i))
            }
        }

        visit(root)
        return sb.toString().trim()
    }

    /**
     * Captures the current screen as a JPEG (longest side [maxDimension]) for Gemma's vision encoder.
     * Needs Android 11+; returns null when unavailable or refused.
     */
    suspend fun captureScreenshotJpeg(maxDimension: Int = 768): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val jpeg = runCatching {
                            val bitmap = screenshot.hardwareBuffer.use { hwBuffer ->
                                val hardware = android.graphics.Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                try {
                                    hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hardware?.recycle()
                                }
                            }
                            bitmap?.let {
                                try {
                                    val scale = minOf(1f, maxDimension.toFloat() / maxOf(it.width, it.height))
                                    val scaled = if (scale < 1f) {
                                        android.graphics.Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                                    } else {
                                        it
                                    }
                                    try {
                                        val out = java.io.ByteArrayOutputStream()
                                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                        out.toByteArray()
                                    } finally {
                                        if (scaled !== it) scaled.recycle()
                                    }
                                } finally {
                                    it.recycle()
                                }
                            }
                        }.getOrNull()
                        if (continuation.isActive) continuation.resumeWith(Result.success(jpeg))
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(null))
                    }
                }
            )
        }
    }

    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Lock screen, screenshot, notifications, quick settings, recents, ... (see [SystemControls]). */
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    private var floatingBlobView: org.fossistant.assistant.ui.components.FloatingAgentBlobView? = null
    private var windowManager: android.view.WindowManager? = null

    /**
     * Shows a small floating circular Cosmic AI Blob in the top-right corner of the screen
     * to signify that Phone Use / Agent Mode is controlling the phone.
     * Tapping the blob triggers [onStop].
     */
    fun showFloatingAgentBlob(onStop: () -> Unit) {
        if (floatingBlobView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager ?: return
        windowManager = wm

        val density = resources.displayMetrics.density
        val sizePx = (64 * density).toInt()

        val params = android.view.WindowManager.LayoutParams(
            sizePx,
            sizePx,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            x = (16 * density).toInt()
            y = (42 * density).toInt()
        }

        val blobView = org.fossistant.assistant.ui.components.FloatingAgentBlobView(this) {
            hideFloatingAgentBlob()
            onStop()
        }
        floatingBlobView = blobView

        try {
            wm.addView(blobView, params)
        } catch (e: Exception) {
            android.util.Log.e("AgentAccessibility", "Failed to add floating agent blob view", e)
        }
    }

    /**
     * Hides and destroys the floating agent blob view.
     */
    fun hideFloatingAgentBlob() {
        floatingBlobView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                android.util.Log.e("AgentAccessibility", "Failed to remove floating agent blob view", e)
            }
            floatingBlobView = null
        }
    }

    /**
     * Updates the dynamic animation state (thinking, talking, listening, idle)
     * and audio levels of the floating blob.
     */
    fun updateBlobState(
        activityState: org.fossistant.assistant.ui.components.BlobActivityState,
        voiceRmsLevel: Float = 0f,
        ttsAudioLevel: Float = 0f
    ) {
        floatingBlobView?.updateState(activityState, voiceRmsLevel, ttsAudioLevel)
    }

    companion object {
        @Volatile
        var activeInstance: FossistantAgentAccessibilityService? = null
            private set

        val isAgentModeActive: Boolean
            get() = activeInstance?.floatingBlobView != null

        fun showFloatingBlobIfServiceActive(onStop: () -> Unit): Boolean {
            val service = activeInstance ?: return false
            service.showFloatingAgentBlob(onStop)
            return true
        }

        fun hideFloatingBlob() {
            activeInstance?.hideFloatingAgentBlob()
        }

        fun updateFloatingBlobState(
            activityState: org.fossistant.assistant.ui.components.BlobActivityState,
            voiceRmsLevel: Float = 0f,
            ttsAudioLevel: Float = 0f
        ) {
            activeInstance?.updateBlobState(activityState, voiceRmsLevel, ttsAudioLevel)
        }
    }
}
