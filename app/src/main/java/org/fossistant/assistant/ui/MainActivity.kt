package org.fossistant.assistant.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import org.fossistant.assistant.FossistantApp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.fossistant.assistant.engine.FastMultilingualTtsEngine
import org.fossistant.assistant.model.AiCharacterPersona
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.model.FastTtsVoiceProfile
import org.fossistant.assistant.model.HardwareInferenceBackend
import org.fossistant.assistant.model.MediaAttachment
import org.fossistant.assistant.model.MediaModality
import org.fossistant.assistant.model.MemoryFact
import org.fossistant.assistant.model.ModelTier
import org.fossistant.assistant.model.PrimaryLlmModel
import org.fossistant.assistant.model.ResponseLengthPreference
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.ui.components.BlobActivityState
import org.fossistant.assistant.ui.components.CameraLiveViewfinder
import org.fossistant.assistant.ui.components.CosmicAiBlob
import org.fossistant.assistant.ui.overlay.AssistOverlayActivity

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Never run prompts taken from launch intents: the launcher entry is exported, so any installed app
        // could otherwise make the assistant place calls or send messages. Opening a saved chat is harmless.
        handleOpenChatIntent(intent)
        // A fresh launch starts on the start page. Only when Android recreated the screen after reclaiming the app in
        // the background (savedInstanceState is set) does the chat come back, and only if it was used recently.
        if (savedInstanceState != null) viewModel.restoreLastChat()

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            // The overlay popup has its own ViewModel but shares the single microphone engine; only the
            // foreground (resumed) screen may auto-start listening, otherwise the two keep stealing the mic.
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            val isScreenResumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            val moodTheme = uiState.moodTheme

            // Dynamically update Activity Title, Recent Tasks label/icon, and Launcher <activity-alias> icon
            LaunchedEffect(uiState.aiName, uiState.moodTheme) {
                AppIconSwitcher.applyDynamicAppNameAndTaskDescription(
                    activity = this@MainActivity,
                    aiName = uiState.aiName,
                    theme = uiState.moodTheme
                )
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = moodTheme.primaryAccent,
                    secondary = moodTheme.secondaryAccent,
                    tertiary = moodTheme.tertiaryAccent,
                    background = Color.Black,
                    surface = moodTheme.coreNebulaColor
                )
            ) {
                // First launch (or reopened from Settings): the setup replaces the whole screen until it's finished.
                if (uiState.showOnboarding) {
                    org.fossistant.assistant.ui.onboarding.OnboardingScreen(uiState = uiState, viewModel = viewModel)
                    return@MaterialTheme
                }
                var showCustomizationDialog by remember { mutableStateOf(false) }
                var showPersonalityDialog by remember { mutableStateOf(false) }
                var showWakeWordDialog by remember { mutableStateOf(false) }
                var showVoiceProfilesDialog by remember { mutableStateOf(false) }
                var showMemoryDialog by remember { mutableStateOf(false) }
                var showIntegrationsDialog by remember { mutableStateOf(false) }
                var showHomeAssistantDialog by remember { mutableStateOf(false) }
                var pendingImportModel by remember { mutableStateOf<PrimaryLlmModel?>(null) }
                var showHardwareAccelerationDialog by remember { mutableStateOf(false) }
                var showLogsDialog by remember { mutableStateOf(false) }

                val documentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { viewModel.attachMediaUri(it, MediaModality.LARGE_TEXT) } }

                val videoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { viewModel.attachMediaUri(it, MediaModality.VIDEO) } }

                val audioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { viewModel.attachMediaUri(it, MediaModality.AUDIO) } }

                val imageLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { viewModel.attachMediaUri(it, MediaModality.IMAGE) } }

                // Plus menu: a photo taken with the camera app, and any document (text, PDF, Word)
                var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
                val takePhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture()
                ) { saved ->
                    val uri = pendingPhotoUri
                    pendingPhotoUri = null
                    if (saved && uri != null) viewModel.attachMediaUri(uri, MediaModality.IMAGE)
                }
                fun launchCamera() {
                    val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
                    val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    pendingPhotoUri = uri
                    takePhotoLauncher.launch(uri)
                }
                val photoPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) launchCamera() else viewModel.reportError("Camera permission is needed to take a photo.")
                }
                val filesLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { viewModel.attachMediaUri(it, MediaModality.LARGE_TEXT) } }

                // Settings > Export / Import data
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip")
                ) { uri -> uri?.let { viewModel.exportData(it) } }
                var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> pendingImportUri = uri }
                pendingImportUri?.let { uri ->
                    AlertDialog(
                        onDismissRequest = { pendingImportUri = null },
                        containerColor = Color(0xFF101420),
                        title = { Text("Import data?", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Chats, memories, skills, projects, recordings and settings from the backup replace the ones " +
                                    "on this phone. Models aren't touched. Fossistant restarts afterwards.",
                                color = Color(0xFFE2E8F0)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { pendingImportUri = null; viewModel.importData(uri) }) { Text("Import", color = Color(0xFFA78BFA)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
                    )
                }
                // A crash since the last launch: ask once whether to send the report (opens the email app).
                val crashContext = androidx.compose.ui.platform.LocalContext.current
                var crashReport by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    crashReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        org.fossistant.assistant.util.CrashReporter.pendingReport(crashContext)
                    }
                }
                crashReport?.let { report ->
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFF101420),
                        title = { Text("Fossistant crashed last time", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Send a crash report to help fix it? Your email app opens with the report (device model, " +
                                    "Android version, the error and the last app log lines) so you can read or edit it first. " +
                                    "Nothing is sent unless you send that email.",
                                color = Color(0xFFE2E8F0)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                runCatching { crashContext.startActivity(org.fossistant.assistant.util.CrashReporter.sendIntent(report)) }
                                org.fossistant.assistant.util.CrashReporter.clear(crashContext)
                                crashReport = null
                            }) { Text("Send report", color = Color(0xFFA78BFA)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                org.fossistant.assistant.util.CrashReporter.clear(crashContext)
                                crashReport = null
                            }) { Text("Don't send", color = Color(0xFF94A3B8)) }
                        }
                    )
                }
                uiState.backupStatus?.let { status ->
                    AlertDialog(
                        onDismissRequest = { if (!uiState.isBackupRunning) viewModel.dismissBackupStatus() },
                        containerColor = Color(0xFF101420),
                        title = { Text(if (uiState.isBackupRunning) "Working..." else "Your data", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (uiState.isBackupRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(status, color = Color(0xFFE2E8F0))
                            }
                        },
                        confirmButton = {
                            if (!uiState.isBackupRunning) {
                                TextButton(onClick = { viewModel.dismissBackupStatus() }) {
                                    Text(if (uiState.backupNeedsRestart) "Restart" else "OK", color = Color(0xFFA78BFA))
                                }
                            }
                        }
                    )
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.setScreenMode(AppScreenMode.VOICE_MODE)
                        startDictation(uiState.selectedLanguage)
                    } else {
                        viewModel.reportError("Microphone permission is required for Whisper voice mode.")
                    }
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.toggleLiveCamera(true)
                    } else {
                        viewModel.reportError("Camera permission is required for Live Camera Vision streaming.")
                    }
                }

                // Tool permissions (calls, SMS, contacts, calendar, location, notifications) are asked for in the setup,
                // each with its purpose, instead of all at once on every launch. Settings > Setup & guide asks again.
                @Suppress("UNUSED_VARIABLE")
                val directActionPermissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* Permissions evaluated dynamically by DeviceAndWebToolExecutor */ }

                LaunchedEffect(
                    uiState.screenMode,
                    uiState.isListeningVoice,
                    uiState.isVoiceMutedByUser,
                    uiState.isGenerating,
                    uiState.isTtsSpeaking,
                    uiState.isReasoning,
                    isScreenResumed
                ) {
                    if (isScreenResumed &&
                        uiState.screenMode == AppScreenMode.VOICE_MODE &&
                        !uiState.isListeningVoice &&
                        !uiState.isVoiceMutedByUser &&
                        !uiState.isGenerating &&
                        !uiState.isTtsSpeaking &&
                        !uiState.isReasoning
                    ) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startDictation(uiState.selectedLanguage, continuous = FossistantApp.isVoiceDuplexMode)
                        }
                    }
                }

                // Safety net for the effect above: if voice mode sits idle with the microphone off (an engine stop
                // the state change didn't catch), start listening again.
                LaunchedEffect(uiState.screenMode, isScreenResumed) {
                    while (uiState.screenMode == AppScreenMode.VOICE_MODE && isScreenResumed) {
                        kotlinx.coroutines.delay(VOICE_MIC_CHECK_MS)
                        val s = viewModel.uiState.value
                        val idle = s.screenMode == AppScreenMode.VOICE_MODE && !s.isVoiceMutedByUser &&
                            !s.isGenerating && !s.isTtsSpeaking && !s.isReasoning
                        if (idle && !viewModel.sttEngine.isListening.value &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            org.fossistant.assistant.util.AppLogger.w("MainActivity", "Voice mode was idle with the microphone off: listening again")
                            startDictation(s.selectedLanguage, continuous = FossistantApp.isVoiceDuplexMode)
                        }
                    }
                }

                BiomorphicFossistantApp(
                    uiState = uiState,
                    onSendPrompt = { viewModel.sendPrompt(it) },
                    onToggleReasoning = { viewModel.toggleReasoningMode() },
                    onMicButtonClick = {
                        if (uiState.isListeningVoice) {
                            // Tapping Mic while listening mutes the mic, NEVER ends the call
                            viewModel.stopVoiceCapture()
                        } else {
                            viewModel.stopVoiceAndTts()
                            viewModel.setVoiceMutedByUser(false)
                            if (uiState.screenMode != AppScreenMode.VOICE_MODE) {
                                viewModel.setScreenMode(AppScreenMode.VOICE_MODE)
                            }
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                startDictation(uiState.selectedLanguage)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onHangUp = {
                        // Classic red Nokia button ends the voice call session
                        viewModel.stopVoiceAndTts()
                        viewModel.setScreenMode(
                            if (uiState.messages.isEmpty()) AppScreenMode.OPEN_HOME else AppScreenMode.TEXT_CHAT
                        )
                    },
                    onToggleSideMenu = { viewModel.toggleSideMenu(it) },
                    onSelectPreviousChat = { viewModel.openPreviousChat(it) },
                    onDeleteChat = { viewModel.deleteChatSession(it) },
                    onClearAllChats = { viewModel.clearAllChatSessions() },
                    onSpeakMessage = { viewModel.speakMessage(it) },
                    onNewChat = { viewModel.startNewConversation() },
                    onNavigateSettings = { viewModel.setScreenMode(AppScreenMode.SETTINGS) },
                    featureActions = FeatureUiActions(
                        onOpenSetup = { viewModel.openOnboarding() },
                        onOpenTools = {
                            viewModel.toggleSideMenu(false)
                            org.fossistant.assistant.ui.features.FeaturesActivity.open(this@MainActivity)
                        },
                        onToggleTemporaryChat = { viewModel.setTemporaryChat(it) },
                        onShowLockedChats = { authenticateForLockedChats { viewModel.setLockedChatsUnlocked(true) } },
                        onToggleChatLock = { id, locked -> viewModel.setChatLocked(id, locked) },
                        onLeaveProject = { viewModel.leaveProject() },
                        onTakePhoto = {
                            // The app declares CAMERA, so Android requires it before handing off to the camera app.
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else {
                                photoPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onPickFiles = {
                            filesLauncher.launch(
                                arrayOf(
                                    "text/*", "application/pdf", "application/json",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                )
                            )
                        },
                        onSetResearchArmed = { viewModel.setResearchArmed(it) },
                        onPickImageModel = { viewModel.pickImageModel(it) },
                        onCancelImageDownload = { viewModel.cancelImageModelDownload(it) },
                        onDisarmImageGen = { viewModel.disarmImageGen() },
                        onPickProject = { viewModel.setChatProject(it) },
                        onOpenToolsAt = { screen ->
                            org.fossistant.assistant.ui.features.FeaturesActivity.open(this@MainActivity, screen)
                        },
                        onExportData = {
                            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            exportLauncher.launch("fossistant-backup-$day.zip")
                        },
                        onImportData = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        onClearAllChats = { viewModel.clearAllChatSessions() },
                        onStartProjectChat = { viewModel.startProjectChat(it) },
                        onToggleMtp = { viewModel.setMtpEnabled(it) },
                        onToggleStrictToolCalls = { viewModel.setStrictToolCalls(it) }
                    ),
                    onBackFromSettings = {
                        viewModel.setScreenMode(
                            if (uiState.messages.isEmpty()) AppScreenMode.OPEN_HOME else AppScreenMode.TEXT_CHAT
                        )
                    },
                    onAttachLargeText = { documentLauncher.launch("text/*") },
                    onAttachVideo = { videoLauncher.launch("video/*") },
                    onAttachAudio = { audioLauncher.launch("audio/*") },
                    onAttachImage = { imageLauncher.launch("image/*") },
                    onToggleLiveCamera = {
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
                    onLiveCameraFrameCaptured = { viewModel.updateLiveCameraFrameJpeg(it) },
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onDismissError = { viewModel.dismissError() },
                    onToggleVoiceProfiles = { viewModel.toggleVoiceProfiles(it) },
                    onOpenVoiceProfiles = { showVoiceProfilesDialog = true },
                    onToggleVoiceTrigger = { viewModel.toggleVoiceTrigger(it) },
                    onOpenWakeWordSettings = { showWakeWordDialog = true },
                    onOpenPersonalitySettings = { showPersonalityDialog = true },
                    onToggleAiMemory = { viewModel.toggleAiMemory(it) },
                    onOpenMemoryManager = { showMemoryDialog = true },
                    onToggleSoundNotifications = { viewModel.toggleSoundNotifications(it) },
                    onOpenCustomization = { showCustomizationDialog = true },
                    onOpenIntegrations = { showIntegrationsDialog = true },
                    onOpenHardwareAcceleration = { showHardwareAccelerationDialog = true },
                    onToggleVoiceDuplexMode = { viewModel.toggleVoiceDuplexMode(it) },
                    onToggleLimitCompute = { viewModel.setLimitCompute(it) },
                    onBlobClick = {
                        viewModel.onBlobTapped {
                            startDictation(uiState.selectedLanguage, continuous = FossistantApp.isVoiceDuplexMode)
                        }
                    },
                    onOpenLogs = { showLogsDialog = true }
                )

                if (showVoiceProfilesDialog) {
                    VoiceProfilesDialog(
                        selectedLanguage = uiState.selectedLanguage,
                        selectedProfile = uiState.selectedVoiceProfile,
                        onSelectLanguage = { viewModel.selectLanguage(it) },
                        onSelectProfile = { viewModel.selectVoiceProfile(it) },
                        onPreviewVoice = { viewModel.previewSelectedVoice() },
                        onDismiss = { showVoiceProfilesDialog = false }
                    )
                }

                if (showWakeWordDialog) {
                    WakeWordSettingsDialog(
                        voiceTriggerEnabled = uiState.voiceTriggerEnabled,
                        wakeWordPhrase = uiState.wakeWordPhrase,
                        wakeWordSensitivity = uiState.wakeWordSensitivity,
                        liveMicRmsDb = uiState.backgroundMicRmsDb,
                        isListeningTest = uiState.isListeningVoice,
                        moodTheme = uiState.moodTheme,
                        onToggleEnabled = { viewModel.toggleVoiceTrigger(it) },
                        onUpdateConfig = { phrase, sens ->
                            viewModel.updateWakeWordConfig(phrase, sens)
                        },
                        onTestMic = {
                            if (uiState.isListeningVoice) {
                                viewModel.stopVoiceCapture()
                            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.sttEngine.startListening(
                                    scope = lifecycleScope,
                                    language = uiState.selectedLanguage,
                                    onPartial = { partial ->
                                        viewModel.setVoiceListeningState(true, partial, enterVoiceScreenMode = false)
                                    },
                                    onFinalResult = {
                                        viewModel.setVoiceListeningState(false, "", enterVoiceScreenMode = false)
                                    }
                                )
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onDismiss = {
                            if (uiState.isListeningVoice) viewModel.stopVoiceCapture()
                            showWakeWordDialog = false
                        }
                    )
                }

                if (showPersonalityDialog) {
                    PersonalityAndIdentityDialog(
                        aiName = uiState.aiName,
                        characterPersona = uiState.characterPersona,
                        customInstructions = uiState.customPersonalityInstructions,
                        responseLength = uiState.responseLength,
                        moodTheme = uiState.moodTheme,
                        onSave = { newName, newPersona, newCustom, newLength ->
                            viewModel.updateAiIdentityAndPersonality(
                                aiName = newName,
                                persona = newPersona,
                                customInstructions = newCustom,
                                lengthPref = newLength
                            )
                        },
                        onDismiss = { showPersonalityDialog = false }
                    )
                }

                if (showCustomizationDialog) {
                    CustomizationDialog(
                        moodTheme = uiState.moodTheme,
                        characterPersona = uiState.characterPersona,
                        responseLength = uiState.responseLength,
                        onSelectMoodTheme = { viewModel.updateBlobMoodTheme(it) },
                        onSelectPersona = { viewModel.updateCharacterAndLength(persona = it) },
                        onSelectResponseLength = { viewModel.updateCharacterAndLength(lengthPref = it) },
                        onPreviewOverlayShortcut = {
                            startActivity(Intent(this, AssistOverlayActivity::class.java))
                        },
                        onDismiss = { showCustomizationDialog = false }
                    )
                }

                if (showMemoryDialog) {
                    MemoryManagerDialog(
                        aiMemoryEnabled = uiState.aiMemoryEnabled,
                        smartRamEvictionEnabled = uiState.memorySmartEvictionEnabled,
                        facts = uiState.memoryFacts,
                        onToggleAiMemory = { viewModel.toggleAiMemory(it) },
                        onToggleSmartEviction = { viewModel.toggleMemorySmartEviction(it) },
                        onAddFact = { viewModel.addMemoryFact(it) },
                        onRemoveFact = { viewModel.removeMemoryFact(it) },
                        onClearAll = { viewModel.clearAllMemories() },
                        onDismiss = { showMemoryDialog = false }
                    )
                }

                if (showIntegrationsDialog) {
                    IntegrationsDialog(
                        onRequestDefaultAssistantRole = { requestDefaultAssistantSetting() },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenNotificationListenerSettings = {
                            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        },
                        onRunSampleCapability = { cmd ->
                            showIntegrationsDialog = false
                            viewModel.sendPrompt(cmd)
                        },
                        onOpenHomeAssistant = {
                            showIntegrationsDialog = false
                            showHomeAssistantDialog = true
                        },
                        onDismiss = { showIntegrationsDialog = false }
                    )
                }

                val modelFilePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val model = pendingImportModel
                    pendingImportModel = null
                    if (uri != null && model != null) viewModel.importModel(model, uri)
                }
                uiState.modelImportRequest?.let { model ->
                    ModelImportDialog(
                        model = model,
                        onChooseFile = {
                            pendingImportModel = model
                            viewModel.dismissModelImport()
                            modelFilePicker.launch(arrayOf("*/*"))
                        },
                        onDismiss = { viewModel.dismissModelImport() }
                    )
                }
                uiState.modelImportProgress?.let { progress ->
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFF101420),
                        title = { Text("Importing model", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Text("${(progress * 100).toInt()}%", color = Color(0xFFE2E8F0))
                            }
                        },
                        confirmButton = {}
                    )
                }

                if (showHomeAssistantDialog) {
                    HomeAssistantDialog(
                        initialUrl = FossistantApp.homeAssistantUrl,
                        initialToken = FossistantApp.homeAssistantToken,
                        onTest = { url, token ->
                            val client = org.fossistant.assistant.tools.HomeAssistantClient({ url }, { token })
                            client.testConnection().summary
                        },
                        onSave = { url, token ->
                            FossistantApp.homeAssistantUrl = url.trim()
                            FossistantApp.homeAssistantToken = token.trim()
                            FossistantApp.savePreferences(this@MainActivity)
                            showHomeAssistantDialog = false
                        },
                        onDismiss = { showHomeAssistantDialog = false }
                    )
                }

                if (showHardwareAccelerationDialog) {
                    HardwareAccelerationDialog(
                        currentPrimaryBackend = uiState.primaryHardwareBackend,
                        primaryModelDisplayName = uiState.primaryModelDisplayName,
                        currentWhisperBackend = uiState.whisperHardwareBackend,
                        currentWhisperModel = uiState.whisperModelChoice,
                        whisperLatencyMs = uiState.whisperLastLatencyMs,
                        moodTheme = moodTheme,
                        onSelectPrimaryBackend = { viewModel.setPrimaryHardwareBackend(it) },
                        onSelectWhisperBackend = { viewModel.setWhisperHardwareBackend(it) },
                        onSelectWhisperModel = { viewModel.switchWhisperModel(it) },
                        onDismiss = { showHardwareAccelerationDialog = false }
                    )
                }

                if (showLogsDialog) {
                    SystemLogsDialog(
                        moodTheme = moodTheme,
                        onDismiss = { showLogsDialog = false }
                    )
                }
            }
        }
    }

    private fun requestDefaultAssistantSetting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    @Suppress("DEPRECATION")
                    startActivityForResult(roleIntent, 9101)
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Throwable) {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun startDictation(language: SupportedLanguage, continuous: Boolean = FossistantApp.isVoiceDuplexMode) {
        viewModel.setVoiceListeningState(true, "Listening with Whisper (${language.displayName})...")
        viewModel.sttEngine.startListening(
            scope = lifecycleScope,
            language = language,
            continuous = continuous,
            onPartial = { partial ->
                viewModel.setVoiceListeningState(true, partial)
            },
            onError = { error ->
                org.fossistant.assistant.util.AppLogger.w("MainActivity", "Listening stopped: $error")
            },
            onFinalResult = { transcript ->
                if (transcript.isNotBlank()) {
                    if (!continuous) {
                        viewModel.sttEngine.stopListening()
                    }
                    viewModel.sendPrompt(transcript, stayInVoiceMode = true, forceSpeakResponse = true)
                } else if (!continuous) {
                    viewModel.setVoiceListeningState(false)
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenChatIntent(intent)
    }

    private fun handleOpenChatIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_OPEN_CHAT_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_CHAT_ID)
        viewModel.openChatById(chatId)
    }

    /** Fingerprint, face or screen lock before locked chats are shown (framework API, no library). */
    private fun authenticateForLockedChats(onSuccess: () -> Unit) {
        val builder = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Show locked chats")
            .setSubtitle("Confirm it's you")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        runCatching {
            builder.build().authenticate(
                android.os.CancellationSignal(),
                mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            viewModel.reportError("Couldn't unlock: $errString. Locked chats need a screen lock on the phone.")
                        }
                    }
                }
            )
        }.onFailure { viewModel.reportError("Couldn't unlock: ${it.message}") }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        // A rotation also stops the activity for a moment; only really leaving the app frees the model.
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // In the foreground the microphone foreground service may always start (e.g. after a reboot,
        // when the background start was refused).
        org.fossistant.assistant.service.VoiceWakeWordService.startIfEnabled(this)
        FossistantApp.pendingSharedPrompt?.let { prompt ->
            FossistantApp.pendingSharedPrompt = null
            viewModel.sendPrompt(prompt)
        }
        FossistantApp.pendingResearchQuestion?.let { question ->
            FossistantApp.pendingResearchQuestion = null
            viewModel.startDeepResearch(question)
        }
        FossistantApp.pendingProjectChatId?.let { projectId ->
            FossistantApp.pendingProjectChatId = null
            viewModel.startProjectChat(projectId)
        }
    }

    companion object {
        /** How often voice mode checks that the microphone is still on while nothing is playing. */
        private const val VOICE_MIC_CHECK_MS = 1500L

        /** Opens a saved chat (used by scheduled-task notifications). */
        const val EXTRA_OPEN_CHAT_ID = "org.fossistant.assistant.OPEN_CHAT_ID"
    }

    override fun onDestroy() {
        viewModel.sttEngine.stopListening()
        super.onDestroy()
    }
}

private fun moodBorderBrush(theme: BlobMoodTheme): Brush = Brush.linearGradient(
    colors = listOf(
        theme.primaryAccent,
        theme.tertiaryAccent,
        theme.secondaryAccent
    )
)

/**
 * Voice-mode subtitles synced to the speaker: words appear as they're spoken, the newest in the accent colour
 * fading to white as more follow, and only the last few lines stay (older ones scroll up under a fade).
 */
@Composable
private fun SpokenCaptions(moodTheme: BlobMoodTheme) {
    val captionFlow = remember {
        FossistantApp.instance?.ttsEngine?.caption
            ?: kotlinx.coroutines.flow.MutableStateFlow(FastMultilingualTtsEngine.SpeechCaption())
    }
    val speakingFlow = remember {
        FossistantApp.instance?.ttsEngine?.isSpeaking ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val caption by captionFlow.collectAsState()
    val speaking by speakingFlow.collectAsState()
    val text = caption.text
    // Reveal whole words: extend the spoken position to the end of the word being said.
    var frontier = caption.spokenChars.coerceIn(0, text.length)
    if (frontier in 1 until text.length && !text[frontier - 1].isWhitespace()) {
        while (frontier < text.length && !text[frontier].isWhitespace()) frontier++
    }
    // Once everything has been spoken, the highlighted last words fade to white too.
    val settled = remember { androidx.compose.animation.core.Animatable(0f) }
    val finished = !speaking && text.isNotEmpty() && frontier >= text.length
    LaunchedEffect(finished, text) {
        if (finished) settled.animateTo(1f, androidx.compose.animation.core.tween(CAPTION_SETTLE_MS)) else settled.snapTo(0f)
    }
    if (frontier == 0) return
    // Keep a tail of a few lines; the box shows the bottom of it.
    var start = (frontier - CAPTION_TAIL_CHARS).coerceAtLeast(0)
    if (start > 0) start = text.indexOf(' ', start).let { if (it in start until frontier) it + 1 else start }
    val accent = moodTheme.secondaryAccent
    val styled = androidx.compose.ui.text.buildAnnotatedString {
        var i = start
        while (i < frontier) {
            var end = i
            while (end < frontier && !text[end].isWhitespace()) end++
            while (end < frontier && text[end].isWhitespace()) end++
            // Distance behind the voice, in characters (about 15 a second): the last word is accent, ~2 s back is white.
            val behind = (frontier - end).toFloat()
            val fade = maxOf((behind / CAPTION_FADE_CHARS).coerceIn(0f, 1f), settled.value)
            val color = androidx.compose.ui.graphics.lerp(accent, Color(0xFFF1F5F9), fade)
            withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append(text.substring(i, end)) }
            i = end
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp)
            .height(104.dp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Older lines fade out at the top instead of being cut off.
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                )
            }
            .clipToBounds(),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = styled,
            fontSize = 19.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.wrapContentHeight(align = Alignment.Bottom, unbounded = true)
        )
    }
}

private const val CAPTION_TAIL_CHARS = 220
private const val CAPTION_FADE_CHARS = 30f
private const val CAPTION_SETTLE_MS = 1200

/** Shown while a model loads: what's happening and that the first load after an update takes longer. */
@Composable
private fun ModelPreparingBanner(modelName: String, status: String, moodTheme: BlobMoodTheme) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "preparing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "sweep"
    )
    val detail = when {
        status.contains("Unpacking", ignoreCase = true) -> status.trimEnd('.')
        status.contains("llama", ignoreCase = true) -> "Loading the model and checking the GPU"
        else -> "Loading onto the GPU"
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6111726))
            .border(1.dp, moodTheme.secondaryAccent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CosmicAiBlob(
                activityState = BlobActivityState.AI_REASONING,
                moodTheme = moodTheme,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Preparing $modelName…", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // A light that sweeps along a thin track
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1E2638))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .fillMaxHeight()
                    .offset(x = maxWidth * (sweep * 1.35f - 0.35f))
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, moodTheme.secondaryAccent, Color.Transparent)))
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "The first load after installing or updating takes longer; later loads reuse the prepared files.",
            color = Color(0xFF64748B),
            fontSize = 11.sp
        )
    }
}

/** Callbacks for the Tools hub, temporary and locked chats, projects and PDF export. */
data class FeatureUiActions(
    /** Reopens the first-launch setup (language, model download, permissions, tour). */
    val onOpenSetup: () -> Unit = {},
    val onOpenTools: () -> Unit = {},
    val onToggleTemporaryChat: (Boolean) -> Unit = {},
    val onShowLockedChats: () -> Unit = {},
    val onToggleChatLock: (String, Boolean) -> Unit = { _, _ -> },
    val onLeaveProject: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onSetResearchArmed: (Boolean) -> Unit = {},
    val onPickProject: (org.fossistant.assistant.features.Project?) -> Unit = {},
    val onOpenToolsAt: (String?) -> Unit = {},
    val onExportData: () -> Unit = {},
    val onImportData: () -> Unit = {},
    val onClearAllChats: () -> Unit = {},
    val onStartProjectChat: (String) -> Unit = {},
    val onToggleMtp: (Boolean) -> Unit = {},
    val onToggleStrictToolCalls: (Boolean) -> Unit = {},
    val onPickImageModel: (org.fossistant.assistant.model.ImageGenModelChoice) -> Unit = {},
    val onCancelImageDownload: (org.fossistant.assistant.model.ImageGenModelChoice) -> Unit = {},
    val onDisarmImageGen: () -> Unit = {}
)

@Composable
fun BiomorphicFossistantApp(
    uiState: AssistantUiState,
    featureActions: FeatureUiActions = FeatureUiActions(),
    onSendPrompt: (String) -> Unit,
    onToggleReasoning: () -> Unit,
    onMicButtonClick: () -> Unit,
    onHangUp: () -> Unit,
    onToggleSideMenu: (Boolean?) -> Unit,
    onSelectPreviousChat: (PreviousChatSession) -> Unit,
    onDeleteChat: (String) -> Unit,
    onClearAllChats: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onNewChat: () -> Unit,
    onNavigateSettings: () -> Unit,
    onBackFromSettings: () -> Unit,
    onAttachLargeText: () -> Unit,
    onAttachVideo: () -> Unit,
    onAttachAudio: () -> Unit,
    onAttachImage: () -> Unit,
    onToggleLiveCamera: () -> Unit,
    onLiveCameraFrameCaptured: (ByteArray) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissError: () -> Unit,
    onToggleVoiceProfiles: (Boolean) -> Unit,
    onOpenVoiceProfiles: () -> Unit,
    onToggleVoiceTrigger: (Boolean) -> Unit,
    onOpenWakeWordSettings: () -> Unit,
    onOpenPersonalitySettings: () -> Unit,
    onToggleAiMemory: (Boolean) -> Unit,
    onOpenMemoryManager: () -> Unit,
    onToggleSoundNotifications: (Boolean) -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenHardwareAcceleration: () -> Unit,
    onToggleVoiceDuplexMode: (Boolean) -> Unit = {},
    onToggleLimitCompute: (Boolean) -> Unit = {},
    onBlobClick: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    // Kept across rotation (the screen is rebuilt then), so a half-typed message isn't lost.
    var inputText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var typingPulseTrigger by remember { mutableIntStateOf(0) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    val moodTheme = uiState.moodTheme
    val aiName = uiState.aiName.ifBlank { "Fossistant" }

    var lastLlmBubbleCenterYInPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val blobActivityState = when {
        uiState.isReasoning -> BlobActivityState.AI_REASONING
        uiState.isGenerating || uiState.isTtsSpeaking -> BlobActivityState.AI_TALKING
        uiState.voiceRmsDb > 30f || inputText.isNotEmpty() -> BlobActivityState.USER_TALKING
        else -> BlobActivityState.IDLE
    }

    // Turn-taking in Voice Mode: when assistant stops speaking/reasoning, automatically turn mic on
    LaunchedEffect(
        uiState.screenMode,
        uiState.isListeningVoice,
        uiState.isVoiceMutedByUser,
        uiState.isTtsSpeaking,
        uiState.isGenerating,
        uiState.isReasoning
    ) {
        if (uiState.screenMode == AppScreenMode.VOICE_MODE &&
            !uiState.isVoiceMutedByUser &&
            !uiState.isTtsSpeaking &&
            !uiState.isGenerating &&
            !uiState.isReasoning &&
            !uiState.isListeningVoice
        ) {
            onMicButtonClick()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        moodTheme.coreNebulaColor.copy(alpha = 0.38f),
                        Color.Black
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val screenWidthDp = maxWidth
        val screenHeightDp = maxHeight

        val lastLlmMsgId = uiState.messages.lastOrNull { !it.isUser }?.id
        val lastLlmYDp = with(density) { lastLlmBubbleCenterYInPx.toDp() }

        val targetBlobSize = when (uiState.screenMode) {
            AppScreenMode.OPEN_HOME -> 290.dp
            AppScreenMode.VOICE_MODE -> 255.dp
            AppScreenMode.TEXT_CHAT -> if (lastLlmMsgId != null) 42.dp else 240.dp
            AppScreenMode.SETTINGS -> 0.dp
        }

        val targetBlobX = when (uiState.screenMode) {
            AppScreenMode.OPEN_HOME -> (screenWidthDp - targetBlobSize) / 2f
            AppScreenMode.VOICE_MODE -> (screenWidthDp - targetBlobSize) / 2f
            AppScreenMode.TEXT_CHAT -> if (lastLlmMsgId != null) 14.dp else (screenWidthDp - targetBlobSize) / 2f
            AppScreenMode.SETTINGS -> (screenWidthDp / 2f)
        }

        val targetBlobY = when (uiState.screenMode) {
            AppScreenMode.OPEN_HOME -> ((screenHeightDp - targetBlobSize) * 0.40f).coerceAtLeast(80.dp)
            AppScreenMode.VOICE_MODE -> (screenHeightDp * 0.20f)
            AppScreenMode.TEXT_CHAT -> {
                if (lastLlmMsgId != null && lastLlmYDp > 40.dp) {
                    (lastLlmYDp - 21.dp).coerceIn(64.dp, screenHeightDp - 110.dp)
                } else {
                    screenHeightDp * 0.52f
                }
            }
            AppScreenMode.SETTINGS -> screenHeightDp
        }

        val animatedBlobSize by animateDpAsState(
            targetValue = targetBlobSize,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 190f),
            label = "blobSize"
        )
        val animatedBlobX by animateDpAsState(
            targetValue = targetBlobX,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 190f),
            label = "blobX"
        )
        val animatedBlobY by animateDpAsState(
            targetValue = targetBlobY,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 190f),
            label = "blobY"
        )

        // 1. Continuous Animated Cosmic Biomorphic AI Blob Layer (Synced with Mic + TTS Audio!)
        if (animatedBlobSize > 2.dp) {
            CosmicAiBlob(
                activityState = blobActivityState,
                moodTheme = moodTheme,
                typingPulseTrigger = typingPulseTrigger,
                voiceRmsLevel = uiState.voiceRmsDb,
                ttsAudioLevel = uiState.ttsAudioLevel,
                showVoiceConcentricRings = uiState.screenMode == AppScreenMode.VOICE_MODE,
                modifier = Modifier
                    .offset(x = animatedBlobX, y = animatedBlobY)
                    .size(animatedBlobSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        // Tapping the blob talks/interrupts only in voice mode; on the home and chat screens it's decoration.
                        enabled = uiState.screenMode == AppScreenMode.VOICE_MODE
                    ) {
                        onBlobClick()
                    }
            )
        }

        // 2. Foreground Screen Content based on AppScreenMode
        when (uiState.screenMode) {
            AppScreenMode.SETTINGS -> {
                BiomorphicSettingsScreen(
                    uiState = uiState,
                    onBack = onBackFromSettings,
                    onResetChat = onNewChat,
                    onToggleVoiceProfiles = onToggleVoiceProfiles,
                    onOpenVoiceProfiles = onOpenVoiceProfiles,
                    onToggleVoiceTrigger = onToggleVoiceTrigger,
                    onOpenWakeWordSettings = onOpenWakeWordSettings,
                    onOpenPersonalitySettings = onOpenPersonalitySettings,
                    onToggleAiMemory = onToggleAiMemory,
                    onOpenMemoryManager = onOpenMemoryManager,
                    onToggleSoundNotifications = onToggleSoundNotifications,
                    onOpenCustomization = onOpenCustomization,
                    onOpenIntegrations = onOpenIntegrations,
                    onOpenHardwareAcceleration = onOpenHardwareAcceleration,
                    onToggleVoiceDuplexMode = onToggleVoiceDuplexMode,
                    onToggleLimitCompute = onToggleLimitCompute,
                    onOpenLogs = onOpenLogs,
                    featureActions = featureActions
                )
            }

            AppScreenMode.VOICE_MODE -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top-left Glowing 3-Bar Menu Button + AI Name Title
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GlowingHamburgerButton(moodTheme = moodTheme, onClick = { onToggleSideMenu(null) })
                        Text(
                            text = aiName,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.size(42.dp))
                    }

                    // Diagnostic Error Banner in Voice Mode
                    DiagnosticErrorBanner(
                        errorMessage = uiState.errorMessage,
                        onDismiss = onDismissError
                    )

                    // Optional Live Camera Viewfinder in Voice Mode (Gemini Live style)
                    AnimatedVisibility(visible = uiState.isLiveCameraActive) {
                        if (uiState.isLiveCameraActive) {
                            CameraLiveViewfinder(
                                moodTheme = moodTheme,
                                onFrameCaptured = onLiveCameraFrameCaptured,
                                onClose = onToggleLiveCamera,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Subtitles that follow the voice: only words already spoken, newest in the accent colour
                    SpokenCaptions(moodTheme = moodTheme)

                    // "Listening..." / live partial transcript under the centered Cosmic Blob
                    Text(
                        text = uiState.liveVoicePartial.ifEmpty {
                            when {
                                uiState.isReasoning -> "$aiName is reasoning..."
                                uiState.isGenerating && !uiState.isTtsSpeaking -> "$aiName is thinking..."
                                uiState.isTtsSpeaking -> "$aiName speaking (${uiState.selectedVoiceProfile.name})..."
                                uiState.isListeningVoice -> "Listening with Whisper..."
                                else -> "Tap Mic to speak with $aiName"
                            }
                        },
                        color = Color(0xFFCBD5E1),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bottom Control Row: Camera | Mic Mute | Reasoning | Nokia Red Hang-Up
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 18.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Camera Live Viewfinder Button (bottom left)
                        FrostedCircleIconButton(
                            size = 52.dp,
                            moodTheme = moodTheme,
                            onClick = onToggleLiveCamera
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Toggle Live Camera Viewfinder",
                                tint = if (uiState.isLiveCameraActive) moodTheme.secondaryAccent else Color(0xFFE2E8F0),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // 2. Mic Mute / Unmute Button (toggles mic capture without ending the call)
                        FrostedCircleIconButton(
                            size = 58.dp,
                            moodTheme = moodTheme,
                            onClick = onMicButtonClick
                        ) {
                            Icon(
                                imageVector = if (uiState.isListeningVoice) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (uiState.isListeningVoice) "Turn Mic Off" else "Start Listening",
                                tint = if (uiState.isListeningVoice) Color(0xFFEC4899) else moodTheme.secondaryAccent,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 3. Reasoning Toggle Button with LLM Status Indicator Dot
                        val statusDotColor = when {
                            uiState.errorMessage != null ||
                                uiState.gemmaMemoryStatus.contains("Error", ignoreCase = true) ||
                                uiState.gemmaMemoryStatus.contains("Missing", ignoreCase = true) -> Color(0xFFEF4444)
                            uiState.isGemmaLoadingMemory -> Color(0xFFF59E0B)
                            uiState.isGemmaLoadedInRam -> if (uiState.isLlmOnCpu) Color(0xFF3B82F6) else Color(0xFF10B981)
                            else -> Color(0xFF64748B)
                        }
                        Box(contentAlignment = Alignment.TopEnd) {
                            FrostedCircleIconButton(
                                size = 52.dp,
                                moodTheme = moodTheme,
                                onClick = onToggleReasoning
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = "Toggle Reasoning Mode",
                                    tint = if (uiState.isReasoningToggleEnabled) moodTheme.secondaryAccent else Color(0xFF94A3B8),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .padding(top = 1.dp, end = 1.dp)
                                    .size(9.dp)
                                    .shadow(4.dp, CircleShape, spotColor = statusDotColor)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            )
                        }

                        // 4. Hang Up Button: Classic Nokia Red Phone Button
                        Surface(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .clickable { onHangUp() },
                            shape = CircleShape,
                            color = Color(0xFFDC2626), // Classic Nokia Red
                            shadowElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Hang Up Voice Mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }

            AppScreenMode.OPEN_HOME, AppScreenMode.TEXT_CHAT -> {
                val listState = rememberLazyListState()
                LaunchedEffect(uiState.messages.size) {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.lastIndex)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Top-left Glowing 3-Bar Menu Button + Dynamic AI Name + Active Reasoning status pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowingHamburgerButton(moodTheme = moodTheme, onClick = { onToggleSideMenu(null) })
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = aiName,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (uiState.isReasoningToggleEnabled) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(moodTheme.coreNebulaColor.copy(alpha = 0.85f))
                                    .border(1.dp, moodTheme.secondaryAccent, RoundedCornerShape(14.dp))
                                    .clickable { onToggleReasoning() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = moodTheme.secondaryAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Reasoning ON",
                                    color = moodTheme.secondaryAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Diagnostic Error Banner in Home / Text Chat
                    DiagnosticErrorBanner(
                        errorMessage = uiState.errorMessage,
                        onDismiss = onDismissError
                    )

                    // A model is being prepared (first load after an update can take a minute)
                    AnimatedVisibility(visible = uiState.isGemmaLoadingMemory) {
                        ModelPreparingBanner(
                            modelName = uiState.primaryModelDisplayName,
                            status = uiState.gemmaMemoryStatus,
                            moodTheme = moodTheme
                        )
                    }

                    // Project / temporary chat indicator
                    if (uiState.activeProjectName != null || uiState.isTemporaryChat) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xCC111726))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (uiState.isTemporaryChat) Icons.Default.VisibilityOff else Icons.Default.Folder,
                                contentDescription = null,
                                tint = moodTheme.secondaryAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isTemporaryChat) "Temporary chat: not saved, no memory"
                                else "Project: ${uiState.activeProjectName}",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (uiState.isTemporaryChat) "End" else "Leave",
                                color = moodTheme.secondaryAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    if (uiState.isTemporaryChat) featureActions.onToggleTemporaryChat(false) else featureActions.onLeaveProject()
                                }
                            )
                        }
                    }

                    // Conversation Area
                    if (uiState.screenMode == AppScreenMode.TEXT_CHAT && uiState.messages.isNotEmpty()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(uiState.messages, key = { it.id }) { msg ->
                                val isLastLlmMessage = !msg.isUser && msg.id == lastLlmMsgId
                                BiomorphicChatBubbleRow(
                                    message = msg,
                                    moodTheme = moodTheme,
                                    modelDisplayName = uiState.primaryModelDisplayName,
                                    isLastLlmMessage = isLastLlmMessage,
                                    onLastLlmPositioned = { centerY ->
                                        lastLlmBubbleCenterYInPx = centerY
                                    },
                                    onSpeakMessage = onSpeakMessage
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Optional Multimodal Attachment Drawer (when user taps '+')
                    AnimatedVisibility(visible = uiState.pendingAttachments.isNotEmpty() || uiState.isResearchArmed || uiState.imageGenArmed != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 6.dp)
                        ) {
                            if (uiState.pendingAttachments.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    uiState.pendingAttachments.forEach { att ->
                                        AttachmentChip(att, moodTheme = moodTheme, onRemove = { onRemoveAttachment(att.id) })
                                    }
                                }
                            }
                            if (uiState.isResearchArmed) {
                                // Mode chip: the next message starts Deep Research
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(moodTheme.secondaryAccent.copy(alpha = 0.16f))
                                        .border(1.dp, moodTheme.secondaryAccent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                        .clickable { featureActions.onSetResearchArmed(false) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.TravelExplore, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Deep research", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.Close, contentDescription = "Turn off Deep research", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                                }
                            }
                            uiState.imageGenArmed?.let {
                                // Mode chip: typed messages are drawn as pictures (an attached photo is edited)
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(moodTheme.secondaryAccent.copy(alpha = 0.16f))
                                        .border(1.dp, moodTheme.secondaryAccent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                        .clickable { featureActions.onDisarmImageGen() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Palette, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (uiState.pendingAttachments.any { it.modality == MediaModality.IMAGE }) "Edit photo"
                                        else "Create image",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.Close, contentDescription = "Turn off image mode", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Bottom Control Bar
                    val chatStatusDotColor = when {
                        uiState.errorMessage != null ||
                            uiState.gemmaMemoryStatus.contains("Error", ignoreCase = true) ||
                            uiState.gemmaMemoryStatus.contains("Missing", ignoreCase = true) -> Color(0xFFEF4444)
                        uiState.isGemmaLoadingMemory -> Color(0xFFF59E0B)
                        uiState.isGemmaLoadedInRam -> if (uiState.isLlmOnCpu) Color(0xFF3B82F6) else Color(0xFF10B981)
                        else -> Color(0xFF64748B)
                    }
                    if (showAttachmentMenu) {
                        PlusMenuSheet(
                            state = PlusMenuState(
                                isResearchArmed = uiState.isResearchArmed,
                                isReasoningOn = uiState.isReasoningToggleEnabled,
                                isTemporaryChat = uiState.isTemporaryChat,
                                activeProjectId = uiState.activeProjectId,
                                activeProjectName = uiState.activeProjectName,
                                imageGenArmed = uiState.imageGenArmed,
                                imageGenReady = uiState.imageGenReady,
                                imageGenDownloads = uiState.imageGenDownloads
                            ),
                            actions = PlusMenuActions(
                                onCamera = featureActions.onTakePhoto,
                                onPhotos = onAttachImage,
                                onFiles = featureActions.onPickFiles,
                                onAudio = onAttachAudio,
                                onVideo = onAttachVideo,
                                onToggleResearch = featureActions.onSetResearchArmed,
                                onToggleReasoning = onToggleReasoning,
                                onToggleTemporary = featureActions.onToggleTemporaryChat,
                                onPickSkill = { skill ->
                                    val trigger = skill.triggers.firstOrNull().orEmpty()
                                    inputText = if (trigger.isEmpty()) inputText else "$trigger: ${inputText.trimStart()}"
                                },
                                onPickProject = featureActions.onPickProject,
                                onMakePdf = { inputText = "Make a PDF about " },
                                onOpenTools = featureActions.onOpenToolsAt,
                                onPickImageModel = featureActions.onPickImageModel,
                                onCancelImageDownload = featureActions.onCancelImageDownload
                            ),
                            moodTheme = moodTheme,
                            onDismiss = { showAttachmentMenu = false }
                        )
                    }
                    BottomBiomorphicInputBar(
                        inputText = inputText,
                        aiName = if (uiState.isResearchArmed) "" else aiName,
                        moodTheme = moodTheme,
                        isReasoningEnabled = uiState.isReasoningToggleEnabled,
                        isListeningVoice = uiState.isListeningVoice,
                        statusDotColor = chatStatusDotColor,
                        onInputChange = { newText ->
                            inputText = newText
                            typingPulseTrigger++
                        },
                        showPlusButton = true,
                        onPlusClick = { showAttachmentMenu = !showAttachmentMenu },
                        onSendClick = {
                            if (inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) {
                                onSendPrompt(inputText)
                                inputText = ""
                                showAttachmentMenu = false
                            }
                        },
                        onToggleReasoningClick = onToggleReasoning,
                        onMicClick = onMicButtonClick
                    )
                }
            }
        }

        // 3. Frosted Glass Side Menu Drawer Overlay (`SIDE MENU`)
        AnimatedVisibility(
            visible = uiState.isSideMenuOpen,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onToggleSideMenu(false) }
                    )
            ) {
                FrostedSideMenuDrawer(
                    previousChats = uiState.previousChats.filter { !it.locked || uiState.lockedChatsUnlocked },
                    lockedHiddenCount = if (uiState.lockedChatsUnlocked) 0 else uiState.previousChats.count { it.locked },
                    isTemporaryChat = uiState.isTemporaryChat,
                    featureActions = featureActions,
                    moodTheme = moodTheme,
                    onSelectChat = onSelectPreviousChat,
                    onDeleteChat = onDeleteChat,
                    onClearAllChats = onClearAllChats,
                    onNewChat = onNewChat,
                    onOpenSettings = onNavigateSettings,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(290.dp)
                        .padding(12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                )
            }
        }
    }
}

/**
 * Top-left glowing 3-bar gradient menu icon themed by [moodTheme].
 */
@Composable
private fun GlowingHamburgerButton(
    moodTheme: BlobMoodTheme,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(30.dp, 22.dp)) {
            val barHeight = 3.6.dp.toPx()
            val spacing = (size.height - 3 * barHeight) / 2f
            val brush = Brush.horizontalGradient(
                colors = listOf(moodTheme.primaryAccent, moodTheme.secondaryAccent)
            )
            for (i in 0..2) {
                val y = i * (barHeight + spacing)
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
                )
            }
        }
    }
}

/**
 * Compact, always-visible top visual cue displaying:
 * 1. Real-time memory loading status of the on-device models:
 *    - Whisper (multilingual STT)
 *    - Gemma 4 E2B (2.58GB LiteRT-LM multimodal LLM)
 * 2. Live processing speed (`tok/s`, TTFT `ms`, and STT latency `ms`).
 */
/**
 * Minimalist Top Status Cue: Clean single dot indicating the state of the main model.
 * - Green: Loaded into RAM
 * - Orange: Loading into RAM
 * - Red: Unavailable / Error
 * - Gray: Not loaded / Standby
 */
@Composable
fun ModelMemoryAndSpeedTopCueBar(
    uiState: AssistantUiState,
    modifier: Modifier = Modifier
) {
    val moodTheme = uiState.moodTheme
    val gemmaDotColor = when {
        uiState.errorMessage != null ||
            uiState.gemmaMemoryStatus.contains("Error", ignoreCase = true) ||
            uiState.gemmaMemoryStatus.contains("Missing", ignoreCase = true) -> Color(0xFFEF4444) // Red: unavailable
        uiState.isGemmaLoadingMemory -> Color(0xFFF59E0B) // Orange: loading
        uiState.isGemmaLoadedInRam -> Color(0xFF10B981) // Green: loaded
        else -> Color(0xFF64748B) // Gray: not loaded
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xB20D111A),
            border = BorderStroke(0.8.dp, moodTheme.primaryAccent.copy(alpha = 0.28f))
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(gemmaDotColor)
                )
            }
        }
    }
}

@Composable
private fun DiagnosticErrorBanner(
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
        if (!errorMessage.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF02A0A14)
            ) {
                Row(
                    modifier = Modifier
                        .border(1.2.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFECACA),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss Error",
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom Input Bar:
 * - Left: Circular frosted `+` button
 * - Center: Black pill text field with mood-themed gradient border and inner right Paper-Plane Send button
 * - Right:
 *   - When [inputText] is blank: Circular frosted **Voice Mode (Mic)** button.
 *   - When [inputText] is not blank (user is texting): Turns into a **Reasoning Toggle** button
 *     ([isReasoningEnabled] is OFF by default; when toggled ON, activates Gemma 4 E2B `<|think|>` reasoning).
 */
@Composable
private fun BottomBiomorphicInputBar(
    inputText: String,
    aiName: String,
    moodTheme: BlobMoodTheme,
    isReasoningEnabled: Boolean,
    isListeningVoice: Boolean,
    statusDotColor: Color,
    onInputChange: (String) -> Unit,
    showPlusButton: Boolean,
    onPlusClick: () -> Unit,
    onSendClick: () -> Unit,
    onToggleReasoningClick: () -> Unit,
    onMicClick: () -> Unit
) {
    val isUserTexting = inputText.isNotBlank()
    val borderBrush = moodBorderBrush(moodTheme)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume taps on bottom bar to prevent fall-through */ }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showPlusButton) {
            FrostedCircleIconButton(
                size = 54.dp,
                moodTheme = moodTheme,
                onClick = onPlusClick
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach Media",
                    tint = moodTheme.secondaryAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        val focusRequester = remember { FocusRequester() }

        // Center Black Pill with Gradient Border + Inner Paper-Plane Button
        Row(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .shadow(16.dp, RoundedCornerShape(27.dp), spotColor = moodTheme.secondaryAccent)
                .clip(RoundedCornerShape(27.dp))
                .background(Color(0xEE05070B))
                .border(1.4.dp, borderBrush, RoundedCornerShape(27.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (aiName.isEmpty()) "What should I research?" else "Ask $aiName...",
                        color = Color(0xFF64748B),
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(moodTheme.secondaryAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }

            // Inner Right Dark Circle with Paper-Plane Send Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF161B26))
                    .clickable(onClick = onSendClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = moodTheme.secondaryAccent,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-28f)
                )
            }
        }

        // Right Circular Button:
        // When user is texting -> Reasoning Toggle Button (OFF by default).
        // When text field is empty -> Voice Mode (Mic) Button.
        // Paired with LLM Status Indicator Dot right on/next to the button
        Box(contentAlignment = Alignment.TopEnd) {
            if (isUserTexting) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .shadow(
                            elevation = if (isReasoningEnabled) 18.dp else 8.dp,
                            shape = CircleShape,
                            spotColor = if (isReasoningEnabled) moodTheme.secondaryAccent else moodTheme.primaryAccent
                        )
                        .clip(CircleShape)
                        .background(
                            if (isReasoningEnabled) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        moodTheme.secondaryAccent.copy(alpha = 0.35f),
                                        moodTheme.coreNebulaColor
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(Color(0xCC1E2436), Color(0xDD111522))
                                )
                            }
                        )
                        .border(
                            width = if (isReasoningEnabled) 2.dp else 1.2.dp,
                            brush = if (isReasoningEnabled) {
                                Brush.linearGradient(listOf(moodTheme.secondaryAccent, Color.White))
                            } else {
                                borderBrush
                            },
                            shape = CircleShape
                        )
                        .clickable(onClick = onToggleReasoningClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = if (isReasoningEnabled) "Reasoning Mode ON" else "Reasoning Mode OFF",
                        tint = if (isReasoningEnabled) moodTheme.secondaryAccent else Color(0xFF94A3B8),
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                FrostedCircleIconButton(
                    size = 54.dp,
                    moodTheme = moodTheme,
                    onClick = onMicClick
                ) {
                    Icon(
                        imageVector = if (isListeningVoice) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListeningVoice) "Turn Mic Off" else "Voice Mode",
                        tint = if (isListeningVoice) Color(0xFFEC4899) else moodTheme.secondaryAccent,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 1.dp, end = 1.dp)
                    .size(9.dp)
                    .shadow(4.dp, CircleShape, spotColor = statusDotColor)
                    .clip(CircleShape)
                    .background(statusDotColor)
                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            )
        }
    }
}

@Composable
private fun FrostedCircleIconButton(
    size: androidx.compose.ui.unit.Dp,
    moodTheme: BlobMoodTheme,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(14.dp, CircleShape, spotColor = moodTheme.primaryAccent)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xCC1E2436),
                        Color(0xDD111522)
                    )
                )
            )
            .border(1.2.dp, moodBorderBrush(moodTheme), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Chat Bubble Row matching Image 2 Panel 3 (`TEXT CHAT`) and styled with [moodTheme]:
 * - User bubbles: Right-aligned frosted glass with [moodTheme] primary border.
 * - LLM bubbles: Left-aligned with a 48.dp left gutter so the single continuous [CosmicAiBlob]
 *   sits exclusively next to the **last** LLM response bubble!
 */
@Composable
private fun BiomorphicChatBubbleRow(
    message: ChatMessage,
    moodTheme: BlobMoodTheme,
    modelDisplayName: String = "Fossistant",
    isLastLlmMessage: Boolean,
    onLastLlmPositioned: (Float) -> Unit,
    onSpeakMessage: (String) -> Unit
) {
    val isUser = message.isUser
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isLastLlmMessage) {
                    Modifier.onGloballyPositioned { coords ->
                        val rootPos = coords.positionInRoot()
                        val centerY = rootPos.y + (coords.size.height / 2f)
                        onLastLlmPositioned(centerY)
                    }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isUser) {
            Spacer(modifier = Modifier.width(48.dp))
        }

        val bubbleFill = if (isUser) {
            Brush.linearGradient(
                colors = listOf(moodTheme.userBubbleStart, moodTheme.userBubbleEnd)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(moodTheme.aiBubbleStart, moodTheme.aiBubbleEnd)
            )
        }

        val bubbleBorder = if (isUser) {
            Brush.linearGradient(
                colors = listOf(moodTheme.primaryAccent, moodTheme.tertiaryAccent)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(moodTheme.secondaryAccent, moodTheme.tertiaryAccent)
            )
        }

        var isReasoningExpanded by remember(message.id) { mutableStateOf(false) }
        val hasReasoning = !message.reasoningTrace.isNullOrBlank() || message.isReasoningPhase
        // Pictures, charts, infographics and search photos get a wider bubble.
        val imageAttachments = message.attachments.filter { it.modality == MediaModality.IMAGE }
        val hasVisuals = !isUser && org.fossistant.assistant.ui.components.hasToolVisuals(message.toolExecutions)

        Column(
            modifier = Modifier
                .widthIn(max = if (imageAttachments.isNotEmpty() || hasVisuals) 340.dp else 285.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    spotColor = if (isUser) moodTheme.primaryAccent else moodTheme.secondaryAccent
                )
                .clip(RoundedCornerShape(20.dp))
                .background(bubbleFill)
                .border(1.3.dp, bubbleBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!isUser && hasReasoning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66000000))
                        .border(0.8.dp, moodTheme.primaryAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .clickable { isReasoningExpanded = !isReasoningExpanded }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CosmicAiBlob(
                            activityState = BlobActivityState.AI_REASONING,
                            moodTheme = moodTheme,
                            showVoiceConcentricRings = false,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (message.isReasoningPhase) "Reasoning..." else "Reasoning",
                            color = moodTheme.secondaryAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = if (isReasoningExpanded) "▲" else "▼",
                        color = moodTheme.secondaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                AnimatedVisibility(visible = isReasoningExpanded && !message.reasoningTrace.isNullOrBlank()) {
                    Text(
                        text = message.reasoningTrace.orEmpty()
                            .replace("<|think|>", "")
                            .replace("<think>", "")
                            .replace("</think>", "")
                            .trim(),
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x55000000))
                            .padding(8.dp)
                    )
                }
            }
            if (message.toolExecutions.isNotEmpty()) {
                org.fossistant.assistant.ui.components.ToolActivityChips(
                    tools = message.toolExecutions,
                    accent = moodTheme.secondaryAccent,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                org.fossistant.assistant.ui.components.ToolVisuals(
                    tools = message.toolExecutions,
                    accent = moodTheme.secondaryAccent,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (imageAttachments.isNotEmpty()) {
                org.fossistant.assistant.ui.components.ChatImages(
                    attachments = imageAttachments,
                    accent = moodTheme.secondaryAccent,
                    canSave = !isUser,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            val visibleMessageText = if (message.isReasoningPhase && message.text == "Thinking...") {
                ""
            } else {
                message.text
            }
            if (visibleMessageText.isNotEmpty() || !hasReasoning) {
                Text(
                    text = visibleMessageText,
                    color = Color(0xFFF8FAFC),
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }
            if (!isUser) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (message.modelTier == ModelTier.DIRECT_ACTION) {
                            "Direct action"
                        } else if (!message.reasoningTrace.isNullOrBlank()) {
                            "${message.modelName ?: modelDisplayName} (<think>)"
                        } else {
                            message.modelName ?: modelDisplayName
                        },
                        color = Color(0x9994A3B8),
                        fontSize = 10.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Read Aloud",
                            tint = moodTheme.secondaryAccent.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onSpeakMessage(message.text) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Frosted Glassmorphic Side Menu Drawer matching Image 2 Panel 1 (`SIDE MENU`).
 */
@Composable
private fun FrostedSideMenuDrawer(
    previousChats: List<PreviousChatSession>,
    lockedHiddenCount: Int = 0,
    isTemporaryChat: Boolean = false,
    featureActions: FeatureUiActions = FeatureUiActions(),
    moodTheme: BlobMoodTheme,
    onSelectChat: (PreviousChatSession) -> Unit,
    onDeleteChat: (String) -> Unit,
    onClearAllChats: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderBrush = moodBorderBrush(moodTheme)
    Column(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(30.dp), spotColor = moodTheme.primaryAccent)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xD8141826),
                        Color(0xDF0E111B)
                    )
                )
            )
            .border(1.4.dp, borderBrush, RoundedCornerShape(30.dp))
            .padding(20.dp)
    ) {
        // User Avatar Circle with Mood Glow
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(12.dp, CircleShape, spotColor = moodTheme.secondaryAccent)
                .clip(CircleShape)
                .background(Color(0xFF1E2638))
                .border(1.dp, borderBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Profile",
                tint = Color(0xFFE2E8F0),
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Temporary chat
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isTemporaryChat) moodTheme.secondaryAccent.copy(alpha = 0.25f) else Color(0xCC111726))
                    .border(1.2.dp, borderBrush, RoundedCornerShape(20.dp))
                    .clickable { featureActions.onToggleTemporaryChat(!isTemporaryChat) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isTemporaryChat) "Temp: on" else "Temporary", color = Color(0xFFE2E8F0), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Projects: tap to start a chat inside one
        val projects by (org.fossistant.assistant.FossistantApp.instance?.features?.projects?.items
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PROJECTS", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
            Text(
                text = "+ New",
                color = moodTheme.secondaryAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { featureActions.onOpenToolsAt("PROJECTS") }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (projects.isEmpty()) {
            Text("No projects yet", color = Color(0xFF64748B), fontSize = 13.sp)
        } else {
            projects.take(5).forEach { project ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { featureActions.onStartProjectChat(project.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(project.name, color = Color(0xFFCBD5E1), fontSize = 14.sp, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "PREVIOUS CHATS",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (lockedHiddenCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = featureActions.onShowLockedChats)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Locked chats ($lockedHiddenCount)", color = Color(0xFF94A3B8), fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (previousChats.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "No previous chats yet.\nStart typing or speaking to begin!",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                previousChats.forEach { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectChat(session) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = session.title,
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Chat",
                            tint = Color(0xFF64748B),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onDeleteChat(session.id) }
                        )
                    }
                }
            }
        }

        // Bottom Row: [New Chat] and [⚙ Settings] Pill Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xCC111726))
                    .border(1.2.dp, borderBrush, RoundedCornerShape(22.dp))
                    .clickable(onClick = onNewChat),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "New Chat", color = Color(0xFFE2E8F0), fontSize = 13.sp)
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xCC111726))
                    .border(1.2.dp, borderBrush, RoundedCornerShape(22.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = moodTheme.secondaryAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = "Settings", color = Color(0xFFE2E8F0), fontSize = 13.sp)
            }
        }
    }
}

/**
 * Glassmorphic Settings Screen with dedicated submenus for:
 * 1. Voice Profiles (7 Languages, 21 Fast TTS Voices)
 * 2. Voice Trigger / Wake Word Submenu (Custom phrase, sensitivity slider, live mic test meter)
 * 3. Personality & AI Name Submenu (Custom AI Name that changes app title, Persona, Custom instructions, Response length)
 * 4. Customization (Blob Color & App Mood + Dynamic Launcher Icon styles)
 * 5. Integrations
 * 6. Memory
 * 7. Privacy Options
 * 8. Sound & Notifications
 */
@Composable
private fun BiomorphicSettingsScreen(
    uiState: AssistantUiState,
    onBack: () -> Unit,
    onResetChat: () -> Unit,
    onToggleVoiceProfiles: (Boolean) -> Unit,
    onOpenVoiceProfiles: () -> Unit,
    onToggleVoiceTrigger: (Boolean) -> Unit,
    onOpenWakeWordSettings: () -> Unit,
    onOpenPersonalitySettings: () -> Unit,
    onToggleAiMemory: (Boolean) -> Unit,
    onOpenMemoryManager: () -> Unit,
    onToggleSoundNotifications: (Boolean) -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenHardwareAcceleration: () -> Unit,
    onToggleVoiceDuplexMode: (Boolean) -> Unit = {},
    onToggleLimitCompute: (Boolean) -> Unit = {},
    onOpenLogs: () -> Unit = {},
    featureActions: FeatureUiActions = FeatureUiActions()
) {
    var confirmDeleteChats by remember { mutableStateOf(false) }
    if (confirmDeleteChats) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChats = false },
            containerColor = Color(0xFF101420),
            title = { Text("Delete all chats?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Every saved chat is deleted from this phone. This can't be undone (unless you exported a backup).", color = Color(0xFFE2E8F0)) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteChats = false; featureActions.onClearAllChats() }) { Text("Delete all", color = Color(0xFFEF4444)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChats = false }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
        )
    }
    val moodTheme = uiState.moodTheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header: `< Back` | `Settings` | Exit Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = moodTheme.secondaryAccent,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = "Back",
                    color = Color(0xFFCBD5E1),
                    fontSize = 16.sp
                )
            }

            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = "Reset Session",
                tint = Color(0xFF94A3B8),
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onResetChat)
            )
        }

        // 1. Voice Profiles (7 Languages, 21 Fast TTS Voices - Tap row to choose voice, switch to toggle)
        SettingsGlassRowToggle(
            title = "Voice Profiles (${uiState.selectedVoiceProfile.name})",
            checked = uiState.voiceProfilesEnabled,
            moodTheme = moodTheme,
            onRowClick = onOpenVoiceProfiles,
            onCheckedChange = onToggleVoiceProfiles
        )

        // 2. Voice Trigger / Wake Word Submenu (Tap row to open Wake Word setup submenu, switch to toggle)
        SettingsGlassRowToggle(
            title = "Voice Trigger (\"${uiState.wakeWordPhrase}\")",
            checked = uiState.voiceTriggerEnabled,
            moodTheme = moodTheme,
            onRowClick = onOpenWakeWordSettings,
            onCheckedChange = onToggleVoiceTrigger
        )

        // 2b. Duplex Voice Mode vs Turn-by-Turn switch
        SettingsGlassRowToggle(
            title = if (uiState.isVoiceDuplexMode) "Voice Mode: Duplex (Barge-In)" else "Voice Mode: Turn-by-Turn",
            checked = uiState.isVoiceDuplexMode,
            moodTheme = moodTheme,
            onCheckedChange = onToggleVoiceDuplexMode
        )

        // 2c. Compute cap: keeps the phone usable while a big model runs on the CPU
        SettingsGlassRowToggle(
            title = if (uiState.limitCompute) "Limit Compute: On (phone stays responsive)" else "Limit Compute: Off (fastest)",
            checked = uiState.limitCompute,
            moodTheme = moodTheme,
            onCheckedChange = onToggleLimitCompute
        )

        // 2d. MTP speculative decoding (Gemma): switch off to compare speed
        SettingsGlassRowToggle(
            title = if (uiState.mtpEnabled) "Speculative Decoding (MTP): On" else "Speculative Decoding (MTP): Off",
            checked = uiState.mtpEnabled,
            moodTheme = moodTheme,
            onCheckedChange = featureActions.onToggleMtp
        )

        // 2e. Constrained decoding: Gemma can only write well-formed tool calls (LiteRT-LM, experimental)
        SettingsGlassRowToggle(
            title = if (uiState.strictToolCalls) "Strict Tool Calls: On" else "Strict Tool Calls: Off",
            checked = uiState.strictToolCalls,
            moodTheme = moodTheme,
            onCheckedChange = featureActions.onToggleStrictToolCalls
        )

        // 3. Personality & AI Name Submenu (Change AI Name, App Title, Character Persona & Custom Instructions)
        SettingsGlassRowChevron(
            title = "Personality & AI Name (${uiState.aiName})",
            moodTheme = moodTheme,
            onClick = onOpenPersonalitySettings
        )

        // 4. Customization (Blob Color & App Mood + Dynamic Launcher Icon Style)
        SettingsGlassRowChevron(
            title = "Blob Color & App Icon (${uiState.moodTheme.displayName})",
            moodTheme = moodTheme,
            onClick = onOpenCustomization
        )

        // 5. Integrations (17 Google Assistant capabilities & system roles)
        SettingsGlassRowChevron(
            title = "Integrations",
            moodTheme = moodTheme,
            onClick = onOpenIntegrations
        )

        // 6. Hardware Acceleration (GPU & NPU hardware offloading for Whisper STT and Gemma)
        val accelSubtitle = "Whisper: ${uiState.whisperHardwareBackend.shortBadge} • Gemma: ${uiState.primaryHardwareBackend.shortBadge}"
        SettingsGlassRowChevron(
            title = "Hardware Acceleration ($accelSubtitle)",
            moodTheme = moodTheme,
            onClick = onOpenHardwareAcceleration
        )

        // 6. Memory (Persistent On-Device AI Memory System - Tap row to view/edit facts, switch to toggle)
        SettingsGlassRowToggle(
            title = "Memory (${uiState.memoryFacts.size} facts)",
            checked = uiState.aiMemoryEnabled,
            moodTheme = moodTheme,
            onRowClick = onOpenMemoryManager,
            onCheckedChange = onToggleAiMemory
        )

        // 7. Privacy Options
        SettingsGlassRowChevron(
            title = "Privacy Options",
            moodTheme = moodTheme,
            onClick = onOpenMemoryManager
        )

        // 7b. Your data: one-file backup (chats, files sent, memories, skills, projects, recordings, settings)
        SettingsGlassRowChevron(
            title = "Export Data (backup file)",
            moodTheme = moodTheme,
            onClick = featureActions.onExportData
        )
        SettingsGlassRowChevron(
            title = "Import Data (from a backup)",
            moodTheme = moodTheme,
            onClick = featureActions.onImportData
        )
        SettingsGlassRowChevron(
            title = "Delete All Chats",
            moodTheme = moodTheme,
            onClick = { confirmDeleteChats = true }
        )

        // 8. System Logs (Crash-resilient 10,000-line circular ring buffer)
        SettingsGlassRowChevron(
            title = "Setup & guide (language, models, permissions)",
            moodTheme = moodTheme,
            onClick = featureActions.onOpenSetup
        )
        SettingsGlassRowChevron(
            title = "System Logs (${org.fossistant.assistant.util.AppLogger.getLineCount()} lines)",
            moodTheme = moodTheme,
            onClick = onOpenLogs
        )

        // 9. Sound & Notifications
        SettingsGlassRowToggle(
            title = "Sound & Notifications",
            checked = uiState.soundNotificationsEnabled,
            moodTheme = moodTheme,
            onCheckedChange = onToggleSoundNotifications
        )
    }
}

@Composable
private fun SettingsGlassRowToggle(
    title: String,
    checked: Boolean,
    moodTheme: BlobMoodTheme,
    onRowClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(12.dp, RoundedCornerShape(22.dp), spotColor = moodTheme.secondaryAccent)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xDD161B29), Color(0xDD101722))
                )
            )
            .border(1.3.dp, moodBorderBrush(moodTheme), RoundedCornerShape(22.dp))
            .then(if (onRowClick != null) Modifier.clickable(onClick = onRowClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = moodTheme.secondaryAccent,
                checkedTrackColor = moodTheme.coreNebulaColor,
                uncheckedThumbColor = Color(0xFF64748B),
                uncheckedTrackColor = Color(0xFF0F172A)
            )
        )
    }
}

@Composable
private fun SettingsGlassRowChevron(
    title: String,
    moodTheme: BlobMoodTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(12.dp, RoundedCornerShape(22.dp), spotColor = moodTheme.primaryAccent)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xDD161B29), Color(0xDD101722))
                )
            )
            .border(1.3.dp, moodBorderBrush(moodTheme), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, color = Color.White, fontSize = 15.sp)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun GlassAttachmentChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    moodTheme: BlobMoodTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xDD151B28))
            .border(1.dp, moodBorderBrush(moodTheme), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = moodTheme.secondaryAccent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun AttachmentChip(
    attachment: MediaAttachment,
    moodTheme: BlobMoodTheme,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, moodBorderBrush(moodTheme), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${attachment.fileName} (${attachment.modality})",
            color = Color.White,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Remove",
            tint = Color(0xFF94A3B8),
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onRemove)
        )
    }
}

/**
 * Dialog for selecting from the 7 Supported Languages and 21 Fast TTS Voices.
 */
@Composable
private fun VoiceProfilesDialog(
    selectedLanguage: SupportedLanguage,
    selectedProfile: FastTtsVoiceProfile,
    onSelectLanguage: (SupportedLanguage) -> Unit,
    onSelectProfile: (FastTtsVoiceProfile) -> Unit,
    onPreviewVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    val voicesForLang = FastMultilingualTtsEngine.profilesForLanguage(selectedLanguage)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("Language & Voice", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Language (${SupportedLanguage.entries.size - 1} languages; Automatic detects what you speak):",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                var languageQuery by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = languageQuery,
                    onValueChange = { languageQuery = it },
                    placeholder = { Text("Search languages") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val visibleLanguages = SupportedLanguage.entries.filter {
                    languageQuery.isBlank() || it.displayName.contains(languageQuery, ignoreCase = true)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF161B26))
                        .verticalScroll(rememberScrollState())
                ) {
                    visibleLanguages.forEach { lang ->
                        val isSelected = lang == selectedLanguage
                        Text(
                            text = lang.displayName,
                            color = if (isSelected) Color(0xFF5EEAD4) else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color(0xFF312E81) else Color.Transparent)
                                .clickable { onSelectLanguage(lang) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Voice style for ${selectedLanguage.displayName}:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                voicesForLang.forEach { profile ->
                    val isCurrent = profile.id == selectedProfile.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCurrent) Color(0xFF1E1B4B) else Color(0xFF161B26))
                            .border(
                                1.dp,
                                if (isCurrent) Color(0xFF5EEAD4) else Color(0xFF263042),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectProfile(profile) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${profile.styleDescription} • ${profile.speechRate}x speed",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                        if (isCurrent) {
                            Text("Active", color = Color(0xFF5EEAD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                TextButton(onClick = onPreviewVoice) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFF5EEAD4),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Selected Fast Voice", color = Color(0xFF5EEAD4))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFFA78BFA))
            }
        }
    )
}

/**
 * Dedicated Wake Word ("Voice Trigger") Submenu in Settings:
 * - Explains clearly how on-device wake word detection works
 * - Lets users toggle Wake Word ON/OFF
 * - Lets users customize the Wake Word Phrase (or pick a preset)
 * - Lets users tune Wake Word Sensitivity (10% - 95%)
 * - Includes a real-time Microphone Level Meter (`dB`) and Test button
 */
@Composable
private fun WakeWordSettingsDialog(
    voiceTriggerEnabled: Boolean,
    wakeWordPhrase: String,
    wakeWordSensitivity: Float,
    liveMicRmsDb: Float,
    isListeningTest: Boolean,
    moodTheme: BlobMoodTheme,
    onToggleEnabled: (Boolean) -> Unit,
    onUpdateConfig: (String, Float) -> Unit,
    onTestMic: () -> Unit,
    onDismiss: () -> Unit
) {
    var phraseInput by remember(wakeWordPhrase) { mutableStateOf(wakeWordPhrase) }
    var sensitivityInput by remember(wakeWordSensitivity) { mutableFloatStateOf(wakeWordSensitivity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("Voice Trigger (Wake Word Setup)", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "When Voice Trigger is enabled, Fossistant listens locally on-device for your wake phrase using Whisper Multilingual without sending audio to the cloud. When detected, it vibrates and opens the Assistant Popup in live voice mode.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Enable Hands-Free Wake Word",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = voiceTriggerEnabled,
                        onCheckedChange = onToggleEnabled
                    )
                }

                Text(
                    text = "1. Wake Word Phrase:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = phraseInput,
                    onValueChange = {
                        phraseInput = it
                        onUpdateConfig(it, sensitivityInput)
                    },
                    placeholder = { Text("e.g. Hey Fossistant", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Preset Wake Phrase Chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Hey Fossistant", "Hey Assistant", "OK Fossistant", "Hey Jarvis", "Computer").forEach { preset ->
                        val isSelected = phraseInput.equals(preset, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF1E293B))
                                .border(
                                    1.dp,
                                    if (isSelected) moodTheme.secondaryAccent else Color(0xFF334155),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    phraseInput = preset
                                    onUpdateConfig(preset, sensitivityInput)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(preset, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }

                Text(
                    text = "2. Detection Sensitivity: ${(sensitivityInput * 100).toInt()}%",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = sensitivityInput,
                    onValueChange = {
                        sensitivityInput = it
                        onUpdateConfig(phraseInput, it)
                    },
                    valueRange = 0.15f..0.95f
                )
                Text(
                    text = "Higher sensitivity triggers more easily in quiet rooms; lower sensitivity reduces false triggers in noisy environments.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )

                // Live Microphone Level Meter
                Text(
                    text = "3. Live Microphone Signal (${liveMicRmsDb.toInt()} dB):",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { (liveMicRmsDb / 85f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = moodTheme.secondaryAccent,
                    trackColor = Color(0xFF1E293B)
                )

                TextButton(onClick = onTestMic) {
                    Icon(
                        imageVector = if (isListeningTest) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = moodTheme.secondaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isListeningTest) "Stop Microphone Test" else "Test Wake Phrase with Whisper Mic Now",
                        color = moodTheme.secondaryAccent
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUpdateConfig(phraseInput, sensitivityInput)
                    onDismiss()
                }
            ) {
                Text("Save & Close", color = moodTheme.primaryAccent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Dedicated Personality & AI Name Submenu in Settings:
 * - Lets users set a custom AI Name (which dynamically updates the App Name/Title, headers, and system prompt)
 * - Lets users select the AI Character Persona
 * - Lets users add custom personality instructions / system behavior notes
 * - Lets users select Response Length (Shorter, Balanced, Longer)
 */
@Composable
private fun PersonalityAndIdentityDialog(
    aiName: String,
    characterPersona: AiCharacterPersona,
    customInstructions: String,
    responseLength: ResponseLengthPreference,
    moodTheme: BlobMoodTheme,
    onSave: (String, AiCharacterPersona, String, ResponseLengthPreference) -> Unit,
    onDismiss: () -> Unit
) {
    var nameInput by remember(aiName) { mutableStateOf(aiName) }
    var selectedPersona by remember(characterPersona) { mutableStateOf(characterPersona) }
    var customInput by remember(customInstructions) { mutableStateOf(customInstructions) }
    var selectedLength by remember(responseLength) { mutableStateOf(responseLength) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("Personality & AI Name", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. AI Name (Updates App Name & Assistant Identity):",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it
                        onSave(it, selectedPersona, customInput, selectedLength)
                    },
                    placeholder = { Text("e.g. Fossistant, Nova, Jarvis, Aria", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "2. AI Character Persona:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                AiCharacterPersona.entries.forEach { persona ->
                    val isSelected = persona == selectedPersona
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF161B26))
                            .border(
                                1.dp,
                                if (isSelected) moodTheme.primaryAccent else Color(0xFF263042),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedPersona = persona
                                onSave(nameInput, persona, customInput, selectedLength)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(persona.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(persona.systemTraitInstruction, color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Text("✓", color = moodTheme.primaryAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "3. Custom Personality Instructions:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it
                        onSave(nameInput, selectedPersona, it, selectedLength)
                    },
                    placeholder = {
                        Text(
                            "Optional custom traits (e.g. 'Speak like a sci-fi ship AI and call me Captain')",
                            fontSize = 12.sp
                        )
                    },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "4. Response Length:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ResponseLengthPreference.entries.forEach { pref ->
                        val isSelected = pref == selectedLength
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF161B26))
                                .border(
                                    1.dp,
                                    if (isSelected) moodTheme.secondaryAccent else Color(0xFF263042),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    selectedLength = pref
                                    onSave(nameInput, selectedPersona, customInput, pref)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pref.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(nameInput, selectedPersona, customInput, selectedLength)
                    onDismiss()
                }
            ) {
                Text("Save", color = moodTheme.primaryAccent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Dialog for customizing:
 * 1. Blob Color, Whole-App Mood & Dynamic Home-Screen App Icon ([BlobMoodTheme])
 * 2. Default Assistant Shortcut Overlay preview
 */
@Composable
private fun CustomizationDialog(
    moodTheme: BlobMoodTheme,
    characterPersona: AiCharacterPersona,
    responseLength: ResponseLengthPreference,
    onSelectMoodTheme: (BlobMoodTheme) -> Unit,
    onSelectPersona: (AiCharacterPersona) -> Unit,
    onSelectResponseLength: (ResponseLengthPreference) -> Unit,
    onPreviewOverlayShortcut: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("Blob Color & App Icon Style", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. Blob Color & Dynamic App Icon Style (changes both the AI blob mood and your Android Launcher Icon):",
                    color = moodTheme.secondaryAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                BlobMoodTheme.entries.forEach { theme ->
                    val isSelected = theme == moodTheme
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) theme.coreNebulaColor else Color(0xFF161B26))
                            .border(
                                1.2.dp,
                                if (isSelected) theme.secondaryAccent else Color(0xFF263042),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectMoodTheme(theme) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(theme.primaryAccent, theme.secondaryAccent)
                                        )
                                    )
                                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(theme.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${theme.moodSubtitle} • Matching App Icon", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }
                        if (isSelected) {
                            Text("✓ Active Icon", color = theme.secondaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                TextButton(onClick = onPreviewOverlayShortcut) {
                    Text("Preview Default Assistant Shortcut Popup UI", color = moodTheme.secondaryAccent)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = moodTheme.primaryAccent)
            }
        }
    )
}

/**
 * Dialog for viewing, adding, and deleting facts in the Persistent On-Device AI Memory System.
 */
@Composable
private fun MemoryManagerDialog(
    aiMemoryEnabled: Boolean,
    smartRamEvictionEnabled: Boolean,
    facts: List<MemoryFact>,
    onToggleAiMemory: (Boolean) -> Unit,
    onToggleSmartEviction: (Boolean) -> Unit,
    onAddFact: (String) -> Unit,
    onRemoveFact: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var newFactText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("On-Device AI Memory System", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.height(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto-Remember User Facts", color = Color.White, fontSize = 13.sp)
                    Switch(checked = aiMemoryEnabled, onCheckedChange = onToggleAiMemory)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("8GB RAM Smart Eviction", color = Color.White, fontSize = 13.sp)
                    Switch(checked = smartRamEvictionEnabled, onCheckedChange = onToggleSmartEviction)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newFactText,
                        onValueChange = { newFactText = it },
                        placeholder = { Text("Add a memory (e.g. I love jazz)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (newFactText.isNotBlank()) {
                                onAddFact(newFactText)
                                newFactText = ""
                            }
                        }
                    ) {
                        Text("Add", color = Color(0xFF5EEAD4))
                    }
                }

                Text(
                    text = "Stored Memories (${facts.size}):",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (facts.isEmpty()) {
                    Text(
                        text = "No memories stored yet. Tell Fossistant \"Remember that...\" or \"My name is...\" in chat, or add one above.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(facts, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF161B26))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "• ${item.fact}",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Memory",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onRemoveFact(item.id) }
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (facts.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("Clear All", color = Color(0xFFEF4444))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFFA78BFA))
            }
        }
    )
}

@Composable
private fun IntegrationsDialog(
    onRequestDefaultAssistantRole: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRunSampleCapability: (String) -> Unit,
    onOpenHomeAssistant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text("Assistant APIs & 17+ Capabilities", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.height(340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(onClick = onRequestDefaultAssistantRole) {
                    Text("1. Set as Default Digital Assistant (ROLE_ASSISTANT)", color = Color(0xFF5EEAD4))
                }
                TextButton(onClick = onOpenAccessibilitySettings) {
                    Text("2. Enable Live Screen Sharing (Accessibility)", color = Color(0xFF5EEAD4))
                }
                TextButton(onClick = onOpenNotificationListenerSettings) {
                    Text("3. Enable Notification Reply & MediaSession Access", color = Color(0xFF5EEAD4))
                }
                TextButton(onClick = onOpenHomeAssistant) {
                    Text("4. Home Assistant (smart home control)", color = Color(0xFF5EEAD4))
                }
                Text(
                    text = "Tap any Google Assistant capability below to test:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val sampleCommands = listOf(
                        "Play audiobook Dune",
                        "Set a music alarm for 7:30 am playing Jazz",
                        "Show recipe for classic carbonara",
                        "Start stopwatch",
                        "Broadcast to family: Dinner is ready!",
                        "Send an email to alex@example.com saying Let's meet at 3",
                        "Reschedule dentist appointment to tomorrow at 4pm",
                        "Start driving mode to Work",
                        "Schedule a Family Bell",
                        "Meditate for 10 minutes",
                        "Start my running workout",
                        "How did I sleep?",
                        "Video call Mom",
                        "How is my commute to work?",
                        "Check my flight status",
                        "What is John's contact info?",
                        "Make a reservation at Olive Garden"
                    )
                    items(sampleCommands) { cmd ->
                        Text(
                            text = "• \"$cmd\"",
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRunSampleCapability(cmd) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFFA78BFA))
            }
        }
    )
}

/** Explains how to get an imported model onto the phone and opens the file picker. */
@Composable
private fun ModelImportDialog(
    model: PrimaryLlmModel,
    onChooseFile: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = { Text("Import ${model.displayName}", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${model.displayName} (${model.ramBadge}) isn't in this APK. Copy " +
                        "${model.filename} to the phone once (for example into Downloads) and choose it here. " +
                        "It's kept across every app update.",
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp
                )
                Text("Source: ${model.sourceUrl}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                Text(
                    "Or push it with adb to /sdcard/Android/data/org.fossistant.assistant/files/models/",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onChooseFile) { Text("Choose file", color = Color(0xFFA78BFA)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF94A3B8)) } }
    )
}

/**
 * Home Assistant connection settings: server address and a long-lived access token
 * (Home Assistant > Profile > Security > Long-lived access tokens).
 */
@Composable
private fun HomeAssistantDialog(
    initialUrl: String,
    initialToken: String,
    onTest: suspend (url: String, token: String) -> String,
    onSave: (url: String, token: String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = { Text("Home Assistant", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Lets the assistant control your lights, switches, thermostats, blinds, locks and scenes. " +
                        "Create a token in Home Assistant under Profile > Security > Long-lived access tokens.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; status = null },
                    label = { Text("Server address") },
                    placeholder = { Text("http://192.168.1.10:8123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; status = null },
                    label = { Text("Long-lived access token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                status?.let { Text(it, color = Color(0xFFE2E8F0), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url, token) }) { Text("Save", color = Color(0xFFA78BFA)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        testing = true
                        scope.launch {
                            status = onTest(url, token)
                            testing = false
                        }
                    }
                ) { Text(if (testing) "Testing…" else "Test connection", color = Color(0xFF5EEAD4)) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF94A3B8)) }
            }
        }
    )
}

@Composable
private fun HardwareAccelerationDialog(
    currentPrimaryBackend: HardwareInferenceBackend,
    primaryModelDisplayName: String,
    currentWhisperBackend: HardwareInferenceBackend,
    currentWhisperModel: WhisperModelChoice,
    whisperLatencyMs: Long,
    moodTheme: BlobMoodTheme,
    onSelectPrimaryBackend: (HardwareInferenceBackend) -> Unit,
    onSelectWhisperBackend: (HardwareInferenceBackend) -> Unit,
    onSelectWhisperModel: (WhisperModelChoice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101420),
        title = {
            Text(
                text = "⚡ Hardware Acceleration",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .height(480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Info banner explaining compute offload
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x2210B981))
                        .border(1.dp, Color(0x5510B981), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "GPU offload uses Vulkan / OpenCL compute shaders with automatic CPU SIMD fallback. Optional NPU acceleration targets Android NNAPI & Tensor TPU.",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                // Section 1: Primary AI Model Backend (Gemma 4 E2B)
                Text(
                    text = "1. Primary AI Model ($primaryModelDisplayName):",
                    color = moodTheme.secondaryAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )

                HardwareInferenceBackend.entries.forEach { backend ->
                    val isSelected = backend == currentPrimaryBackend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF161B26))
                            .border(
                                1.2.dp,
                                if (isSelected) moodTheme.secondaryAccent else Color(0xFF263042),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectPrimaryBackend(backend) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = backend.displayName,
                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = backend.subtitle,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = "ACTIVE",
                                color = moodTheme.secondaryAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Section 2: Whisper STT model and backend
                Text(
                    text = "2. Whisper Multilingual STT:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )

                WhisperModelChoice.entries.forEach { model ->
                    val isSelected = model == currentWhisperModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF161B26))
                            .border(
                                1.2.dp,
                                if (isSelected) moodTheme.secondaryAccent else Color(0xFF263042),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectWhisperModel(model) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = model.shortDescription,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = "ACTIVE",
                                color = moodTheme.secondaryAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = "Whisper runs on:",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )

                HardwareInferenceBackend.entries.forEach { backend ->
                    val isSelected = backend == currentWhisperBackend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) moodTheme.coreNebulaColor else Color(0xFF161B26))
                            .border(
                                1.2.dp,
                                if (isSelected) moodTheme.secondaryAccent else Color(0xFF263042),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectWhisperBackend(backend) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = backend.displayName,
                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = backend.subtitle,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = "ACTIVE",
                                color = moodTheme.secondaryAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Section 3: Live Hardware Telemetry
                Text(
                    text = "3. Live Hardware Telemetry:",
                    color = moodTheme.secondaryAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F1522))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Whisper STT Latency:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Text(
                                text = if (whisperLatencyMs > 0) "${whisperLatencyMs}ms (⚡ Hardware Accelerated)" else "Standby (75MB in RAM)",
                                color = if (whisperLatencyMs > 0) Color(0xFF10B981) else Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Compute Acceleration:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Text("Vulkan / OpenCL / NNAPI", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = moodTheme.secondaryAccent, fontWeight = FontWeight.Bold)
            }
        }
    )
}


@Composable
private fun SystemLogsDialog(
    moodTheme: BlobMoodTheme,
    onDismiss: () -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copiedFeedback by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf(org.fossistant.assistant.util.AppLogger.getAllLogsAsText()) }
    var lineCount by remember { mutableIntStateOf(org.fossistant.assistant.util.AppLogger.getLineCount()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D111A),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "System Logs & Diagnostics",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "$lineCount / 10,000 lines retained",
                        color = moodTheme.secondaryAccent,
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    onClick = {
                        val allLogs = org.fossistant.assistant.util.AppLogger.getAllLogsAsText()
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(allLogs))
                        copiedFeedback = true
                    }
                ) {
                    Text(
                        text = if (copiedFeedback) "Copied!" else "Copy All",
                        color = if (copiedFeedback) Color(0xFF10B981) else moodTheme.secondaryAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF080C14)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(logText) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .verticalScroll(scrollState)
                    ) {
                        BasicTextField(
                            value = logText.ifBlank { "No logs recorded yet." },
                            onValueChange = {},
                            readOnly = true,
                            textStyle = TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        org.fossistant.assistant.util.AppLogger.clear()
                        logText = org.fossistant.assistant.util.AppLogger.getAllLogsAsText()
                        lineCount = org.fossistant.assistant.util.AppLogger.getLineCount()
                    }
                ) {
                    Text("Clear Logs", color = Color(0xFFEF4444), fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    )
}
