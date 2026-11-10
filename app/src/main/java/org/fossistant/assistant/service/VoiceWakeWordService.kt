package org.fossistant.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.engine.WhisperMultilingualSttEngine
import org.fossistant.assistant.ui.overlay.AssistOverlayActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Continuous Background Wake Word Detection Service ("Hey Fossistant", custom wake word, etc.).
 *
 * Key features:
 * - Automatically yields and releases the hardware microphone whenever [WhisperMultilingualSttEngine.isActiveSttSession]
 *   is true so foreground voice transcription / Voice Mode has 100% exclusive access to `AudioRecord`.
 * - Publishes live RMS dB to [WhisperMultilingualSttEngine.latestBackgroundMicRmsDb] for the Wake Word Settings
 *   live microphone level meter.
 * - Verifies spoken hotwords against [FossistantApp.wakeWordPhrase] and [FossistantApp.customAiName] using
 *   configurable [FossistantApp.wakeWordSensitivity] + on-device Whisper verification.
 */
class VoiceWakeWordService : Service() {

    private val isRunning = AtomicBoolean(false)
    private var listeningJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "fossistant_wake_word_channel"
        const val NOTIFICATION_ID = 2001

        fun startIfEnabled(context: Context) {
            if (FossistantApp.isVoiceTriggerEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(context, VoiceWakeWordService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (t: Throwable) {
                    // Android 12+ refuses microphone foreground services started from the background; the
                    // assistant / accessibility / notification services and opening the app retry it.
                    org.fossistant.assistant.util.AppLogger.w("VoiceWakeWordService", "Wake word listening could not start now (${t::class.simpleName}: ${t.message})")
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, VoiceWakeWordService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FossistantApp.isVoiceTriggerEnabled ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        startWakeWordLoop()
        return START_STICKY
    }

    private fun startWakeWordLoop() {
        if (isRunning.getAndSet(true)) return

        listeningJob = serviceScope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuf, 4096)
            val shortBuf = ShortArray(1024)
            val speechAccumulator = PcmAccumulator(sampleRate * 4)
            var speechFrames = 0

            while (isActive && isRunning.get() && FossistantApp.isVoiceTriggerEnabled) {
                // Yield microphone completely while active STT / Voice Mode is recording!
                if (WhisperMultilingualSttEngine.isActiveSttSession) {
                    delay(120)
                    continue
                }

                if (ContextCompat.checkSelfPermission(this@VoiceWakeWordService, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    stopSelf()
                    break
                }

                var record: AudioRecord? = null
                try {
                    record = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                    }.getOrNull()
                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { record?.release() }
                        record = runCatching {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            )
                        }.getOrNull()
                    }
                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        delay(1000)
                        continue
                    }

                    val activeRecord = record
                    activeRecord.startRecording()
                    val app = application as? FossistantApp
                    speechAccumulator.clear()
                    speechFrames = 0

                    while (isActive && isRunning.get() &&
                        FossistantApp.isVoiceTriggerEnabled &&
                        !WhisperMultilingualSttEngine.isActiveSttSession
                    ) {
                        val read = activeRecord.read(shortBuf, 0, shortBuf.size)
                        if (read > 0) {
                            var sumSquares = 0.0
                            for (i in 0 until read) {
                                val s = shortBuf[i].toDouble()
                                sumSquares += s * s
                            }
                            val rms = sqrt(sumSquares / read)
                            val rmsDb = if (rms > 1.0) (20.0 * log10(rms)).toFloat().coerceIn(0f, 90f) else 0f
                            WhisperMultilingualSttEngine.latestBackgroundMicRmsDb = rmsDb

                            // Sensitivity threshold: higher wakeWordSensitivity (0..1) -> lower RMS threshold needed
                            val sensitivity = FossistantApp.wakeWordSensitivity.coerceIn(0.1f, 0.95f)
                            val voiceThresholdRms = 340.0 - (sensitivity * 220.0) // 130..318 RMS
                            val hasVoice = rms >= voiceThresholdRms

                            if (hasVoice) {
                                speechFrames++
                                speechAccumulator.append(shortBuf, read)

                                if (speechAccumulator.size > sampleRate * 3) {
                                    // Keep latest 2.2 seconds
                                    val keep = (sampleRate * 2.2).toInt()
                                    speechAccumulator.keepTail(keep)
                                }
                            } else if (speechFrames >= 8 && speechAccumulator.size >= sampleRate / 2) {
                                // Speech burst ended -> check if it matches the wake word!
                                val candidateSamples = speechAccumulator.toShortArray()
                                speechAccumulator.clear()
                                speechFrames = 0

                                if (matchesWakePhrase(app, candidateSamples, sensitivity)) {
                                    // Release mic BEFORE opening AssistOverlayActivity so the overlay can record!
                                    runCatching {
                                        activeRecord.stop()
                                        activeRecord.release()
                                    }
                                    record = null
                                    triggerWakeWordActivation()
                                    delay(3500)
                                    break
                                }
                            } else {
                                speechAccumulator.clear()
                                speechFrames = 0
                            }
                        }
                    }
                } catch (_: Throwable) {
                    delay(500)
                } finally {
                    runCatching {
                        record?.stop()
                        record?.release()
                    }
                    WhisperMultilingualSttEngine.latestBackgroundMicRmsDb = 0f
                }
            }
        }
    }

    private suspend fun matchesWakePhrase(
        app: FossistantApp?,
        samples: ShortArray,
        sensitivity: Float
    ): Boolean {
        if (!matchesWakeCadence(samples, sensitivity)) return false

        val stt = app?.sttEngine
        if (stt != null) {
            val transcript = stt.transcribePcm16(samples, samples.size).lowercase().trim()
            if (transcript.isNotBlank()) {
                val customPhrase = FossistantApp.wakeWordPhrase.lowercase().trim()
                val aiNameLower = FossistantApp.customAiName.lowercase().trim()
                val targetKeywords = buildList {
                    if (customPhrase.isNotEmpty()) {
                        add(customPhrase)
                        customPhrase.split(" ").filter { it.length >= 4 }.forEach { add(it) }
                    }
                    if (aiNameLower.isNotEmpty()) {
                        add(aiNameLower)
                        add("hey $aiNameLower")
                    }
                    add("fossistant")
                    add("assistant")
                }
                if (targetKeywords.any { transcript.contains(it) }) {
                    return true
                }
                // If sensitivity is high (>= 0.80f) and Whisper heard a short 2-3 word greeting ("hey ..."), allow it
                if (sensitivity >= 0.80f && (transcript.startsWith("hey ") || transcript.startsWith("ok "))) {
                    return true
                }
                return false
            }
        }
        return sensitivity >= 0.75f
    }

    private fun matchesWakeCadence(samples: ShortArray, sensitivity: Float): Boolean {
        if (samples.size < (16000 * 0.45).toInt()) return false
        val windowSize = 1600 // 100ms
        var burstCount = 0
        var inBurst = false
        val burstRms = 280.0 - (sensitivity * 140.0)
        var i = 0
        while (i + windowSize <= samples.size) {
            var sumSquares = 0.0
            for (j in 0 until windowSize step 4) {
                val s = samples[i + j].toDouble()
                sumSquares += s * s
            }
            val rms = sqrt(sumSquares / (windowSize / 4))
            if (rms > burstRms) {
                if (!inBurst) {
                    burstCount++
                    inBurst = true
                }
            } else if (rms < burstRms * 0.65) {
                inBurst = false
            }
            i += windowSize
        }
        return burstCount in 2..6
    }

    private fun triggerWakeWordActivation() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}

        val intent = Intent(this, AssistOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("AUTO_START_VOICE", true)
        }
        startActivity(intent)
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, AssistOverlayActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val phrase = FossistantApp.wakeWordPhrase.ifBlank { "Hey ${FossistantApp.customAiName}" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${FossistantApp.customAiName} Voice Trigger")
            .setContentText("Listening for \"$phrase\"...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Trigger Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background hotword detection for Fossistant Assistant"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        listeningJob?.cancel()
        listeningJob = null
        serviceScope.cancel()
        WhisperMultilingualSttEngine.latestBackgroundMicRmsDb = 0f
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Primitive 16-bit PCM buffer for wake-word bursts (avoids boxing every sample into a `List<Short>`).
 */
private class PcmAccumulator(capacity: Int) {
    private var data = ShortArray(capacity)
    var size: Int = 0
        private set

    fun append(src: ShortArray, count: Int) {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        System.arraycopy(src, 0, data, size, count)
        size += count
    }

    fun keepTail(keep: Int) {
        if (keep >= size) return
        System.arraycopy(data, size - keep, data, 0, keep)
        size = keep
    }

    fun clear() {
        size = 0
    }

    fun toShortArray(): ShortArray = data.copyOf(size)
}
