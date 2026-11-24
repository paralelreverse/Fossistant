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
import org.fossistant.assistant.ui.overlay.CircleToSearchCropper
import org.fossistant.assistant.util.DeviceLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals(8192, org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B.startContextTokens)
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
        assertTrue(toolSet.smartHomeTools.smartHome("unlock", "front door", "", "").startsWith("Not done yet"))
        assertTrue(toolSet.smartHomeTools.smartHome("open", "garage door", "", "").startsWith("Not done yet"))
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
    fun `optional tool groups are declared apart and every action tool requires its reply`() {
        val toolSet = FossistantGemmaToolSet(toolExecutor, memoryManager, onToolFinished = {}, onConfirmationNeeded = {})
        fun toolNames(owner: Any) = owner::class.declaredMemberFunctions
            .filter { it.findAnnotation<com.google.ai.edge.litertlm.Tool>() != null }.map { it.name }.toSet()
        val core = toolNames(toolSet)
        assertEquals(setOf("smartHome"), toolNames(toolSet.smartHomeTools))
        assertEquals(setOf("useConnector"), toolNames(toolSet.connectorTools))
        assertEquals(setOf("findAppActions", "runAppAction"), toolNames(toolSet.appActionTools))
        assertEquals(setOf("controlScreen"), toolNames(toolSet.screenAgentTools))
        assertEquals(setOf("readNotifications", "replyNotification"), toolNames(toolSet.notificationTools))
        assertFalse(
            core.any {
                it in setOf(
                    "smartHome", "useConnector", "findAppActions", "runAppAction",
                    "controlScreen", "readNotifications", "replyNotification"
                )
            }
        )
        // Strict tool calls skip optional inputs, so reply must be required wherever it exists.
        toolSet.allToolSets.flatMap { it::class.declaredMemberFunctions }
            .filter { it.findAnnotation<com.google.ai.edge.litertlm.Tool>() != null }.forEach { function ->
            function.valueParameters.firstOrNull { it.name == "reply" }?.let {
                assertFalse("${function.name}: reply must be required", it.isOptional)
            }
        }
        // Calls Gemma wrote but LiteRT couldn't parse still reach every group.
        val invoker = org.fossistant.assistant.engine.ToolSetInvoker(toolSet)
        assertTrue(invoker.invoke("smart_home", mapOf("action" to "unlock", "device" to "front door")).startsWith("Not done yet"))
        assertTrue(invoker.invoke("set_timer", mapOf("seconds" to "300", "label" to "", "reply" to "Timer set.")).isNotBlank())
        assertTrue(invoker.invoke("no_such_tool", emptyMap()).startsWith("Error"))
    }

    @Test
    fun `weather reaches Gemma in words, not symbols`() {
        val sentence = org.fossistant.assistant.tools.weatherSentence("Warsaw (Poland)", "light rain", 21.4, 90, 9.6)
        assertEquals("Weather in Warsaw (Poland): light rain, 21 degrees Celsius, humidity 90 percent, wind 10 kilometres per hour.", sentence)
        assertFalse(sentence.contains("%") || sentence.contains("°") || sentence.contains("km/h"))
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

    @Test
    fun `newly exposed tools and screen control are wired with confirmations and step limits`() {
        val finished = mutableListOf<ToolExecutionResult>()
        val parked = mutableListOf<PendingConfirmation>()
        val toolSet = FossistantGemmaToolSet(
            executor = toolExecutor,
            memoryManager = memoryManager,
            onToolFinished = { finished.add(it) },
            onConfirmationNeeded = { parked.add(it) }
        )
        val invoker = org.fossistant.assistant.engine.ToolSetInvoker(toolSet)

        // Direct @Tool methods: reply_notification & reschedule_calendar_event
        assertTrue(invoker.invoke("reply_notification", mapOf("sender" to "Alex", "message" to "On my way")).startsWith("Not done yet"))
        assertEquals("reply_notification", parked.last().request.toolName)
        assertEquals("Alex", parked.last().request.arguments["sender"])

        val rescheduled = invoker.invoke(
            "reschedule_calendar_event",
            mapOf("title" to "Standup", "newTime" to "tomorrow 11am", "shiftMinutes" to "30", "reply" to "Moved Standup.")
        )
        assertFalse(rescheduled.startsWith("Error"))

        // assistant_feature routes all remaining executor capabilities
        listOf(
            "in_app_search" to "Spotify: Daft Punk",
            "global_app_search" to "receipt",
            "direct_action" to "compose",
            "media_alarm" to "07:15: Lo-Fi Beats",
            "family_bell" to "18:30: Dinner time",
            "reschedule_calendar_event" to "Sync: tomorrow 2pm"
        ).forEach { (feature, details) ->
            val out = toolSet.assistantFeature(feature, details)
            assertFalse("$feature returned unknown feature error: $out", out.contains("unknown feature", ignoreCase = true))
        }
        assertTrue(toolSet.assistantFeature("transactional_action", "payment: \$25 to Alex").startsWith("Not done yet"))
        assertEquals("transactional_action", parked.last().request.toolName)
        assertTrue(toolSet.assistantFeature("reply_notification", "Sam: Sounds good").startsWith("Not done yet"))
        assertEquals("reply_notification", parked.last().request.toolName)
        // device_location runs without erroring as unknown feature (reports unavailable in context=null)
        assertFalse(toolSet.assistantFeature("device_location", "").contains("unknown feature", ignoreCase = true))

        // Screen automation: sensitive clicks confirm; step limit stops at 12 and resets on startTurn()
        toolSet.startTurn()
        assertTrue(invoker.invoke("control_screen", mapOf("action" to "click", "targetText" to "Pay now")).startsWith("Not done yet"))
        assertEquals("screen_agent_action", parked.last().request.toolName)
        repeat(11) {
            val step = toolSet.screenAgentTools.controlScreen("read_screen", "")
            assertFalse("Step ${it + 2} should succeed: $step", step.startsWith("Error: step limit"))
        }
        val thirteenth = toolSet.screenAgentTools.controlScreen("read_screen", "")
        assertTrue("13th screen action must hit step limit: $thirteenth", thirteenth.startsWith("Error: step limit"))
        toolSet.startTurn()
        assertFalse(toolSet.screenAgentTools.controlScreen("read_screen", "").startsWith("Error: step limit"))
    }

    @Test
    fun `Whisper STT computeKind reflects hardware backend preference`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)
        stt.setHardwareBackend(org.fossistant.assistant.model.HardwareInferenceBackend.AUTO)
        assertEquals("GPU/CPU (Auto)", stt.computeKind)
        stt.setHardwareBackend(org.fossistant.assistant.model.HardwareInferenceBackend.GPU)
        assertEquals("GPU (Vulkan)", stt.computeKind)
        stt.setHardwareBackend(org.fossistant.assistant.model.HardwareInferenceBackend.CPU)
        assertEquals("CPU", stt.computeKind)
    }

    @Test
    fun `circleToSearchBoundingRectComputesAndClampsValidStroke`() {
        val nearEdge = CircleToSearchCropper.boundingRectFromPoints(
            points = listOf(10f to 20f, 300f to 20f, 300f to 400f, 10f to 400f),
            canvasWidth = 1000f,
            canvasHeight = 2000f,
            paddingFraction = 0.04f
        )
        assertNotNull(nearEdge)
        nearEdge!!
        assertEquals(0f, nearEdge.left, 0.0001f)
        assertEquals(0f, nearEdge.top, 0.0001f)
        assertEquals(0.34f, nearEdge.right, 0.0001f)
        assertEquals(0.24f, nearEdge.bottom, 0.0001f)
        assertTrue(nearEdge.isValid())

        // A horizontal highlight across a single text line should produce a valid strip crop.
        val highlight = CircleToSearchCropper.boundingRectFromPoints(
            points = listOf(200f to 500f, 700f to 504f),
            canvasWidth = 1000f,
            canvasHeight = 2000f,
            paddingFraction = 0.04f
        )
        assertNotNull(highlight)
        assertTrue(highlight!!.isValid())
        assertTrue(highlight.width > 0.50f)
    }

    @Test
    fun `circleToSearchBoundingRectRejectsTinyTapOrSinglePoint`() {
        assertNull(CircleToSearchCropper.boundingRectFromPoints(emptyList(), 1000f, 2000f))
        assertNull(CircleToSearchCropper.boundingRectFromPoints(listOf(100f to 100f), 1000f, 2000f))
        assertNull(CircleToSearchCropper.boundingRectFromPoints(listOf(100f to 100f, 300f to 300f), 0f, 2000f))
        assertNull(
            CircleToSearchCropper.boundingRectFromPoints(
                points = listOf(500f to 500f, 510f to 505f),
                canvasWidth = 1000f,
                canvasHeight = 2000f
            )
        )
        assertNull(
            CircleToSearchCropper.boundingRectFromPoints(
                points = listOf(Float.NaN to 100f, 200f to 200f),
                canvasWidth = 1000f,
                canvasHeight = 2000f
            )
        )
    }

    @Test
    fun `circleToSearchPixelCropRectComputesAndEnforcesMinDimensions`() {
        val exact = CircleToSearchCropper.pixelCropRect(
            norm = CircleToSearchCropper.NormalizedRect(0.25f, 0.25f, 0.75f, 0.50f),
            imageWidth = 1080,
            imageHeight = 2400
        )
        assertNotNull(exact)
        assertArrayEquals(intArrayOf(270, 600, 540, 600), exact)

        val clamped = CircleToSearchCropper.pixelCropRect(
            norm = CircleToSearchCropper.NormalizedRect(0.95f, 0.95f, 0.985f, 0.985f),
            imageWidth = 1080,
            imageHeight = 2400,
            minSidePx = 64
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(64, clamped[2])
        assertTrue(clamped[0] >= 0 && clamped[0] + clamped[2] <= 1080)
        assertTrue(clamped[1] >= 0 && clamped[1] + clamped[3] <= 2400)

        assertNull(
            CircleToSearchCropper.pixelCropRect(
                norm = CircleToSearchCropper.NormalizedRect(0.1f, 0.1f, 0.11f, 0.11f),
                imageWidth = 1080,
                imageHeight = 2400
            )
        )
    }

    @Test
    fun `sharedHttpPrivateOrLanUrl and sha256 work as expected`() {
        assertTrue(org.fossistant.assistant.util.SharedHttp.isPrivateOrLanUrl("http://192.168.1.50:8123/api/mcp"))
        assertTrue(org.fossistant.assistant.util.SharedHttp.isPrivateOrLanUrl("http://homeassistant.local:8123"))
        assertTrue(org.fossistant.assistant.util.SharedHttp.isPrivateOrLanUrl("http://localhost:8080"))
        assertFalse(org.fossistant.assistant.util.SharedHttp.isPrivateOrLanUrl("http://example.com/api"))

        val f = java.io.File(tempDir, "hello.txt").apply { writeText("hello") }
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            org.fossistant.assistant.util.ResumableFileDownloader.sha256(f)
        )
    }

    @Test
    fun `connectorManager refuses public cleartext bearer token and preserves lazy stores`() = runTest {
        val dir = Files.createTempDirectory("fossistant_connector_test").toFile().apply { deleteOnExit() }
        val store = org.fossistant.assistant.features.JsonListStore(
            dir,
            "connectors.json",
            org.fossistant.assistant.features.Connector.serializer(),
            { it.id }
        )
        val manager = org.fossistant.assistant.features.ConnectorManager(store) {}
        val publicCleartext = org.fossistant.assistant.features.Connector(
            name = "PublicHTTP",
            url = "http://example.com/mcp",
            token = "top-secret-token"
        )
        val refreshed = manager.refresh(publicCleartext)
        assertTrue("Expected HTTPS refusal, got: ${refreshed.lastError}", refreshed.lastError.contains("HTTPS", ignoreCase = true))

        // Lazy stores defer disk read until loadIfNeeded() is called.
        val eagerMem = AiMemoryManager(storageDir = dir)
        eagerMem.rememberFact("User prefers dark mode", "PREFERENCE")
        val lazyMem = AiMemoryManager(storageDir = dir, eagerLoad = false)
        assertTrue(lazyMem.facts.value.isEmpty())
        lazyMem.loadIfNeeded()
        assertEquals(listOf("User prefers dark mode"), lazyMem.facts.value.map { it.fact })
    }

    @Test
    fun `calendar reschedule parser and default empty family bell list work`() {
        val bridge = org.fossistant.assistant.tools.AppShortcutsAndAssistantApiBridge(context = null)
        assertTrue(bridge.manageFamilyBell("list", "", "").contains("No Family Bell"))

        val now = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 20, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oldStart = now + 60 * 60_000L // 10:00 today
        val inTwoHours = org.fossistant.assistant.tools.AppShortcutsAndAssistantApiBridge.parseRescheduleTargetMs(
            oldStartMs = oldStart,
            newTimeDescription = "in 2 hours",
            shiftMinutes = 60,
            nowMs = now
        )
        assertEquals(now + 2 * 3_600_000L, inTwoHours)

        val tomorrow11 = org.fossistant.assistant.tools.AppShortcutsAndAssistantApiBridge.parseRescheduleTargetMs(
            oldStartMs = oldStart,
            newTimeDescription = "tomorrow 11am",
            shiftMinutes = 60,
            nowMs = now
        )
        val expectedTomorrow11 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 21, 11, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedTomorrow11, tomorrow11)
    }

    @Test
    fun `cleanSpecialTokens fast path and reasoning tag stripping preserve normal text`() {
        val engine = GemmaLiteRtEngine(null, toolExecutor, MultimodalChunkProcessor(null), memoryManager)
        // Fast path (no '<' character)
        assertEquals("Hello, world! 2 + 2 = 4", engine.cleanSpecialTokens("  Hello, world! 2 + 2 = 4  "))
        // Regex path with multiple control tokens
        assertEquals(
            "Done.",
            engine.cleanSpecialTokens("<|im_start|><|think|><think>Done.<end_of_turn><|eot_id|><eos><|turn|><turn|></think><|im_end|>")
        )
        // Non-control angle brackets are preserved
        assertEquals("x < y and a > b", engine.cleanSpecialTokens("x < y and a > b"))
        // Channel thought parsing strips trailing special tokens
        val parsed = engine.splitGemmaReasoningAndAnswer("<|channel>thought\nstep 1<channel|>Final answer.<end_of_turn>", enableThinking = true)
        assertEquals("step 1", parsed.reasoningTrace)
        assertEquals("Final answer.", parsed.visibleAnswer)
        assertFalse(parsed.isStillThinking)
    }
}
