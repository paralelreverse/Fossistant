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
     * Finds a node matching [queryText] (case-insensitive) and performs a click on it or its
     * nearest clickable parent.
     */
    fun clickNodeByText(queryText: String): Boolean {
        if (queryText.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val cleanQuery = queryText.trim().lowercase()

        fun clickNearestAncestor(start: AccessibilityNodeInfo?): Boolean {
            var current = start
            while (current != null) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                current = current.parent
            }
            return false
        }

        // 0. Exact label match first, so "OK" never taps "Book" and "Pay" never taps "PayPal".
        var exactClicked = false
        fun searchExact(node: AccessibilityNodeInfo?) {
            if (node == null || exactClicked) return
            val label = (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim()?.lowercase()
            if (label == cleanQuery && clickNearestAncestor(node)) {
                exactClicked = true
                return
            }
            for (i in 0 until node.childCount) {
                if (exactClicked) break
                searchExact(node.getChild(i))
            }
        }
        searchExact(root)
        if (exactClicked) return true

        // 1. Try finding by AccessibilityManager text match
        val matches = root.findAccessibilityNodeInfosByText(cleanQuery)
        for (node in matches) {
            if (clickNearestAncestor(node)) return true
        }

        // 2. Fallback search through all nodes inspecting text, contentDescription, and hintText
        var clicked = false
        fun searchSubstring(node: AccessibilityNodeInfo?) {
            if (node == null || clicked) return
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            val hint = node.hintText?.toString()?.lowercase()
            if ((text != null && text.contains(cleanQuery)) ||
                (desc != null && desc.contains(cleanQuery)) ||
                (hint != null && hint.contains(cleanQuery))
            ) {
                if (clickNearestAncestor(node)) {
                    clicked = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                if (clicked) break
                searchSubstring(node.getChild(i))
            }
        }
        searchSubstring(root)
        return clicked
    }

    /**
     * Types [text] into an editable input field on the active screen.
     * If [targetField] is provided, attempts to target that field; otherwise uses active focus or the first editable node.
     */
    fun inputTextIntoNode(text: String, targetField: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        if (!targetField.isNullOrBlank()) {
            val cleanTarget = targetField.trim().lowercase()
            val matches = root.findAccessibilityNodeInfosByText(targetField)
            for (node in matches) {
                if (node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    return true
                }
            }
            var matchedEditable: AccessibilityNodeInfo? = null
            fun findMatchingEditable(node: AccessibilityNodeInfo?) {
                if (node == null || matchedEditable != null) return
                if (node.isEditable) {
                    val label = listOfNotNull(node.text, node.contentDescription, node.hintText)
                        .joinToString(" ").lowercase()
                    if (label.contains(cleanTarget)) {
                        matchedEditable = node
                        return
                    }
                }
                for (i in 0 until node.childCount) {
                    if (matchedEditable != null) break
                    findMatchingEditable(node.getChild(i))
                }
            }
            findMatchingEditable(root)
            if (matchedEditable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true) {
                return true
            }
        }

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return true
        }

        var firstEditable: AccessibilityNodeInfo? = null
        fun findFirstEditable(node: AccessibilityNodeInfo?) {
            if (node == null || firstEditable != null) return
            if (node.isEditable) {
                firstEditable = node
                return
            }
            for (i in 0 until node.childCount) {
                if (firstEditable != null) break
                findFirstEditable(node.getChild(i))
            }
        }
        findFirstEditable(root)
        return firstEditable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ?: false
    }

    /**
     * Scrolls the first scrollable container on the active screen.
     */
    fun scrollActiveScreen(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                findScrollable(node.getChild(i))?.let { return it }
            }
            return null
        }
        val scrollable = findScrollable(root) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
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
