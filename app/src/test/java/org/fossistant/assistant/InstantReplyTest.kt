package org.fossistant.assistant

import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.FossistantGemmaToolSet
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Action tools hand Gemma's pre-written reply to the engine the moment they succeed. */
class InstantReplyTest {

    private val finished = mutableListOf<ToolExecutionResult>()
    private val replies = mutableListOf<String>()
    private var answered = false
    private val toolSet = FossistantGemmaToolSet(
        DeviceAndWebToolExecutor(context = null),
        AiMemoryManager(storageDir = Files.createTempDirectory("fossistant_reply_test").toFile().apply { deleteOnExit() }),
        onToolFinished = { finished.add(it) },
        onConfirmationNeeded = {},
        onInstantReply = { replies.add(it) },
        isTurnAnswered = { answered }
    )

    @Test
    fun successfulActionsGiveTheirReply() {
        toolSet.changeDeviceSetting("flashlight", "on", " Flashlight on. ")
        toolSet.setTimer(60, "", "Timer set for a minute.")
        assertEquals(listOf("Flashlight on.", "Timer set for a minute."), replies)
        assertEquals(2, finished.size)
    }

    @Test
    fun noReplyLeavesTheAnswerToGemma() {
        toolSet.changeDeviceSetting("flashlight", "on", "")
        assertTrue(replies.isEmpty())
    }

    @Test
    fun informationSubActionsIgnoreTheReply() {
        toolSet.phoneAction("battery", "Your battery is fine.")
        toolSet.controlMedia("whats_playing", "", reply = "A song is playing.")
        toolSet.controlStopwatch("status", "It's running.")
        assertTrue(replies.isEmpty())
        toolSet.controlMedia("pause", "", reply = "Paused.")
        assertEquals(listOf("Paused."), replies)
    }

    @Test
    fun failedActionsNeverShowTheirReply() {
        val temporaryChat = FossistantGemmaToolSet(
            DeviceAndWebToolExecutor(context = null),
            AiMemoryManager(storageDir = Files.createTempDirectory("fossistant_reply_test").toFile().apply { deleteOnExit() }),
            onToolFinished = { finished.add(it) },
            onConfirmationNeeded = {},
            isTemporaryChat = { true },
            onInstantReply = { replies.add(it) }
        )
        val result = temporaryChat.rememberUserFact("Likes tea", "Got it, I'll remember that.")
        assertTrue(result, result.startsWith("Error:"))
        assertTrue(replies.isEmpty())
    }

    @Test
    fun toolsDoNothingOnceTheTurnIsAnswered() {
        answered = true
        val result = toolSet.changeDeviceSetting("flashlight", "off", "Flashlight off.")
        assertTrue(result, result.startsWith("Already done"))
        assertTrue(finished.isEmpty())
        assertTrue(replies.isEmpty())
        assertTrue(toolSet.makePhoneCall("Mom").startsWith("Already done"))
    }
}
