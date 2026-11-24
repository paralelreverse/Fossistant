package org.fossistant.assistant

import org.fossistant.assistant.engine.WhisperMultilingualSttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Experimental Gemma voice input: recordings travel to the chat as a marked file path, as WAV. */
class GemmaVoiceInputTest {

    @Test
    fun voiceResultsAreRecognisedAndTextIsNot() {
        val result = WhisperMultilingualSttEngine.VOICE_AUDIO_PREFIX + "/data/cache/voice_clips/voice-1.wav"
        assertEquals("/data/cache/voice_clips/voice-1.wav", WhisperMultilingualSttEngine.voiceAudioPath(result))
        assertNull(WhisperMultilingualSttEngine.voiceAudioPath("turn on the flashlight"))
        assertNull(WhisperMultilingualSttEngine.voiceAudioPath("gemma-voice:/x.wav"))
    }

    @Test
    fun speechGateIgnoresBackgroundNoiseButNotASofterVoice() {
        val gate = WhisperMultilingualSttEngine.SpeechLevelGate()
        repeat(10) { assertTrue(gate.isSpeaking(isVoice = true, frameDb = 60f)) }  // the user talking
        assertTrue(gate.isSpeaking(isVoice = true, frameDb = 50f))                // trailing off a little
        assertFalse(gate.isSpeaking(isVoice = true, frameDb = 40f))               // a TV the detector calls voice
        assertFalse(gate.isSpeaking(isVoice = false, frameDb = 60f))              // not voice at all
        gate.reset()
        assertTrue(gate.isSpeaking(isVoice = true, frameDb = 40f))                // a new, quieter speaker
        assertEquals(1500L, WhisperMultilingualSttEngine.GEMMA_END_OF_SPEECH_MS)
    }

    @Test
    fun writesAPlayable16kMonoWav() {
        val samples = shortArrayOf(0, 1000, -1000, 32767)
        val wav = WhisperMultilingualSttEngine.pcm16ToWav(samples, samples.size, 16_000)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + samples.size * 2, header.getInt(4))
        assertEquals(1, header.getShort(22).toInt())        // mono
        assertEquals(16_000, header.getInt(24))             // sample rate
        assertEquals(16, header.getShort(34).toInt())       // bits per sample
        assertEquals(samples.size * 2, header.getInt(40))
        assertEquals(-1000, header.getShort(48).toInt())
    }
}
