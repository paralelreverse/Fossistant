package org.fossistant.assistant

import org.fossistant.assistant.engine.GemmaToolCallRecovery
import org.fossistant.assistant.engine.GemmaToolCallRecovery.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaToolCallRecoveryTest {

    @Test
    fun recoversTheBareValueCallFromThePhone() {
        // The error LiteRT-LM 0.19 threw for "uh... stop the music" (shortened).
        val message = "Status Code: 3. Message: Failed to parse tool calls from code block: call:control_media{action:stop}\n" +
            "full response: <|tool_call>call:control_media{action:stop}<tool_call|>\n" +
            "error: Failed to parse FC tool calls: FallThrough(ParseCancelledError(NoAltError(...)))"
        assertEquals(listOf(Call("control_media", mapOf("action" to "stop"))), GemmaToolCallRecovery.parseFromError(message))
    }

    @Test
    fun readsDelimitedStringsNumbersAndMixedValues() {
        val calls = GemmaToolCallRecovery.parseCalls(
            "call:set_alarm{hour:7,minute:30,label:<|\"|>Gym, then work<|\"|>} call:control_media{action:play,query:Daft Punk}"
        )
        assertEquals(
            listOf(
                Call("set_alarm", mapOf("hour" to "7", "minute" to "30", "label" to "Gym, then work")),
                Call("control_media", mapOf("action" to "play", "query" to "Daft Punk"))
            ),
            calls
        )
    }

    @Test
    fun keepsCommasInsideABareSentence() {
        val calls = GemmaToolCallRecovery.parseCalls("call:create_note{title:Shopping,content:milk, eggs, bread}")
        assertEquals(mapOf("title" to "Shopping", "content" to "milk, eggs, bread"), calls.single().arguments)
    }

    @Test
    fun ignoresOtherErrors() {
        assertTrue(GemmaToolCallRecovery.parseFromError("Status Code: 13. Message: context full").isEmpty())
        assertTrue(GemmaToolCallRecovery.parseFromError(null).isEmpty())
    }
}
