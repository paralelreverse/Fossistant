package org.fossistant.assistant.features

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.R
import org.fossistant.assistant.model.MediaAttachment
import org.fossistant.assistant.model.MediaModality
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.service.FossistantAgentAccessibilityService
import org.fossistant.assistant.ui.components.BlobActivityState
import java.io.File

/** Listens once with Whisper and returns the transcript ("" on silence or error). */
internal suspend fun listenOnce(scope: CoroutineScope, language: SupportedLanguage): String {
    val app = FossistantApp.instance ?: return ""
    val result = CompletableDeferred<String>()
    app.sttEngine.startListening(
        scope = scope,
        language = language,
        continuous = false,
        onError = { if (!result.isCompleted) result.complete("") },
        onFinalResult = { text -> if (!result.isCompleted) result.complete(text) }
    )
    return try {
        result.await()
    } finally {
        app.sttEngine.stopListening()
    }
}

/** Waits until the assistant has finished speaking (and gives speech up to 2 s to start). */
internal suspend fun awaitSpeechDone() {
    val tts = FossistantApp.instance?.ttsEngine ?: return
    withTimeoutOrNull(2_000) { tts.isSpeaking.first { it } }
    withTimeoutOrNull(120_000) { tts.isSpeaking.first { !it } }
    delay(250)
}

/**
 * Interpreter mode: a two-way spoken conversation between two languages. Whisper detects which language was
 * spoken, the local model translates, and the reply is spoken with the other language's voice.
 */
class InterpreterSession(private val scope: CoroutineScope) {

    data class Line(val original: String, val translation: String, val fromLanguage: String, val toLanguage: String)

    data class State(
        val running: Boolean = false,
        val status: String = "",
        val lines: List<Line> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun start(first: SupportedLanguage, second: SupportedLanguage) {
        if (job?.isActive == true) return
        val app = FossistantApp.instance ?: return
        job = scope.launch {
            _state.update { it.copy(running = true, status = "Listening…") }
            while (isActive) {
                _state.update { it.copy(status = "Listening… speak ${first.displayName} or ${second.displayName}") }
                val heard = listenOnce(this, SupportedLanguage.AUTO).trim()
                if (heard.isEmpty()) continue
                val detected = app.sttEngine.lastDetectedLanguage.value
                val (from, to) = directionFor(detected, first, second)
                _state.update { it.copy(status = "Translating into ${to.displayName}…") }
                val translation = app.gemmaEngine.completeOnce(
                    instructions = "You are a live interpreter. Translate the user's words from ${from.englishName} into " +
                        "${to.englishName}. Keep the meaning, tone and names. Reply with the translation only.",
                    input = heard,
                    maxTokens = 400,
                    timeoutMs = 60_000
                )
                if (translation.isNullOrBlank()) {
                    _state.update { it.copy(status = "Couldn't translate: ${app.gemmaEngine.lastEngineError ?: "model unavailable"}") }
                    delay(1500)
                    continue
                }
                _state.update { it.copy(lines = it.lines + Line(heard, translation, from.displayName, to.displayName), status = "Speaking ${to.displayName}…") }
                app.ttsEngine.speakFast(translation, flushQueue = true, languageTag = to.code)
                awaitSpeechDone()
            }
        }.also { it.invokeOnCompletion { _state.update { s -> s.copy(running = false, status = "") } } }
    }

    fun stop() {
        job?.cancel()
        job = null
        FossistantApp.instance?.let {
            it.sttEngine.stopListening()
            it.ttsEngine.stop()
        }
    }

    companion object {
        /** Speech in [second] goes into [first]; anything else (including unknown) goes into [second]. */
        internal fun directionFor(detected: String?, first: SupportedLanguage, second: SupportedLanguage): Pair<SupportedLanguage, SupportedLanguage> {
            val code = detected?.lowercase()
            return if (code != null && (code == second.whisperCode || SupportedLanguage.fromCode(code) == second)) {
                second to first
            } else {
                first to second
            }
        }
    }
}

/**
 * Live screen sharing (Gemini Live style): the popup shrinks to the floating blob, you keep using the phone and
 * talk; every question is sent with a fresh screenshot of what's on screen. Needs the accessibility service
 * (for screenshots and the blob) and a vision model.
 */
class LiveScreenShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startInForeground()
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 2, Intent(this, LiveScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_assistant)
            .setContentTitle("Sharing your screen with Fossistant")
            .setContentText("Talk about what's on screen. Tap the bubble or Stop to end.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun loop() {
        val app = FossistantApp.instance ?: return shutdown()
        val accessibility = FossistantAgentAccessibilityService.activeInstance ?: return shutdown()
        _active.value = true
        accessibility.showFloatingAgentBlob { stop(this) }
        app.gemmaEngine.isVoiceMode = true
        app.ttsEngine.speakFast("I'm looking at your screen. Ask me anything about it.", flushQueue = true)
        awaitSpeechDone()
        while (scope.isActive) {
            accessibility.updateBlobState(BlobActivityState.USER_TALKING)
            val question = listenOnce(scope, FossistantApp.screenShareLanguage).trim()
            if (question.isEmpty()) continue
            accessibility.updateBlobState(BlobActivityState.AI_REASONING)
            val screenshot = accessibility.captureScreenshotJpeg()
            val attachments = screenshot?.let { bytes ->
                val file = File(cacheDir, "screen_share_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
                cacheDir.listFiles { f -> f.name.startsWith("screen_share_") && f != file }?.forEach { it.delete() }
                listOf(
                    MediaAttachment(
                        id = "screen_share",
                        uriString = file.absolutePath,
                        fileName = "Current screen",
                        modality = MediaModality.IMAGE,
                        mimeType = "image/jpeg",
                        sizeBytes = bytes.size.toLong()
                    )
                )
            }.orEmpty()
            var answer = ""
            app.router.processUserTurn(question, attachments).collect { message ->
                if (!message.isReasoningPhase) answer = message.text
            }
            if (answer.isNotBlank()) {
                accessibility.updateBlobState(BlobActivityState.AI_TALKING)
                app.ttsEngine.speakFast(answer, flushQueue = true)
                awaitSpeechDone()
            }
        }
    }

    private fun shutdown() {
        job?.cancel()
        job = null
        FossistantApp.instance?.let {
            it.sttEngine.stopListening()
            it.ttsEngine.stop()
            it.gemmaEngine.cancelCurrentGeneration()
            it.gemmaEngine.isVoiceMode = false
        }
        FossistantAgentAccessibilityService.hideFloatingBlob()
        _active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        FossistantApp.instance?.gemmaEngine?.isVoiceMode = false
        _active.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "screen_share"
        private const val NOTIFICATION_ID = 4109
        private const val ACTION_STOP = "org.fossistant.assistant.STOP_SCREEN_SHARE"

        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LiveScreenShareService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
