package org.fossistant.assistant.engine

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fossistant.assistant.model.HardwareInferenceBackend
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.tools.FuzzyAppAndCommandMatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * On-Device Whisper Multilingual (`whisper-tiny.bin` or `whisper-small-q5_1.bin` via `libwhisper_jni.so`) & AudioRecord Speech-to-Text Engine.
 *
 * Key fixes & architecture:
 * 1. Exclusive Microphone Coordination: Signals [isActiveSttSession] so [org.fossistant.assistant.service.VoiceWakeWordService]
 *    releases its background AudioRecord immediately, preventing Android microphone contention.
 * 2. Real-time Audio-Synced Amplitude: Computes normalized 60fps RMS dB and [normalizedAudioLevel] `[0.0f, 1.0f]`
 *    from raw 16kHz PCM samples to drive the [org.fossistant.assistant.ui.components.CosmicAiBlob] animation.
 * 3. Native `whisper.cpp` JNI Decoder (`libwhisper_jni.so`): Decodes captured 16kHz mono PCM directly against
 *    the bundled Whisper multilingual checkpoints across all 7 languages.
 * 4. Toggleable Mic & Silence Timeouts: Tapping the mic while listening immediately turns the mic off, and
 *    automatic no-speech / trailing-silence timeouts prevent hanging.
 */
class WhisperMultilingualSttEngine(
    private val context: Context?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var systemRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _livePartial = MutableStateFlow("")
    val livePartial: StateFlow<String> = _livePartial.asStateFlow()

    private val _currentRmsDb = MutableStateFlow(0f)
    val currentRmsDb: StateFlow<Float> = _currentRmsDb.asStateFlow()

    private val _normalizedAudioLevel = MutableStateFlow(0f)
    val normalizedAudioLevel: StateFlow<Float> = _normalizedAudioLevel.asStateFlow()

    /** Whisper code of the language heard in the last transcript (e.g. "pl"), detected in automatic mode. */
    private val _lastDetectedLanguage = MutableStateFlow<String?>(null)
    val lastDetectedLanguage: StateFlow<String?> = _lastDetectedLanguage.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    var activeModel: WhisperModelChoice = WhisperModelChoice.WHISPER_TINY
        private set

    var onUserInterruption: (() -> Unit)? = null
    var onTentativeBargeIn: (() -> Unit)? = null
    var onBargeInCleared: (() -> Unit)? = null
    var isSelfEchoChecker: ((String) -> Boolean)? = null
    var isTtsSpeakingProvider: (() -> Boolean)? = null

    /** True while the assistant is writing or speaking an answer: only sustained speech may cut in then. */
    var isAssistantBusyProvider: (() -> Boolean)? = null
    /** True while TTS audio plays plus a short echo tail afterwards (see FastMultilingualTtsEngine.isEchoGuardActive). */
    var isEchoGuardActiveProvider: (() -> Boolean)? = null
    var ttsAudioLevelProvider: (() -> Float)? = null
    /** Notified when duplex speakerphone routing (MODE_IN_COMMUNICATION) is switched on or off. */
    var onCommunicationModeChanged: ((Boolean) -> Unit)? = null

    /** Incremented by every start/stop; a session only touches shared state while its id is current. */
    private val sessionCounter = AtomicLong(0L)
    private val communicationRoutingActive = AtomicBoolean(false)

    val playbackReferenceBuffer = PlaybackReferenceBuffer()

    fun onTtsPlaybackAudioChunk(pcmChunk: ShortArray) {
        playbackReferenceBuffer.write(pcmChunk)
    }

    @Volatile
    var hardwareBackend: HardwareInferenceBackend = HardwareInferenceBackend.AUTO
        private set

    /**
     * The bundled `libwhisper_jni.so` decodes on the CPU (4 threads); the hardware-backend preference is
     * stored but cannot switch whisper.cpp to a GPU/NPU at runtime, so status reports what actually runs.
     */
    val computeKind: String
        get() = "CPU"

    private val _memoryStatus = MutableStateFlow(if (sharedWhisperHandle != 0L) formatMemoryStatus() else "Loading...")
    val memoryStatus: StateFlow<String> = _memoryStatus.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    fun setHardwareBackend(backend: HardwareInferenceBackend) {
        hardwareBackend = backend
        updateMemoryStatus()
    }

    fun formatMemoryStatus(): String {
        val accelBadge = computeKind
        val latency = _lastLatencyMs.value
        val modelBadge = activeModel.ramBadge
        return if (latency > 0L) {
            "$accelBadge ($modelBadge • ${latency}ms)"
        } else {
            "$accelBadge ($modelBadge)"
        }
    }

    fun updateMemoryStatus() {
        if (sharedWhisperHandle != 0L && isJniLibraryLoaded) {
            _memoryStatus.value = formatMemoryStatus()
        }
    }

    private external fun nativeInitWhisper(modelPath: String): Long
    private external fun nativeTranscribePcm16(
        handle: Long,
        pcmSamples: ShortArray,
        sampleCount: Int,
        langCode: String
    ): String
    private external fun nativeFreeWhisper(handle: Long)

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val INITIAL_SILENCE_MAX_MS = 7000L // 7s max wait if no speech is detected
        private const val FRAME_SAMPLES = 1024            // 64ms per frame at 16kHz
        private const val SPECULATIVE_DECODE_AFTER_MS = 300L // start decoding this long into a pause
        private const val HARD_BARGE_IN_VOICED_MS = 1500L // sustained speech over TTS = real interruption (a long "hmmm" isn't)
        private const val MIN_UTTERANCE_VOICED_MS = 128L  // shorter bursts are clicks/coughs, not speech
        /** Quietest frame that can count as speech (filtered RMS, dB of 16-bit samples). */
        internal const val SPEECH_MIN_DB = 42.0f
        /**
         * How far an utterance's loudest frame must rise above the room's background noise. Someone speaking into the
         * phone stands far above it; a voice across the room barely does. Relative, so it works with any microphone
         * level (fixed dB limits rejected the Pixel's normal speech at 39-52 dB).
         */
        internal const val NEAR_FIELD_SNR_DB = 18.0f
        /** Voiced speech needed to interrupt an answer being written or spoken (shorter = hesitation, aside). */
        internal const val BUSY_MIN_VOICED_MS = 1000L

        /** Whole-transcript phrases Whisper hallucinates on silence, noise, or music. */
        private val WHISPER_HALLUCINATIONS = setOf(
            "you", "thank you", "thanks", "thank you for watching", "thanks for watching",
            "thank you so much for watching", "please subscribe", "subscribe", "bye", "bye bye",
            "so", "okay okay", "the end", "music", "applause", "silence", "1", "5",
            "napisy stworzone przez społeczność amara.org", "untertitel der amara.org-community",
            "sous-titres réalisés para la communauté d'amara.org", "subtítulos realizados por la comunidad de amara.org",
            "ご視聴ありがとうございました"
        )

        fun isWhisperHallucination(text: String): Boolean {
            val normalized = text.trim().lowercase().trimEnd('.', '!', '?', '…', ' ').trim()
            if (normalized.isEmpty()) return true
            if (normalized in WHISPER_HALLUCINATIONS) return true
            // Repeated single token ("you you you you") is a classic silence hallucination.
            val words = normalized.split(Regex("\\s+"))
            return words.size >= 3 && words.toSet().size == 1
        }
        const val WHISPER_MODEL_FILENAME = "whisper-small-q5_1.bin"

        @Volatile
        var isActiveSttSession: Boolean = false

        @Volatile
        var latestBackgroundMicRmsDb: Float = 0f

        private val whisperMutex = Mutex()

        @Volatile
        private var sharedWhisperHandle: Long = 0L

        @Volatile
        private var isJniLibraryLoaded: Boolean = false

        fun isHeadsetConnected(context: Context?): Boolean {
            val appContext = context ?: return false
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
            }
        }

        /** Hesitation sounds only ("um", "hmm", "uh huh"): never a prompt and never an interruption. */
        private val FILLER_WORD = Regex("^(u+h+|u+m+|h+m+|m+h*m*|a+h+|e+r+m*|e+h+|o+h+|h+u+h+|mhm|uhhuh|mmhmm|ähm|äh|hmpf)$")

        fun isFillerUtterance(text: String): Boolean {
            val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.all { FILLER_WORD.matches(it) }
        }

        fun isBackchannelUtterance(text: String): Boolean {
            val clean = text.trim().lowercase().replace(Regex("[^\\p{L}]"), "")
            val backchannels = setOf(
                "yeah", "yep", "yes", "okay", "ok", "uhhuh", "mhm", "mmhmm", "right", "sure", "gotit", "cool",
                "si", "oui", "ja", "daccord", "genau", "vabene", "tak", "dobrze"
            )
            return backchannels.contains(clean)
        }

        fun computeDynamicSilenceTimeout(partialText: String): Long {
            val trimmed = partialText.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("Hearing speech", ignoreCase = true) ||
                trimmed.startsWith("Listening", ignoreCase = true) ||
                trimmed.startsWith("Transcribing", ignoreCase = true)
            ) {
                return 1100L // words not decoded yet
            }
            val lastChar = trimmed.last()
            val isSentenceClosure = lastChar in listOf('.', '?', '!', '。', '？', '！')
            val words = trimmed.lowercase().split(Regex("\\s+"))
            val lastWord = words.lastOrNull().orEmpty()
            val isHesitation = lastWord in listOf(
                "and", "to", "the", "a", "an", "or", "but", "with", "for", "in", "on", "at", "by", "that", "which",
                "so", "because", "like", "if", "when", "as", "about", "uh", "um", "er",
                "y", "para", "con", "de", "que", "et", "pour", "avec", "dans", "und", "zu", "mit", "e", "per", "con", "i", "do", "z"
            )
            return when {
                isSentenceClosure -> 700L  // complete sentence (from the early Whisper decode)
                isHesitation -> 1600L      // pausing after a conjunction/hesitation
                else -> 950L               // default conversational pause
            }
        }
    }

    /**
     * Resolves or extracts the bundled Whisper model file for the given [choice].
     */
    fun resolveOrExtractModelFile(choice: WhisperModelChoice = activeModel): File? {
        val appContext = context
        val targetFilename = choice.filename
        val targetDir = appContext?.filesDir?.resolve("models")?.apply { mkdirs() }
        val targetFile = targetDir?.resolve(targetFilename)

        if (targetFile != null && targetFile.exists() && targetFile.length() > choice.minBytes) {
            return targetFile
        }

        if (appContext != null && targetFile != null) {
            val tempFile = File(targetDir, "$targetFilename.tmp")
            try {
                _memoryStatus.value = "Unpacking (${choice.ramBadge})..."
                appContext.assets.open("models/$targetFilename").use { input ->
                    tempFile.outputStream().buffered(256 * 1024).use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }
                if (tempFile.length() > choice.minBytes) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                    return targetFile
                }
            } catch (t: Throwable) {
                _lastError.value = "Failed extracting $targetFilename: ${t.message}"
                runCatching { tempFile.delete() }
            }
        }

        val localCandidates = listOf(
            File("models/$targetFilename"),
            File("../models/$targetFilename"),
            File("app/src/voice/assets/models/$targetFilename"),
            File("src/voice/assets/models/$targetFilename")
        )
        return localCandidates.firstOrNull { it.exists() && it.length() > choice.minBytes }
    }

    /**
     * Switches the active Whisper model on the fly (Tiny <-> Base <-> Small Q5_1).
     */
    suspend fun switchModel(choice: WhisperModelChoice): Boolean = withContext(ioDispatcher) {
        whisperMutex.withLock {
            if (activeModel == choice && sharedWhisperHandle != 0L) {
                return@withLock true
            }
            if (sharedWhisperHandle != 0L) {
                runCatching { nativeFreeWhisper(sharedWhisperHandle) }
                sharedWhisperHandle = 0L
            }
            activeModel = choice
            _memoryStatus.value = "Loading (${choice.ramBadge})..."
            val modelFile = resolveOrExtractModelFile(choice)
            if (modelFile == null || !modelFile.exists()) {
                _lastError.value = missingModelMessage(choice)
                _memoryStatus.value = "Missing (${choice.ramBadge})"
                return@withLock false
            }
            return@withLock try {
                if (!isJniLibraryLoaded) {
                    runCatching { System.loadLibrary("c++_shared") }
                    System.loadLibrary("whisper_jni")
                    isJniLibraryLoaded = true
                }
                val handle = nativeInitWhisper(modelFile.absolutePath)
                if (handle != 0L) {
                    sharedWhisperHandle = handle
                    _lastError.value = null
                    _memoryStatus.value = formatMemoryStatus()
                    true
                } else {
                    _lastError.value = "Failed to load ${choice.displayName}."
                    _memoryStatus.value = formatMemoryStatus()
                    false
                }
            } catch (t: Throwable) {
                _lastError.value = "Whisper error: ${t.message}"
                _memoryStatus.value = formatMemoryStatus()
                false
            }
        }
    }

    /**
     * Ensures the native `libwhisper_jni.so` context is initialized with the active Whisper model.
     */
    suspend fun ensureNativeWhisperInitialized(): Boolean = withContext(ioDispatcher) {
        whisperMutex.withLock {
            if (sharedWhisperHandle != 0L && isJniLibraryLoaded) {
                _memoryStatus.value = formatMemoryStatus()
                return@withLock true
            }
            _memoryStatus.value = "Loading (${activeModel.ramBadge})..."
            val modelFile = resolveOrExtractModelFile(activeModel)
            if (modelFile == null || !modelFile.exists()) {
                _lastError.value = missingModelMessage(activeModel)
                _memoryStatus.value = "Missing (${activeModel.ramBadge})"
                return@withLock false
            }
            return@withLock try {
                if (!isJniLibraryLoaded) {
                    runCatching { System.loadLibrary("c++_shared") }
                    System.loadLibrary("whisper_jni")
                    isJniLibraryLoaded = true
                }
                val handle = nativeInitWhisper(modelFile.absolutePath)
                if (handle != 0L) {
                    sharedWhisperHandle = handle
                    _lastError.value = null
                    _memoryStatus.value = formatMemoryStatus()
                    true
                } else {
                    _lastError.value = "nativeInitWhisper returned 0 for ${modelFile.absolutePath}"
                    _memoryStatus.value = "Fallback"
                    false
                }
            } catch (t: Throwable) {
                if (context != null) {
                    _lastError.value = "Whisper JNI load notice: ${t.message}"
                }
                _memoryStatus.value = "Fallback"
                false
            }
        }
    }

    private fun missingModelMessage(choice: WhisperModelChoice): String =
        if (org.fossistant.assistant.BuildConfig.BUNDLES_MODELS) {
            "Whisper model (${choice.filename}) not found."
        } else {
            "${choice.displayName} isn't on this device. Install the full APK once; later slim updates reuse its models."
        }

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Decodes a 16kHz mono 16-bit PCM buffer into text using the active Whisper model.
     */
    suspend fun transcribePcm16(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        language: SupportedLanguage = SupportedLanguage.ENGLISH
    ): String = withContext(ioDispatcher) {
        if (sampleCount <= 0) return@withContext ""
        val ready = ensureNativeWhisperInitialized()
        if (!ready || sharedWhisperHandle == 0L) return@withContext ""
        whisperMutex.withLock {
            try {
                val startMs = System.currentTimeMillis()
                val rawLang = language.whisperCode
                val rawResult = nativeTranscribePcm16(sharedWhisperHandle, samples, sampleCount, rawLang)
                // Native result: "<detected language>\t<transcript>" (older builds return only the text).
                val tab = rawResult.indexOf('\t')
                val rawText = if (tab >= 0) rawResult.substring(tab + 1) else rawResult
                if (tab > 0) _lastDetectedLanguage.value = rawResult.substring(0, tab)
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                _lastLatencyMs.value = latency
                updateMemoryStatus()
                cleanWhisperArtifacts(rawText)
            } catch (t: Throwable) {
                _lastError.value = "Whisper decode error: ${t.message}"
                ""
            }
        }
    }

    internal fun cleanWhisperArtifacts(raw: String): String {
        val cleaned = raw
            .replace(Regex("\\[.*?\\]"), "") // Strip [BLANK_AUDIO], [Music], [Silence]
            .replace(Regex("\\(.*?\\)"), "") // Strip (inaudible), (sighs)
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapseRepeatedPhrase(cleaned)
    }

    /**
     * Whisper sometimes loops on short clips and emits the same phrase several times
     * ("And so my fellow Americans! And so my fellow Americans!"); keep a single copy.
     */
    internal fun collapseRepeatedPhrase(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return text
        val normalized = words.map { it.lowercase().trim { c -> !c.isLetterOrDigit() } }
        for (period in 2..words.size / 2) {
            if (words.size % period != 0) continue
            val repeats = (period until words.size step period).all { start ->
                (0 until period).all { i -> normalized[start + i] == normalized[i] }
            }
            if (repeats) return words.take(period).joinToString(" ")
        }
        return text
    }

    /**
     * Computes normalized audio amplitude `[0.0f, 1.0f]` and RMS dB `[0.0f, 90.0f]` from a 16-bit PCM frame.
     */
    fun computePcmLevels(pcmBuffer: ShortArray, readCount: Int): Pair<Float, Float> {
        if (readCount <= 0) return 0f to 0f
        var sumSquares = 0.0
        for (i in 0 until readCount) {
            val s = pcmBuffer[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / readCount)
        val rmsDb = if (rms > 1.0) (20.0 * log10(rms)).toFloat().coerceIn(0f, 90f) else 0f
        // Map human speech RMS (approx 30 dB quiet to 78 dB loud) into [0.0f, 1.0f]
        val normalized = ((rmsDb - 26f) / 48f).coerceIn(0f, 1f)
        return rmsDb to normalized
    }

    /**
     * Circular ring buffer storing recent 16kHz mono PCM reference samples generated by TTS.
     * Capacity 32,768 shorts (~2.048s) provides ample reference history for Android acoustic delay estimation.
     */
    class PlaybackReferenceBuffer(private val capacity: Int = 32768) {
        private val buffer = ShortArray(capacity)
        private var writeHead = 0
        private var totalWritten = 0L

        @Synchronized
        fun write(samples: ShortArray, count: Int = samples.size) {
            for (i in 0 until count) {
                buffer[writeHead] = samples[i]
                writeHead = (writeHead + 1) % capacity
            }
            totalWritten += count
        }

        @Synchronized
        fun copyRecent(destination: ShortArray, maxSamples: Int = destination.size): Int {
            val count = minOf(maxSamples, capacity, totalWritten.toInt())
            val readStart = (writeHead - count + capacity) % capacity
            for (i in 0 until count) {
                destination[i] = buffer[(readStart + i) % capacity]
            }
            return count
        }

        @Synchronized
        fun clear() {
            buffer.fill(0)
            writeHead = 0
            totalWritten = 0L
        }
    }

    /**
     * Software echo *detector* (normalized cross-correlation against the TTS playback reference).
     *
     * The microphone signal is NOT modified: the `VOICE_COMMUNICATION` source already runs the platform's
     * hardware AEC, and subtracting a single-delay/single-gain estimate on top of it only distorts the
     * user's voice before Whisper. This class just classifies frames as echo-dominant or double-talk so the
     * barge-in logic can ignore residual speaker echo. The reference arrives from
     * [FastMultilingualTtsEngine] at playback time, resampled to 16 kHz.
     */
    class SoftwareAcousticEchoCanceler(
        private val refBuffer: PlaybackReferenceBuffer
    ) {
        private val recentRefCopy = ShortArray(REF_WINDOW_SAMPLES)
        private var smoothedDelaySamples = 3200 // ~200ms: AudioTrack buffer + HAL output/input latency
        private var smoothedCouplingFactor = 0.5f

        data class AecResult(
            val cleanPcm: ShortArray,
            val isSpeakerEchoDominant: Boolean,
            val isDoubleTalk: Boolean,
            val echoReductionDb: Float
        )

        fun processFrame(
            micPcm: ShortArray,
            length: Int,
            isTtsPlaying: Boolean
        ): AecResult {
            if (!isTtsPlaying || length <= 0) {
                return AecResult(micPcm, isSpeakerEchoDominant = false, isDoubleTalk = false, echoReductionDb = 0f)
            }

            val refCount = refBuffer.copyRecent(recentRefCopy, REF_WINDOW_SAMPLES)
            if (refCount < length + MIN_LAG) {
                return AecResult(micPcm, false, false, 0f)
            }

            var micEnergy = 0.0
            for (i in 0 until length) {
                val m = micPcm[i].toDouble()
                micEnergy += m * m
            }
            if (micEnergy < 1000.0) {
                return AecResult(micPcm, false, false, 0f)
            }

            val maxLag = minOf(MAX_LAG, refCount - length)
            val searchStart = maxOf(MIN_LAG, smoothedDelaySamples - 1600)
            val searchEnd = minOf(maxLag, smoothedDelaySamples + 1600)

            var bestLag = smoothedDelaySamples.coerceIn(MIN_LAG, maxLag)
            var maxCorr = 0.0

            fun correlationAt(lag: Int, step: Int): Double {
                var dot = 0.0
                var refE = 0.0
                var micE = 0.0
                var i = 0
                while (i < length) {
                    val r = recentRefCopy[refCount - 1 - lag - (length - 1 - i)].toDouble()
                    val m = micPcm[i].toDouble()
                    dot += m * r
                    refE += r * r
                    micE += m * m
                    i += step
                }
                return if (refE > 100.0) dot / (sqrt(micE * refE) + 1.0) else 0.0
            }

            var lag = searchStart
            while (lag < searchEnd) {
                val c = correlationAt(lag, 4)
                if (c > maxCorr) {
                    maxCorr = c
                    bestLag = lag
                }
                lag += 32
            }
            for (fine in maxOf(MIN_LAG, bestLag - 32)..minOf(maxLag, bestLag + 32)) {
                val c = correlationAt(fine, 1)
                if (c > maxCorr) {
                    maxCorr = c
                    bestLag = fine
                }
            }

            if (maxCorr > 0.28) {
                smoothedDelaySamples = (0.85 * smoothedDelaySamples + 0.15 * bestLag).toInt()
            }
            if (maxCorr <= 0.26) {
                return AecResult(micPcm, false, false, 0f)
            }

            val delay = smoothedDelaySamples.coerceIn(MIN_LAG, maxLag)
            var dotProduct = 0.0
            var refLagEnergy = 0.0
            for (i in 0 until length) {
                val r = recentRefCopy[refCount - 1 - delay - (length - 1 - i)].toDouble()
                dotProduct += micPcm[i] * r
                refLagEnergy += r * r
            }
            val coupling = if (refLagEnergy > 100.0) {
                (dotProduct / refLagEnergy).toFloat().coerceIn(0.0f, 1.3f)
            } else 0f
            smoothedCouplingFactor = 0.8f * smoothedCouplingFactor + 0.2f * coupling

            // Residual after a hypothetical subtraction, used only to estimate how much of the frame is echo.
            var residualEnergy = 0.0
            for (i in 0 until length) {
                val r = recentRefCopy[refCount - 1 - delay - (length - 1 - i)].toDouble()
                val sub = micPcm[i] - smoothedCouplingFactor * r
                residualEnergy += sub * sub
            }

            val erleDb = if (residualEnergy > 1.0) {
                (10.0 * log10(micEnergy / residualEnergy)).toFloat().coerceIn(0f, 40f)
            } else 0f
            val echoEnergyFraction = (smoothedCouplingFactor * smoothedCouplingFactor * refLagEnergy) / (micEnergy + 1.0)
            val isEchoDominant = echoEnergyFraction > 0.55 && maxCorr > 0.32
            val isDoubleTalk = maxCorr > 0.20 && (residualEnergy / (micEnergy + 1.0) > 0.35)

            return AecResult(
                cleanPcm = micPcm,
                isSpeakerEchoDominant = isEchoDominant,
                isDoubleTalk = isDoubleTalk,
                echoReductionDb = erleDb
            )
        }

        private companion object {
            const val REF_WINDOW_SAMPLES = 9600 // 600ms of 16 kHz reference history
            const val MIN_LAG = 320             // 20ms
            const val MAX_LAG = 8000            // 500ms
        }
    }

    /**
     * Deep Vocal Activity Detector:
     * 1. 2nd-order Butterworth High-Pass Filter (fc = 160Hz) removes engine rumble & wind noise.
     * 2. Aerodynamic Breath Puff / Microphone Blow Rejection.
     * 3. Pitch Periodicity via Lag Autocorrelation (detects human vocal tract vibrations in 80Hz - 280Hz).
     * 4. Adaptive Ambient Noise Floor Tracking with multi-frame continuity hysteresis.
     */
    class DeepVocalActivityDetector {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        var ambientNoiseFloorDb: Float = 32.0f
            private set

        private var consecutiveSpeechFrames = 0

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
            ambientNoiseFloorDb = 32.0f
            consecutiveSpeechFrames = 0
        }

        fun processFrame(pcm: ShortArray, length: Int): VadResult {
            if (length <= 0) return VadResult(isVoice = false, rmsDb = 0f, filteredRmsDb = 0f, zcr = 0, pitchPeriodicity = 0f)

            var sumRawSquares = 0.0
            var sumFilteredSquares = 0.0
            var zeroCrossings = 0
            var prevFiltered = 0.0
            var peakAbs = 0

            val filtered = DoubleArray(length)

            for (i in 0 until length) {
                val sample = pcm[i].toInt()
                val absVal = kotlin.math.abs(sample)
                if (absVal > peakAbs) peakAbs = absVal

                val x0 = sample.toDouble()
                sumRawSquares += x0 * x0

                // 2nd-order Butterworth high-pass filter (fc=160Hz, fs=16kHz)
                val y0 = 0.956543 * x0 - 1.913086 * x1 + 0.956543 * x2 - (-1.911197 * y1 + 0.914976 * y2)
                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0
                filtered[i] = y0

                sumFilteredSquares += y0 * y0

                if (i > 0 && ((y0 >= 0.0 && prevFiltered < 0.0) || (y0 < 0.0 && prevFiltered >= 0.0))) {
                    zeroCrossings++
                }
                prevFiltered = y0
            }

            val rawRms = sqrt(sumRawSquares / length)
            val rawRmsDb = if (rawRms > 1.0) (20.0 * log10(rawRms)).toFloat().coerceIn(0f, 90f) else 0f

            val filteredRms = sqrt(sumFilteredSquares / length)
            val filteredRmsDb = if (filteredRms > 1.0) (20.0 * log10(filteredRms)).toFloat().coerceIn(0f, 90f) else 0f

            // 1. Aerodynamic Breath Puff / Microphone Blow Rejection:
            val isBreathPuff = (rawRmsDb - filteredRmsDb > 11.5f) ||
                (peakAbs > 24000 && zeroCrossings < 32) ||
                (filteredRmsDb > 55f && zeroCrossings < 20)

            // 2. Pitch Periodicity via Lag Autocorrelation (80Hz - 280Hz, lags 57..200 at 16kHz)
            var maxAutoCorr = 0.0
            val maxLag = minOf(200, length / 2)
            val minLag = 57
            val r0 = sumFilteredSquares.coerceAtLeast(1.0)

            for (lag in minLag..maxLag step 2) {
                var dot = 0.0
                for (i in 0 until (length - lag) step 2) {
                    dot += filtered[i] * filtered[i + lag]
                }
                val normCorr = (dot * 2.0) / r0
                if (normCorr > maxAutoCorr) {
                    maxAutoCorr = normCorr
                }
            }
            val pitchPeriodicity = maxAutoCorr.toFloat().coerceIn(0f, 1f)

            // 3. Near-Field Speech Discrimination:
            // Speech has to stand clearly above the room's noise; distance is judged per utterance (NEAR_FIELD_SNR_DB).
            val dynamicSpeechThresholdDb = maxOf(SPEECH_MIN_DB, ambientNoiseFloorDb + 10.0f)
            val hasPitchHarmonics = pitchPeriodicity >= 0.28f
            val hasVocalZcr = zeroCrossings in 16..115

            val isSpeechFrame = !isBreathPuff &&
                (filteredRmsDb >= dynamicSpeechThresholdDb) &&
                hasVocalZcr &&
                (hasPitchHarmonics || filteredRmsDb >= dynamicSpeechThresholdDb + 4f)

            if (isSpeechFrame) {
                consecutiveSpeechFrames++
            } else {
                consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - 1)
                ambientNoiseFloorDb = 0.96f * ambientNoiseFloorDb + 0.04f * filteredRmsDb.coerceIn(20f, 75f)
            }

            val isConfirmedVoice = consecutiveSpeechFrames >= 2

            return VadResult(
                isVoice = isConfirmedVoice,
                rmsDb = rawRmsDb,
                filteredRmsDb = filteredRmsDb,
                zcr = zeroCrossings,
                pitchPeriodicity = pitchPeriodicity
            )
        }
    }

    data class VadResult(
        val isVoice: Boolean,
        val rmsDb: Float,
        val filteredRmsDb: Float,
        val zcr: Int,
        val pitchPeriodicity: Float = 0f
    )

    /**
     * Toggles microphone listening: if already listening, immediately stops and turns the mic off.
     */
    fun toggleListening(
        scope: CoroutineScope,
        language: SupportedLanguage = SupportedLanguage.ENGLISH,
        continuous: Boolean = false,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onFinalResult: (String) -> Unit
    ) {
        if (_isListening.value || isRecording.get()) {
            stopListening()
        } else {
            startListening(
                scope = scope,
                language = language,
                continuous = continuous,
                onPartial = onPartial,
                onError = onError,
                onFinalResult = onFinalResult
            )
        }
    }

    /**
     * Starts listening to user speech via microphone.
     *
     * In [continuous] (full-duplex) mode the microphone stays open across turns. While the assistant is
     * speaking, user speech is detected on the hardware-AEC'd signal (with the software echo detector and
     * the TTS echo-guard window rejecting residual speaker echo):
     * - ~130ms of voiced speech ducks TTS to 20% (stage 1, undone if it was a false alarm);
     * - ~0.8s of sustained voiced speech stops TTS and cancels generation (hard barge-in);
     * - a shorter utterance is transcribed when it ends; backchannels ("yeah", "ok") and echoes of the
     *   assistant's own words are dropped, anything else interrupts and becomes the next prompt.
     *
     * Every call starts a new session: the previous session's coroutine is cancelled and joined (so its
     * AudioRecord is released) before the new one opens the microphone, and a stale session can never
     * reset the state of, or deliver transcripts into, a newer one.
     */
    fun startListening(
        scope: CoroutineScope,
        language: SupportedLanguage = SupportedLanguage.ENGLISH,
        continuous: Boolean = false,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onFinalResult: (String) -> Unit
    ) {
        org.fossistant.assistant.util.AppLogger.i("WhisperEngine", "startListening: language=${language.code}, continuous=$continuous")
        val appContext = context ?: run {
            onError("No Android Context available for microphone recording.")
            onFinalResult("")
            return
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val err = "Microphone permission (RECORD_AUDIO) is not granted."
            _lastError.value = err
            onError(err)
            onFinalResult("")
            return
        }

        val sessionId = sessionCounter.incrementAndGet()
        val previousJob = recordingJob
        previousJob?.cancel()
        stopSystemRecognizer()

        isActiveSttSession = true
        _isListening.value = true
        _livePartial.value = "Listening..."
        _lastError.value = null
        isRecording.set(true)

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isHeadset = isHeadsetConnected(appContext)

        val job = scope.launch(ioDispatcher) {
            // Wait until the previous session has fully released its AudioRecord.
            previousJob?.cancelAndJoin()
            if (!isCurrentSession(sessionId)) return@launch

            if (continuous && !isHeadset) {
                enableCommunicationRouting(audioManager)
            }
            // Give VoiceWakeWordService time to release its background AudioRecord if it was holding the mic
            delay(60)
            ensureNativeWhisperInitialized()

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize * 2, 8192)
            var audioRecord: AudioRecord? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var isCurrentlyDucked = false

            suspend fun deliverStatus(status: String) {
                _livePartial.value = status
                withContext(Dispatchers.Main) {
                    if (isCurrentSession(sessionId)) onPartial(status)
                }
            }

            try {
                // Primary source: VOICE_COMMUNICATION for hardware Acoustic Echo Cancellation (AEC)
                audioRecord = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE_HZ,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                }.getOrNull()

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { audioRecord?.release() }
                    audioRecord = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize
                        )
                    }.getOrNull()
                }

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    val err = "Microphone hardware busy or failed to initialize AudioRecord."
                    _lastError.value = err
                    withContext(Dispatchers.Main) {
                        if (isCurrentSession(sessionId)) {
                            onError(err)
                            // Try foreign system SpeechRecognizer only after AudioRecord is closed
                            startForeignSystemRecognizerFallback(appContext, language, onPartial, onError, onFinalResult)
                        }
                    }
                    return@launch
                }

                // Enable hardware Acoustic Echo Cancellation and Noise Suppression on the AudioRecord session
                runCatching {
                    val sessionIdAudio = audioRecord.audioSessionId
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionIdAudio)?.apply {
                            enabled = true
                        }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionIdAudio)?.apply {
                            enabled = true
                        }
                    }
                }

                audioRecord.startRecording()
                val pcmBuffer = ShortArray(FRAME_SAMPLES) // 64ms per frame at 16kHz
                val frameMs = FRAME_SAMPLES * 1000L / SAMPLE_RATE_HZ
                val ringBufferSize = 4096 // 256ms pre-roll ring buffer
                val preRollRing = ShortArray(ringBufferSize)
                var preRollIndex = 0

                val fullAudioSamples = ShortArray(SAMPLE_RATE_HZ * 25)
                var totalSamples = 0
                var speechDetected = false
                var utteranceRevision = 0
                var utteranceVoicedMs = 0L
                var utterancePeakDb = 0f
                var utteranceStartedDuringEcho = false
                var hardInterruptSent = false
                var consecutiveVoiceFrames = 0
                var lastVoiceMs = System.currentTimeMillis()
                var lastVoiceCandidateMs = 0L
                var idleSinceMs = System.currentTimeMillis()
                var lastStatusMs = 0L

                var speculativeRevision = -1
                var speculativeJob: Deferred<String>? = null

                val softwareAec = SoftwareAcousticEchoCanceler(playbackReferenceBuffer)
                val vad = DeepVocalActivityDetector()

                fun pushPreRoll(src: ShortArray, count: Int) {
                    for (i in 0 until count) {
                        preRollRing[preRollIndex] = src[i]
                        preRollIndex = (preRollIndex + 1) % ringBufferSize
                    }
                }

                fun appendUtterance(src: ShortArray, count: Int) {
                    if (totalSamples + count <= fullAudioSamples.size) {
                        System.arraycopy(src, 0, fullAudioSamples, totalSamples, count)
                        totalSamples += count
                    }
                }

                while (isActive && isRecording.get() && isCurrentSession(sessionId)) {
                    val readCount = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount < 0) throw IllegalStateException("AudioRecord.read failed ($readCount)")
                    if (readCount == 0) continue
                    val now = System.currentTimeMillis()
                    val ttsPlaying = isTtsSpeakingProvider?.invoke() ?: false
                    val echoGuard = isEchoGuardActiveProvider?.invoke() ?: ttsPlaying

                    val aecResult = softwareAec.processFrame(pcmBuffer, readCount, ttsPlaying && !isHeadset)
                    val vadResult = vad.processFrame(pcmBuffer, readCount)
                    _currentRmsDb.value = vadResult.rmsDb
                    _normalizedAudioLevel.value = ((vadResult.filteredRmsDb - 26f) / 48f).coerceIn(0f, 1f)

                    // While the speaker may still be audible, require clear voiced (pitched) speech that the
                    // echo detector does not attribute to the TTS signal.
                    val isUserVoiceFrame = if (!echoGuard || isHeadset) {
                        vadResult.isVoice
                    } else {
                        vadResult.isVoice &&
                            vadResult.pitchPeriodicity >= 0.28f &&
                            (aecResult.isDoubleTalk || !aecResult.isSpeakerEchoDominant)
                    }

                    if (isUserVoiceFrame) {
                        consecutiveVoiceFrames++
                        lastVoiceCandidateMs = now
                    } else {
                        consecutiveVoiceFrames = 0
                    }

                    // Stage 1 barge-in: duck TTS on the first sign of user speech while it is playing.
                    if (ttsPlaying && !isCurrentlyDucked && consecutiveVoiceFrames >= 2) {
                        isCurrentlyDucked = true
                        onTentativeBargeIn?.invoke()
                        deliverStatus("Hearing interruption...")
                    }

                    val startFrames = if (echoGuard && !isHeadset) 3 else 2
                    if (!speechDetected && consecutiveVoiceFrames >= startFrames) {
                        speechDetected = true
                        totalSamples = 0
                        for (i in 0 until ringBufferSize) {
                            fullAudioSamples[i] = preRollRing[(preRollIndex + i) % ringBufferSize]
                        }
                        totalSamples = ringBufferSize
                        utteranceVoicedMs = 0L
                        utterancePeakDb = 0f
                        utteranceStartedDuringEcho = echoGuard
                        hardInterruptSent = false
                    }

                    if (speechDetected) {
                        appendUtterance(pcmBuffer, readCount)
                        if (isUserVoiceFrame) {
                            lastVoiceMs = now
                            utteranceVoicedMs += frameMs
                            utterancePeakDb = maxOf(utterancePeakDb, vadResult.filteredRmsDb)
                            utteranceRevision++
                        }
                        if (now - lastStatusMs > 250L) {
                            lastStatusMs = now
                            val secs = totalSamples / SAMPLE_RATE_HZ.toFloat()
                            deliverStatus("Hearing speech (${"%.1f".format(secs)}s)...")
                        }

                        // Hard barge-in: sustained speech over the assistant is a real interruption.
                        val heardSoFar = speculativeJob?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()
                        if (ttsPlaying && !hardInterruptSent && utteranceVoicedMs >= HARD_BARGE_IN_VOICED_MS &&
                            (heardSoFar == null || !isFillerUtterance(cleanWhisperArtifacts(heardSoFar)))
                        ) {
                            hardInterruptSent = true
                            isCurrentlyDucked = false
                            withContext(Dispatchers.Main) {
                                if (isCurrentSession(sessionId)) onUserInterruption?.invoke()
                            }
                        }

                        val silenceMs = now - lastVoiceMs
                        // Early transcription: decode what we have shortly after the user pauses so the
                        // endpoint can use the actual words, and the final transcript is usually ready.
                        if (!isUserVoiceFrame && silenceMs >= SPECULATIVE_DECODE_AFTER_MS &&
                            speculativeRevision != utteranceRevision && speculativeJob?.isActive != true
                        ) {
                            val snapshot = fullAudioSamples.copyOf(totalSamples)
                            speculativeRevision = utteranceRevision
                            speculativeJob = async(ioDispatcher) { transcribePcm16(snapshot, snapshot.size, language) }
                        }

                        val speculativeText = speculativeJob
                            ?.takeIf { speculativeRevision == utteranceRevision && it.isCompleted && !it.isCancelled }
                            ?.getCompleted()
                            ?.let { cleanWhisperArtifacts(it) }
                        val endpointTimeout = computeDynamicSilenceTimeout(speculativeText.orEmpty())
                        val isSilenceExceeded = !isUserVoiceFrame && silenceMs > endpointTimeout
                        val isMaxDurationReached = totalSamples >= SAMPLE_RATE_HZ * 20

                        if (isSilenceExceeded || isMaxDurationReached) {
                            val revision = utteranceRevision
                            val reusable = speculativeJob?.takeIf { speculativeRevision == revision && !isMaxDurationReached }
                            val voicedMs = utteranceVoicedMs
                            val noiseFloorDb = vad.ambientNoiseFloorDb
                            val snrDb = utterancePeakDb - noiseFloorDb
                            val startedDuringEcho = utteranceStartedDuringEcho
                            val interruptAlreadySent = hardInterruptSent
                            val samplesToDecode = if (reusable == null) fullAudioSamples.copyOf(totalSamples) else null
                            val wasDucked = isCurrentlyDucked

                            totalSamples = 0
                            speechDetected = false
                            utteranceVoicedMs = 0L
                            hardInterruptSent = false
                            consecutiveVoiceFrames = 0
                            isCurrentlyDucked = false
                            speculativeJob = null
                            speculativeRevision = -1
                            idleSinceMs = now

                            val nearField = isHeadset || snrDb >= NEAR_FIELD_SNR_DB
                            if (voicedMs >= MIN_UTTERANCE_VOICED_MS && !nearField) {
                                org.fossistant.assistant.util.AppLogger.i(
                                    "WhisperEngine",
                                    "Ignored distant speech (${voicedMs} ms, peak ${"%.1f".format(utterancePeakDb)} dB, only ${"%.1f".format(snrDb)} dB above the ${"%.1f".format(noiseFloorDb)} dB noise; needs $NEAR_FIELD_SNR_DB)"
                                )
                            }
                            if (voicedMs >= MIN_UTTERANCE_VOICED_MS && nearField) {
                                org.fossistant.assistant.util.AppLogger.i(
                                    "WhisperEngine",
                                    "Utterance: ${voicedMs} ms, peak ${"%.1f".format(utterancePeakDb)} dB, ${"%.1f".format(snrDb)} dB above the noise"
                                )
                                deliverStatus("Transcribing with Whisper...")
                                launch(ioDispatcher) {
                                    val transcript = reusable?.await()
                                        ?: transcribePcm16(samplesToDecode!!, samplesToDecode.size, language)
                                    handleTranscript(
                                        sessionId = sessionId,
                                        voicedMs = voicedMs,
                                        rawTranscript = transcript,
                                        startedDuringEcho = startedDuringEcho,
                                        interruptAlreadySent = interruptAlreadySent,
                                        wasDucked = wasDucked,
                                        onPartial = onPartial,
                                        onFinalResult = onFinalResult
                                    )
                                }
                                if (!continuous) break
                            } else {
                                if (wasDucked) onBargeInCleared?.invoke()
                                deliverStatus("Listening with Whisper...")
                            }
                        }
                    } else {
                        pushPreRoll(pcmBuffer, readCount)

                        // False alarm (cough, breath, echo burst): restore TTS volume.
                        if (isCurrentlyDucked && now - lastVoiceCandidateMs > 400L) {
                            isCurrentlyDucked = false
                            onBargeInCleared?.invoke()
                        }

                        if (now - idleSinceMs > INITIAL_SILENCE_MAX_MS) {
                            if (!continuous) {
                                _livePartial.value = ""
                                withContext(Dispatchers.Main) {
                                    if (isCurrentSession(sessionId)) {
                                        onError("No speech detected from microphone.")
                                        onFinalResult("")
                                    }
                                }
                                break
                            }
                            idleSinceMs = now
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val err = "Microphone recording error: ${t.message}"
                org.fossistant.assistant.util.AppLogger.w("WhisperEngine", err)
                _lastError.value = err
                withContext(NonCancellable + Dispatchers.Main) {
                    if (isCurrentSession(sessionId)) {
                        onError(err)
                        onFinalResult("")
                    }
                }
            } finally {
                if (isCurrentlyDucked) {
                    onBargeInCleared?.invoke()
                }
                runCatching {
                    echoCanceler?.enabled = false
                    echoCanceler?.release()
                }
                runCatching {
                    noiseSuppressor?.enabled = false
                    noiseSuppressor?.release()
                }
                runCatching {
                    audioRecord?.stop()
                    audioRecord?.release()
                }
                if (isCurrentSession(sessionId)) {
                    restoreCommunicationRouting(audioManager)
                    _currentRmsDb.value = 0f
                    _normalizedAudioLevel.value = 0f
                    isRecording.set(false)
                    isActiveSttSession = false
                }
            }
        }
        recordingJob = job
        // Runs after the session AND its in-flight transcriptions finish, so "listening" stays true until
        // the final transcript of a turn-by-turn session has been delivered.
        job.invokeOnCompletion {
            if (isCurrentSession(sessionId)) {
                isRecording.set(false)
                isActiveSttSession = false
                _isListening.value = false
            }
        }
    }

    private suspend fun handleTranscript(
        sessionId: Long,
        voicedMs: Long,
        rawTranscript: String,
        startedDuringEcho: Boolean,
        interruptAlreadySent: Boolean,
        wasDucked: Boolean,
        onPartial: (String) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        val cleaned = cleanWhisperArtifacts(rawTranscript)
        val autocorrected = if (isWhisperHallucination(cleaned)) "" else FuzzyAppAndCommandMatcher.autocorrectTranscript(cleaned)

        suspend fun dropAndResume(reason: String?) {
            if (reason != null) org.fossistant.assistant.util.AppLogger.i("WhisperEngine", reason)
            if (wasDucked && !interruptAlreadySent) onBargeInCleared?.invoke()
            withContext(Dispatchers.Main) {
                if (isCurrentSession(sessionId)) {
                    _livePartial.value = "Listening with Whisper..."
                    onPartial("Listening with Whisper...")
                }
            }
        }

        if (autocorrected.isBlank()) {
            dropAndResume(null)
            return
        }

        // While an answer is being written or spoken, only about a second or more of real speech counts.
        if (!interruptAlreadySent && voicedMs < BUSY_MIN_VOICED_MS && isAssistantBusyProvider?.invoke() == true) {
            dropAndResume("Ignored short speech during the answer (${voicedMs} ms): '${org.fossistant.assistant.util.AppLogger.userText(autocorrected)}'")
            return
        }

        // Hesitations ("um", "hmm") would otherwise cancel the answer being written or spoken and become a prompt.
        if (isFillerUtterance(autocorrected)) {
            dropAndResume("Ignored filler: '${org.fossistant.assistant.util.AppLogger.userText(autocorrected)}'")
            return
        }

        // 1. Self-echo rejection: the assistant's own words picked up by the microphone.
        if ((startedDuringEcho || interruptAlreadySent) && isSelfEchoChecker?.invoke(autocorrected) == true) {
            dropAndResume("Suppressed self-echo transcript: '${org.fossistant.assistant.util.AppLogger.userText(autocorrected)}'")
            return
        }

        // 2. Conversational backchannel while the assistant keeps talking ("yeah", "ok", "mhm").
        val ttsPlayingNow = isTtsSpeakingProvider?.invoke() ?: false
        if (ttsPlayingNow && !interruptAlreadySent && isBackchannelUtterance(autocorrected)) {
            dropAndResume("Ignored conversational backchannel: '${org.fossistant.assistant.util.AppLogger.userText(autocorrected)}'")
            return
        }

        withContext(Dispatchers.Main) {
            if (!isCurrentSession(sessionId)) return@withContext
            if (ttsPlayingNow && !interruptAlreadySent) {
                onUserInterruption?.invoke()
            } else if (wasDucked) {
                onBargeInCleared?.invoke()
            }
            org.fossistant.assistant.util.AppLogger.i("WhisperEngine", "Transcribed: '${org.fossistant.assistant.util.AppLogger.userText(autocorrected)}'")
            onPartial(autocorrected)
            onFinalResult(autocorrected)
        }
    }

    private fun isCurrentSession(sessionId: Long): Boolean = sessionCounter.get() == sessionId

    /**
     * Routes duplex audio for hardware echo cancellation on the loudspeaker: communication mode plus the
     * built-in speaker as communication device (API 31+), or the legacy speakerphone flag.
     */
    private fun enableCommunicationRouting(audioManager: AudioManager?) {
        val am = audioManager ?: return
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
            communicationRoutingActive.set(true)
            onCommunicationModeChanged?.invoke(true)
        }
    }

    private fun restoreCommunicationRouting(audioManager: AudioManager?) {
        if (!communicationRoutingActive.getAndSet(false)) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
        onCommunicationModeChanged?.invoke(false)
    }

    /**
     * Fallback to an external (non-Fossistant) system SpeechRecognizer only if AudioRecord could not open.
     * Never binds to FossistantRecognitionService to prevent recursive service loops.
     */
    private fun startForeignSystemRecognizerFallback(
        appContext: Context,
        language: SupportedLanguage,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        try {
            val pm = appContext.packageManager
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val resolveInfos = pm.queryIntentServices(intent, 0)
            val foreignService = resolveInfos.firstOrNull {
                it.serviceInfo.packageName != appContext.packageName
            }
            if (foreignService == null) {
                stopListening()
                onError("No external SpeechRecognizer found and AudioRecord failed.")
                onFinalResult("")
                return
            }

            val component = ComponentName(foreignService.serviceInfo.packageName, foreignService.serviceInfo.name)
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext, component)
            systemRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    val clamped = rmsdB.coerceAtLeast(0f) * 7f
                    _currentRmsDb.value = clamped
                    _normalizedAudioLevel.value = (clamped / 70f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    val errMsg = "SpeechRecognizer error code $error"
                    _lastError.value = errMsg
                    stopListening()
                    onError(errMsg)
                    onFinalResult("")
                }
                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    stopListening()
                    if (transcript.isNotBlank()) {
                        _livePartial.value = transcript
                        onFinalResult(transcript)
                    } else {
                        onFinalResult("")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        _livePartial.value = partial
                        onPartial(partial)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.code)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(speechIntent)
        } catch (t: Throwable) {
            stopListening()
            onError("Speech fallback failed: ${t.message}")
            onFinalResult("")
        }
    }

    private fun stopSystemRecognizer() {
        try {
            systemRecognizer?.stopListening()
            systemRecognizer?.destroy()
        } catch (_: Throwable) {}
        systemRecognizer = null
    }

    /**
     * Immediately stops microphone recording and resets listening state. Pending transcriptions of the
     * stopped session are cancelled and can no longer deliver a prompt.
     */
    fun stopListening() {
        sessionCounter.incrementAndGet()
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        restoreCommunicationRouting(audioManager)
        onBargeInCleared?.invoke()
        isRecording.set(false)
        isActiveSttSession = false
        recordingJob?.cancel()
        recordingJob = null
        stopSystemRecognizer()
        _isListening.value = false
        _currentRmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
        _livePartial.value = ""
    }
}
