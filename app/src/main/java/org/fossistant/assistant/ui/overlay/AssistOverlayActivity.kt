package org.fossistant.assistant.ui.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.model.ModelTier
import org.fossistant.assistant.service.FossistantAgentAccessibilityService
import org.fossistant.assistant.ui.AppIconSwitcher
import org.fossistant.assistant.ui.AssistantUiState
import org.fossistant.assistant.ui.AssistantViewModel
import org.fossistant.assistant.ui.ModelMemoryAndSpeedTopCueBar
import org.fossistant.assistant.ui.components.BlobActivityState
import org.fossistant.assistant.ui.components.CameraLiveViewfinder
import org.fossistant.assistant.ui.components.CosmicAiBlob

/**
 * Android Assistant Shortcut Overlay UI (`ACTION_ASSIST` / `VoiceInteractionSession` / Wake Word).
 *
 * User-Centric Redesign:
 * 1. Smooth Frosty Blur Effect: Clear/transparent at top around the Iconic Cosmic AI Blob,
 *    smoothly transitioning to frosty dark glass at the bottom behind the response and dock.
 * 2. Bottom Dock with Exactly 5 Circular Buttons (Left to Right):
 *    [1. Agent/Phone Use] -> [2. Live Camera View] -> [3. Mic Shut Off (Center)] -> [4. Keyboard] -> [5. End Call]
 * 3. Single Minimalist Model Status Indicator Dot positioned above the 5th button (End Call).
 * 4. Phone Use / Agent Mode Floating Blob: When Agent Mode is triggered, this overlay minimizes
 *    and a small circular floating Cosmic AI Blob appears in the top-right corner over active apps.
 *    Tapping the floating blob immediately stops agent mode.
 * 5. Privacy & Clarity: Spoken answer shown cleanly with no reasoning traces.
 */
class AssistOverlayActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()
    private var isTransferringToFloatingAgent = false
    private var isScreenResumed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        viewModel.resetLiveCameraSession()
        viewModel.setVoiceMutedByUser(false)
        org.fossistant.assistant.util.AppLogger.i("AssistOverlay", "onCreate: displaying assistant popup overlay")

        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(uiState.aiName, uiState.moodTheme) {
                AppIconSwitcher.applyDynamicAppNameAndTaskDescription(
                    activity = this@AssistOverlayActivity,
                    aiName = uiState.aiName,
                    theme = uiState.moodTheme
                )
            }

            val audioPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    startVoiceCapture()
                } else {
                    viewModel.reportError("Microphone permission is required for voice assistant.")
                }
            }

            val cameraPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    viewModel.toggleLiveCamera(true)
                } else {
                    viewModel.reportError("Camera permission is required for Live Camera Vision.")
                }
            }

            // Request microphone permission on popup launch if not yet granted
            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(
                        this@AssistOverlayActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            // Hands-free continuous turn-taking in popup mode
            LaunchedEffect(
                uiState.isListeningVoice,
                uiState.isVoiceMutedByUser,
                uiState.isGenerating,
                uiState.isTtsSpeaking,
                uiState.isReasoning,
                isScreenResumed
            ) {
                if (isScreenResumed &&
                    !uiState.isListeningVoice &&
                    !uiState.isVoiceMutedByUser &&
                    !uiState.isGenerating &&
                    !uiState.isTtsSpeaking &&
                    !uiState.isReasoning
                ) {
                    if (ContextCompat.checkSelfPermission(
                            this@AssistOverlayActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startVoiceCapture()
                    }
                }
            }

            // Safety net: if popup sits idle with the microphone off, start listening again.
            LaunchedEffect(isScreenResumed) {
                while (isScreenResumed) {
                    kotlinx.coroutines.delay(1200L)
                    val s = viewModel.uiState.value
                    val idle = !s.isVoiceMutedByUser && !s.isGenerating && !s.isTtsSpeaking && !s.isReasoning
                    if (idle && !viewModel.sttEngine.isListening.value &&
                        ContextCompat.checkSelfPermission(this@AssistOverlayActivity, Manifest.permission.RECORD_AUDIO) ==
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
                            viewModel.sendPrompt("What's on my screen? Help me with it.", stayInVoiceMode = true, forceSpeakResponse = true)
                        } else {
                            viewModel.reportError("No screenshot from the assist gesture. Turn on \"Use screenshot\" in Settings > Apps > Default apps > Digital assistant, or use live sharing.")
                        }
                    },
                    onShareLive = {
                        showScreenChoice = false
                        startLiveScreenShare()
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
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startVoiceCapture()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                    finish()
                },
                onOpenAccessibilitySettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                },
                onCameraAction = {
                    if (uiState.isLiveCameraActive) {
                        viewModel.toggleLiveCamera(false)
                    } else {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.toggleLiveCamera(true)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                        if (ContextCompat.checkSelfPermission(this@AssistOverlayActivity, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startVoiceCapture()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onDismiss = {
                    viewModel.stopVoiceAndTts()
                    finish()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.setVoiceMutedByUser(false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        isScreenResumed = true
    }

    override fun onPause() {
        isScreenResumed = false
        super.onPause()
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
                org.fossistant.assistant.util.AppLogger.w("AssistOverlay", "Listening stopped: $error")
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

    /**
     * Live screen sharing: the popup closes, the floating blob appears, and every question is answered with a
     * fresh screenshot. Needs the accessibility service, the microphone and a vision model.
     */
    private fun startLiveScreenShare() {
        when {
            FossistantAgentAccessibilityService.activeInstance == null -> viewModel.reportError(
                "Screen sharing needs the Fossistant accessibility service. Enable it in Android Settings > Accessibility."
            )
            !FossistantApp.primaryModelChoice.supportsVision -> viewModel.reportError(
                "${FossistantApp.primaryModelChoice.displayName} can't see images. Switch to Gemma 4 E2B in Settings to share your screen."
            )
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                viewModel.reportError("Screen sharing needs the microphone permission.")
            else -> {
                isTransferringToFloatingAgent = true
                viewModel.stopVoiceAndTts()
                FossistantApp.screenShareLanguage = viewModel.uiState.value.selectedLanguage
                org.fossistant.assistant.features.LiveScreenShareService.start(this)
                finish()
            }
        }
    }

    override fun onStop() {
        if (!isTransferringToFloatingAgent) {
            viewModel.stopVoiceAndTts()
            viewModel.resetLiveCameraSession()
        }
        if (intent?.getBooleanExtra("from_voice_interaction", false) == true) {
            org.fossistant.assistant.service.FossistantVoiceInteractionSession.dismissActiveSession()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!isTransferringToFloatingAgent) {
            viewModel.stopVoiceAndTts()
            viewModel.resetLiveCameraSession()
        }
        if (intent?.getBooleanExtra("from_voice_interaction", false) == true) {
            org.fossistant.assistant.service.FossistantVoiceInteractionSession.dismissActiveSession()
        }
        super.onDestroy()
    }
}

@Composable
fun AssistOverlayScreen(
    uiState: AssistantUiState,
    onScreenShareAction: () -> Unit = {},
    latestAssistantMessage: ChatMessage?,
    onMicMuteToggle: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onEndCallAction: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCameraAction: () -> Unit,
    onLiveCameraFrameCaptured: (ByteArray) -> Unit,
    onCloseLiveCamera: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onDismissError: () -> Unit,
    onBlobClick: () -> Unit = {},
    onKeyboardToggle: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val moodTheme = uiState.moodTheme
    val aiName = uiState.aiName.ifBlank { "Fossistant" }

    var isKeyboardOpen by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val blobSize by animateDpAsState(
        targetValue = when {
            isKeyboardOpen -> 76.dp
            uiState.isLiveCameraActive -> 130.dp
            else -> 195.dp
        },
        label = "blobSize"
    )

    // Never show reasoning traces in the popup overlay — only clean spoken response
    val visiblePopupAnswer = latestAssistantMessage?.let { msg ->
        if (msg.isReasoningPhase || msg.text == "Thinking..." || msg.text == "Formulating answer...") {
            ""
        } else {
            msg.text.trim()
        }
    }.orEmpty()

    val blobActivityState = when {
        uiState.isReasoning -> BlobActivityState.AI_REASONING
        uiState.isGenerating || uiState.isTtsSpeaking -> BlobActivityState.AI_TALKING
        uiState.voiceRmsDb > 30f || textInput.isNotBlank() -> BlobActivityState.USER_TALKING
        else -> BlobActivityState.IDLE
    }

    // Root overlay container with smooth progressive frosted dark glass gradient:
    // Completely clear/transparent at the top so the underlying screen stays visible,
    // smoothly transitioning into a frosted obsidian glass backdrop behind the blob, response card, and dock.
    val primaryGlowColor = moodTheme.primaryAccent
    val nebulaGlowColor = moodTheme.coreNebulaColor
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val verticalScrim = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.22f to Color(0x0D040712),
                    0.42f to Color(0x4D050916),
                    0.62f to Color(0x9E060B19),
                    0.80f to Color(0xD6060A17),
                    1.00f to Color(0xF2040711)
                )
                val ambientGlow = Brush.radialGradient(
                    colors = listOf(
                        primaryGlowColor.copy(alpha = 0.16f),
                        nebulaGlowColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.72f),
                    radius = size.width * 0.85f
                )
                onDrawBehind {
                    drawRect(brush = verticalScrim)
                    drawRect(brush = ambientGlow)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Consume taps inside assistant area
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Diagnostic Error & Live Camera
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val activeError = uiState.errorMessage ?: latestAssistantMessage?.errorMessage
                AnimatedVisibility(visible = !activeError.isNullOrBlank()) {
                    if (!activeError.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xF02A0A14)
                        ) {
                            Column(
                                modifier = Modifier
                                    .border(1.2.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Error",
                                            tint = Color(0xFFF87171),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = activeError,
                                            color = Color(0xFFFECACA),
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = onDismissError,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss Error",
                                            tint = Color(0xFFFCA5A5),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                if (activeError.contains("Accessibility", ignoreCase = true)) {
                                    TextButton(onClick = onOpenAccessibilitySettings) {
                                        Text(
                                            text = "Open Accessibility Settings →",
                                            color = moodTheme.secondaryAccent,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Camera Viewfinder (if camera vision active)
                AnimatedVisibility(visible = uiState.isLiveCameraActive) {
                    if (uiState.isLiveCameraActive) {
                        CameraLiveViewfinder(
                            moodTheme = moodTheme,
                            onFrameCaptured = onLiveCameraFrameCaptured,
                            onClose = onCloseLiveCamera,
                            heightDp = if (isKeyboardOpen) 130 else 225,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }

            // Center Stage: Iconic Cosmic AI Blob & Clean Response (Lowered comfortably above dock)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                // The Signature AI Blob
                CosmicAiBlob(
                    activityState = blobActivityState,
                    moodTheme = moodTheme,
                    voiceRmsLevel = uiState.voiceRmsDb,
                    ttsAudioLevel = uiState.ttsAudioLevel,
                    showVoiceConcentricRings = true,
                    modifier = Modifier
                        .size(blobSize)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onBlobClick()
                        }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Live Status / Transcription Prompt
                val statusHeadline = when {
                    uiState.isListeningVoice && uiState.liveVoicePartial.isNotBlank() -> uiState.liveVoicePartial
                    uiState.isListeningVoice -> "Listening..."
                    uiState.isGenerating && visiblePopupAnswer.isEmpty() -> "Working on it..."
                    uiState.isTtsSpeaking -> aiName
                    else -> "Ready"
                }

                Text(
                    text = statusHeadline,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                // Clean Assistant Response Card (Strictly NO reasoning traces in popup view)
                AnimatedVisibility(
                    visible = visiblePopupAnswer.isNotEmpty() && !uiState.isListeningVoice,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, start = 8.dp, end = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xD0111625)
                    ) {
                        Column(
                            modifier = Modifier
                                .border(1.dp, moodTheme.primaryAccent.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val badgeColor = if (latestAssistantMessage?.modelTier == ModelTier.DIRECT_ACTION) {
                                    Color(0xFF10B981)
                                } else {
                                    moodTheme.secondaryAccent
                                }
                                Text(
                                    text = latestAssistantMessage?.modelTier?.displayName ?: aiName,
                                    color = badgeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Speak Response",
                                    tint = moodTheme.secondaryAccent,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onSpeakMessage(visiblePopupAnswer) }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            latestAssistantMessage?.toolExecutions?.takeIf { it.isNotEmpty() }?.let { tools ->
                                org.fossistant.assistant.ui.components.ToolActivityChips(
                                    tools = tools,
                                    accent = moodTheme.secondaryAccent,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                org.fossistant.assistant.ui.components.ToolVisuals(
                                    tools = tools,
                                    accent = moodTheme.secondaryAccent,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Text(
                                text = visiblePopupAnswer,
                                color = Color(0xFFF8FAFC),
                                fontSize = 14.5.sp,
                                lineHeight = 19.sp,
                                maxLines = 8
                            )
                        }
                    }
                }
            }

            // Bottom Section: In-Overlay Keyboard Typing Bar + Exactly 5 Circular Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // In-Overlay Keyboard Text Input Field (toggled by Button 4)
                AnimatedVisibility(
                    visible = isKeyboardOpen,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xEE141A29))
                            .border(1.2.dp, moodTheme.primaryAccent.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = {
                                Text(
                                    text = "Ask $aiName anything...",
                                    color = Color(0x8894A3B8),
                                    fontSize = 13.5.sp
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (textInput.isNotBlank()) {
                                    val prompt = textInput.trim()
                                    textInput = ""
                                    isKeyboardOpen = false
                                    onKeyboardToggle(false)
                                    keyboardController?.hide()
                                    onSendPrompt(prompt)
                                }
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = moodTheme.secondaryAccent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    val prompt = textInput.trim()
                                    textInput = ""
                                    isKeyboardOpen = false
                                    onKeyboardToggle(false)
                                    keyboardController?.hide()
                                    onSendPrompt(prompt)
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(moodTheme.primaryAccent)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Dynamic Shifting Rainbow Glow Animation for the Control Dock Pill
                // Read rainbowAngleState ONLY inside drawWithCache's draw lambda so AssistOverlayScreen
                // never recomposes or invalidates the Surface elevation shadow layer per frame!
                val infiniteTransition = rememberInfiniteTransition(label = "dockRainbowGlow")
                val rainbowAngleState = infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 4500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rainbowAngle"
                )
                val rainbowColors = remember {
                    listOf(
                        Color(0xFF00F0FF), // Neon Cyan
                        Color(0xFF8B5CF6), // Purple
                        Color(0xFFFF007A), // Hot Pink
                        Color(0xFFF59E0B), // Amber
                        Color(0xFF10B981), // Emerald
                        Color(0xFF00F0FF)  // Back to Cyan
                    )
                }
                val dockShape = remember { RoundedCornerShape(38.dp) }

                // Dock of Exactly 5 Circular Action Buttons in a Rainbow Glowing Pill Container
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 22.dp,
                            shape = dockShape,
                            spotColor = moodTheme.primaryAccent.copy(alpha = 0.55f)
                        ),
                    shape = dockShape,
                    color = Color(0xE60B101D)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithCache {
                                val strokeWidthPx = 2.dp.toPx()
                                val halfStroke = strokeWidthPx / 2f
                                val cornerRadiusPx = 38.dp.toPx()
                                val topLeftOffset = Offset(halfStroke, halfStroke)
                                val borderSize = Size(
                                    width = (size.width - strokeWidthPx).coerceAtLeast(0f),
                                    height = (size.height - strokeWidthPx).coerceAtLeast(0f)
                                )
                                val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                                val strokeStyle = Stroke(width = strokeWidthPx)
                                val cx = size.width * 0.5f
                                val cy = size.height * 0.5f
                                val gradDist = size.width * 0.65f
                                onDrawWithContent {
                                    drawContent()
                                    val angleDeg = rainbowAngleState.value
                                    val rad = angleDeg * (Math.PI / 180.0)
                                    val cosA = kotlin.math.cos(rad).toFloat()
                                    val sinA = kotlin.math.sin(rad).toFloat()
                                    val brush = Brush.linearGradient(
                                        colors = rainbowColors,
                                        start = Offset(cx - cosA * gradDist, cy - sinA * gradDist),
                                        end = Offset(cx + cosA * gradDist, cy + sinA * gradDist)
                                    )
                                    drawRoundRect(
                                        brush = brush,
                                        topLeft = topLeftOffset,
                                        size = borderSize,
                                        cornerRadius = cornerRadius,
                                        style = strokeStyle
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Screen: ask about the screen behind the popup, or share it live
                        CircularQuickActionButton(
                            icon = Icons.Default.ScreenShare,
                            contentDescription = "Share Screen",
                            isActive = false,
                            activeColor = moodTheme.secondaryAccent,
                            sizeDp = 42,
                            onClick = onScreenShareAction
                        )

                        // 3. Live Camera Viewfinder
                        CircularQuickActionButton(
                            icon = Icons.Default.PhotoCamera,
                            contentDescription = "Live Camera Vision",
                            isActive = uiState.isLiveCameraActive,
                            activeColor = moodTheme.secondaryAccent,
                            sizeDp = 42,
                            onClick = onCameraAction
                        )

                        // 3. Center Mic Shut Off Button (Prominent 58dp button)
                        val isMicActive = uiState.isListeningVoice
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .shadow(
                                    elevation = 14.dp,
                                    shape = CircleShape,
                                    spotColor = if (isMicActive) Color(0xFF00E5FF) else Color(0xFFEF4444)
                                )
                                .clip(CircleShape)
                                .background(
                                    if (isMicActive) {
                                        Brush.linearGradient(
                                            listOf(moodTheme.primaryAccent, moodTheme.secondaryAccent)
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color(0xFF2B1017), Color(0xFF181B26))
                                        )
                                    }
                                )
                                .border(
                                    width = 2.dp,
                                    color = if (isMicActive) Color.White.copy(alpha = 0.90f) else Color(0xFFEF4444),
                                    shape = CircleShape
                                )
                                .clickable(onClick = onMicMuteToggle),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isMicActive) "Shut Off Microphone" else "Unmute Microphone",
                                tint = if (isMicActive) Color.White else Color(0xFFF87171),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // 4. Keyboard Input Toggle
                        CircularQuickActionButton(
                            icon = Icons.Default.Keyboard,
                            contentDescription = "Type Message",
                            isActive = isKeyboardOpen,
                            activeColor = moodTheme.secondaryAccent,
                            sizeDp = 42,
                            onClick = {
                                val nextState = !isKeyboardOpen
                                isKeyboardOpen = nextState
                                onKeyboardToggle(nextState)
                            }
                        )

                        // 5. End Call Button with Model Status Indicator Dot
                        Box(
                            contentAlignment = Alignment.TopEnd
                        ) {
                            // Circular Nokia-Red End Call Button
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .shadow(8.dp, CircleShape, spotColor = Color(0xFFDC2626))
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFFEF4444), Color(0xFFB91C1C))
                                        )
                                    )
                                    .border(1.2.dp, Color(0xFFFCA5A5).copy(alpha = 0.8f), CircleShape)
                                    .clickable(onClick = onEndCallAction),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "End Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Single Minimalist Model Status Indicator Dot positioned above/at top-right of Button 5
                            val statusDotColor = when {
                                uiState.errorMessage != null ||
                                    uiState.gemmaMemoryStatus.contains("Error", ignoreCase = true) ||
                                    uiState.gemmaMemoryStatus.contains("Missing", ignoreCase = true) -> Color(0xFFEF4444) // Error (red)
                                uiState.isGemmaLoadingMemory -> Color(0xFFF59E0B) // Loading (amber/orange)
                                uiState.isGemmaLoadedInRam -> {
                                    if (uiState.isLlmOnCpu) {
                                        Color(0xFF3B82F6) // CPU fallback / CPU mode (Blue)
                                    } else {
                                        Color(0xFF10B981) // GPU hardware accelerated (Green)
                                    }
                                }
                                else -> Color(0xFF64748B) // Standby (gray)
                            }
                            Box(
                                modifier = Modifier
                                    .padding(top = 0.dp, end = 0.dp)
                                    .size(9.dp)
                                    .shadow(4.dp, CircleShape, spotColor = statusDotColor)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularQuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color,
    sizeDp: Int = 46,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(if (isActive) Color(0xEE1E293B) else Color(0xCC161B26))
            .border(
                width = if (isActive) 1.6.dp else 1.dp,
                color = if (isActive) activeColor else Color(0x33FFFFFF),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) activeColor else Color(0xFFE2E8F0),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun ScreenShareChoiceDialog(
    hasAssistScreenshot: Boolean,
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
                    if (hasAssistScreenshot) "Ask about the screen that was open when you called me, or share your screen live while you use the phone."
                    else "Share your screen live: I shrink to a bubble, you use the phone, and I see the screen every time you ask something.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onShareLive) { Text("Share live") } },
        dismissButton = {
            if (hasAssistScreenshot) TextButton(onClick = onAskAboutScreen) { Text("Ask about this screen") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
