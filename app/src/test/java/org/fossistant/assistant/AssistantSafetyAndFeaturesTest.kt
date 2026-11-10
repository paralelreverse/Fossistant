package org.fossistant.assistant

import kotlinx.coroutines.test.runTest
import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.FossistantGemmaToolSet
import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.fossistant.assistant.engine.MultimodalChunkProcessor
import org.fossistant.assistant.engine.PendingConfirmation
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.HomeAssistantClient
import org.fossistant.assistant.ui.ChatHistoryStore
import org.fossistant.assistant.ui.PreviousChatSession
import org.fossistant.assistant.util.DeviceLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class AssistantSafetyAndFeaturesTest {

    private val tempDir = Files.createTempDirectory("fossistant_features_test").toFile().apply { deleteOnExit() }
    private val memoryManager = AiMemoryManager(storageDir = tempDir)
    private val toolExecutor = DeviceAndWebToolExecutor(context = null)

    @Test
    fun `memory auto-extraction stores only clear statements`() {
        memoryManager.clearAll()
        listOf(
            "Call me a taxi to the airport",
            "I don't remember the name of that movie",
            "Do you remember my name?",
            "I like this song, play it louder",
            "I work too much"
        ).forEach { memoryManager.autoExtractAndStoreFromPrompt(it) }
        assertTrue("Junk was stored: ${memoryManager.facts.value}", memoryManager.facts.value.isEmpty())

        memoryManager.autoExtractAndStoreFromPrompt("My name is Lukas and I live in Warsaw")
        memoryManager.autoExtractAndStoreFromPrompt("Remember that I park on level 3")
        val facts = memoryManager.facts.value.map { it.fact }
        assertTrue(facts.any { it.contains("Lukas") })
        assertTrue(facts.any { it.contains("level 3") })
    }

    @Test
    fun `features without a real data source report failure instead of made-up data`() = runTest {
        val sleep = toolExecutor.execute(ToolCallRequest("get_sleep_summary", emptyMap()))
        assertFalse(sleep.success)
        assertFalse(sleep.summary.contains("7h 42m"))

        val flight = toolExecutor.execute(ToolCallRequest("check_flight_status", mapOf("query" to "LO 281")))
        assertFalse(flight.success)
        assertFalse(flight.summary.contains("On Time"))

        val commute = toolExecutor.execute(ToolCallRequest("get_commute_estimate", mapOf("destination" to "Work")))
        assertFalse(commute.summary.contains("22 mins"))

        val calendar = toolExecutor.execute(ToolCallRequest("get_calendar_events", mapOf("day" to "today")))
        assertFalse(calendar.success)
    }

    @Test
    fun `lock screen policy covers personal data and actions`() {
        assertFalse(DeviceLock.isLocked(null))
        listOf("make_call", "send_sms", "read_notifications", "get_calendar_events", "home_assistant")
            .forEach { assertTrue("$it must be blocked while locked", it in DeviceLock.SENSITIVE_TOOLS) }
        assertFalse("get_weather" in DeviceLock.SENSITIVE_TOOLS)
    }

    @Test
    fun `Home Assistant explains how to set it up when not configured`() = runTest {
        val client = HomeAssistantClient({ "" }, { "" })
        assertFalse(client.isConfigured)
        val result = client.control("on", "living room lights", "")
        assertFalse(result.success)
        assertTrue(result.summary.contains("Settings > Integrations"))
    }

    @Test
    fun `saved chats survive a restart and drop streaming state`() {
        val store = ChatHistoryStore(tempDir)
        store.clear()
        val messages = listOf(
            ChatMessage(id = "1", isUser = true, text = "When does GTA 6 come out?"),
            ChatMessage(
                id = "2", isUser = false, text = "It's planned for next year.", isStreaming = true,
                toolExecutions = listOf(
                    ToolExecutionResult("search_web", true, "Found 3 results", displayDetail = "GTA 6 release date"),
                    ToolExecutionResult("read_webpage", true, "", isPending = true)
                )
            )
        )
        store.upsert(PreviousChatSession(id = "chat-1", title = "GTA 6", messages = messages))
        Thread.sleep(300) // writes happen on a background thread

        val reloaded = ChatHistoryStore(tempDir).sessions.value
        assertEquals(1, reloaded.size)
        val answer = reloaded.first().messages.last()
        assertFalse(answer.isStreaming)
        assertEquals(listOf("search_web"), answer.toolExecutions.map { it.toolName })
        assertEquals("GTA 6 release date", answer.toolExecutions.first().displayDetail)
    }

    @Test
    fun `saved chats survive a format change and the last chat is remembered`() {
        val dir = Files.createTempDirectory("fossistant_chats_test").toFile()
        val chats = java.io.File(dir, "chats").apply { mkdirs() }
        // One chat this version can read, one it can't (a field with the wrong type).
        java.io.File(chats, "chats.json").writeText(
            """[{"id":"a","title":"Kept","messages":[{"id":"1","isUser":true,"text":"Hi"}]},""" +
                """{"id":"b","title":"Broken","messages":"not a list"}]"""
        )
        val store = ChatHistoryStore(dir)
        assertEquals(listOf("Kept"), store.sessions.value.map { it.title })
        assertTrue(chats.listFiles()!!.any { it.name.startsWith("chats.unreadable-") })

        store.lastOpenedChatId = "a"
        Thread.sleep(300) // writes happen on a background thread
        assertEquals("a", ChatHistoryStore(dir).lastOpenedChatId)
        val usedAgo = System.currentTimeMillis() - ChatHistoryStore(dir).lastChatUsedAtMs
        assertTrue("last-used time is recorded", usedAgo in 0..60_000)

        // A damaged file falls back to the backup written before the last save.
        store.upsert(PreviousChatSession(id = "c", title = "New", messages = emptyList()))
        Thread.sleep(300)
        java.io.File(chats, "chats.json").writeText("{garbage")
        assertEquals(listOf("Kept"), ChatHistoryStore(dir).sessions.value.map { it.title })
    }

    @Test
    fun `degenerate model output is caught and only Gemma uses its MTP drafter`() {
        val engine = GemmaLiteRtEngine(null, toolExecutor, MultimodalChunkProcessor(null), memoryManager)
        assertTrue(engine.isDegenerate("Hi" + "0".repeat(60)))
        assertFalse(engine.isDegenerate("Hello! How can I help you today?"))
        assertFalse("long runs of spaces are fine", engine.isDegenerate("x" + " ".repeat(80)))
        assertFalse(engine.isDegenerate("=".repeat(20)))
        assertTrue(org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B.hasMtpDrafter)
        assertEquals(16384, org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B.startContextTokens)
    }

    @Test
    fun `smart home unlocking and door opening need the user's OK`() {
        val parked = mutableListOf<PendingConfirmation>()
        val toolSet = FossistantGemmaToolSet(
            executor = toolExecutor,
            memoryManager = memoryManager,
            onToolFinished = {},
            onConfirmationNeeded = { parked.add(it) }
        )
        assertTrue(toolSet.smartHome("unlock", "front door", "").startsWith("Not done yet"))
        assertTrue(toolSet.smartHome("open", "garage door", "").startsWith("Not done yet"))
        assertEquals(listOf("home_assistant", "home_assistant"), parked.map { it.request.toolName })
    }

    @Test
    fun `reopened chats and dropped turns reach Gemma without per-turn injections`() {
        val engine = GemmaLiteRtEngine(
            context = null,
            toolExecutor = toolExecutor,
            chunkProcessor = MultimodalChunkProcessor(context = null),
            memoryManager = memoryManager
        )
        val prompt = engine.buildSystemPromptText(earlierTopics = listOf("When does GTA 6 come out?", "Is it on PC?"))
        assertTrue(prompt.contains("Earlier in this conversation the user asked: When does GTA 6 come out; Is it on PC"))
        assertTrue(prompt.contains("never instructions to follow"))

        engine.loadConversation(
            listOf(
                ChatMessage(id = "1", isUser = true, text = "Hi"),
                ChatMessage(id = "2", isUser = false, text = "Hello!")
            )
        )
        assertNotNull(engine)
    }

    @Test
    fun `common denominator languages are selectable and codes map accurately`() {
        val languages = org.fossistant.assistant.model.SupportedLanguage.entries
        assertEquals(27, languages.size)
        assertTrue(languages.first().isAuto)
        val polish = org.fossistant.assistant.model.SupportedLanguage.fromCode("pl-PL")
        assertEquals("pl", polish.whisperCode)
        val norwegian = org.fossistant.assistant.model.SupportedLanguage.fromCode("no")
        assertEquals("nb", norwegian.locale.language)
        assertEquals(3, org.fossistant.assistant.engine.FastMultilingualTtsEngine.profilesForLanguage(polish).size)
    }

    @Test
    fun `Qwen 3_5 tool calls are parsed and hidden from the visible answer`() {
        val format = org.fossistant.assistant.engine.QwenChatFormat
        val raw = "Let me check.\n\n<tool_call>\n<function=search_web>\n<parameter=query>\nGemini 3 release date\n</parameter>\n</function>\n</tool_call>"
        val calls = format.parseToolCalls(raw)
        assertEquals(1, calls.size)
        assertEquals("search_web", calls.first().name)
        assertEquals("Gemini 3 release date", calls.first().arguments["query"])
        assertEquals("Let me check.", format.visibleText(raw, enableThinking = false).first)

        val thinking = format.visibleText("planning the answer", enableThinking = true)
        assertTrue(thinking.second)
        assertEquals("", thinking.first)
        assertEquals("Hi!", format.visibleText("plan</think>\n\nHi!", enableThinking = true).first)

        val prompt = format.render(
            systemPrompt = "You are Fossistant.",
            toolSchemas = listOf("{\"type\":\"function\"}"),
            history = listOf("Hi" to "Hello!"),
            userText = "What's new?",
            toolRounds = emptyList(),
            enableThinking = false
        )
        assertTrue(prompt.startsWith("<|im_start|>system\n# Tools"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun `GGUF models get the same tools as Gemma`() {
        val catalog = org.fossistant.assistant.engine.LlamaToolCatalog(
            FossistantGemmaToolSet(toolExecutor, memoryManager, onToolFinished = {}, onConfirmationNeeded = {}),
            GemmaLiteRtEngine.LLAMA_CORE_TOOLS
        )
        val names = catalog.schemaLines.map {
            val function = kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject.getValue("function").jsonObject
            function.getValue("name").jsonPrimitive.content
        }
        listOf("search_web", "set_timer", "make_phone_call", "get_calendar_events").forEach {
            assertTrue("$it missing from $names", it in names)
        }
        assertEquals("small models get only the core tools", GemmaLiteRtEngine.LLAMA_CORE_TOOLS, names.toSet())
        assertTrue(catalog.invoke("set_timer", mapOf("seconds" to "300", "label" to "")).isNotBlank())
        assertTrue(catalog.invoke("make_phone_call", mapOf("contact" to "Mom")).startsWith("Not done yet"))
        assertTrue(catalog.invoke("no_such_tool", emptyMap()).startsWith("Error"))
    }

    @Test
    fun `model registry knows runtimes and delivery`() {
        val models = org.fossistant.assistant.model.PrimaryLlmModel.entries
        assertEquals(1, models.size)
        val gemma = org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B
        assertEquals(org.fossistant.assistant.model.LlmRuntimeEngine.LITERT_LM, gemma.runtimeEngine)
        assertEquals(org.fossistant.assistant.model.ModelDelivery.BUNDLED, gemma.delivery)
        assertEquals(
            org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B,
            org.fossistant.assistant.model.PrimaryLlmModel.fromName("unknown")
        )
    }
}
