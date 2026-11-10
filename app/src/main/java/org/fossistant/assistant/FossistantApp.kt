package org.fossistant.assistant

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.DualModelRouter
import org.fossistant.assistant.engine.FastMultilingualTtsEngine
import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.fossistant.assistant.engine.MultimodalChunkProcessor
import org.fossistant.assistant.engine.WhisperMultilingualSttEngine
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.model.HardwareInferenceBackend
import org.fossistant.assistant.model.PrimaryLlmModel
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor

/**
 * Application singleton managing the Dual-Model lifecycle, Fast 7-Language TTS Engine,
 * Whisper Multilingual STT Engine, Persistent On-Device AI Memory System, and 8GB RAM callbacks.
 */
class FossistantApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Emits whenever the user barges in on the assistant in duplex voice mode. */
    val userInterruptions = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    lateinit var memoryManager: AiMemoryManager
        private set

    /** Saved chats, shared by the main screen and the assistant popup. */
    lateinit var chatHistoryStore: org.fossistant.assistant.ui.ChatHistoryStore
        private set

    lateinit var ttsEngine: FastMultilingualTtsEngine
        private set

    lateinit var sttEngine: WhisperMultilingualSttEngine
        private set

    lateinit var gemmaEngine: GemmaLiteRtEngine
        private set


    lateinit var toolExecutor: DeviceAndWebToolExecutor
        private set

    lateinit var router: DualModelRouter
        private set

    /** Skills, projects, scheduled tasks, connectors, recordings and Deep Research. */
    lateinit var features: org.fossistant.assistant.features.Features
        private set

    /** On-device image generation (Stable Diffusion 1.5 / LCM); its models download on first use. */
    val imageGenEngine by lazy { org.fossistant.assistant.engine.ImageGenEngine(this) }

    /** Downloads Gemma (MTP build) and Whisper into private storage when this APK doesn't carry them (slim). */
    val modelDownloads by lazy { org.fossistant.assistant.engine.ModelDownloadManager(this) }

    private var appRescan: kotlinx.coroutines.Job? = null

    /** Re-reads the app action list a few seconds after an app is installed, updated or removed. */
    private fun watchInstalledApps() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val index = toolExecutor.appActions ?: return
                index.invalidate()
                appRescan?.cancel()
                appRescan = appScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(5_000) // an install sends several broadcasts
                    index.actions()
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { org.fossistant.assistant.util.AppLogger.w("FossistantApp", "Can't watch app installs: ${it.message}") }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize persistent 10,000-line crash-resilient AppLogger
        org.fossistant.assistant.util.AppLogger.init(this)
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            org.fossistant.assistant.util.AppLogger.logFatalCrash(thread, throwable)
            org.fossistant.assistant.util.CrashReporter.recordJavaCrash(this, thread, throwable)
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }

        loadPreferences(this)

        val modelsDir = filesDir.resolve("models").apply { mkdirs() }
        memoryManager = AiMemoryManager(storageDir = filesDir, context = applicationContext)
        chatHistoryStore = org.fossistant.assistant.ui.ChatHistoryStore(filesDir)
        ttsEngine = FastMultilingualTtsEngine(context = applicationContext)
        sttEngine = WhisperMultilingualSttEngine(context = applicationContext).apply {
            setHardwareBackend(whisperHardwareBackend)
        }
        toolExecutor = DeviceAndWebToolExecutor(context = applicationContext)
        watchInstalledApps()
        gemmaEngine = GemmaLiteRtEngine(
            context = applicationContext,
            toolExecutor = toolExecutor,
            chunkProcessor = MultimodalChunkProcessor(applicationContext),
            memoryManager = memoryManager
        ).apply {
            activeModel = primaryModelChoice
            hardwareBackend = primaryHardwareBackend
            limitCompute = Companion.limitCompute
            allowSpeculativeDecoding = Companion.useMtp
            strictToolCalls = Companion.strictToolCalls
            aiName = customAiName
            customPersonalityInstructions = this@FossistantApp.let { Companion.customPersonalityInstructions }
        }
        // Dropped models (Bonsai, MiniCPM, the community GPU+MTP Gemma): free their unpacked files,
        // LiteRT GPU caches and saved llama.cpp state.
        val droppedModels = listOf("Ternary-Bonsai", "MiniCPM5-2B_int4.litertlm", "MiniCPM-V", "gemma-4-E2B-it-web-mtp", "massive-multilingual-router")
        listOf(modelsDir, filesDir.resolve("litertlm_compiled_shaders")).forEach { dir ->
            dir.listFiles { f -> droppedModels.any { f.name.startsWith(it) } }?.forEach { it.delete() }
        }
        filesDir.resolve("llama_state").deleteRecursively()
        // First launch of an APK without Gemma: start on a model that's actually there.
        if (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("primary_model_choice", null) == null &&
            !gemmaEngine.isModelAvailable(primaryModelChoice)
        ) {
            PrimaryLlmModel.entries.firstOrNull { gemmaEngine.isModelAvailable(it) }?.let { available ->
                primaryModelChoice = available
                gemmaEngine.activeModel = available
            }
        }
        router = DualModelRouter(
            gemmaEngine = gemmaEngine,
            toolExecutor = toolExecutor
        )
        features = org.fossistant.assistant.features.Features(applicationContext, gemmaEngine, toolExecutor)
        org.fossistant.assistant.features.TaskScheduler.ensureChannels(this)
        org.fossistant.assistant.features.TaskScheduler.scheduleAll(this)

        // Wire Tier-2 Full Duplex & Real-Time Barge-In pipeline:
        // 1. Forward raw TTS 16-bit PCM to Software Acoustic Echo Canceler (AEC)
        ttsEngine.onAudioPcmAvailable = { pcmChunk ->
            sttEngine.onTtsPlaybackAudioChunk(pcmChunk)
        }
        // 2. Stage-1 Barge-in: Duck TTS volume down to 20% on tentative user speech
        sttEngine.onTentativeBargeIn = {
            ttsEngine.setDucked(true)
        }
        // 3. Un-duck TTS volume back to 100% on false alarm or backchannel
        sttEngine.onBargeInCleared = {
            ttsEngine.setDucked(false)
        }
        // 4. Suppress self-echo transcripts of assistant's own speech
        sttEngine.isSelfEchoChecker = { candidate ->
            ttsEngine.isLikelySelfEcho(candidate)
        }
        // 5. Hard user interruption: stop TTS, cancel ongoing LLM generation
        sttEngine.onUserInterruption = {
            ttsEngine.setDucked(false)
            ttsEngine.stop()
            gemmaEngine.cancelCurrentGeneration()
            // Lets view models cancel their streaming job so no leftover sentences get spoken.
            userInterruptions.tryEmit(Unit)
        }
        sttEngine.isTtsSpeakingProvider = { ttsEngine.isSpeaking.value }
        sttEngine.isAssistantBusyProvider = { ttsEngine.isSpeaking.value || gemmaEngine.isGeneratingActive }
        sttEngine.isEchoGuardActiveProvider = { ttsEngine.isEchoGuardActive() }
        // 6. Duplex speakerphone: play TTS on the voice-communication path so hardware AEC can cancel it
        sttEngine.onCommunicationModeChanged = { active ->
            ttsEngine.useCommunicationAudio = active
        }
        sttEngine.ttsAudioLevelProvider = { ttsEngine.ttsAudioLevel.value }

        // Read the installed apps' shortcuts and App Actions before the first chat needs them.
        appScope.launch(Dispatchers.IO) { toolExecutor.appActions?.actions() }

        // Asynchronously pre-warm native neural engines on background IO
        appScope.launch(Dispatchers.IO) {
            sttEngine.switchModel(whisperModelChoice)
            gemmaEngine.ensureInitialized()
            if (BuildConfig.BUNDLES_MODELS) {
                // Unpack every model this APK carries once, so a later slim update (no models in the APK), or
                // another model APK installed over this one, can still use all of them.
                WhisperModelChoice.entries.forEach { sttEngine.resolveOrExtractModelFile(it) }
                PrimaryLlmModel.entries
                    .filter { org.fossistant.assistant.util.BundledModels.contains(this@FossistantApp, it.filename) }
                    .forEach { gemmaEngine.resolveModelFile(model = it) }
            }
        }

        // Start background wake word service if enabled. Android 12+ refuses microphone foreground services from a
        // process starting in the background; MainActivity.onResume starts it once the app is on screen.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            org.fossistant.assistant.service.VoiceWakeWordService.startIfEnabled(this)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Once Gemma 4 E2B is loaded after Tier-1 Router calls on it, keep it in memory until the app is closed.
    }

    override fun onTerminate() {
        ttsEngine.shutdown()
        sttEngine.stopListening()
        kotlinx.coroutines.runBlocking {
            gemmaEngine.releaseMemory()
        }
        super.onTerminate()
    }

    companion object {
        private const val PREFS_NAME = "fossistant_prefs"

        @Volatile
        var instance: FossistantApp? = null
            private set

        /**
         * A prompt chosen in the share sheet / text-selection dialog, handed to MainActivity in-process
         * (deliberately not via intent extras, which any app could forge).
         */
        @Volatile
        var pendingSharedPrompt: String? = null

        /** A Deep Research question / project chat started from the Tools screen, handed over in-process. */
        @Volatile
        var pendingResearchQuestion: String? = null

        @Volatile
        var pendingProjectChatId: String? = null

        /** Home Assistant server (e.g. http://192.168.1.10:8123) and long-lived access token. */
        @Volatile
        var homeAssistantUrl: String = ""

        @Volatile
        var homeAssistantToken: String = ""

        @Volatile
        var lastCapturedScreenContext: String? = null

        @Volatile
        var lastCapturedScreenshotJpeg: ByteArray? = null

        /** When [lastCapturedScreenshotJpeg] was taken (the assist gesture), to ignore stale screenshots. */
        @Volatile
        var lastCapturedScreenshotAtMs: Long = 0L

        /** Language Whisper listens in during live screen sharing (the popup's selected language). */
        @Volatile
        var screenShareLanguage: org.fossistant.assistant.model.SupportedLanguage =
            org.fossistant.assistant.model.SupportedLanguage.AUTO

        @Volatile
        var isVoiceTriggerEnabled: Boolean = true

        @Volatile
        var wakeWordPhrase: String = "Hey Fossistant"

        @Volatile
        var wakeWordSensitivity: Float = 0.65f

        @Volatile
        var customAiName: String = "Fossistant"

        @Volatile
        var customPersonalityInstructions: String = ""

        @Volatile
        var currentMoodTheme: BlobMoodTheme = BlobMoodTheme.COSMIC_NEBULA

        @Volatile
        var whisperHardwareBackend: HardwareInferenceBackend = HardwareInferenceBackend.AUTO

        @Volatile
        var primaryHardwareBackend: HardwareInferenceBackend = HardwareInferenceBackend.AUTO

        @Volatile
        var primaryModelChoice: PrimaryLlmModel = PrimaryLlmModel.GEMMA_4_E2B

        @Volatile
        var whisperModelChoice: WhisperModelChoice = WhisperModelChoice.WHISPER_TINY

        @Volatile
        var isVoiceDuplexMode: Boolean = false // Default to Turn-by-Turn mode

        /** Keep the phone responsive while a model runs (fewer threads, lower priority, paced tokens). */
        @Volatile
        var limitCompute: Boolean = false
        var useMtp: Boolean = true
        /** LiteRT constrained decoding for tool calls (Gemma can only write well-formed calls). */
        var strictToolCalls: Boolean = true

        /** The first-launch setup (language, voice, models, permissions, tour) has been finished. */
        @Volatile
        var isOnboardingCompleted: Boolean = false

        /** Only download models over Wi-Fi / unmetered networks. */
        @Volatile
        var downloadOverWifiOnly: Boolean = true

        /** Language picked in the setup: Whisper listens in it and the voice speaks it (code, or "auto"). */
        @Volatile
        var preferredLanguageCode: String = org.fossistant.assistant.model.SupportedLanguage.AUTO_CODE

        fun completeOnboarding(context: Context) {
            isOnboardingCompleted = true
            savePreferences(context)
        }

        fun loadPreferences(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isVoiceTriggerEnabled = prefs.getBoolean("voice_trigger_enabled", true)
            wakeWordPhrase = prefs.getString("wake_word_phrase", "Hey Fossistant") ?: "Hey Fossistant"
            wakeWordSensitivity = prefs.getFloat("wake_word_sensitivity", 0.65f)
            customAiName = prefs.getString("custom_ai_name", "Fossistant") ?: "Fossistant"
            customPersonalityInstructions = prefs.getString("custom_personality_instructions", "") ?: ""
            val themeName = prefs.getString("blob_mood_theme", BlobMoodTheme.COSMIC_NEBULA.name)
            currentMoodTheme = BlobMoodTheme.entries.firstOrNull { it.name == themeName } ?: BlobMoodTheme.COSMIC_NEBULA
            val whisperBackendStr = prefs.getString("whisper_hardware_backend", HardwareInferenceBackend.AUTO.name)
            whisperHardwareBackend = HardwareInferenceBackend.entries.firstOrNull { it.name == whisperBackendStr } ?: HardwareInferenceBackend.AUTO
            val primaryBackendStr = prefs.getString("primary_hardware_backend", HardwareInferenceBackend.AUTO.name)
            primaryHardwareBackend = HardwareInferenceBackend.entries.firstOrNull { it.name == primaryBackendStr } ?: HardwareInferenceBackend.AUTO
            primaryModelChoice = PrimaryLlmModel.fromName(prefs.getString("primary_model_choice", null))
            // Whisper Tiny is the default again (Small took longer to transcribe than Gemma took to answer).
            // Installs that saved Small before Tiny came back move to Tiny once; a later pick of Small sticks.
            val whisperModelStr = prefs.getString("whisper_model_choice", null)
                .takeIf { prefs.getBoolean("whisper_tiny_is_back", false) }
            whisperModelChoice = WhisperModelChoice.entries.firstOrNull { it.name == whisperModelStr } ?: WhisperModelChoice.WHISPER_TINY
            isVoiceDuplexMode = prefs.getBoolean("voice_duplex_mode", false)
            limitCompute = prefs.getBoolean("limit_compute", false)
            useMtp = prefs.getBoolean("use_mtp", true)
            strictToolCalls = prefs.getBoolean("strict_tool_calls", true)
            // Installs from before the setup existed already have a saved model choice: skip the setup for them.
            isOnboardingCompleted = prefs.getBoolean("is_onboarding_completed", prefs.contains("primary_model_choice"))
            downloadOverWifiOnly = prefs.getBoolean("download_wifi_only", true)
            preferredLanguageCode = prefs.getString("preferred_language", null)
                ?: org.fossistant.assistant.model.SupportedLanguage.AUTO_CODE
            homeAssistantUrl = prefs.getString("home_assistant_url", "") ?: ""
            homeAssistantToken = prefs.getString("home_assistant_token", "") ?: ""
        }

        fun savePreferences(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("voice_trigger_enabled", isVoiceTriggerEnabled)
                .putString("wake_word_phrase", wakeWordPhrase)
                .putFloat("wake_word_sensitivity", wakeWordSensitivity)
                .putString("custom_ai_name", customAiName)
                .putString("custom_personality_instructions", customPersonalityInstructions)
                .putString("blob_mood_theme", currentMoodTheme.name)
                .putString("whisper_hardware_backend", whisperHardwareBackend.name)
                .putString("primary_hardware_backend", primaryHardwareBackend.name)
                .putString("primary_model_choice", primaryModelChoice.name)
                .putString("whisper_model_choice", whisperModelChoice.name)
                .putBoolean("whisper_tiny_is_back", true)
                .putString("home_assistant_url", homeAssistantUrl)
                .putString("home_assistant_token", homeAssistantToken)
                .putBoolean("voice_duplex_mode", isVoiceDuplexMode)
                .putBoolean("limit_compute", limitCompute)
                .putBoolean("use_mtp", useMtp)
                .putBoolean("strict_tool_calls", strictToolCalls)
                .putBoolean("is_onboarding_completed", isOnboardingCompleted)
                .putBoolean("download_wifi_only", downloadOverWifiOnly)
                .putString("preferred_language", preferredLanguageCode)
                .apply()
        }
    }
}
