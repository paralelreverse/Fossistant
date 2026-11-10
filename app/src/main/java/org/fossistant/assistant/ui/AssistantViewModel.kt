package org.fossistant.assistant.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.engine.DualModelRouter
import org.fossistant.assistant.engine.FastMultilingualTtsEngine
import org.fossistant.assistant.engine.WhisperMultilingualSttEngine
import org.fossistant.assistant.model.AiCharacterPersona
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.model.FastTtsVoiceProfile
import org.fossistant.assistant.model.HardwareInferenceBackend
import org.fossistant.assistant.model.MediaAttachment
import org.fossistant.assistant.model.MediaModality
import org.fossistant.assistant.model.MemoryFact
import org.fossistant.assistant.model.LlmRuntimeEngine
import org.fossistant.assistant.model.PrimaryLlmModel
import org.fossistant.assistant.model.ResponseLengthPreference
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.service.FossistantAgentAccessibilityService
import org.fossistant.assistant.ui.components.BlobActivityState
import java.io.File
import java.util.UUID

/**
 * Primary screen states matching the uploaded biomorphic UI reference screens:
 * - [OPEN_HOME]: Initial open-app state where the Cosmic AI Blob is tucked behind the bottom text field.
 * - [VOICE_MODE]: Blob jumps from behind the text field to the center of the screen with concentric rings
 *   and the bottom iridescent waveform ribbon.
 * - [TEXT_CHAT]: Turn-based chat where the blob shrinks and docks to the left of the LAST LLM response bubble.
 * - [SETTINGS]: Glassmorphic settings screen (Voice Profiles, Voice Trigger, Personality & AI Name, Customization, Integrations, Memory, Privacy, Sound).
 */
enum class AppScreenMode {
    OPEN_HOME,
    VOICE_MODE,
    TEXT_CHAT,
    SETTINGS
}

@kotlinx.serialization.Serializable
data class PreviousChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    /** Hidden from the chat list until unlocked with fingerprint / screen lock. */
    val locked: Boolean = false,
    /** The project this chat belongs to, if any. */
    val projectId: String? = null
)

data class AssistantUiState(
    val screenMode: AppScreenMode = AppScreenMode.OPEN_HOME,
    val isSideMenuOpen: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val currentChatSessionId: String = UUID.randomUUID().toString(),
    val previousChats: List<PreviousChatSession> = emptyList(),
    val pendingAttachments: List<MediaAttachment> = emptyList(),
    val isListeningVoice: Boolean = false,
    val isVoiceMutedByUser: Boolean = false,
    val voiceRmsDb: Float = 0f,
    val ttsAudioLevel: Float = 0f,
    val liveVoicePartial: String = "",
    // Reasoning toggle in the composer bar (OFF by default when typing text)
    val isReasoningToggleEnabled: Boolean = false,
    val isReasoning: Boolean = false,
    val isGenerating: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    val isGemmaLoadedInRam: Boolean = false,
    // Live Model Memory Loading & Processing Speed Telemetry for Top Status Cue
    val whisperMemoryStatus: String = "Loading...",
    val whisperLastLatencyMs: Long = 0L,
    val gemmaMemoryStatus: String = "Standby (2.58GB)",
    val isGemmaLoadingMemory: Boolean = false,
    val gemmaSpeedStatus: String = "Idle",
    val gemmaTokPerSec: Float = 0f,
    val errorMessage: String? = null,
    // Voice Trigger (Wake Word) & Fast 7-Language TTS Voice settings
    val voiceTriggerEnabled: Boolean = FossistantApp.isVoiceTriggerEnabled,
    val wakeWordPhrase: String = FossistantApp.wakeWordPhrase,
    val wakeWordSensitivity: Float = FossistantApp.wakeWordSensitivity,
    val backgroundMicRmsDb: Float = 0f,
    val voiceProfilesEnabled: Boolean = true,
    val selectedLanguage: SupportedLanguage = SupportedLanguage.AUTO,
    val selectedVoiceProfile: FastTtsVoiceProfile = FastMultilingualTtsEngine.defaultProfileFor(SupportedLanguage.AUTO),
    // Blob Color & Whole-App Mood + AI Character, AI Name & Response Length Customization
    val moodTheme: BlobMoodTheme = FossistantApp.currentMoodTheme,
    val aiName: String = FossistantApp.customAiName,
    val customPersonalityInstructions: String = FossistantApp.customPersonalityInstructions,
    val characterPersona: AiCharacterPersona = AiCharacterPersona.EMPATHETIC_COMPANION,
    val responseLength: ResponseLengthPreference = ResponseLengthPreference.BALANCED,
    // Live Camera Viewfinder State
    val isLiveCameraActive: Boolean = false,
    // Persistent On-Device AI Memory System
    val aiMemoryEnabled: Boolean = true,
    val memoryFacts: List<MemoryFact> = emptyList(),
    val memorySmartEvictionEnabled: Boolean = true,
    val soundNotificationsEnabled: Boolean = true,
    // Hardware Acceleration Settings & Dynamic Model Display Name
    val primaryModelChoice: PrimaryLlmModel = FossistantApp.primaryModelChoice,
    val primaryModelDisplayName: String = FossistantApp.primaryModelChoice.displayName,
    val whisperModelChoice: WhisperModelChoice = FossistantApp.whisperModelChoice,
    val whisperHardwareBackend: HardwareInferenceBackend = FossistantApp.whisperHardwareBackend,
    val primaryHardwareBackend: HardwareInferenceBackend = FossistantApp.primaryHardwareBackend,
    val isLlmOnCpu: Boolean = false,
    val isVoiceDuplexMode: Boolean = FossistantApp.isVoiceDuplexMode,
    val limitCompute: Boolean = FossistantApp.limitCompute,
    val mtpEnabled: Boolean = FossistantApp.useMtp,
    val strictToolCalls: Boolean = FossistantApp.strictToolCalls,
    /** An imported model the user picked that isn't on the device yet: the UI asks for its file. */
    val modelImportRequest: PrimaryLlmModel? = null,
    /** Progress of a running model import (0..1), or null. */
    val modelImportProgress: Float? = null,
    /** Temporary chat: not saved, nothing goes to memory. */
    val isTemporaryChat: Boolean = false,
    /** Project the current chat belongs to (its instructions and files go with every question). */
    val activeProjectId: String? = null,
    val activeProjectName: String? = null,
    /** Locked chats are shown in the list only after the user unlocked them in this session. */
    val lockedChatsUnlocked: Boolean = false,
    /** The next typed message starts Deep Research (switched on from the plus menu). */
    val isResearchArmed: Boolean = false,
    /** Image mode (picked in the plus menu): typed messages are drawn by this model until it's switched off. */
    val imageGenArmed: org.fossistant.assistant.model.ImageGenModelChoice? = null,
    /** Image models whose files are on the phone. */
    val imageGenReady: Set<org.fossistant.assistant.model.ImageGenModelChoice> = emptySet(),
    /** Image model downloads in progress or failed. */
    val imageGenDownloads: Map<org.fossistant.assistant.model.ImageGenModelChoice, org.fossistant.assistant.engine.ImageGenEngine.DownloadProgress> = emptyMap(),
    /** Export / import progress or result, shown in a dialog; null when idle. */
    val backupStatus: String? = null,
    val isBackupRunning: Boolean = false,
    /** An import finished: the app restarts when the user confirms. */
    val backupNeedsRestart: Boolean = false,
    /** First-launch setup is showing (also reopened from Settings > Setup & guide). */
    val showOnboarding: Boolean = !FossistantApp.isOnboardingCompleted,
    val modelDownload: org.fossistant.assistant.engine.ModelDownloadManager.Progress =
        org.fossistant.assistant.engine.ModelDownloadManager.Progress(),
    val downloadOverWifiOnly: Boolean = FossistantApp.downloadOverWifiOnly
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val app: FossistantApp = application as FossistantApp
    private val router: DualModelRouter = app.router
    val sttEngine: WhisperMultilingualSttEngine = app.sttEngine

    private val _uiState = MutableStateFlow(
        AssistantUiState(
            memoryFacts = app.memoryManager.facts.value,
            selectedVoiceProfile = app.ttsEngine.activeProfile.value,
            voiceTriggerEnabled = FossistantApp.isVoiceTriggerEnabled,
            wakeWordPhrase = FossistantApp.wakeWordPhrase,
            wakeWordSensitivity = FossistantApp.wakeWordSensitivity,
            moodTheme = FossistantApp.currentMoodTheme,
            aiName = FossistantApp.customAiName,
            customPersonalityInstructions = FossistantApp.customPersonalityInstructions,
            isGemmaLoadedInRam = app.gemmaEngine.isNativeModelLoaded,
            whisperMemoryStatus = app.sttEngine.memoryStatus.value,
            gemmaMemoryStatus = app.gemmaEngine.memoryStatus.value,
            isGemmaLoadingMemory = app.gemmaEngine.isLoadingMemory.value,
            gemmaSpeedStatus = app.gemmaEngine.speedStatus.value,
            gemmaTokPerSec = app.gemmaEngine.currentTokPerSec.value,
            primaryModelChoice = FossistantApp.primaryModelChoice,
            primaryModelDisplayName = FossistantApp.primaryModelChoice.displayName,
            whisperModelChoice = FossistantApp.whisperModelChoice,
            whisperHardwareBackend = FossistantApp.whisperHardwareBackend,
            primaryHardwareBackend = FossistantApp.primaryHardwareBackend,
            isLlmOnCpu = FossistantApp.primaryHardwareBackend == HardwareInferenceBackend.CPU,
            isVoiceDuplexMode = FossistantApp.isVoiceDuplexMode
        )
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null

    init {
        // Apply saved AI name, personality, and hardware backend to Gemma engine on startup
        app.gemmaEngine.aiName = FossistantApp.customAiName
        app.gemmaEngine.customPersonalityInstructions = FossistantApp.customPersonalityInstructions
        app.gemmaEngine.hardwareBackend = FossistantApp.primaryHardwareBackend

        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "AssistantViewModel initialized. Active model: ${FossistantApp.primaryModelChoice.displayName}")

        // Language and voice saved by the setup (or a later change in Settings).
        SupportedLanguage.fromCode(FossistantApp.preferredLanguageCode).takeIf { !it.isAuto }?.let { saved ->
            val voice = FastMultilingualTtsEngine.defaultProfileFor(saved)
            app.ttsEngine.selectVoiceProfile(voice)
            _uiState.update { it.copy(selectedLanguage = saved, selectedVoiceProfile = voice) }
        }

        // Model downloads for APKs without bundled models (slim).
        viewModelScope.launch {
            app.modelDownloads.progress.collect { p ->
                _uiState.update { it.copy(modelDownload = p) }
            }
        }

        // Image model downloads; a finished one arms image mode with that model.
        viewModelScope.launch {
            app.imageGenEngine.downloads.collect { downloads ->
                val ready = imageGenReadySet()
                _uiState.update { state ->
                    val justFinished = state.imageGenDownloads.keys.firstOrNull { it !in downloads && it in ready }
                    state.copy(
                        imageGenDownloads = downloads,
                        imageGenReady = ready,
                        imageGenArmed = justFinished ?: state.imageGenArmed
                    )
                }
            }
        }

        // Observe Whisper and Gemma 4 E2B telemetry
        viewModelScope.launch {
            app.sttEngine.memoryStatus.collect { status ->
                _uiState.update { it.copy(whisperMemoryStatus = status) }
            }
        }
        viewModelScope.launch {
            app.sttEngine.lastLatencyMs.collect { ms ->
                _uiState.update { it.copy(whisperLastLatencyMs = ms) }
            }
        }
        viewModelScope.launch {
            app.gemmaEngine.memoryStatus.collect { status ->
                val onCpu = app.gemmaEngine.activeBackendName.contains("CPU", ignoreCase = true) ||
                            app.gemmaEngine.hardwareBackend == HardwareInferenceBackend.CPU
                _uiState.update {
                    it.copy(
                        gemmaMemoryStatus = status,
                        isGemmaLoadedInRam = app.gemmaEngine.isNativeModelLoaded,
                        isLlmOnCpu = onCpu
                    )
                }
                org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "Gemma status: $status (Loaded: ${app.gemmaEngine.isNativeModelLoaded}, CPU: $onCpu)")
            }
        }
        viewModelScope.launch {
            app.gemmaEngine.isLoadingMemory.collect { loading ->
                _uiState.update { it.copy(isGemmaLoadingMemory = loading) }
            }
        }
        viewModelScope.launch {
            app.gemmaEngine.speedStatus.collect { speed ->
                _uiState.update { it.copy(gemmaSpeedStatus = speed) }
            }
        }
        viewModelScope.launch {
            app.gemmaEngine.currentTokPerSec.collect { tokPerSec ->
                _uiState.update { it.copy(gemmaTokPerSec = tokPerSec) }
            }
        }

        // Saved chats are shared with the other screen's view model and persisted on disk
        viewModelScope.launch {
            app.chatHistoryStore.sessions.collect { sessions ->
                _uiState.update { it.copy(previousChats = sessions) }
            }
        }
        // Observe persistent AI memory facts
        viewModelScope.launch {
            app.memoryManager.facts.collect { updatedFacts ->
                _uiState.update { it.copy(memoryFacts = updatedFacts) }
            }
        }
        // Observe fast TTS speaking state
        viewModelScope.launch {
            var wasSpeaking = false
            app.ttsEngine.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isTtsSpeaking = speaking) }
                if (wasSpeaking && !speaking) {
                    // TTS finished speaking: reset live voice partial cue cleanly
                    _uiState.update { it.copy(liveVoicePartial = "") }
                }
                wasSpeaking = speaking
            }
        }
        // Observe fast TTS real-time audio envelope for audio-synced CosmicAiBlob & Waveform animations
        viewModelScope.launch {
            app.ttsEngine.ttsAudioLevel.collect { level ->
                val quantized = kotlin.math.round(level * 40f) / 40f
                if (_uiState.value.ttsAudioLevel != quantized) {
                    _uiState.update { it.copy(ttsAudioLevel = quantized) }
                }
            }
        }
        // Observe TTS diagnostic errors
        viewModelScope.launch {
            app.ttsEngine.lastTtsError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _uiState.update { it.copy(errorMessage = err) }
                }
            }
        }
        // Observe STT live RMS dB for Cosmic AI Blob voice reactive animations. Quantized to 1 dB so the
        // whole-screen state is not re-emitted (and recomposed) for every 64ms microphone frame.
        viewModelScope.launch {
            sttEngine.currentRmsDb.collect { rms ->
                val quantized = kotlin.math.round(rms)
                if (_uiState.value.voiceRmsDb != quantized) {
                    _uiState.update { it.copy(voiceRmsDb = quantized) }
                }
            }
        }
        // Keep the UI's listening flag in sync when the engine stops on its own (error, timeout, another
        // screen taking over the microphone) so the auto-listen effects can restart it.
        viewModelScope.launch {
            sttEngine.isListening.collect { listening ->
                if (!listening && _uiState.value.isListeningVoice && !sttEngine.isListening.value) {
                    _uiState.update { it.copy(isListeningVoice = false, voiceRmsDb = 0f) }
                }
            }
        }
        // Duplex barge-in: stop streaming the interrupted answer (TTS and the LLM are already stopped).
        viewModelScope.launch {
            app.userInterruptions.collect {
                activeGenerationJob?.cancel()
            }
        }
        // Observe STT live partial transcripts
        viewModelScope.launch {
            sttEngine.livePartial.collect { partial ->
                if (partial.isNotEmpty()) {
                    _uiState.update { it.copy(liveVoicePartial = partial) }
                }
            }
        }
        // Observe STT diagnostic errors
        viewModelScope.launch {
            sttEngine.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _uiState.update { it.copy(errorMessage = err) }
                }
            }
        }
        // Poll background wake-word mic RMS for the Settings Wake Word test meter
        viewModelScope.launch {
            while (isActive) {
                val bgRms = maxOf(
                    WhisperMultilingualSttEngine.latestBackgroundMicRmsDb,
                    sttEngine.currentRmsDb.value
                )
                if (_uiState.value.backgroundMicRmsDb != bgRms) {
                    _uiState.update { it.copy(backgroundMicRmsDb = bgRms) }
                }
                delay(140)
            }
        }
        // Propagate state & audio levels to floating agent blob during Agent Mode
        viewModelScope.launch {
            _uiState.collect { state ->
                if (FossistantAgentAccessibilityService.isAgentModeActive) {
                    val activityState = when {
                        state.isReasoning -> BlobActivityState.AI_REASONING
                        state.isGenerating || state.isTtsSpeaking -> BlobActivityState.AI_TALKING
                        state.voiceRmsDb > 30f -> BlobActivityState.USER_TALKING
                        else -> BlobActivityState.IDLE
                    }
                    FossistantAgentAccessibilityService.updateFloatingBlobState(
                        activityState = activityState,
                        voiceRmsLevel = state.voiceRmsDb,
                        ttsAudioLevel = state.ttsAudioLevel
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /**
     * Resets live camera viewfinder state strictly for per-session privacy.
     */
    fun resetLiveCameraSession() {
        _uiState.update { state ->
            state.copy(
                isLiveCameraActive = false,
                pendingAttachments = state.pendingAttachments.filterNot { it.id == LIVE_CAMERA_ATTACHMENT_ID }
            )
        }
    }

    /**
     * Immediately stops any active Whisper microphone capture AND any ongoing TTS speech playback.
     * Called when the Assistant Popup closes or when the user cancels voice mode.
     */
    fun stopVoiceAndTts() {
        sttEngine.stopListening()
        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        app.gemmaEngine.cancelCurrentGeneration()
        app.imageGenEngine.cancelGeneration()
        _uiState.update {
            it.copy(
                isListeningVoice = false,
                voiceRmsDb = 0f,
                ttsAudioLevel = 0f,
                isTtsSpeaking = false,
                isGenerating = false,
                isReasoning = false,
                liveVoicePartial = "",
                isLiveCameraActive = false,
                pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == LIVE_CAMERA_ATTACHMENT_ID }
            )
        }
    }

    /**
     * Immediately stops active Whisper microphone capture so tapping the mic button while listening
     * turns the microphone off right away.
     */
    fun stopVoiceCapture() {
        sttEngine.stopListening()
        _uiState.update {
            it.copy(
                isListeningVoice = false,
                isVoiceMutedByUser = true,
                voiceRmsDb = 0f,
                liveVoicePartial = ""
            )
        }
    }

    fun setVoiceMutedByUser(muted: Boolean) {
        _uiState.update { it.copy(isVoiceMutedByUser = muted) }
    }

    fun setScreenMode(mode: AppScreenMode) {
        _uiState.update {
            val isModeChange = it.screenMode != mode
            it.copy(
                screenMode = mode,
                isSideMenuOpen = false,
                isLiveCameraActive = if (isModeChange) false else it.isLiveCameraActive,
                pendingAttachments = if (isModeChange) it.pendingAttachments.filterNot { a -> a.id == LIVE_CAMERA_ATTACHMENT_ID } else it.pendingAttachments
            )
        }
    }

    fun toggleSideMenu(open: Boolean? = null) {
        _uiState.update { state ->
            state.copy(isSideMenuOpen = open ?: !state.isSideMenuOpen)
        }
    }

    fun toggleReasoningMode(enabled: Boolean? = null) {
        _uiState.update { state ->
            state.copy(isReasoningToggleEnabled = enabled ?: !state.isReasoningToggleEnabled)
        }
    }

    fun toggleVoiceTrigger(enabled: Boolean) {
        val ctx = getApplication<Application>()
        FossistantApp.isVoiceTriggerEnabled = enabled
        FossistantApp.savePreferences(ctx)
        _uiState.update { it.copy(voiceTriggerEnabled = enabled) }
        if (enabled) {
            org.fossistant.assistant.service.VoiceWakeWordService.startIfEnabled(ctx)
        } else {
            org.fossistant.assistant.service.VoiceWakeWordService.stop(ctx)
        }
    }

    fun updateWakeWordConfig(phrase: String, sensitivity: Float) {
        val cleanPhrase = phrase.trim().ifEmpty { "Hey Fossistant" }
        val clampedSens = sensitivity.coerceIn(0.10f, 0.95f)
        val ctx = getApplication<Application>()
        FossistantApp.wakeWordPhrase = cleanPhrase
        FossistantApp.wakeWordSensitivity = clampedSens
        FossistantApp.savePreferences(ctx)
        _uiState.update {
            it.copy(
                wakeWordPhrase = cleanPhrase,
                wakeWordSensitivity = clampedSens
            )
        }
    }

    fun updateBlobMoodTheme(theme: BlobMoodTheme) {
        val ctx = getApplication<Application>()
        FossistantApp.currentMoodTheme = theme
        FossistantApp.savePreferences(ctx)
        AppIconSwitcher.applyLauncherIconForTheme(ctx, theme)
        _uiState.update { it.copy(moodTheme = theme) }
    }

    fun updateCharacterAndLength(
        persona: AiCharacterPersona = _uiState.value.characterPersona,
        lengthPref: ResponseLengthPreference = _uiState.value.responseLength
    ) {
        _uiState.update {
            it.copy(
                characterPersona = persona,
                responseLength = lengthPref
            )
        }
        viewModelScope.launch {
            app.gemmaEngine.updateCustomization(
                persona = persona,
                lengthPref = lengthPref,
                customAiName = _uiState.value.aiName,
                customNotes = _uiState.value.customPersonalityInstructions
            )
        }
    }

    fun updateAiIdentityAndPersonality(
        aiName: String = _uiState.value.aiName,
        persona: AiCharacterPersona = _uiState.value.characterPersona,
        customInstructions: String = _uiState.value.customPersonalityInstructions,
        lengthPref: ResponseLengthPreference = _uiState.value.responseLength
    ) {
        val cleanName = aiName.trim().ifEmpty { "Fossistant" }
        val ctx = getApplication<Application>()
        FossistantApp.customAiName = cleanName
        FossistantApp.customPersonalityInstructions = customInstructions
        FossistantApp.savePreferences(ctx)
        _uiState.update {
            it.copy(
                aiName = cleanName,
                characterPersona = persona,
                customPersonalityInstructions = customInstructions,
                responseLength = lengthPref
            )
        }
        viewModelScope.launch {
            app.gemmaEngine.updateCustomization(
                persona = persona,
                lengthPref = lengthPref,
                customAiName = cleanName,
                customNotes = customInstructions
            )
        }
    }

    fun selectVoiceProfile(profile: FastTtsVoiceProfile) {
        app.ttsEngine.selectVoiceProfile(profile)
        FossistantApp.preferredLanguageCode = profile.language.whisperCode
        FossistantApp.savePreferences(getApplication())
        _uiState.update {
            it.copy(
                selectedLanguage = profile.language,
                selectedVoiceProfile = profile
            )
        }
    }

    fun selectLanguage(language: SupportedLanguage) {
        val defaultVoice = FastMultilingualTtsEngine.defaultProfileFor(language)
        selectVoiceProfile(defaultVoice)
    }

    fun previewSelectedVoice() {
        val profile = _uiState.value.selectedVoiceProfile
        val aiName = _uiState.value.aiName.ifBlank { "Fossistant" }
        val sampleText = when (profile.language) {
            SupportedLanguage.SPANISH -> "¡Hola! Soy $aiName hablando con la voz ${profile.name}."
            SupportedLanguage.FRENCH -> "Bonjour ! Je suis $aiName avec la voix ${profile.name}."
            SupportedLanguage.GERMAN -> "Hallo! Ich bin $aiName mit der Stimme ${profile.name}."
            SupportedLanguage.ITALIAN -> "Ciao! Sono $aiName e parlo con la voce ${profile.name}."
            SupportedLanguage.JAPANESE -> "こんにちは！${aiName}の音声プロファイル、${profile.name}です。"
            SupportedLanguage.POLISH -> "Cześć! Jestem $aiName i mówię głosem ${profile.name}."
            SupportedLanguage.PORTUGUESE -> "Olá! Eu sou o $aiName falando com a voz ${profile.name}."
            SupportedLanguage.DUTCH -> "Hallo! Ik ben $aiName en spreek met de stem ${profile.name}."
            SupportedLanguage.RUSSIAN -> "Привет! Я $aiName, говорю голосом ${profile.name}."
            SupportedLanguage.UKRAINIAN -> "Привіт! Я $aiName, розмовляю голосом ${profile.name}."
            SupportedLanguage.CHINESE -> "你好！我是 $aiName，正在使用 ${profile.name} 语音。"
            SupportedLanguage.KOREAN -> "안녕하세요! ${profile.name} 음성으로 말하는 $aiName 입니다."
            SupportedLanguage.TURKISH -> "Merhaba! Ben $aiName, ${profile.name} sesiyle konuşuyorum."
            SupportedLanguage.SWEDISH -> "Hej! Jag är $aiName och talar med rösten ${profile.name}."
            SupportedLanguage.VIETNAMESE -> "Xin chào! Tôi là $aiName đang nói bằng giọng ${profile.name}."
            SupportedLanguage.INDONESIAN -> "Halo! Saya $aiName berbicara dengan suara ${profile.name}."
            SupportedLanguage.ARABIC -> "مرحباً! أنا $aiName أتحدث بصوت ${profile.name}."
            SupportedLanguage.HINDI -> "नमस्ते! मैं $aiName हूँ, ${profile.name} आवाज़ में बात कर रहा हूँ।"
            SupportedLanguage.GREEK -> "Γεια σας! Είμαι ο $aiName και μιλάω με τη φωνή ${profile.name}."
            SupportedLanguage.CZECH -> "Ahoj! Jsem $aiName a mluvím hlasem ${profile.name}."
            SupportedLanguage.ROMANIAN -> "Bună! Sunt $aiName și vorbesc cu vocea ${profile.name}."
            SupportedLanguage.DANISH -> "Hej! Jeg er $aiName og taler med stemmen ${profile.name}."
            SupportedLanguage.HUNGARIAN -> "Helló! $aiName vagyok, és a(z) ${profile.name} hangon beszélek."
            SupportedLanguage.FINNISH -> "Hei! Olen $aiName ja puhun äänellä ${profile.name}."
            SupportedLanguage.NORWEGIAN -> "Hei! Jeg er $aiName og snakker med stemmen ${profile.name}."
            else -> "Hello! I am $aiName speaking with the ${profile.name} voice."
        }
        app.ttsEngine.speakFast(sampleText, flushQueue = true, languageTag = profile.language.code)
    }

    fun toggleAiMemory(enabled: Boolean) {
        app.gemmaEngine.isMemoryEnabled = enabled
        _uiState.update { it.copy(aiMemoryEnabled = enabled) }
    }

    fun addMemoryFact(factText: String, category: String = "USER_FACT") {
        if (factText.isBlank()) return
        app.memoryManager.rememberFact(factText, category)
    }

    fun removeMemoryFact(id: String) {
        app.memoryManager.removeFact(id)
    }

    fun clearAllMemories() {
        app.memoryManager.clearAll()
    }

    fun startNewConversation() {
        val currentMessages = _uiState.value.messages
        val currentSessionId = _uiState.value.currentChatSessionId
        if (currentMessages.isNotEmpty()) {
            saveCurrentChatSession(currentSessionId, currentMessages)
        }
        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        app.chatHistoryStore.lastOpenedChatId = null
        viewModelScope.launch {
            app.gemmaEngine.resetConversation()
        }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                currentChatSessionId = UUID.randomUUID().toString(),
                screenMode = AppScreenMode.OPEN_HOME,
                isSideMenuOpen = false,
                pendingAttachments = emptyList(),
                activeProjectId = null,
                activeProjectName = null,
                isReasoning = false,
                isGenerating = false,
                liveVoicePartial = "",
                errorMessage = null,
                isGemmaLoadedInRam = app.gemmaEngine.isNativeModelLoaded
            )
        }
    }

    fun deleteChatSession(sessionId: String) {
        app.chatHistoryStore.delete(sessionId)
        _uiState.update { state ->
            val isCurrent = (state.currentChatSessionId == sessionId)
            state.copy(
                messages = if (isCurrent) emptyList() else state.messages,
                screenMode = if (isCurrent) AppScreenMode.OPEN_HOME else state.screenMode,
                currentChatSessionId = if (isCurrent) UUID.randomUUID().toString() else state.currentChatSessionId
            )
        }
    }

    fun clearAllChatSessions() {
        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        app.chatHistoryStore.clear()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                currentChatSessionId = UUID.randomUUID().toString(),
                screenMode = AppScreenMode.OPEN_HOME,
                isSideMenuOpen = false
            )
        }
    }

    private fun saveCurrentChatSession(sessionId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty() || _uiState.value.isTemporaryChat) return
        val firstUserMsg = messages.firstOrNull { it.isUser }?.text ?: "Chat Session"
        val title = firstUserMsg.take(34).trim().replaceFirstChar { it.uppercase() }
        val existing = app.chatHistoryStore.sessions.value.firstOrNull { it.id == sessionId }
        app.chatHistoryStore.lastOpenedChatId = sessionId
        app.chatHistoryStore.upsert(
            PreviousChatSession(
                id = sessionId,
                title = existing?.title ?: title,
                messages = messages,
                locked = existing?.locked ?: false,
                projectId = _uiState.value.activeProjectId
            )
        )
    }

    // ---- Temporary chats, locked chats and projects ----------------------------------------------------

    /** Starts a temporary chat (not saved, no memory), or leaves it for a normal new chat. */
    fun setTemporaryChat(enabled: Boolean) {
        startNewConversation()
        app.gemmaEngine.isTemporaryChat = enabled
        _uiState.update { it.copy(isTemporaryChat = enabled) }
    }

    fun setChatLocked(sessionId: String, locked: Boolean) {
        val session = app.chatHistoryStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        app.chatHistoryStore.upsert(session.copy(locked = locked))
    }

    fun setLockedChatsUnlocked(unlocked: Boolean) {
        _uiState.update { it.copy(lockedChatsUnlocked = unlocked) }
    }

    /**
     * Reopens the chat that was open when the app last closed (after an update or restart), with its history
     * given back to the model. Locked chats stay closed.
     */
    fun restoreLastChat() {
        if (_uiState.value.messages.isNotEmpty()) return
        val store = app.chatHistoryStore
        if (System.currentTimeMillis() - store.lastChatUsedAtMs > RESUME_CHAT_WINDOW_MS) return
        val id = store.lastOpenedChatId ?: return
        val session = app.chatHistoryStore.sessions.value.firstOrNull { it.id == id } ?: return
        if (session.locked || session.messages.isEmpty()) return
        openPreviousChat(session)
    }

    private var backgroundedAtMs = 0L

    /** The main screen went to the background: remember when, so a much later return starts fresh. */
    fun onAppBackgrounded() {
        backgroundedAtMs = System.currentTimeMillis()
        if (_uiState.value.messages.isNotEmpty() && !_uiState.value.isTemporaryChat) app.chatHistoryStore.touchLastChat()
    }

    /**
     * The main screen is visible again. Back within [RESUME_CHAT_WINDOW_MS]: the chat stays. Later than that: the
     * start page with a new chat (the old one is saved in the menu), like opening the app fresh.
     */
    fun onAppForegrounded() {
        val away = if (backgroundedAtMs == 0L) 0L else System.currentTimeMillis() - backgroundedAtMs
        backgroundedAtMs = 0L
        val state = _uiState.value
        if (away > RESUME_CHAT_WINDOW_MS && state.messages.isNotEmpty() && !state.isGenerating && !state.isReasoning &&
            state.screenMode != AppScreenMode.VOICE_MODE
        ) {
            if (state.isTemporaryChat) setTemporaryChat(false) else startNewConversation()
        }
    }

    /** Opens a saved chat by id (e.g. from a scheduled-task notification); locked chats stay closed. */
    fun openChatById(sessionId: String) {
        val session = app.chatHistoryStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (session.locked && !_uiState.value.lockedChatsUnlocked) {
            reportError("That chat is locked. Unlock locked chats in the menu first.")
            return
        }
        openPreviousChat(session)
    }

    /** Starts a new chat inside a project. */
    fun startProjectChat(projectId: String) {
        val project = app.features.projects.get(projectId) ?: return
        startNewConversation()
        _uiState.update {
            it.copy(activeProjectId = project.id, activeProjectName = project.name, screenMode = AppScreenMode.TEXT_CHAT)
        }
    }

    fun exportData(target: Uri) {
        _uiState.update { it.copy(isBackupRunning = true, backupStatus = "Exporting your data...") }
        viewModelScope.launch {
            val result = org.fossistant.assistant.features.DataBackup.export(getApplication(), target)
            _uiState.update {
                it.copy(
                    isBackupRunning = false,
                    backupStatus = result.fold(
                        { s -> "Exported ${s.chats} chats, ${s.attachments} pictures and files, and ${s.files} other files (memories, skills, projects, recordings, settings)." },
                        { e -> "Export failed: ${e.message}" }
                    )
                )
            }
        }
    }

    fun importData(source: Uri) {
        _uiState.update { it.copy(isBackupRunning = true, backupStatus = "Importing your data...") }
        viewModelScope.launch {
            val result = org.fossistant.assistant.features.DataBackup.import(getApplication(), source)
            _uiState.update {
                it.copy(
                    isBackupRunning = false,
                    backupNeedsRestart = result.isSuccess,
                    backupStatus = result.fold(
                        { s -> "Imported ${s.chats} chats, ${s.attachments} pictures and files, and ${s.files} other files. Fossistant restarts to load them." },
                        { e -> "Import failed: ${e.message}" }
                    )
                )
            }
        }
    }

    fun dismissBackupStatus() {
        val restart = _uiState.value.backupNeedsRestart
        _uiState.update { it.copy(backupStatus = null, backupNeedsRestart = false) }
        if (restart) org.fossistant.assistant.features.DataBackup.restartApp(getApplication())
    }

    fun setResearchArmed(armed: Boolean) {
        _uiState.update { it.copy(isResearchArmed = armed) }
    }

    private fun imageGenReadySet() =
        org.fossistant.assistant.model.ImageGenModelChoice.entries.filter { app.imageGenEngine.isReady(it) }.toSet()

    /**
     * Picked in the plus menu: a downloaded model switches image mode on (or off when it's already on);
     * a missing one starts its download, which then switches image mode on when it finishes.
     */
    fun pickImageModel(choice: org.fossistant.assistant.model.ImageGenModelChoice) {
        if (app.imageGenEngine.isReady(choice)) {
            _uiState.update {
                it.copy(
                    imageGenArmed = if (it.imageGenArmed == choice) null else choice,
                    imageGenReady = imageGenReadySet(),
                    isResearchArmed = false
                )
            }
        } else {
            app.imageGenEngine.startDownload(choice)
        }
    }

    fun cancelImageModelDownload(choice: org.fossistant.assistant.model.ImageGenModelChoice) {
        app.imageGenEngine.cancelDownload(choice)
    }

    fun disarmImageGen() {
        _uiState.update { it.copy(imageGenArmed = null) }
    }

    /**
     * Draws [prompt] with [choice]. An attached photo is edited instead (img2img). The answer bubble shows the
     * step count while it works and the finished picture as an image attachment.
     */
    private fun startImageGeneration(
        prompt: String,
        choice: org.fossistant.assistant.model.ImageGenModelChoice,
        attachments: List<MediaAttachment>
    ) {
        val source = attachments.firstOrNull { it.modality == MediaModality.IMAGE }
        val userMessage = ChatMessage(id = UUID.randomUUID().toString(), isUser = true, text = prompt, attachments = attachments)
        val answerId = UUID.randomUUID().toString()
        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        app.gemmaEngine.cancelCurrentGeneration()
        val verb = if (source != null) "Editing your picture" else "Drawing"
        fun post(message: ChatMessage, busy: Boolean) = _uiState.update { state ->
            val index = state.messages.indexOfFirst { it.id == answerId }
            val list = if (index >= 0) state.messages.toMutableList().apply { set(index, message) } else state.messages + message
            state.copy(messages = list, isGenerating = busy)
        }
        _uiState.update {
            it.copy(
                screenMode = AppScreenMode.TEXT_CHAT,
                isSideMenuOpen = false,
                messages = it.messages + userMessage,
                pendingAttachments = emptyList(),
                isGenerating = true,
                isReasoning = false,
                errorMessage = null
            )
        }
        post(ChatMessage(id = answerId, isUser = false, text = "$verb with ${choice.displayName}…", isStreaming = true, modelName = choice.displayName), true)
        activeGenerationJob = viewModelScope.launch {
            try {
                val initBitmap = source?.let { loadAttachmentBitmap(it) }
                val started = System.currentTimeMillis()
                val file = app.imageGenEngine.generate(choice, prompt, initImage = initBitmap) { step, steps ->
                    post(
                        ChatMessage(id = answerId, isUser = false, text = "$verb with ${choice.displayName}… step $step of $steps", isStreaming = true, modelName = choice.displayName),
                        true
                    )
                }
                val seconds = (System.currentTimeMillis() - started) / 1000
                val image = MediaAttachment(
                    id = "generated_${file.name}",
                    uriString = file.absolutePath,
                    fileName = file.name,
                    modality = MediaModality.IMAGE,
                    mimeType = "image/png",
                    sizeBytes = file.length()
                )
                post(
                    ChatMessage(id = answerId, isUser = false, text = "Here it is (${seconds}s on this phone).", attachments = listOf(image), modelName = choice.displayName),
                    false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                post(ChatMessage(id = answerId, isUser = false, text = "Stopped.", modelName = choice.displayName), false)
                throw e
            } catch (e: Throwable) {
                post(
                    ChatMessage(id = answerId, isUser = false, text = "I couldn't make that picture.", errorMessage = e.message, modelName = choice.displayName),
                    false
                )
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
                saveCurrentChatSession(_uiState.value.currentChatSessionId, _uiState.value.messages)
            }
        }
    }

    private suspend fun loadAttachmentBitmap(attachment: MediaAttachment): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(attachment.uriString)
                val isLocalFile = uri.scheme == null || uri.scheme == "file"
                val localPath = if (isLocalFile) (uri.path ?: attachment.uriString) else null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (localPath != null) {
                    android.graphics.BitmapFactory.decodeFile(localPath, bounds)
                } else {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                    } ?: return@runCatching null
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                // Decode near 512 px so a 50 MP photo doesn't take hundreds of MB in heap.
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                if (localPath != null) {
                    android.graphics.BitmapFactory.decodeFile(localPath, decodeOpts)
                } else {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, decodeOpts)
                    }
                }
            }.getOrNull()
        }

    /** Moves the current chat into [project] (or out of any project); an empty chat just starts in it. */
    fun setChatProject(project: org.fossistant.assistant.features.Project?) {
        if (project != null && _uiState.value.messages.isEmpty()) {
            startProjectChat(project.id)
            return
        }
        _uiState.update { it.copy(activeProjectId = project?.id, activeProjectName = project?.name) }
        saveCurrentChatSession(_uiState.value.currentChatSessionId, _uiState.value.messages)
    }

    fun leaveProject() {
        _uiState.update { it.copy(activeProjectId = null, activeProjectName = null) }
    }

    // ---- PDF export, screen context ---------------------------------------------------------------------

    /** Saves [text] as a PDF in Downloads/Fossistant and opens it. */
    fun saveAsPdf(text: String, title: String? = null) {
        val ctx = getApplication<Application>()
        val docTitle = title ?: text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trimStart('#', ' ')?.take(60) ?: "Fossistant answer"
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.fossistant.assistant.features.PdfExporter.export(ctx, docTitle, text)
            }
            result.onSuccess { saved ->
                org.fossistant.assistant.features.PdfExporter.open(ctx, saved.uri)
            }.onFailure { reportError("Couldn't save the PDF: ${it.message}") }
        }
    }

    /**
     * Attaches the screenshot Android took when the assistant was opened over another app (needs "Use
     * screenshot" in the default assistant settings), plus the screen text. Returns false if none is available.
     */
    fun attachAssistScreenshot(): Boolean {
        val jpeg = FossistantApp.lastCapturedScreenshotJpeg
        val fresh = System.currentTimeMillis() - FossistantApp.lastCapturedScreenshotAtMs < SCREENSHOT_MAX_AGE_MS
        if (jpeg == null || !fresh) return false
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "assist_screen_${System.currentTimeMillis()}.jpg").apply { writeBytes(jpeg) }
        ctx.cacheDir.listFiles { f -> f.name.startsWith("assist_screen_") && f != file }?.forEach { it.delete() }
        val imageAttachment = MediaAttachment(
            id = "assist_screenshot",
            uriString = file.absolutePath,
            fileName = "Screen",
            modality = MediaModality.IMAGE,
            mimeType = "image/jpeg",
            sizeBytes = jpeg.size.toLong()
        )
        val screenText = FossistantApp.lastCapturedScreenContext?.takeIf { it.isNotBlank() }
        val textAttachment = screenText?.let { dump ->
            MediaAttachment(
                id = "screen_context_live",
                uriString = "screen://current",
                fileName = "Active_Screen_Context.txt",
                modality = MediaModality.LARGE_TEXT,
                mimeType = "text/plain",
                sizeBytes = dump.length.toLong(),
                extractedTextPreview = dump
            )
        }
        val replacedIds = setOfNotNull(imageAttachment.id, textAttachment?.id)
        _uiState.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.filterNot { it.id in replacedIds } +
                    listOfNotNull(imageAttachment, textAttachment)
            )
        }
        return true
    }

    // ---- Deep Research ---------------------------------------------------------------------------------

    /** Runs Deep Research on [question]: progress shows as the answer's status, then the cited report. */
    fun startDeepResearch(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val userMessage = ChatMessage(id = UUID.randomUUID().toString(), isUser = true, text = "Deep research: $q")
        val answerId = UUID.randomUUID().toString()
        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        _uiState.update {
            it.copy(
                screenMode = AppScreenMode.TEXT_CHAT,
                isSideMenuOpen = false,
                messages = it.messages + userMessage,
                isReasoning = true,
                isGenerating = false,
                errorMessage = null
            )
        }
        activeGenerationJob = viewModelScope.launch {
            try {
                app.features.research.run(q).collect { update ->
                    val message = ChatMessage(
                        id = answerId,
                        isUser = false,
                        text = update.report ?: update.status,
                        isStreaming = !update.done,
                        isReasoningPhase = !update.done,
                        errorMessage = if (update.failed) update.status else null
                    )
                    _uiState.update { state ->
                        val index = state.messages.indexOfFirst { it.id == answerId }
                        val list = if (index >= 0) state.messages.toMutableList().apply { set(index, message) } else state.messages + message
                        state.copy(messages = list, isReasoning = !update.done, isGenerating = false)
                    }
                }
                app.gemmaEngine.recordExternalTurn(userMessage.text, _uiState.value.messages.lastOrNull { it.id == answerId }?.text.orEmpty())
            } finally {
                _uiState.update { it.copy(isReasoning = false, isGenerating = false) }
                saveCurrentChatSession(_uiState.value.currentChatSessionId, _uiState.value.messages)
            }
        }
    }

    fun openPreviousChat(session: PreviousChatSession) {
        val currentMessages = _uiState.value.messages
        val currentSessionId = _uiState.value.currentChatSessionId
        if (currentMessages.isNotEmpty() && currentSessionId != session.id) {
            saveCurrentChatSession(currentSessionId, currentMessages)
        }
        if (session.messages.isNotEmpty()) {
            app.chatHistoryStore.lastOpenedChatId = session.id
            // Give Gemma the reopened chat as its conversation history, so it remembers what was said there.
            activeGenerationJob?.cancel()
            app.gemmaEngine.loadConversation(session.messages)
            val project = session.projectId?.let { id -> app.features.projects.get(id) }
            _uiState.update {
                it.copy(
                    messages = session.messages,
                    currentChatSessionId = session.id,
                    activeProjectId = project?.id,
                    activeProjectName = project?.name,
                    screenMode = AppScreenMode.TEXT_CHAT,
                    isSideMenuOpen = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentChatSessionId = session.id,
                    screenMode = AppScreenMode.OPEN_HOME,
                    isSideMenuOpen = false
                )
            }
            sendPrompt(session.title)
        }
    }

    /** On-device language identification (Android's TextClassifier); null when unsure. */
    private suspend fun detectTextLanguageTag(text: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
            val manager = getApplication<Application>()
                .getSystemService(android.view.textclassifier.TextClassificationManager::class.java)
            val result = manager.textClassifier.detectLanguage(
                android.view.textclassifier.TextLanguage.Request.Builder(text).build()
            )
            if (result.localeHypothesisCount == 0) return@runCatching null
            val best = result.getLocale(0)
            if (result.getConfidenceScore(best) >= 0.5f) best.toLanguageTag() else null
        }.getOrNull()
    }

    fun speakMessage(text: String) {
        if (text.isNotBlank()) {
            app.ttsEngine.speakFast(text, flushQueue = true)
        }
    }

    fun sendPrompt(
        rawPrompt: String,
        stayInVoiceMode: Boolean = false,
        forceSpeakResponse: Boolean = false
    ) {
        val prompt = rawPrompt.trim()
        val stateSnapshot = _uiState.value
        if (stateSnapshot.isResearchArmed && prompt.isNotEmpty() && stateSnapshot.screenMode != AppScreenMode.VOICE_MODE) {
            _uiState.update { it.copy(isResearchArmed = false) }
            startDeepResearch(prompt)
            return
        }
        stateSnapshot.imageGenArmed?.let { choice ->
            if (prompt.isNotEmpty() && stateSnapshot.screenMode != AppScreenMode.VOICE_MODE) {
                startImageGeneration(prompt, choice, stateSnapshot.pendingAttachments.filterNot { it.id == LIVE_CAMERA_ATTACHMENT_ID })
                return
            }
        }
        DEEP_RESEARCH_PREFIX.find(prompt)?.let { match ->
            if (stateSnapshot.pendingAttachments.isEmpty() && stateSnapshot.screenMode != AppScreenMode.VOICE_MODE) {
                startDeepResearch(prompt.substring(match.range.last + 1))
                return
            }
        }
        // The live camera keeps overwriting one frame file; each question keeps its own copy so the chat shows
        // the frame that was actually asked about.
        val attachments = stateSnapshot.pendingAttachments.map { attachment ->
            if (attachment.id == LIVE_CAMERA_ATTACHMENT_ID) snapshotLiveFrame(attachment) else attachment
        }
        if (prompt.isEmpty() && attachments.isEmpty()) return

        val enableReasoning = stateSnapshot.isReasoningToggleEnabled
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = prompt.ifEmpty { "[Attached ${attachments.size} file(s)]" },
            attachments = attachments
        )

        // Keep live camera frame attachment if Live Camera Viewfinder is still active
        val retainedAttachments = if (stateSnapshot.isLiveCameraActive) {
            stateSnapshot.pendingAttachments.filter { it.id == LIVE_CAMERA_ATTACHMENT_ID }
        } else {
            emptyList()
        }

        // Enter AI_REASONING (only if enabled) or AI_TALKING/generating state
        _uiState.update { state ->
            val isInVoice = stayInVoiceMode || state.screenMode == AppScreenMode.VOICE_MODE
            val shouldListen = isInVoice && !state.isVoiceMutedByUser && FossistantApp.isVoiceDuplexMode
            state.copy(
                screenMode = if (isInVoice) AppScreenMode.VOICE_MODE else AppScreenMode.TEXT_CHAT,
                isSideMenuOpen = false,
                messages = state.messages + userMessage,
                pendingAttachments = retainedAttachments,
                isListeningVoice = shouldListen,
                isReasoning = enableReasoning,
                isGenerating = !enableReasoning,
                liveVoicePartial = "",
                errorMessage = null
            )
        }
        if (!FossistantApp.isVoiceDuplexMode) {
            sttEngine.stopListening()
        }
        saveCurrentChatSession(_uiState.value.currentChatSessionId, _uiState.value.messages)

        val isVoiceSession = stayInVoiceMode || stateSnapshot.screenMode == AppScreenMode.VOICE_MODE || forceSpeakResponse
        app.gemmaEngine.isVoiceMode = isVoiceSession

        activeGenerationJob?.cancel()
        app.ttsEngine.stop()
        app.gemmaEngine.cancelCurrentGeneration()
        activeGenerationJob = viewModelScope.launch {
            try {
                // Language the answer is spoken in: the chosen language, or in Automatic mode the language
                // Whisper heard (voice) or the on-device language identifier finds in the typed prompt.
                val replyLanguageTag: String? = when {
                    !stateSnapshot.selectedLanguage.isAuto -> stateSnapshot.selectedLanguage.code
                    isVoiceSession && sttEngine.lastDetectedLanguage.value != null ->
                        SupportedLanguage.fromCode(sttEngine.lastDetectedLanguage.value).code
                    else -> detectTextLanguageTag(prompt)
                }
                val shouldSpeak = isVoiceSession && stateSnapshot.voiceProfilesEnabled && stateSnapshot.soundNotificationsEnabled
                var spokenCharCount = 0
                var spokenText = ""
                var isFirstTtsChunk = true
                val sentenceRegex = Regex("(?<=[.!?\\n])\\s+")

                val turnContext = app.features.turnContext(prompt, stateSnapshot.activeProjectId)
                router.processUserTurn(
                    prompt = prompt.ifEmpty { "Describe what you see in the attached image or context." },
                    attachments = attachments,
                    enableReasoning = enableReasoning,
                    turnContext = turnContext
                ).collect { assistantMsg ->
                    _uiState.update { state ->
                        val existingIdx = state.messages.indexOfFirst { it.id == assistantMsg.id }
                        val updatedList = if (existingIdx >= 0) {
                            state.messages.toMutableList().apply { set(existingIdx, assistantMsg) }
                        } else {
                            state.messages + assistantMsg
                        }
                        state.copy(
                            messages = updatedList,
                            // Stay in AI_REASONING while the model is in its <think> phase;
                            // once visible answer tokens stream, switch to AI_TALKING!
                            isReasoning = assistantMsg.isReasoningPhase,
                            isGenerating = assistantMsg.isStreaming && !assistantMsg.isReasoningPhase,
                            isGemmaLoadedInRam = app.gemmaEngine.isNativeModelLoaded,
                            errorMessage = assistantMsg.errorMessage ?: state.errorMessage
                        )
                    }

                    // Streaming Parallel TTS: Speak sentences in parallel as tokens stream from the model!
                    if (shouldSpeak && !assistantMsg.isReasoningPhase) {
                        if (spokenText.isNotEmpty() && !assistantMsg.text.startsWith(spokenText)) {
                            // Text was rewritten (e.g. final cleanup); re-anchor after the longest spoken prefix.
                            spokenCharCount = assistantMsg.text.commonPrefixWith(spokenText).length
                        }
                    }
                    if (shouldSpeak && !assistantMsg.isReasoningPhase && assistantMsg.text.length > spokenCharCount) {
                        var unhandled = assistantMsg.text.substring(spokenCharCount)
                        var match = sentenceRegex.find(unhandled)
                        while (match != null) {
                            val endIdx = match.range.last + 1
                            val clause = unhandled.substring(0, match.range.first + 1).trim()
                            if (clause.isNotEmpty()) {
                                app.ttsEngine.speakFast(clause, flushQueue = isFirstTtsChunk, languageTag = replyLanguageTag)
                                isFirstTtsChunk = false
                            }
                            spokenCharCount += endIdx
                            spokenText = assistantMsg.text.substring(0, spokenCharCount)
                            unhandled = assistantMsg.text.substring(spokenCharCount)
                            match = sentenceRegex.find(unhandled)
                        }
                    }
                }

                // Speak any trailing sentence/clause that did not end in explicit trailing delimiter
                if (shouldSpeak) {
                    val finalAssistantMsg = _uiState.value.messages.lastOrNull { !it.isUser }?.text.orEmpty()
                    if (spokenText.isNotEmpty() && !finalAssistantMsg.startsWith(spokenText)) {
                        spokenCharCount = finalAssistantMsg.commonPrefixWith(spokenText).length
                    }
                    if (finalAssistantMsg.length > spokenCharCount) {
                        val remaining = finalAssistantMsg.substring(spokenCharCount).trim()
                        if (remaining.isNotEmpty()) {
                            app.ttsEngine.speakFast(remaining, flushQueue = isFirstTtsChunk, languageTag = replyLanguageTag)
                        }
                    }
                }

                // Automatically persist session with dynamically generated title from user prompt
                val finalMessages = _uiState.value.messages
                saveCurrentChatSession(_uiState.value.currentChatSessionId, finalMessages)

                val currentState = _uiState.value
                val isInVoice = (currentState.screenMode == AppScreenMode.VOICE_MODE) || isVoiceSession
                if (isInVoice && !currentState.isVoiceMutedByUser && !app.ttsEngine.isSpeaking.value) {
                    _uiState.update {
                        it.copy(
                            liveVoicePartial = ""
                        )
                    }
                }
            } finally {
                // Ensure reasoning and generating flags are ALWAYS cleanly cleared when generation concludes or cancels
                _uiState.update {
                    it.copy(
                        isReasoning = false,
                        isGenerating = false
                    )
                }
            }
        }
    }

    fun toggleVoiceProfiles(enabled: Boolean) {
        _uiState.update { it.copy(voiceProfilesEnabled = enabled) }
        if (!enabled) {
            app.ttsEngine.stop()
        }
    }

    fun toggleMemorySmartEviction(enabled: Boolean) {
        _uiState.update { it.copy(memorySmartEvictionEnabled = enabled) }
        if (enabled) {
            releaseGemmaFromRamNow()
        }
    }

    fun setStrictToolCalls(enabled: Boolean) {
        FossistantApp.strictToolCalls = enabled
        FossistantApp.savePreferences(getApplication())
        app.gemmaEngine.strictToolCalls = enabled
        _uiState.update { it.copy(strictToolCalls = enabled) }
        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "Strict tool calls switched ${if (enabled) "on" else "off"} by the user")
    }

    fun setMtpEnabled(enabled: Boolean) {
        FossistantApp.useMtp = enabled
        FossistantApp.savePreferences(getApplication())
        app.gemmaEngine.allowSpeculativeDecoding = enabled
        _uiState.update { it.copy(mtpEnabled = enabled) }
        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "MTP speculative decoding switched ${if (enabled) "on" else "off"} by the user")
    }

    fun setLimitCompute(enabled: Boolean) {
        FossistantApp.limitCompute = enabled
        FossistantApp.savePreferences(getApplication())
        app.gemmaEngine.limitCompute = enabled
        _uiState.update { it.copy(limitCompute = enabled) }
        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "Compute cap: $enabled")
    }

    fun toggleVoiceDuplexMode(enabled: Boolean) {
        FossistantApp.isVoiceDuplexMode = enabled
        FossistantApp.savePreferences(getApplication())
        _uiState.update { it.copy(isVoiceDuplexMode = enabled) }
        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "Voice delivery mode switched: Duplex=$enabled (Turn-by-turn=${!enabled})")
    }

    /**
     * Tapping the Cosmic AI Blob:
     * If the assistant is talking or generating (especially in Turn-by-Turn mode),
     * it immediately interrupts speech/generation and enables the microphone!
     */
    fun onBlobTapped(onStartVoiceListening: () -> Unit) {
        val state = _uiState.value
        org.fossistant.assistant.util.AppLogger.i("AssistantViewModel", "onBlobTapped: isTtsSpeaking=${state.isTtsSpeaking}, isGenerating=${state.isGenerating}, isReasoning=${state.isReasoning}, isListeningVoice=${state.isListeningVoice}, isDuplex=${state.isVoiceDuplexMode}")

        if (state.isTtsSpeaking || state.isGenerating || state.isReasoning) {
            // Interrupt talking/generating immediately and open the mic!
            stopVoiceAndTts()
            _uiState.update {
                it.copy(
                    isListeningVoice = true,
                    isVoiceMutedByUser = false,
                    liveVoicePartial = "Listening with Whisper..."
                )
            }
            onStartVoiceListening()
        } else if (state.isListeningVoice) {
            stopVoiceCapture()
        } else {
            _uiState.update {
                it.copy(
                    isListeningVoice = true,
                    isVoiceMutedByUser = false,
                    liveVoicePartial = "Listening with Whisper..."
                )
            }
            onStartVoiceListening()
        }
    }

    fun toggleSoundNotifications(enabled: Boolean) {
        _uiState.update { it.copy(soundNotificationsEnabled = enabled) }
        if (!enabled) {
            app.ttsEngine.stop()
        }
    }

    fun toggleLiveCamera(active: Boolean? = null) {
        _uiState.update { state ->
            val next = active ?: !state.isLiveCameraActive
            val updatedAttachments = if (!next) {
                state.pendingAttachments.filterNot { it.id == LIVE_CAMERA_ATTACHMENT_ID }
            } else {
                state.pendingAttachments
            }
            state.copy(
                isLiveCameraActive = next,
                pendingAttachments = updatedAttachments
            )
        }
    }

    /**
     * Saves the latest JPEG frame captured from [org.fossistant.assistant.ui.components.CameraLiveViewfinder]
     * and attaches it to [AssistantUiState.pendingAttachments] so Gemma 4 E2B Vision receives the
     * real-time camera image whenever the user speaks or sends a prompt.
     */
    private fun snapshotLiveFrame(attachment: MediaAttachment): MediaAttachment {
        val ctx = getApplication<Application>()
        return runCatching {
            val dir = File(ctx.cacheDir, "live_frames").apply { mkdirs() }
            val copy = File(dir, "frame_${System.currentTimeMillis()}.jpg")
            File(Uri.parse(attachment.uriString).path ?: return attachment).copyTo(copy, overwrite = true)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_SAVED_FRAMES)?.forEach { it.delete() }
            attachment.copy(id = "live_frame_${copy.name}", uriString = copy.absolutePath, fileName = "Camera")
        }.getOrDefault(attachment)
    }

    fun updateLiveCameraFrameJpeg(jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return
        val ctx = getApplication<Application>()
        runCatching {
            val frameFile = File(ctx.cacheDir, "live_camera_frame.jpg")
            frameFile.writeBytes(jpegBytes)
            val liveAttachment = MediaAttachment(
                id = LIVE_CAMERA_ATTACHMENT_ID,
                uriString = Uri.fromFile(frameFile).toString(),
                fileName = "Live_Camera_Stream.jpg",
                modality = MediaModality.IMAGE,
                mimeType = "image/jpeg",
                sizeBytes = jpegBytes.size.toLong()
            )
            _uiState.update { state ->
                val withoutOld = state.pendingAttachments.filterNot { it.id == LIVE_CAMERA_ATTACHMENT_ID }
                state.copy(pendingAttachments = withoutOld + liveAttachment)
            }
        }
    }

    fun attachMediaUri(uri: Uri, explicitModality: MediaModality? = null) {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var fileName = uri.lastPathSegment ?: "attachment"
        var sizeBytes = 0L

        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
            }
        }

        val modality = explicitModality ?: when {
            mime.startsWith("image/") -> MediaModality.IMAGE
            mime.startsWith("video/") -> MediaModality.VIDEO
            mime.startsWith("audio/") -> MediaModality.AUDIO
            else -> MediaModality.LARGE_TEXT
        }

        val attachment = MediaAttachment(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            fileName = fileName,
            modality = modality,
            mimeType = mime,
            sizeBytes = sizeBytes
        )
        val lower = fileName.lowercase()
        val needsExtraction = modality == MediaModality.LARGE_TEXT &&
            (mime == "application/pdf" || lower.endsWith(".pdf") || lower.endsWith(".docx") || mime.contains("wordprocessingml"))
        if (!needsExtraction) {
            _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
            return
        }
        // PDFs and Word files are binary: read their text once (same reader as project files).
        viewModelScope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.fossistant.assistant.features.ProjectFiles.import(ctx, uri)
            }.map { file -> file.chunks.joinToString("\n\n").take(MAX_DOCUMENT_CHARS) }
            text.onSuccess { extracted ->
                _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment.copy(extractedTextPreview = extracted)) }
            }.onFailure { reportError("Couldn't read $fileName: ${it.message}") }
        }
    }

    /**
     * Captures live screen hierarchy or screenshot context from [FossistantApp.lastCapturedScreenContext]
     * or [org.fossistant.assistant.service.FossistantAgentAccessibilityService].
     * Returns true if real screen context was captured, or false if Accessibility / Assist context is not yet enabled.
     */
    fun attachScreenContextIfAvailable(): Boolean {
        val accessibilityDump = org.fossistant.assistant.service.FossistantAgentAccessibilityService.activeInstance?.dumpCurrentScreenSummary()
        val assistDump = FossistantApp.lastCapturedScreenContext
        val screenDump = accessibilityDump ?: assistDump

        if (screenDump.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Screen Agent requires either the Fossistant Accessibility Service to be enabled or invoking Fossistant via the Android Assistant gesture."
                )
            }
            return false
        }

        val attachment = MediaAttachment(
            id = "screen_context_live",
            uriString = "screen://current",
            fileName = "Active_Screen_Context.txt",
            modality = MediaModality.LARGE_TEXT,
            mimeType = "text/plain",
            sizeBytes = screenDump.length.toLong(),
            extractedTextPreview = screenDump
        )
        _uiState.update { state ->
            val withoutOld = state.pendingAttachments.filterNot { it.id == "screen_context_live" }
            state.copy(
                pendingAttachments = withoutOld + attachment,
                errorMessage = null
            )
        }
        return true
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun setVoiceListeningState(listening: Boolean, partialText: String = "", enterVoiceScreenMode: Boolean = true) {
        _uiState.update {
            it.copy(
                isListeningVoice = listening,
                isVoiceMutedByUser = if (listening) false else it.isVoiceMutedByUser,
                voiceRmsDb = if (listening) it.voiceRmsDb else 0f,
                liveVoicePartial = partialText,
                screenMode = if (listening && enterVoiceScreenMode) AppScreenMode.VOICE_MODE else it.screenMode
            )
        }
    }

    fun updateVoiceRms(rmsDb: Float) {
        _uiState.update { it.copy(voiceRmsDb = rmsDb.coerceAtLeast(0f)) }
    }

    fun releaseGemmaFromRamNow() {
        viewModelScope.launch {
            app.gemmaEngine.releaseMemory()
            _uiState.update { it.copy(isGemmaLoadedInRam = false) }
        }
    }

    fun dismissModelImport() {
        _uiState.update { it.copy(modelImportRequest = null) }
    }

    /** Copies the picked file for [model] into the app, then switches to it. */
    fun importModel(model: PrimaryLlmModel, uri: Uri) {
        _uiState.update { it.copy(modelImportRequest = null, modelImportProgress = 0f) }
        viewModelScope.launch {
            val error = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.gemmaEngine.importModelFile(model, uri) { progress ->
                    _uiState.update { it.copy(modelImportProgress = progress) }
                }
            }
            _uiState.update { it.copy(modelImportProgress = null) }
            if (error != null) {
                reportError(error)
            } else {
                switchPrimaryModel(model)
            }
        }
    }

    fun setPrimaryHardwareBackend(backend: HardwareInferenceBackend) {
        FossistantApp.primaryHardwareBackend = backend
        FossistantApp.savePreferences(getApplication())
        app.gemmaEngine.hardwareBackend = backend
        _uiState.update { it.copy(primaryHardwareBackend = backend) }
        viewModelScope.launch {
            app.gemmaEngine.releaseMemory()
            app.gemmaEngine.ensureInitialized()
        }
    }

    fun setWhisperHardwareBackend(backend: HardwareInferenceBackend) {
        FossistantApp.whisperHardwareBackend = backend
        FossistantApp.savePreferences(getApplication())
        app.sttEngine.setHardwareBackend(backend)
        _uiState.update { it.copy(whisperHardwareBackend = backend) }
    }

    // ---- First-launch setup & model download ----

    /** Gemma (MTP) and the chosen Whisper model are both in private storage. */
    fun areModelsReady(): Boolean =
        app.gemmaEngine.isModelAvailable(FossistantApp.primaryModelChoice) &&
            (app.modelDownloads.isWhisperReady(FossistantApp.whisperModelChoice) ||
                org.fossistant.assistant.util.BundledModels.contains(getApplication(), FossistantApp.whisperModelChoice.filename))

    fun modelBytesToDownload(): Long = app.modelDownloads.missingBytes(FossistantApp.primaryModelChoice, FossistantApp.whisperModelChoice)

    fun freeStorageBytes(): Long = app.modelDownloads.freeBytes()

    fun setDownloadOverWifiOnly(enabled: Boolean) {
        FossistantApp.downloadOverWifiOnly = enabled
        FossistantApp.savePreferences(getApplication())
        _uiState.update { it.copy(downloadOverWifiOnly = enabled) }
    }

    fun startModelDownload() {
        app.modelDownloads.start(
            model = FossistantApp.primaryModelChoice,
            whisper = FossistantApp.whisperModelChoice,
            wifiOnly = FossistantApp.downloadOverWifiOnly
        ) {
            // Loads the downloaded model once so LiteRT builds this phone's GPU programs now, not on the first question.
            app.gemmaEngine.ensureInitialized()
            app.sttEngine.switchModel(FossistantApp.whisperModelChoice)
        }
    }

    fun cancelModelDownload() = app.modelDownloads.cancel()

    fun setAiName(name: String) = updateAiIdentityAndPersonality(aiName = name)

    fun openOnboarding() = _uiState.update { it.copy(showOnboarding = true) }

    fun finishOnboarding() {
        FossistantApp.completeOnboarding(getApplication())
        _uiState.update { it.copy(showOnboarding = false, screenMode = AppScreenMode.OPEN_HOME) }
    }

    fun switchPrimaryModel(model: PrimaryLlmModel) {
        if (_uiState.value.primaryModelChoice == model && _uiState.value.isGemmaLoadedInRam) {
            return
        }
        if (!app.gemmaEngine.isModelAvailable(model)) {
            _uiState.update { it.copy(modelImportRequest = model) }
            return
        }
        activeGenerationJob?.cancel()
        app.gemmaEngine.cancelCurrentGeneration()
        FossistantApp.primaryModelChoice = model
        FossistantApp.savePreferences(getApplication())
        _uiState.update {
            it.copy(
                primaryModelChoice = model,
                primaryModelDisplayName = model.displayName,
                isGemmaLoadingMemory = true,
                isGenerating = false,
                isReasoning = false,
                gemmaMemoryStatus = "Switching to ${model.displayName}..."
            )
        }
        viewModelScope.launch {
            val success = app.gemmaEngine.switchModel(model)
            val isLoaded = app.gemmaEngine.isNativeModelLoaded
            val memStatus = app.gemmaEngine.memoryStatus.value
            val err = if (!success) app.gemmaEngine.lastEngineError else null

            _uiState.update {
                it.copy(
                    primaryModelChoice = model,
                    primaryModelDisplayName = model.displayName,
                    isGemmaLoadingMemory = false,
                    gemmaMemoryStatus = memStatus,
                    isGemmaLoadedInRam = isLoaded,
                    errorMessage = err
                )
            }
        }
    }

    fun switchWhisperModel(model: WhisperModelChoice) {
        if (_uiState.value.whisperModelChoice == model) return
        FossistantApp.whisperModelChoice = model
        FossistantApp.savePreferences(getApplication())
        _uiState.update { it.copy(whisperModelChoice = model) }
        viewModelScope.launch {
            app.sttEngine.switchModel(model)
        }
    }

    companion object {
        const val LIVE_CAMERA_ATTACHMENT_ID = "live_camera_frame"
        /** Coming back within this time keeps the chat; later (or a fresh launch) starts a new one. */
        const val RESUME_CHAT_WINDOW_MS = 10 * 60 * 1000L
        /** Text kept from an attached PDF / Word file (~3K tokens). */
        private const val MAX_DOCUMENT_CHARS = 12_000
        private const val MAX_SAVED_FRAMES = 40
        private const val SCREENSHOT_MAX_AGE_MS = 10 * 60 * 1000L
        /** "deep research: …" or "research: …" starts Deep Research instead of a normal answer. */
        val DEEP_RESEARCH_PREFIX = Regex("^(?i)(deep research|research)\\s*[:\\-]\\s*")
    }
}
