package org.fossistant.assistant

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossistant.assistant.engine.AiMemoryManager
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
 * Application singleton managing the Gemma 4 E2B engine, Multilingual TTS & Whisper STT engines,
 * Persistent On-Device AI Memory System, and RAM trim callbacks.
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

    /** Skills, projects, scheduled tasks, connectors, recordings and Deep Research. */
    lateinit var features: org.fossistant.assistant.features.Features
        private set

    /** On-device image generation (Stable Diffusion 1.5 / LCM); its models download on first use. */
    private val imageGenLazy = lazy { org.fossistant.assistant.engine.ImageGenEngine(this) }
    val imageGenEngine by imageGenLazy

    /** Downloads Gemma (MTP build) and Whisper into private storage when this APK doesn't carry them (slim). */
    val modelDownloads by lazy { org.fossistant.assistant.engine.ModelDownloadManager(this) }

    private var appRescan: kotlinx.coroutines.Job? = null

    @Volatile
    private var initialWarmupFinished: Boolean = false

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
                org.fossistant.assistant.tools.FuzzyAppAndCommandMatcher.invalidateAppCache()
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

        filesDir.resolve("models").mkdirs()
        memoryManager = AiMemoryManager(
            storageDir = filesDir,
            context = applicationContext,
            eagerLoad = false,
            asyncPersist = true
        )
        chatHistoryStore = org.fossistant.assistant.ui.ChatHistoryStore(filesDir, eagerLoad = false)
        ttsEngine = FastMultilingualTtsEngine(context = applicationContext)
        sttEngine = WhisperMultilingualSttEngine(context = applicationContext).apply {
            setHardwareBackend(whisperHardwareBackend)
            audioToGemma = Companion.gemmaVoiceInput
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
            nativeVoiceInput = Companion.gemmaVoiceInput
            aiName = customAiName
            customPersonalityInstructions = this@FossistantApp.let { Companion.customPersonalityInstructions }
        }
        // First launch of an APK without Gemma: start on a model that's actually there.
        if (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("primary_model_choice", null) == null &&
            !gemmaEngine.isModelAvailable(primaryModelChoice)
        ) {
            PrimaryLlmModel.entries.firstOrNull { gemmaEngine.isModelAvailable(it) }?.let { available ->
                primaryModelChoice = available
                gemmaEngine.activeModel = available
            }
        }
        features = org.fossistant.assistant.features.Features(
            applicationContext,
            gemmaEngine,
            toolExecutor,
            eagerLoad = false
        )
        org.fossistant.assistant.features.TaskScheduler.ensureChannels(this)

        // Wire Full Duplex & Real-Time Barge-In pipeline:
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

        // Cancel deferred background model unload (and re-warm if previously trimmed) when any UI resumes.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (::gemmaEngine.isInitialized) {
                    gemmaEngine.cancelDeferredMemoryRelease()
                    if (initialWarmupFinished &&
                        !gemmaEngine.isNativeModelLoaded &&
                        !gemmaEngine.isGeneratingActive &&
                        !gemmaEngine.isLoadingMemory.value
                    ) {
                        appScope.launch(Dispatchers.IO) {
                            gemmaEngine.ensureInitialized()
                        }
                    }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // Load lightweight JSON stores off the Main thread; Gemma awaits this before building its first
        // Conversation so memory facts & MCP connectors are already populated (no first-turn rebuild).
        val storesLoaded = appScope.async(Dispatchers.IO) {
            memoryManager.loadIfNeeded()
            chatHistoryStore.loadIfNeeded()
            features.loadAllStoresAndMigrate()
            org.fossistant.assistant.features.TaskScheduler.scheduleAll(this@FossistantApp)
        }

        // Index installed app shortcuts independently so PackageManager queries never delay startup.
        appScope.launch(Dispatchers.IO) {
            toolExecutor.appActions?.actions()
        }

        // Pre-warm Gemma and Whisper in parallel instead of blocking Gemma behind Whisper loading.
        appScope.launch(Dispatchers.IO) {
            runCatching { storesLoaded.await() }
            gemmaEngine.ensureInitialized()
            initialWarmupFinished = true
            if (BuildConfig.BUNDLES_MODELS) {
                // Unpack every LLM this APK carries once so a later slim update can still use all of them.
                PrimaryLlmModel.entries
                    .filter { org.fossistant.assistant.util.BundledModels.contains(this@FossistantApp, it.filename) }
                    .forEach { gemmaEngine.resolveModelFile(model = it) }
            }
        }
        appScope.launch(Dispatchers.IO) {
            if (!Companion.gemmaVoiceInput) {
                sttEngine.switchModel(whisperModelChoice)
            }
            if (BuildConfig.BUNDLES_MODELS) {
                WhisperModelChoice.entries.forEach { sttEngine.resolveOrExtractModelFile(it) }
            }
        }

        // Start background wake word service if enabled. Android 12+ refuses microphone foreground services from a
        // process starting in the background; MainActivity.onResume starts it once the app is on screen.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            org.fossistant.assistant.service.VoiceWakeWordService.startIfEnabled(this)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (imageGenLazy.isInitialized()) imageGenEngine.release()
            return
        }
        val lowOrBackground =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!lowOrBackground) return

        val immediateGemmaRelease =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE

        org.fossistant.assistant.util.AppLogger.i(
            "FossistantApp",
            "OS memory trim level=$level: ${if (immediateGemmaRelease) "releasing native caches immediately" else "scheduling deferred Gemma release"}"
        )
        if (imageGenLazy.isInitialized()) {
            imageGenEngine.release()
        }
        if (::gemmaEngine.isInitialized && gemmaEngine.isNativeModelLoaded && !gemmaEngine.isGeneratingActive) {
            if (immediateGemmaRelease) {
                appScope.launch(Dispatchers.IO) {
                    gemmaEngine.releaseMemory()
                }
            } else {
                // Brief app switches shouldn't force a cold Gemma reload; release after 2 minutes idle.
                gemmaEngine.scheduleDeferredMemoryRelease(delayMs = 120_000L)
            }
        }
        val criticalMemory =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (criticalMemory &&
            ::sttEngine.isInitialized &&
            !sttEngine.isListening.value &&
            !WhisperMultilingualSttEngine.isActiveSttSession
        ) {
            appScope.launch(Dispatchers.IO) {
                sttEngine.releaseWhisper()
            }
        }
    }

    override fun onTerminate() {
        if (::ttsEngine.isInitialized) ttsEngine.shutdown()
        if (::sttEngine.isInitialized) sttEngine.stopListening()
        if (imageGenLazy.isInitialized()) imageGenEngine.release()
        if (::gemmaEngine.isInitialized) {
            appScope.launch(Dispatchers.IO) {
                gemmaEngine.releaseMemory()
            }
        }
        super.onTerminate()
    }

    internal fun syncRuntimeSettings() {
        if (::gemmaEngine.isInitialized) {
            gemmaEngine.aiName = Companion.customAiName
            gemmaEngine.customPersonalityInstructions = Companion.customPersonalityInstructions
            gemmaEngine.hardwareBackend = Companion.primaryHardwareBackend
            gemmaEngine.limitCompute = Companion.limitCompute
            gemmaEngine.allowSpeculativeDecoding = Companion.useMtp
            gemmaEngine.strictToolCalls = Companion.strictToolCalls
            gemmaEngine.nativeVoiceInput = Companion.gemmaVoiceInput
        }
        if (::sttEngine.isInitialized) {
            sttEngine.audioToGemma = Companion.gemmaVoiceInput
        }
    }

    companion object {
        private const val PREFS_NAME = "fossistant_prefs"

        private val _settingsRevision = MutableStateFlow(0L)
        val settingsRevision: StateFlow<Long> = _settingsRevision.asStateFlow()

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

        @Volatile
        var useMtp: Boolean = true

        /** LiteRT constrained decoding for tool calls (Gemma can only write well-formed calls). */
        @Volatile
        var strictToolCalls: Boolean = true

        /** Experimental: spoken prompts go to Gemma as audio (its own audio encoder) instead of through Whisper. */
        @Volatile
        var gemmaVoiceInput: Boolean = false

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
            gemmaVoiceInput = prefs.getBoolean("gemma_voice_input", false)
            // Installs from before the setup existed already have a saved model choice: skip the setup for them.
            isOnboardingCompleted = prefs.getBoolean("is_onboarding_completed", prefs.contains("primary_model_choice"))
            downloadOverWifiOnly = prefs.getBoolean("download_wifi_only", true)
            preferredLanguageCode = prefs.getString("preferred_language", null)
                ?: org.fossistant.assistant.model.SupportedLanguage.AUTO_CODE
            homeAssistantUrl = prefs.getString("home_assistant_url", "") ?: ""
            homeAssistantToken = org.fossistant.assistant.util.SecretCipher.decrypt(
                prefs.getString("home_assistant_token", "") ?: ""
            )
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
                .putString(
                    "home_assistant_token",
                    org.fossistant.assistant.util.SecretCipher.encrypt(homeAssistantToken)
                )
                .putBoolean("voice_duplex_mode", isVoiceDuplexMode)
                .putBoolean("limit_compute", limitCompute)
                .putBoolean("use_mtp", useMtp)
                .putBoolean("strict_tool_calls", strictToolCalls)
                .putBoolean("gemma_voice_input", gemmaVoiceInput)
                .putBoolean("is_onboarding_completed", isOnboardingCompleted)
                .putBoolean("download_wifi_only", downloadOverWifiOnly)
                .putString("preferred_language", preferredLanguageCode)
                .apply()
            instance?.syncRuntimeSettings()
            _settingsRevision.value = _settingsRevision.value + 1L
        }
    }
}
