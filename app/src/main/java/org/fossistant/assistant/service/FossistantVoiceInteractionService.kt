package org.fossistant.assistant.service

import android.Manifest
import android.app.Application
import android.app.DirectAction
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.features.LiveScreenShareService
import org.fossistant.assistant.ui.AssistantViewModel
import org.fossistant.assistant.ui.overlay.AssistOverlayActivity
import org.fossistant.assistant.ui.overlay.AssistOverlayScreen
import org.fossistant.assistant.ui.overlay.ScreenShareChoiceDialog
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Registers Fossistant as a system-wide Android Digital Assistant (`android.app.role.ASSISTANT`),
 * providing 1:1 compatibility with all system Assistant hooks:
 * - `onHandleAssist`: Extracts `AssistStructure` view tree & `AssistContent` web URI / JSON-LD.
 * - `onHandleScreenshot`: Receives the foreground screen `Bitmap` directly from WindowManager.
 * - `requestDirectActions` & `performDirectAction`: Queries and executes in-app `DirectAction`
 *   tasks exposed by the foreground `Activity.onGetDirectActions`.
 */
class FossistantVoiceInteractionService : VoiceInteractionService() {
    /**
     * The system binds the default assistant's service at boot. Starting the wake word's microphone
     * foreground service from here is allowed; from Application.onCreate in the background it is not.
     */
    override fun onReady() {
        super.onReady()
        VoiceWakeWordService.startIfEnabled(this)
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        runCatching {
            showSession(
                Bundle(),
                VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            )
        }
    }
}

class FossistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return FossistantVoiceInteractionSession(this)
    }
}

class FossistantVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val directActionExecutor = Executors.newSingleThreadExecutor()

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val viewModel: AssistantViewModel by lazy {
        AssistantViewModel(context.applicationContext as Application)
    }

    private var isSessionShown by mutableStateOf(false)

    init {
        setTheme(org.fossistant.assistant.R.style.Theme_Fossistant_Overlay)
    }

    override fun onCreate() {
        super.onCreate()
        activeSession = this
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Render our Compose overlay directly in VoiceInteractionSession's privileged TYPE_VOICE_INTERACTION window.
        setUiEnabled(true)
    }

    private fun configureSessionWindow() {
        window?.window?.let { win ->
            win.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            win.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            win.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            )
            win.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            @Suppress("DEPRECATION")
            win.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            win.navigationBarColor = android.graphics.Color.TRANSPARENT
            win.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            )
            win.decorView.let { decor ->
                decor.setViewTreeLifecycleOwner(this)
                decor.setViewTreeViewModelStoreOwner(this)
                decor.setViewTreeSavedStateRegistryOwner(this)
                decor.findViewById<View>(android.R.id.content)?.let { content ->
                    content.setViewTreeLifecycleOwner(this)
                    content.setViewTreeViewModelStoreOwner(this)
                    content.setViewTreeSavedStateRegistryOwner(this)
                }
            }
        }
    }

    override fun onCreateContentView(): View {
        configureSessionWindow()
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FossistantVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@FossistantVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@FossistantVoiceInteractionSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setContent {
                val uiState by viewModel.uiState.collectAsState()

                // Hands-free continuous turn-taking while the assistant session popup is shown
                LaunchedEffect(
                    uiState.isListeningVoice,
                    uiState.isVoiceMutedByUser,
                    uiState.isGenerating,
                    uiState.isTtsSpeaking,
                    uiState.isReasoning,
                    isSessionShown
                ) {
                    if (isSessionShown &&
                        !uiState.isListeningVoice &&
                        !uiState.isVoiceMutedByUser &&
                        !uiState.isGenerating &&
                        !uiState.isTtsSpeaking &&
                        !uiState.isReasoning
                    ) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startVoiceCapture()
                        }
                    }
                }

                // Safety net: if popup sits idle with the microphone off, start listening again.
                LaunchedEffect(isSessionShown) {
                    while (isSessionShown) {
                        delay(1200L)
                        val s = viewModel.uiState.value
                        val idle = !s.isVoiceMutedByUser && !s.isGenerating && !s.isTtsSpeaking && !s.isReasoning
                        if (idle && !viewModel.sttEngine.isListening.value &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startVoiceCapture()
                        }
                    }
                }

                var showScreenChoice by remember { mutableStateOf(false) }
                if (showScreenChoice) {
                    ScreenShareChoiceDialog(
                        hasAssistScreenshot = FossistantApp.lastCapturedScreenshotJpeg != null &&
                            System.currentTimeMillis() - FossistantApp.lastCapturedScreenshotAtMs < 10 * 60 * 1000L,
                        onAskAboutScreen = {
                            showScreenChoice = false
                            if (viewModel.attachAssistScreenshot()) {
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
                        },
                        onShareLive = {
                            showScreenChoice = false
                            startLiveScreenShareFromSession()
                        },
                        onDismiss = { showScreenChoice = false }
                    )
                }

                AssistOverlayScreen(
                    uiState = uiState,
                    onScreenShareAction = { showScreenChoice = true },
                    latestAssistantMessage = uiState.messages.lastOrNull { !it.isUser && it.id != "welcome" },
                    onMicMuteToggle = {
                        if (uiState.isListeningVoice) {
                            viewModel.stopVoiceCapture()
                            viewModel.setVoiceMutedByUser(true)
                        } else {
                            viewModel.stopVoiceAndTts()
                            viewModel.setVoiceMutedByUser(false)
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                startVoiceCapture()
                            } else {
                                launchOverlayActivityForPermission()
                            }
                        }
                    },
                    onSendPrompt = { promptText ->
                        viewModel.sendPrompt(
                            rawPrompt = promptText,
                            stayInVoiceMode = true,
                            forceSpeakResponse = true
                        )
                    },
                    onEndCallAction = {
                        viewModel.stopVoiceAndTts()
                        hide()
                    },
                    onOpenAccessibilitySettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            hide()
                        }
                    },
                    onCameraAction = {
                        if (uiState.isLiveCameraActive) {
                            viewModel.toggleLiveCamera(false)
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.toggleLiveCamera(true)
                            } else {
                                launchOverlayActivityForPermission()
                            }
                        }
                    },
                    onLiveCameraFrameCaptured = { jpegBytes ->
                        viewModel.updateLiveCameraFrameJpeg(jpegBytes)
                    },
                    onCloseLiveCamera = {
                        viewModel.toggleLiveCamera(false)
                    },
                    onSpeakMessage = { text -> viewModel.speakMessage(text) },
                    onDismissError = { viewModel.dismissError() },
                    onBlobClick = {
                        viewModel.onBlobTapped {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                startVoiceCapture()
                            } else {
                                launchOverlayActivityForPermission()
                            }
                        }
                    },
                    onKeyboardToggle = { open ->
                        window?.window?.let { win ->
                            win.clearFlags(
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                            )
                            win.setSoftInputMode(
                                if (open) {
                                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                } else {
                                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                                }
                            )
                        }
                    },
                    onDismiss = {
                        viewModel.stopVoiceAndTts()
                        hide()
                    }
                )
            }
        }
    }

    private fun startVoiceCapture() {
        viewModel.setVoiceListeningState(
            listening = true,
            partialText = "Listening with Whisper...",
            enterVoiceScreenMode = false
        )
        val continuous = FossistantApp.isVoiceDuplexMode
        viewModel.sttEngine.startListening(
            scope = lifecycleScope,
            language = viewModel.uiState.value.selectedLanguage,
            continuous = continuous,
            onPartial = { partial ->
                viewModel.setVoiceListeningState(
                    listening = true,
                    partialText = partial,
                    enterVoiceScreenMode = false
                )
            },
            onError = { error ->
                org.fossistant.assistant.util.AppLogger.w("VoiceInteraction", "Listening stopped: $error")
                viewModel.setVoiceListeningState(false, enterVoiceScreenMode = false)
            },
            onFinalResult = { transcript ->
                if (transcript.isNotBlank()) {
                    if (!continuous) {
                        viewModel.sttEngine.stopListening()
                    }
                    viewModel.sendPrompt(
                        rawPrompt = transcript,
                        stayInVoiceMode = true,
                        forceSpeakResponse = true
                    )
                } else if (!continuous) {
                    viewModel.setVoiceListeningState(false, enterVoiceScreenMode = false)
                }
            }
        )
    }

    private fun startLiveScreenShareFromSession() {
        when {
            FossistantAgentAccessibilityService.activeInstance == null -> viewModel.reportError(
                "Screen sharing needs the Fossistant accessibility service. Enable it in Android Settings > Accessibility."
            )
            !FossistantApp.primaryModelChoice.supportsVision -> viewModel.reportError(
                "${FossistantApp.primaryModelChoice.displayName} can't see images. Switch to Gemma 4 E2B in Settings to share your screen."
            )
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                launchOverlayActivityForPermission()
            else -> {
                viewModel.stopVoiceAndTts()
                FossistantApp.screenShareLanguage = viewModel.uiState.value.selectedLanguage
                LiveScreenShareService.start(context)
                hide()
            }
        }
    }

    /**
     * Used only when runtime permissions (RECORD_AUDIO / CAMERA) have not yet been granted,
     * since only an Activity can host the Android permission grant dialog.
     */
    private fun launchOverlayActivityForPermission() {
        val assistIntent = Intent(context, AssistOverlayActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val started = runCatching {
            startAssistantActivity(assistIntent)
            true
        }.getOrElse {
            runCatching {
                context.startActivity(assistIntent)
                true
            }.getOrDefault(false)
        }
        if (started) {
            hide()
        } else {
            viewModel.reportError("Please open Fossistant and grant microphone permission.")
        }
    }

    override fun onDestroy() {
        isSessionShown = false
        viewModel.stopVoiceAndTts()
        viewModel.resetLiveCameraSession()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
        directActionExecutor.shutdownNow()
        if (activeSession === this) {
            activeSession = null
        }
        super.onDestroy()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // 1. Extract on-screen text hierarchy & structured web URI from the foreground app
        val structure: AssistStructure? = state.assistStructure
        val content: AssistContent? = state.assistContent
        val sb = StringBuilder()

        content?.webUri?.let { uri ->
            sb.appendLine("Active Web URL: $uri")
        }
        content?.structuredData?.let { jsonLd ->
            sb.appendLine("Structured Data: ${jsonLd.take(500)}")
        }
        if (structure != null) {
            val windowCount = structure.windowNodeCount
            for (i in 0 until windowCount) {
                val windowNode = structure.getWindowNodeAt(i)
                sb.appendLine("Window: ${windowNode.title}")
                traverseViewNode(windowNode.rootViewNode, sb, maxChars = 2500)
            }
        }
        if (sb.isNotEmpty()) {
            FossistantApp.lastCapturedScreenContext = sb.toString().trim()
        }

        // 2. Discover in-app DirectActions exposed by the foreground Activity (API 29+)
        val activityId = state.activityId
        if (activityId != null) {
            requestDirectActions(
                activityId,
                CancellationSignal(),
                directActionExecutor
            ) { actions ->
                activeDirectActions.clear()
                activeDirectActions.addAll(actions)
            }
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            // Downscale & compress to JPEG in-memory so Gemma 4 E2B Vision can inspect the screen
            val bos = ByteArrayOutputStream(32_768)
            screenshot.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            FossistantApp.lastCapturedScreenshotJpeg = bos.toByteArray()
            FossistantApp.lastCapturedScreenshotAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Executes a discovered foreground app `DirectAction` by ID (e.g., "play_media", "add_to_cart").
     */
    fun triggerForegroundDirectAction(
        actionId: String,
        argumentsBundle: Bundle = Bundle.EMPTY,
        onResult: (Bundle?) -> Unit = {}
    ): Boolean {
        val target = activeDirectActions.firstOrNull { it.id.equals(actionId, ignoreCase = true) }
            ?: return false
        performDirectAction(
            target,
            argumentsBundle,
            CancellationSignal(),
            directActionExecutor
        ) { resultBundle ->
            onResult(resultBundle)
        }
        return true
    }

    private fun traverseViewNode(
        node: AssistStructure.ViewNode?,
        out: StringBuilder,
        maxChars: Int
    ) {
        if (node == null || out.length >= maxChars) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            out.append(text).append(' ')
        } else if (!desc.isNullOrEmpty()) {
            out.append('[').append(desc).append("] ")
        }
        for (i in 0 until node.childCount) {
            if (out.length >= maxChars) break
            traverseViewNode(node.getChildAt(i), out, maxChars)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // If triggered via voice wake word and the user turned off Voice Trigger in Settings, ignore it
        val isVoiceWake = (args?.getBoolean("voice_trigger", false) == true)
        if (isVoiceWake && !FossistantApp.isVoiceTriggerEnabled) {
            hide()
            return
        }
        org.fossistant.assistant.util.AppLogger.i("VoiceInteraction", "onShow invoked (showFlags=$showFlags, isVoiceWake=$isVoiceWake)")

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        viewModel.resetLiveCameraSession()
        viewModel.setVoiceMutedByUser(false)
        isSessionShown = true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launchOverlayActivityForPermission()
        }
    }

    override fun onHide() {
        isSessionShown = false
        viewModel.stopVoiceAndTts()
        viewModel.resetLiveCameraSession()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        super.onHide()
    }

    override fun onBackPressed() {
        viewModel.stopVoiceAndTts()
        hide()
    }

    companion object {
        @Volatile
        var activeSession: FossistantVoiceInteractionSession? = null
            private set

        val activeDirectActions = CopyOnWriteArrayList<DirectAction>()

        fun dismissActiveSession() {
            runCatching { activeSession?.hide() }
        }
    }
}

/**
 * System RecognitionService implementation required by Android's VoiceInteractionService contract.
 * Powered by WhisperMultilingualSttEngine to deliver speech recognition to any requesting app or system hook.
 */
class FossistantRecognitionService : RecognitionService() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var sttEngine: org.fossistant.assistant.engine.WhisperMultilingualSttEngine? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? FossistantApp
        sttEngine = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(this)
    }

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.readyForSpeech(Bundle.EMPTY)
        listener?.beginningOfSpeech()
        val langCode = intent?.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE) ?: "en"
        val supportedLang = org.fossistant.assistant.model.SupportedLanguage.fromCode(langCode)

        sttEngine?.startListening(
            scope = serviceScope,
            language = supportedLang,
            onPartial = { partial ->
                val bundle = Bundle().apply {
                    putStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(partial)
                    )
                }
                try {
                    listener?.partialResults(bundle)
                } catch (_: Throwable) {}
            },
            onFinalResult = { finalTranscript ->
                val bundle = Bundle().apply {
                    putStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(finalTranscript)
                    )
                }
                try {
                    listener?.endOfSpeech()
                    listener?.results(bundle)
                } catch (_: Throwable) {}
            }
        )
    }

    override fun onCancel(listener: Callback?) {
        sttEngine?.stopListening()
    }

    override fun onStopListening(listener: Callback?) {
        sttEngine?.stopListening()
    }

    override fun onDestroy() {
        sttEngine?.stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }
}
