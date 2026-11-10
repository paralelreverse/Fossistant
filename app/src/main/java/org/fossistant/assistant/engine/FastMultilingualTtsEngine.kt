package org.fossistant.assistant.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossistant.assistant.model.FastTtsVoiceProfile
import org.fossistant.assistant.model.SupportedLanguage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * High-speed, low-latency Multilingual TTS Engine supporting **7 Languages**
 * (English, Spanish, French, German, Italian, Japanese, Polish) with **multiple distinct
 * Fast TTS Voice Profiles per language** (21 total profiles) and real-time audio amplitude
 * telemetry ([ttsAudioLevel]) to drive audio-synced [org.fossistant.assistant.ui.components.CosmicAiBlob] animations.
 *
 * Playback architecture (needed for full-duplex barge-in):
 * - Each sentence is synthesized with `TextToSpeech.synthesizeToFile`; PCM chunks streamed through
 *   `onAudioAvailable` (or, if the engine does not stream, the finished WAV) are played by this class
 *   through its own [AudioTrack] instead of the TTS service's internal player.
 * - Owning the AudioTrack gives instant real volume ducking ([setDucked]), an exact "is audio playing"
 *   state including an acoustic echo tail ([isEchoGuardActive]), true output levels for the blob, and an
 *   echo-reference stream ([onAudioPcmAvailable], resampled to 16 kHz) emitted when audio is played rather
 *   than when it is synthesized.
 */
class FastMultilingualTtsEngine(private val context: Context?) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady: Boolean = false

    @Volatile
    private var isInitializing: Boolean = false

    /** Clause text per utteranceId for utterances that are queued, synthesizing, or playing. */
    private val activeUtterances = ConcurrentHashMap<String, String>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Normalized real-time TTS output audio amplitude in `[0.0f, 1.0f]`, measured from the PCM frames
     * actually written to the speaker.
     */
    private val _ttsAudioLevel = MutableStateFlow(0f)
    val ttsAudioLevel: StateFlow<Float> = _ttsAudioLevel.asStateFlow()

    private val _lastTtsError = MutableStateFlow<String?>(null)
    val lastTtsError: StateFlow<String?> = _lastTtsError.asStateFlow()

    /** Everything queued to speak since the last flush, and how much of it has come out of the speaker. */
    data class SpeechCaption(val text: String = "", val spokenChars: Int = 0)

    private val _caption = MutableStateFlow(SpeechCaption())

    /** Live subtitles for voice mode: [SpeechCaption.spokenChars] follows the audio actually played. */
    val caption: StateFlow<SpeechCaption> = _caption.asStateFlow()

    /** One clause of [caption] and where its audio sits in the AudioTrack's frame count. */
    private class CaptionSegment(val utteranceId: String, val charStart: Int, val text: String) {
        @Volatile var startFrame = -1L
        @Volatile var synthesizedSamples = 0L
        @Volatile var synthesisDone = false
        @Volatile var playedToEnd = false
    }

    private val captionSegments = CopyOnWriteArrayList<CaptionSegment>()
    private val captionLock = Any()

    /** Speech rate learned from finished clauses (samples per character), for clauses still synthesizing. */
    @Volatile
    private var samplesPerChar = 0f

    private val _activeProfile = MutableStateFlow(profilesForLanguage(SupportedLanguage.AUTO).first())

    /** Languages already reported as missing a voice, so the hint appears once per language. */
    private val reportedMissingVoices = mutableSetOf<String>()
    val activeProfile: StateFlow<FastTtsVoiceProfile> = _activeProfile.asStateFlow()

    /**
     * 16 kHz mono 16-bit PCM echo-reference callback, invoked from the playback thread as each frame is
     * handed to the speaker. Consumed by the echo detector in [WhisperMultilingualSttEngine].
     */
    var onAudioPcmAvailable: ((ShortArray) -> Unit)? = null

    /**
     * When true (full-duplex voice mode) playback uses `USAGE_VOICE_COMMUNICATION` so the platform's
     * hardware acoustic echo canceler on the `VOICE_COMMUNICATION` microphone has the speaker signal as
     * its reference. Otherwise `USAGE_ASSISTANT` is used (normal routing, media-quality volume).
     */
    @Volatile
    var useCommunicationAudio: Boolean = false

    @Volatile
    private var volumeMultiplier: Float = 1.0f

    @Volatile
    private var lastPlaybackEndMs: Long = 0L

    /**
     * Ducks speech volume down to 20% on tentative barge-in so the user's voice can be clearly heard
     * by STT without speaker acoustic feedback, and restores to 100% if false alarm. Applies instantly
     * to the audio that is already playing.
     */
    fun setDucked(ducked: Boolean) {
        volumeMultiplier = if (ducked) DUCKED_VOLUME else 1.0f
        runCatching { audioTrack?.setVolume(volumeMultiplier) }
    }

    /**
     * True while TTS audio is playing and for [ECHO_TAIL_MS] afterwards, i.e. whenever the microphone may
     * still be picking up the assistant's own voice (speaker latency + room reverberation).
     */
    fun isEchoGuardActive(): Boolean =
        _isSpeaking.value || System.currentTimeMillis() - lastPlaybackEndMs < ECHO_TAIL_MS

    data class SpokenUtteranceRecord(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val recentSpokenUtterances = ConcurrentLinkedQueue<SpokenUtteranceRecord>()

    private fun recordSpokenUtterance(text: String) {
        val now = System.currentTimeMillis()
        recentSpokenUtterances.offer(SpokenUtteranceRecord(text, now))
        while (recentSpokenUtterances.isNotEmpty()) {
            val head = recentSpokenUtterances.peek() ?: break
            if (now - head.timestamp > SELF_ECHO_HISTORY_MS) {
                recentSpokenUtterances.poll()
            } else {
                break
            }
        }
    }

    /**
     * Determines whether [candidateText] is an acoustic echo of words recently played by TTS.
     * Prevents self-interruption loops in full duplex mode while letting real interruptions through:
     * - only applies while TTS audio is playing or shortly after it stopped;
     * - matches whole words (not substrings) and ignores common function words;
     * - short commands containing an interruption word ("stop", "wait", "no", ...) are never echo.
     */
    fun isLikelySelfEcho(candidateText: String): Boolean {
        val now = System.currentTimeMillis()
        if (!_isSpeaking.value && now - lastPlaybackEndMs > SELF_ECHO_AFTER_PLAYBACK_MS) return false

        val candidateWords = tokenizeWords(candidateText)
        if (candidateWords.isEmpty()) return false
        if (candidateWords.size <= 4 && candidateWords.any { it in INTERRUPTION_WORDS }) return false

        val contentWords = candidateWords.filter { it.length > 2 && it !in FUNCTION_WORDS }
        if (contentWords.isEmpty()) return false

        val recentWords = HashSet<String>()
        for (record in recentSpokenUtterances) {
            if (now - record.timestamp <= SELF_ECHO_HISTORY_MS) {
                recentWords.addAll(tokenizeWords(record.text))
            }
        }
        if (recentWords.isEmpty()) return false

        val matched = contentWords.count { it in recentWords }
        return if (contentWords.size == 1) {
            matched == 1
        } else {
            matched.toFloat() / contentWords.size >= 0.7f
        }
    }

    private fun tokenizeWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s']"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

    companion object {
        private const val DUCKED_VOLUME = 0.20f
        const val ECHO_TAIL_MS = 450L
        private const val SELF_ECHO_AFTER_PLAYBACK_MS = 2500L
        private const val SELF_ECHO_HISTORY_MS = 20000L
        private const val REFERENCE_SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val CAPTION_TICK_MS = 50L

        private val INTERRUPTION_WORDS = setOf(
            "stop", "wait", "no", "nope", "cancel", "pause", "quiet", "enough", "hold", "shut", "halt",
            "para", "basta", "espera", "arrête", "attends", "stopp", "warte", "aspetta",
            "czekaj", "przestań", "dość", "やめて", "待って", "ストップ"
        )

        private val FUNCTION_WORDS = setOf(
            "the", "and", "you", "your", "for", "are", "was", "were", "that", "this", "with", "have", "has",
            "can", "could", "would", "should", "will", "what", "which", "who", "how", "why", "when", "where",
            "not", "but", "all", "any", "its", "it's", "i'm", "don't", "about", "from", "they", "them", "there",
            "then", "than", "just", "like", "some", "into", "out", "our", "her", "his", "she", "him"
        )

        /**
         * Complete catalog of 21 Fast TTS Voice Profiles across all 7 supported languages
         * (3 distinct fast voices per language: Expressive/Fast Female, Dynamic/Fast Male, Calm/Studio).
         */
        val ALL_VOICE_PROFILES: List<FastTtsVoiceProfile> = listOf(
            // 1. English (en-US)
            FastTtsVoiceProfile("en_nova", "Nova (Ultra-Fast Clear)", SupportedLanguage.ENGLISH, 1.24f, 1.05f, "Crisp, high-speed assistant voice"),
            FastTtsVoiceProfile("en_orion", "Orion (Fast Deep)", SupportedLanguage.ENGLISH, 1.18f, 0.88f, "Warm, resonant baritone"),
            FastTtsVoiceProfile("en_lyra", "Lyra (Natural Studio)", SupportedLanguage.ENGLISH, 1.10f, 1.12f, "Bright, friendly conversational tone"),

            // 2. Spanish (es-ES)
            FastTtsVoiceProfile("es_valeria", "Valeria (Rápida y Clara)", SupportedLanguage.SPANISH, 1.22f, 1.04f, "Voz femenina ágil y natural"),
            FastTtsVoiceProfile("es_mateo", "Mateo (Dinámico)", SupportedLanguage.SPANISH, 1.18f, 0.90f, "Voz masculina cálida y directa"),
            FastTtsVoiceProfile("es_sofia", "Sofía (Expresiva)", SupportedLanguage.SPANISH, 1.12f, 1.10f, "Tono suave y conversacional"),

            // 3. French (fr-FR)
            FastTtsVoiceProfile("fr_chloe", "Chloé (Rapide & Fluide)", SupportedLanguage.FRENCH, 1.22f, 1.06f, "Voix féminine vive et précise"),
            FastTtsVoiceProfile("fr_lucas", "Lucas (Grave & Rapide)", SupportedLanguage.FRENCH, 1.16f, 0.89f, "Voix masculine posée et réactive"),
            FastTtsVoiceProfile("fr_elodie", "Élodie (Douce)", SupportedLanguage.FRENCH, 1.08f, 1.12f, "Voix chaleureuse et naturelle"),

            // 4. German (de-DE)
            FastTtsVoiceProfile("de_hannah", "Hannah (Schnell & Klar)", SupportedLanguage.GERMAN, 1.22f, 1.04f, "Schnelle, präzise Assistenzstimme"),
            FastTtsVoiceProfile("de_lukas", "Lukas (Direkt & Tief)", SupportedLanguage.GERMAN, 1.17f, 0.88f, "Kräftige männliche Stimme"),
            FastTtsVoiceProfile("de_emilia", "Emilia (Natürlich)", SupportedLanguage.GERMAN, 1.10f, 1.10f, "Freundlicher, ruhiger Klang"),

            // 5. Italian (it-IT)
            FastTtsVoiceProfile("it_giulia", "Giulia (Veloce & Nitida)", SupportedLanguage.ITALIAN, 1.24f, 1.05f, "Voce femminile brillante e rapida"),
            FastTtsVoiceProfile("it_leonardo", "Leonardo (Dinamico)", SupportedLanguage.ITALIAN, 1.18f, 0.90f, "Voce maschile profonda e fluida"),
            FastTtsVoiceProfile("it_aurora", "Aurora (Espressiva)", SupportedLanguage.ITALIAN, 1.10f, 1.12f, "Tono morbido e accogliente"),

            // 6. Japanese (ja-JP)
            FastTtsVoiceProfile("ja_aoi", "Aoi 葵 (高速・クリア)", SupportedLanguage.JAPANESE, 1.25f, 1.08f, "聞き取りやすい高速アシスタント音声"),
            FastTtsVoiceProfile("ja_ren", "Ren 蓮 (落ち着き・低音)", SupportedLanguage.JAPANESE, 1.18f, 0.88f, "落ち着いた男性ボイス"),
            FastTtsVoiceProfile("ja_hina", "Hina 陽菜 (ナチュラル)", SupportedLanguage.JAPANESE, 1.12f, 1.15f, "明るく自然な会話トーン"),

            // 7. Polish (pl-PL)
            FastTtsVoiceProfile("pl_zofia", "Zofia (Szybka i Wyraźna)", SupportedLanguage.POLISH, 1.22f, 1.05f, "Szybki, nowoczesny głos asystenta"),
            FastTtsVoiceProfile("pl_jan", "Jan (Dynamiczny Męski)", SupportedLanguage.POLISH, 1.17f, 0.89f, "Głęboki, precyzyjny głos męski"),
            FastTtsVoiceProfile("pl_maja", "Maja (Naturalna)", SupportedLanguage.POLISH, 1.10f, 1.11f, "Ciepły i przyjazny ton rozmowy")
        )

        /**
         * Voice styles for [language]: the hand-tuned ones where they exist, otherwise Standard / Fast / Calm
         * presets, so every language Whisper understands can be spoken with an installed Android voice.
         */
        fun profilesForLanguage(language: SupportedLanguage): List<FastTtsVoiceProfile> =
            ALL_VOICE_PROFILES.filter { it.language == language }.ifEmpty {
                val prefix = language.whisperCode
                listOf(
                    FastTtsVoiceProfile("${prefix}_standard", "Standard", language, 1.10f, 1.0f, "Natural pace with the installed voice"),
                    FastTtsVoiceProfile("${prefix}_fast", "Fast", language, 1.25f, 1.0f, "Quicker replies"),
                    FastTtsVoiceProfile("${prefix}_calm", "Calm", language, 0.95f, 0.95f, "Slower and softer")
                )
            }

        fun voicesForLanguage(language: SupportedLanguage): List<FastTtsVoiceProfile> =
            profilesForLanguage(language)

        fun defaultProfileFor(language: SupportedLanguage): FastTtsVoiceProfile =
            profilesForLanguage(language).first()
    }

    private val pendingSpeechQueue = ConcurrentLinkedQueue<Pair<String, Boolean>>()

    // ---- Own playback pipeline -------------------------------------------------------------------

    private sealed class PlaybackItem(val generation: Int, val utteranceId: String) {
        class Pcm(generation: Int, utteranceId: String, val sampleRate: Int, val samples: ShortArray) :
            PlaybackItem(generation, utteranceId)
        class End(generation: Int, utteranceId: String) : PlaybackItem(generation, utteranceId)
    }

    private class SynthesisState(val generation: Int, val file: File) {
        @Volatile var sampleRate: Int = 0
        @Volatile var streamedPcm: Boolean = false
        @Volatile var streamingUsable: Boolean = true
        @Volatile var playbackStarted: Boolean = false
    }

    /** Bumped on every [stop] / flush so stale synthesis callbacks and queued audio are discarded. */
    private val generation = AtomicInteger(0)
    private val synthesisStates = ConcurrentHashMap<String, SynthesisState>()
    private val playbackQueue = LinkedBlockingQueue<PlaybackItem>()
    private val pendingUtteranceCount = AtomicInteger(0)
    private val startedUtterances: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackUsesCommunication = false
    private var framesWrittenToTrack = 0L

    private val audioTrackLock = Any()

    @Volatile
    private var playbackThread: Thread? = null

    private var smoothedLevel = 0f

    init {
        initTtsIfNeeded()
    }

    @Synchronized
    private fun initTtsIfNeeded() {
        val appContext = context?.applicationContext ?: return
        if (isReady || isInitializing) return
        isInitializing = true
        try {
            tts = TextToSpeech(appContext) { status ->
                isInitializing = false
                if (status == TextToSpeech.SUCCESS) {
                    isReady = true
                    _lastTtsError.value = null
                    applyVoiceProfile(_activeProfile.value)
                    while (pendingSpeechQueue.isNotEmpty()) {
                        val next = pendingSpeechQueue.poll() ?: break
                        speakFast(next.first, next.second)
                    }
                } else {
                    isReady = false
                    _lastTtsError.value = "Android TextToSpeech initialization failed (status=$status). Check system TTS settings."
                }
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                        val state = utteranceId?.let { synthesisStates[it] } ?: return
                        state.sampleRate = sampleRateInHz
                        // Streamed chunks are only usable as 16-bit mono; otherwise wait for the WAV file.
                        state.streamingUsable = audioFormat == AudioFormat.ENCODING_PCM_16BIT && channelCount == 1
                    }

                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        val id = utteranceId ?: return
                        val state = synthesisStates[id] ?: return
                        if (audio == null || audio.size < 2 || !state.streamingUsable || state.sampleRate <= 0) return
                        if (state.generation != generation.get()) return
                        val shorts = ShortArray(audio.size / 2)
                        ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        state.streamedPcm = true
                        captionSegment(id)?.let { it.synthesizedSamples += shorts.size }
                        playbackQueue.offer(PlaybackItem.Pcm(state.generation, id, state.sampleRate, shorts))
                    }

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId ?: return
                        val state = synthesisStates.remove(id) ?: return
                        if (state.generation == generation.get()) {
                            if (!state.streamedPcm) {
                                // Engine did not stream PCM for file synthesis: play the finished WAV instead.
                                readWavPcm16Mono(state.file)?.let { (rate, pcm) ->
                                    captionSegment(id)?.let { it.synthesizedSamples += pcm.size }
                                    playbackQueue.offer(PlaybackItem.Pcm(state.generation, id, rate, pcm))
                                } ?: run {
                                    _lastTtsError.value = "TTS produced unreadable audio for one sentence."
                                }
                            }
                            playbackQueue.offer(PlaybackItem.End(state.generation, id))
                        }
                        captionSegment(id)?.synthesisDone = true
                        runCatching { state.file.delete() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSynthesisFailed(utteranceId, null)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSynthesisFailed(utteranceId, errorCode)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        val state = utteranceId?.let { synthesisStates.remove(it) } ?: return
                        runCatching { state.file.delete() }
                    }
                })
            }
        } catch (t: Throwable) {
            isInitializing = false
            isReady = false
            _lastTtsError.value = "TTS engine error: ${t.message}"
        }
    }

    private fun onSynthesisFailed(utteranceId: String?, errorCode: Int?) {
        val id = utteranceId ?: return
        val state = synthesisStates.remove(id) ?: return
        runCatching { state.file.delete() }
        if (errorCode != null) {
            _lastTtsError.value = "TTS synthesis error (code $errorCode)"
        }
        if (state.generation == generation.get()) {
            playbackQueue.offer(PlaybackItem.End(state.generation, id))
        }
    }

    private fun ensurePlaybackThread() {
        if (playbackThread?.isAlive == true) return
        synchronized(this) {
            if (playbackThread?.isAlive == true) return
            playbackThread = Thread({ playbackLoop() }, "FossistantTtsPlayback").apply {
                isDaemon = true
                start()
            }
            Thread({ captionClockLoop() }, "FossistantTtsCaptions").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun captionSegment(utteranceId: String): CaptionSegment? = captionSegments.firstOrNull { it.utteranceId == utteranceId }

    /** Moves [caption]'s spoken position along with the speaker's playback head, ~20 times a second. */
    private fun captionClockLoop() {
        while (true) {
            try {
                Thread.sleep(CAPTION_TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (_isSpeaking.value) runCatching { updateSpokenPosition() }
        }
    }

    private fun updateSpokenPosition() {
        val track = audioTrack ?: return
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        var spoken = 0
        for (segment in captionSegments) {
            if (segment.startFrame < 0) break
            val played = head - segment.startFrame
            if (played <= 0) break
            // Total length of this clause's audio: exact once synthesized, otherwise estimated from the speech rate.
            val estimate = if (samplesPerChar > 0f) (samplesPerChar * segment.text.length).toLong() else 0L
            val total = if (segment.synthesisDone) segment.synthesizedSamples else maxOf(segment.synthesizedSamples, estimate)
            val fraction = if (segment.playedToEnd && played >= total) 1f else (played.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f)
            spoken = segment.charStart + (fraction * segment.text.length).toInt()
            if (fraction < 1f) break
        }
        val current = _caption.value
        if (spoken > current.spokenChars) _caption.value = current.copy(spokenChars = spoken.coerceAtMost(current.text.length))
    }

    /** Adds one clause to [caption] (not yet spoken). */
    private fun addCaptionClause(utteranceId: String, clause: String) {
        synchronized(captionLock) {
            val current = _caption.value
            val separator = if (current.text.isEmpty()) "" else " "
            captionSegments.add(CaptionSegment(utteranceId, current.text.length + separator.length, clause))
            _caption.value = current.copy(text = current.text + separator + clause)
        }
    }

    private fun clearCaption() {
        synchronized(captionLock) {
            captionSegments.clear()
            _caption.value = SpeechCaption()
        }
    }

    private fun playbackLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        while (true) {
            val item = try {
                playbackQueue.poll(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            if (item.generation != generation.get()) continue
            when (item) {
                is PlaybackItem.Pcm -> playPcm(item)
                is PlaybackItem.End -> finishUtterance(item)
            }
        }
    }

    private fun playPcm(item: PlaybackItem.Pcm) {
        val track = obtainTrack(item.sampleRate) ?: return
        if (activeUtterances.containsKey(item.utteranceId)) {
            val isFirstChunk = startedUtterances.add(item.utteranceId)
            if (isFirstChunk) {
                activeUtterances[item.utteranceId]?.let { recordSpokenUtterance(it) }
            }
        }
        captionSegment(item.utteranceId)?.let { segment ->
            if (segment.startFrame < 0) segment.startFrame = synchronized(audioTrackLock) { framesWrittenToTrack }
        }
        val frameSamples = (item.sampleRate * FRAME_MS / 1000).coerceAtLeast(64)
        var offset = 0
        while (offset < item.samples.size) {
            if (item.generation != generation.get()) return
            val count = minOf(frameSamples, item.samples.size - offset)
            val written = runCatching { track.write(item.samples, offset, count, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1)
            if (written <= 0) return
            synchronized(audioTrackLock) { framesWrittenToTrack += written }
            publishLevel(item.samples, offset, written)
            onAudioPcmAvailable?.let { callback ->
                runCatching { callback(resampleTo16k(item.samples, offset, written, item.sampleRate)) }
            }
            offset += written
        }
    }

    private fun finishUtterance(item: PlaybackItem.End) {
        captionSegment(item.utteranceId)?.let { segment ->
            segment.playedToEnd = true
            if (segment.synthesizedSamples > 0 && segment.text.isNotEmpty()) {
                val rate = segment.synthesizedSamples.toFloat() / segment.text.length
                samplesPerChar = if (samplesPerChar <= 0f) rate else samplesPerChar * 0.7f + rate * 0.3f
            }
        }
        activeUtterances.remove(item.utteranceId)
        startedUtterances.remove(item.utteranceId)
        val remaining = pendingUtteranceCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && playbackQueue.none { it.generation == generation.get() }) {
            drainTrack(item.generation)
            if (item.generation == generation.get() && pendingUtteranceCount.get() <= 0) {
                pendingUtteranceCount.set(0)
                markPlaybackStopped()
            }
        } else {
            // Brief inter-sentence breath dip
            _ttsAudioLevel.value = 0.08f
        }
    }

    /** Waits until the AudioTrack has actually played everything written to it (or a flush happens). */
    private fun drainTrack(forGeneration: Int) {
        val track = audioTrack ?: return
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline && forGeneration == generation.get()) {
            val written = synchronized(audioTrackLock) { framesWrittenToTrack }
            val played = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(written)
            if (played >= written) break
            if (playbackQueue.isNotEmpty()) break
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun markPlaybackStopped() {
        // Everything queued has been heard: show the whole caption.
        _caption.value.let { if (it.spokenChars < it.text.length) _caption.value = it.copy(spokenChars = it.text.length) }
        lastPlaybackEndMs = System.currentTimeMillis()
        smoothedLevel = 0f
        _ttsAudioLevel.value = 0f
        _isSpeaking.value = false
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack? {
        val wantCommunication = useCommunicationAudio
        synchronized(audioTrackLock) {
            val existing = audioTrack
            if (existing != null && trackSampleRate == sampleRate && trackUsesCommunication == wantCommunication) {
                return existing
            }
            existing?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            audioTrack = null
            framesWrittenToTrack = 0L
            return try {
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val attrs = AudioAttributes.Builder()
                    .setUsage(if (wantCommunication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 10 * 2)) // ~100ms keeps barge-in stops snappy
                    .build()
                track.setVolume(volumeMultiplier)
                track.play()
                trackSampleRate = sampleRate
                trackUsesCommunication = wantCommunication
                audioTrack = track
                track
            } catch (t: Throwable) {
                _lastTtsError.value = "Audio output error: ${t.message}"
                null
            }
        }
    }

    private fun publishLevel(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        var sumSq = 0.0
        for (i in offset until offset + count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val level = (sqrt(sumSq / count) / 9000.0).toFloat().coerceIn(0f, 1f)
        smoothedLevel = if (level > smoothedLevel) level else smoothedLevel * 0.7f + level * 0.3f
        _ttsAudioLevel.value = smoothedLevel
    }

    private fun resampleTo16k(samples: ShortArray, offset: Int, count: Int, sampleRate: Int): ShortArray {
        if (sampleRate == REFERENCE_SAMPLE_RATE) return samples.copyOfRange(offset, offset + count)
        val outCount = (count.toLong() * REFERENCE_SAMPLE_RATE / sampleRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outCount)
        val step = sampleRate.toDouble() / REFERENCE_SAMPLE_RATE
        for (i in 0 until outCount) {
            val pos = i * step
            val idx = pos.toInt()
            val frac = pos - idx
            val a = samples[offset + minOf(idx, count - 1)]
            val b = samples[offset + minOf(idx + 1, count - 1)]
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    /** Parses a 16-bit PCM WAV file written by `synthesizeToFile`, down-mixing stereo to mono. */
    private fun readWavPcm16Mono(file: File): Pair<Int, ShortArray>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return@runCatching null
            var pos = 12
            var sampleRate = 0
            var channels = 1
            var bits = 16
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4)
                val chunkSize = bb.getInt(pos + 4)
                val body = pos + 8
                if (chunkId == "fmt ") {
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                } else if (chunkId == "data") {
                    if (bits != 16 || sampleRate <= 0 || channels !in 1..2) return@runCatching null
                    val dataLen = minOf(chunkSize, bytes.size - body)
                    val totalSamples = dataLen / 2
                    val frames = totalSamples / channels
                    val out = ShortArray(frames)
                    for (f in 0 until frames) {
                        out[f] = if (channels == 1) {
                            bb.getShort(body + f * 2)
                        } else {
                            ((bb.getShort(body + f * 4).toInt() + bb.getShort(body + f * 4 + 2).toInt()) / 2).toShort()
                        }
                    }
                    return@runCatching sampleRate to out
                }
                pos = body + chunkSize + (chunkSize and 1)
            }
            null
        }
    }.getOrNull()

    /**
     * Computes syllable-level acoustic energy for a spoken word `[0.25f, 0.98f]` based on vowel density and length.
     */
    internal fun computeWordAcousticEnergy(word: String): Float {
        val clean = word.trim()
        if (clean.isEmpty()) return 0.12f
        val vowels = clean.count { it.lowercaseChar() in "aeiouyáéíóúäöüàèìòùąęó" }
        val vowelRatio = (vowels.toFloat() / clean.length.coerceAtLeast(1)).coerceIn(0.15f, 0.85f)
        val lengthFactor = (clean.length.coerceIn(1, 10) / 10f) * 0.35f
        return (0.32f + vowelRatio * 0.45f + lengthFactor).coerceIn(0.25f, 0.98f)
    }

    /**
     * Selects a [FastTtsVoiceProfile] and configures locale, speech rate, and pitch on the native TTS engine.
     */
    fun selectVoiceProfile(profile: FastTtsVoiceProfile) {
        _activeProfile.value = profile
        applyVoiceProfile(profile)
    }

    private fun applyVoiceProfile(profile: FastTtsVoiceProfile, localeOverride: Locale? = null) {
        val engine = tts ?: return
        if (!isReady) return
        try {
            val locale = localeOverride ?: profile.language.locale
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (reportedMissingVoices.add(locale.language)) {
                    _lastTtsError.value = "No ${locale.getDisplayLanguage(Locale.ENGLISH)} voice is installed. Add one in Android Settings > System > Languages > Text-to-speech output."
                }
                engine.setLanguage(Locale.getDefault())
            }
            engine.setSpeechRate(profile.speechRate)
            engine.setPitch(profile.pitch)

            val matchingVoices = engine.voices?.filter {
                it.locale.language == locale.language
            }.orEmpty()
            if (matchingVoices.isNotEmpty()) {
                val nonNetwork = matchingVoices.filter { !it.isNetworkConnectionRequired }
                val pool = nonNetwork.ifEmpty { matchingVoices }
                val chosen = pool[profile.id.hashCode().ushr(1) % pool.size]
                engine.voice = chosen
            }
        } catch (_: Throwable) {}
    }

    /**
     * Speaks a text response using sentence-chunked low-latency synthesis. With [flushQueue] any speech
     * already queued or playing is cut off first.
     */
    fun speakFast(text: String, flushQueue: Boolean = true, languageTag: String? = null) {
        org.fossistant.assistant.util.AppLogger.i("FastTtsEngine", "speakFast: '${org.fossistant.assistant.util.AppLogger.userText(text, 60)}' (profile=${_activeProfile.value.name})")
        val clauses = splitIntoFastSpeechClauses(text)
        if (clauses.isEmpty()) return
        if (languageTag != null && isReady) {
            // Speak in the reply's language (e.g. the language Whisper heard), with the active style's speed.
            applyVoiceProfile(_activeProfile.value, Locale.forLanguageTag(languageTag))
        }
        if (!isReady || tts == null) {
            if (flushQueue) pendingSpeechQueue.clear()
            pendingSpeechQueue.offer(Pair(text, flushQueue))
            initTtsIfNeeded()
            return
        }
        val engine = tts ?: return
        val appContext = context ?: return
        if (flushQueue) {
            flushPlayback()
            setDucked(false) // a new answer never starts ducked from an earlier barge-in
        }
        ensurePlaybackThread()
        val gen = generation.get()
        val cacheDir = File(appContext.cacheDir, "tts").apply { mkdirs() }
        val params = Bundle()
        for (clause in clauses) {
            val utteranceId = UUID.randomUUID().toString()
            val file = File(cacheDir, "$utteranceId.wav")
            activeUtterances[utteranceId] = clause
            synthesisStates[utteranceId] = SynthesisState(gen, file)
            addCaptionClause(utteranceId, clause)
            pendingUtteranceCount.incrementAndGet()
            _isSpeaking.value = true
            val res = runCatching { engine.synthesizeToFile(clause, params, file, utteranceId) }.getOrDefault(TextToSpeech.ERROR)
            if (res == TextToSpeech.ERROR) {
                synthesisStates.remove(utteranceId)
                activeUtterances.remove(utteranceId)
                playbackQueue.offer(PlaybackItem.End(gen, utteranceId))
                _lastTtsError.value = "TextToSpeech engine failed to queue speech."
            }
        }
    }

    fun speakImmediate(text: String) = speakFast(text, flushQueue = true)

    /**
     * Splits markdown/LLM text into clean sentence clauses for fast initial TTS playback.
     */
    fun splitIntoFastSpeechClauses(raw: String): List<String> {
        val clean = stripMarkdownForSpeech(raw)
        if (clean.isBlank()) return emptyList()
        return clean
            .split(Regex("(?<=[.!?。])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Discards queued synthesis and cuts the speaker immediately. */
    private fun flushPlayback() {
        generation.incrementAndGet()
        runCatching { tts?.stop() }
        playbackQueue.clear()
        synthesisStates.values.forEach { runCatching { it.file.delete() } }
        synthesisStates.clear()
        activeUtterances.clear()
        startedUtterances.clear()
        clearCaption()
        pendingUtteranceCount.set(0)
        synchronized(audioTrackLock) {
            audioTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.play() }
            }
            // playbackHeadPosition restarts after flush; resetting keeps drainTrack() consistent.
            framesWrittenToTrack = runCatching { audioTrack?.playbackHeadPosition?.toLong() ?: 0L }.getOrDefault(0L)
        }
    }

    fun stop() {
        pendingSpeechQueue.clear()
        val wasSpeaking = _isSpeaking.value
        flushPlayback()
        if (wasSpeaking) {
            lastPlaybackEndMs = System.currentTimeMillis()
        }
        setDucked(false)
        smoothedLevel = 0f
        _isSpeaking.value = false
        _ttsAudioLevel.value = 0f
    }

    fun stopSpeaking() = stop()

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {}
        playbackThread?.interrupt()
        playbackThread = null
        synchronized(audioTrackLock) {
            audioTrack?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            audioTrack = null
        }
    }

    internal fun stripMarkdownForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("⚠️ \\[Diagnostic:.*?\\]"), "")
            .replace(Regex("[—–]"), ", ")
            .replace(Regex("--+"), ", ")
            .replace(Regex("\\.{2,}"), ". ")
            .replace("::", ": ")
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]"), "")
            .replace(Regex("^[\\s*•\\-]+", RegexOption.MULTILINE), "")
            .replace(Regex("[*_`#>~|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
