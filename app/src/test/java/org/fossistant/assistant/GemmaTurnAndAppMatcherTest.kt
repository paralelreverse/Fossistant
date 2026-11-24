package org.fossistant.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.FastMultilingualTtsEngine
import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.fossistant.assistant.engine.MultimodalChunkProcessor
import org.fossistant.assistant.model.AiCharacterPersona
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.model.MediaAttachment
import org.fossistant.assistant.model.MediaModality
import org.fossistant.assistant.model.ModelTier
import org.fossistant.assistant.model.ResponseLengthPreference
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.FuzzyAppAndCommandMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GemmaTurnAndAppMatcherTest {

    private val tempMemoryDir = Files.createTempDirectory("fossistant_mem_test").toFile().apply { deleteOnExit() }
    private val memoryManager = AiMemoryManager(storageDir = tempMemoryDir)
    private val toolExecutor = DeviceAndWebToolExecutor(context = null)
    private val chunkProcessor = MultimodalChunkProcessor(context = null)
    private val gemmaEngine = GemmaLiteRtEngine(
        context = null,
        toolExecutor = toolExecutor,
        chunkProcessor = chunkProcessor,
        memoryManager = memoryManager
    )

    @Test
    fun `on-device AI memory system persists and recalls stored user facts without per-turn regex writes`() = runTest {
        memoryManager.clearAll()

        // Casual messages no longer auto-write to memory via regex on every turn.
        gemmaEngine.processUserTurn("Hey, what's up? Give it a call later").toList()
        assertTrue(memoryManager.facts.value.isEmpty())

        // Stored facts persist and are recalled.
        memoryManager.rememberFact("User's name is Lukas", "IDENTITY")
        memoryManager.rememberFact("User lives in Warsaw", "LOCATION")
        memoryManager.rememberFact("User's favorite programming language is Kotlin", "PREFERENCE")

        val reloadedManager = AiMemoryManager(storageDir = tempMemoryDir)
        assertEquals(3, reloadedManager.facts.value.size)

        val recallEmissions = gemmaEngine.processUserTurn("What do you remember about my name and my favorite programming language?").toList()
        val finalAnswer = recallEmissions.last().text
        assertTrue("Expected recalled fact 'Lukas' in response: $finalAnswer", finalAnswer.contains("Lukas"))
        assertTrue("Expected recalled fact 'Kotlin' in response: $finalAnswer", finalAnswer.contains("Kotlin"))
    }

    @Test
    fun `every language gets voice styles with a quick default`() {
        assertEquals("Expected 26 common denominator languages plus Automatic", 27, SupportedLanguage.entries.size)
        assertEquals(SupportedLanguage.POLISH, SupportedLanguage.fromCode("pl-PL"))
        assertEquals(SupportedLanguage.AUTO, SupportedLanguage.fromCode("auto"))
        for (lang in SupportedLanguage.entries) {
            val profiles = FastMultilingualTtsEngine.profilesForLanguage(lang)
            assertTrue("Expected at least 3 fast voice profiles for ${lang.displayName}", profiles.size >= 3)
            assertTrue(
                "The default voice for ${lang.displayName} should speak faster than 1.0",
                profiles.first().speechRate > 1.0f
            )
        }

        val engine = FastMultilingualTtsEngine(context = null)
        val clauses = engine.splitIntoFastSpeechClauses(
            "**Tool Executed**: Weather in Tokyo is 21°C. Have a wonderful afternoon! Ready for your next command?"
        )
        assertEquals(3, clauses.size)
        assertFalse(clauses.first().contains("**"))
    }

    @Test
    fun `blob mood themes, AI character personas, and shorter or longer response preferences customize output`() = runTest {
        assertTrue("Expected at least 6 blob mood themes", BlobMoodTheme.entries.size >= 6)

        gemmaEngine.updateCustomization(
            persona = AiCharacterPersona.SHARP_TECHNICAL,
            lengthPref = ResponseLengthPreference.SHORTER
        )
        val shorterPromptSys = gemmaEngine.buildSystemPromptText(enableThinking = true)
        assertTrue(shorterPromptSys.startsWith("<|think|>"))
        assertTrue(shorterPromptSys.contains("1 to 2 sentences maximum"))

        val shortReply = gemmaEngine.processUserTurn("Tell me about quantum computing").toList().last().text

        gemmaEngine.updateCustomization(
            persona = AiCharacterPersona.EMPATHETIC_COMPANION,
            lengthPref = ResponseLengthPreference.LONGER
        )
        val longReply = gemmaEngine.processUserTurn("Tell me about quantum computing").toList().last().text

        assertTrue(
            "Expected LONGER response (${longReply.length} chars) to be longer than SHORTER response (${shortReply.length} chars)",
            longReply.length > shortReply.length
        )
    }

    private fun bundledGemmaBytes(): Long =
        listOf(File("app/src/full/assets/models"), File("src/full/assets/models"))
            .firstOrNull { it.isDirectory }
            ?.listFiles { f -> f.name.startsWith("gemma-4-E2B-it.part") && f.name.endsWith(".litertlm") }
            ?.sumOf { it.length() } ?: 0L

    @Test
    fun `large text attachment chunks and ranks relevant passages within 8GB RAM budget`() = runTest {
        val largeDoc = buildString {
            repeat(30) { idx ->
                appendLine("Section $idx: General background filler text about mobile operating systems and UI layouts.")
            }
            appendLine("Section 31: LiteRT-LM executes Gemma 4 E2B QAT with Multi-Token Prediction drafters on mobile GPU.")
            repeat(20) { idx ->
                appendLine("Appendix $idx: Additional historical notes on battery chemistry.")
            }
        }

        val chunks = chunkProcessor.chunkLargeText(largeDoc, chunkSize = 400, overlap = 50)
        assertTrue("Expected multiple chunks for large document", chunks.size > 3)

        val topRelevant = chunkProcessor.selectTopRelevantChunks(
            chunks = chunks,
            query = "How does LiteRT-LM execute Multi-Token Prediction drafters?",
            topK = 2
        )
        assertEquals(2, topRelevant.size)
        assertTrue(topRelevant.any { it.contains("Multi-Token Prediction") })

        val attachment = MediaAttachment(
            id = "doc-1",
            uriString = "memory://doc.txt",
            fileName = "architecture_spec.txt",
            modality = MediaModality.LARGE_TEXT,
            mimeType = "text/plain",
            sizeBytes = largeDoc.length.toLong(),
            extractedTextPreview = largeDoc
        )
        val emissions = gemmaEngine.processUserTurn("Summarize the Multi-Token Prediction section", listOf(attachment)).toList()
        val finalMsg = emissions.last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        assertTrue(finalMsg.text.contains("Multi-Token Prediction"))
    }

    @Test
    fun `all legacy assistant features are tools Gemma can run`() = runTest {
        val testCases = listOf(
            "Play audiobook Project Hail Mary" to "play_audiobook",
            "Cast audiobook Dune" to "play_audiobook",
            "Set a music alarm for 7:15 am playing Lo-Fi Beats" to "set_media_alarm",
            "Show recipe for classic carbonara" to "manage_cookbook",
            "Start stopwatch" to "control_stopwatch",
            "Broadcast to family: Dinner is ready!" to "family_broadcast",
            "Send an email to sarah@example.com saying See you soon" to "send_email_or_media_message",
            "Reschedule team standup to tomorrow at 11am" to "reschedule_calendar_event",
            "Start driving mode to Work" to "driving_mode_action",
            "Schedule a Family Bell" to "manage_family_bell",
            "Meditate for 10 minutes" to "start_meditation",
            "Start my running workout" to "control_fitness_activity",
            "How did I sleep?" to "get_sleep_summary",
            "Video call Mom" to "make_duo_call",
            "How is my commute to work?" to "get_commute_estimate",
            "Check my flight status" to "check_flight_status",
            "What is John's contact info?" to "get_contact_info",
            "Make a reservation at Olive Garden" to "transactional_action"
        )

        for ((prompt, tool) in testCases) {
            val result = toolExecutor.execute(ToolCallRequest(tool))
            assertFalse("'$tool' (for '$prompt') is no longer a tool: ${result.summary}", result.summary.startsWith("Unknown tool"))
            assertTrue("Expected non-blank summary for '$tool'", result.summary.isNotBlank())
        }
    }

    @Test
    fun `Gemma special tokens are stripped from visible output and placeholder text is never emitted`() = runTest {
        val parsed = gemmaEngine.splitGemmaReasoningAndAnswer(
            rawOutput = "<think>\nReasoning trace</think>Here is the real answer.<|im_end|><eos>",
            enableThinking = true
        )
        assertEquals("Here is the real answer.", parsed.visibleAnswer)
        assertEquals("Reasoning trace", parsed.reasoningTrace)
        assertFalse(parsed.visibleAnswer.contains("<|im_end|>"))
        assertFalse(parsed.visibleAnswer.contains("<eos>"))

        val streamingThink = gemmaEngine.splitGemmaReasoningAndAnswer(
            rawOutput = "<think>\nAnalyzing step 1...",
            enableThinking = true
        )
        assertTrue(streamingThink.isStillThinking)
        assertEquals("Thinking...", streamingThink.visibleAnswer)

        val channelAnswer = gemmaEngine.splitGemmaReasoningAndAnswer(rawOutput = "Kotlin, Python and more.", enableThinking = true)
        assertFalse(channelAnswer.isStillThinking)
        assertEquals("Kotlin, Python and more.", channelAnswer.visibleAnswer)

        val reply = gemmaEngine.processUserTurn("Explain quantum entanglement simply").toList().last()
        assertFalse("Placeholder must never be emitted", reply.text.contains("I'm right here with you. Regarding"))
    }

    @Test
    fun `conversational greetings and capabilities generate real helpful responses without debug text`() = runTest {
        val helloTurn = gemmaEngine.processUserTurn("hello").toList().last()
        assertTrue(helloTurn.text.contains("Hello") || helloTurn.text.contains("Fossistant"))
        assertFalse(helloTurn.text.contains("processed your request"))

        val capabilitiesTurn = gemmaEngine.processUserTurn("what can you do").toList().last()
        assertTrue(capabilitiesTurn.text.contains("Open any installed app") || capabilitiesTurn.text.contains("alarms"))
        assertFalse(capabilitiesTurn.text.contains("processed your request"))
    }

    @Test
    fun `Whisper STT and Fast TTS compute real audio levels and clean Whisper blank markers`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)
        assertNotNull("Expected whisper-tiny.bin (the default) to resolve", stt.resolveOrExtractModelFile())
        assertNotNull(
            "Expected whisper-small-q5_1.bin to resolve",
            stt.resolveOrExtractModelFile(WhisperModelChoice.WHISPER_SMALL_Q5)
        )
        assertEquals("What time is it?", stt.cleanWhisperArtifacts(" [BLANK_AUDIO] What time is it? (inaudible) "))

        val silentSamples = ShortArray(1024) { 4 }
        val (_, silentNorm) = stt.computePcmLevels(silentSamples, silentSamples.size)
        assertEquals(0f, silentNorm, 0.01f)

        val speechSamples = ShortArray(1024) { idx -> if (idx % 2 == 0) 4200 else -4200 }
        val (speechDb, speechNorm) = stt.computePcmLevels(speechSamples, speechSamples.size)
        assertTrue("Expected speechDb > 60, got $speechDb", speechDb > 60f)
        assertTrue("Expected speechNorm > 0.5, got $speechNorm", speechNorm > 0.5f)

        val tts = FastMultilingualTtsEngine(context = null)
        val wordEnergy = tts.computeWordAcousticEnergy("Aurora")
        assertTrue("Expected non-zero word acoustic energy, got $wordEnergy", wordEnergy > 0.4f)
        tts.stop()
        assertFalse(tts.isSpeaking.value)
        assertEquals(0f, tts.ttsAudioLevel.value, 0.001f)
    }

    @Test
    fun `custom AI name, personality instructions, and dynamic blob color icon styles work properly`() = runTest {
        gemmaEngine.updateCustomization(
            persona = AiCharacterPersona.WITTY_CREATIVE,
            lengthPref = ResponseLengthPreference.SHORTER,
            customAiName = "Nova",
            customNotes = "Always greet me as Captain."
        )
        val sysPrompt = gemmaEngine.buildSystemPromptText(enableThinking = false)
        assertTrue(sysPrompt.contains("You are Nova"))
        assertTrue(sysPrompt.contains("Always greet me as Captain."))

        val greetingTurn = gemmaEngine.processUserTurn("hello").toList().last()
        assertTrue("Expected greeting to use custom AI name Nova, got: ${greetingTurn.text}", greetingTurn.text.contains("Nova"))

        val iconResIds = BlobMoodTheme.entries.map { theme ->
            org.fossistant.assistant.ui.AppIconSwitcher.drawableResForTheme(theme)
        }.toSet()
        assertEquals(6, iconResIds.size)
    }

    @Test
    fun `Gemma 4 E2B primary model resolves correct model file and system prompts`() = runTest {
        gemmaEngine.activeModel = org.fossistant.assistant.model.PrimaryLlmModel.GEMMA_4_E2B
        assertEquals("gemma-4-E2B-it.litertlm", gemmaEngine.activeModel.filename)
        assertTrue(bundledGemmaBytes() > 1_500_000_000L)

        val gemmaSys = gemmaEngine.buildSystemPromptText(enableThinking = true)
        assertTrue(gemmaSys.contains("on-device Android assistant"))
        assertFalse("no model or vendor name: it only costs tokens", gemmaSys.contains("Google"))

        val gemmaTurn = gemmaEngine.processUserTurn("Explain the laws of physics simply").toList().last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, gemmaTurn.modelTier)
        assertTrue(gemmaTurn.text.isNotBlank())
    }

    @Test
    fun `FuzzyAppAndCommandMatcher matches explicit app names without corrupting normal speech`() {
        val correctedFast = FuzzyAppAndCommandMatcher.autocorrectTranscript("open fast assistent please")
        assertTrue("Expected 'fossistant' in '$correctedFast'", correctedFast.contains("fossistant", ignoreCase = true))

        val correctedSpotfy = FuzzyAppAndCommandMatcher.autocorrectTranscript("play jazz on spotfy")
        assertTrue("Expected 'spotify' in '$correctedSpotfy'", correctedSpotfy.contains("spotify", ignoreCase = true))

        val correctedOsm = FuzzyAppAndCommandMatcher.autocorrectTranscript("open osm& and navigate")
        assertTrue("Expected 'osmand' in '$correctedOsm'", correctedOsm.contains("osmand", ignoreCase = true))

        // Unrelated words and sentences must not be rewritten into app/tool names.
        assertEquals("turn on flash lite", FuzzyAppAndCommandMatcher.autocorrectTranscript("turn on flash lite"))
        assertEquals("hey what's up", FuzzyAppAndCommandMatcher.autocorrectTranscript("hey what's up"))
        assertEquals("send a message", FuzzyAppAndCommandMatcher.autocorrectTranscript("send a message"))
        assertEquals("cam", FuzzyAppAndCommandMatcher.autocorrectTranscript("cam"))

        assertEquals(1, FuzzyAppAndCommandMatcher.levenshteinDistance("spotify", "spotfy"))

        val candidates = listOf(
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Fossistant", "org.fossistant.assistant"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Spotify", "com.spotify.music"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("WhatsApp", "com.whatsapp"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("OsmAnd", "net.osmand"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Google Maps", "com.google.android.apps.maps"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Notes", "org.example.notes")
        )

        assertEquals("org.fossistant.assistant", FuzzyAppAndCommandMatcher.findBestMatchCandidate("fast assistent", candidates)?.packageName)
        assertEquals("com.spotify.music", FuzzyAppAndCommandMatcher.findBestMatchCandidate("Spotify", candidates)?.packageName)
        assertEquals("com.spotify.music", FuzzyAppAndCommandMatcher.findBestMatchCandidate("spotfy", candidates)?.packageName)
        assertEquals("net.osmand", FuzzyAppAndCommandMatcher.findBestMatchCandidate("osm&", candidates)?.packageName)
        assertEquals("com.google.android.apps.maps", FuzzyAppAndCommandMatcher.findBestMatchCandidate("Google Maps", candidates)?.packageName)
        assertEquals("com.google.android.apps.maps", FuzzyAppAndCommandMatcher.findBestMatchCandidate("maps", candidates)?.packageName)

        // Casual phrases and unrelated words must be rejected instead of matching an arbitrary app.
        assertNull(FuzzyAppAndCommandMatcher.findBestMatchCandidate("hey what's up", candidates))
        assertNull(FuzzyAppAndCommandMatcher.findBestMatchCandidate("send a message", candidates))
        assertNull(FuzzyAppAndCommandMatcher.findBestMatchCandidate("cam", candidates))
        assertNull(FuzzyAppAndCommandMatcher.findBestMatchCandidate("random unrelated phrase", candidates))
        assertNull(FuzzyAppAndCommandMatcher.findBestMatchCandidate("read my random notes today", candidates))
    }

    @Test
    fun `fuzzy app matching resolves typos, punctuation, and app suffix`() {
        val candidates = listOf(
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Photos", "com.google.android.apps.photos"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Spotify", "com.spotify.music"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Fossistant", "org.fossistant.assistant"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Gallery", "com.simplemobiletools.gallery"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("OsmAnd", "net.osmand")
        )

        assertEquals("Photos", FuzzyAppAndCommandMatcher.findBestMatchCandidate("open phptos app.", candidates)?.label)
        assertEquals("Photos", FuzzyAppAndCommandMatcher.findBestMatchCandidate("opem photos app.", candidates)?.label)
        assertEquals("Fossistant", FuzzyAppAndCommandMatcher.findBestMatchCandidate("open fast assistent", candidates)?.label)
        assertEquals("Spotify", FuzzyAppAndCommandMatcher.findBestMatchCandidate("spotfy", candidates)?.label)
        assertEquals("Gallery", FuzzyAppAndCommandMatcher.findBestMatchCandidate("open gallary app", candidates)?.label)
        assertEquals("OsmAnd", FuzzyAppAndCommandMatcher.findBestMatchCandidate("osm&", candidates)?.label)
    }

    @Test
    fun `Whisper Tiny and Small Q5 exist and resolve valid files`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)
        val choices = WhisperModelChoice.entries
        assertEquals(listOf(WhisperModelChoice.WHISPER_TINY, WhisperModelChoice.WHISPER_SMALL_Q5), choices)
        assertEquals(WhisperModelChoice.WHISPER_TINY, stt.activeModel)

        for (choice in choices) {
            val file = stt.resolveOrExtractModelFile(choice)
            assertNotNull("Expected ${choice.filename} to resolve on disk", file)
            assertTrue("Expected ${choice.filename} size >= ${choice.minBytes}", file!!.length() >= choice.minBytes)
            assertTrue("Expected ramBadge to not be blank", choice.ramBadge.isNotBlank())
            assertTrue("Expected detailedExplanation to not be blank", choice.detailedExplanation.isNotBlank())
        }
    }

    @Test
    fun `direct phone calling and SMS tool execution return proper status`() {
        val callResult = toolExecutor.makePhoneCall("Mom")
        assertTrue(callResult.success)
        assertEquals("make_call", callResult.toolName)

        val smsResult = toolExecutor.sendSms("Dad", "I will be home at 6 PM")
        assertTrue(smsResult.success)
        assertEquals("send_sms", smsResult.toolName)
        assertTrue(smsResult.summary.contains("6 PM"))

        val navResult = toolExecutor.navigateTo("Central Park")
        assertTrue(navResult.success)
        assertEquals("navigate_to", navResult.toolName)

        val calResult = toolExecutor.createCalendarEvent("Doctor Appointment", null)
        assertTrue(calResult.success)
        assertEquals("create_calendar_event", calResult.toolName)
    }

    @Test
    fun `every command goes to Gemma, in any language`() = runTest {
        for (prompt in listOf("Play Daft Punk on Spotify", "Stell einen Wecker auf 7 Uhr", "otwórz zdjęcia")) {
            val finalMsg = gemmaEngine.processUserTurn(prompt).toList().last()
            assertEquals(prompt, ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        }
    }

    @Test
    fun `reasoning toggle ON runs Gemma with think token and reasoning phase`() = runTest {
        val emissions = gemmaEngine.processUserTurn(
            prompt = "Play Daft Punk on Spotify",
            enableReasoning = true
        ).toList()

        assertTrue("Expected multiple stream updates including reasoning phase", emissions.size >= 2)
        assertTrue("First emission should be in reasoning phase", emissions.first().isReasoningPhase)

        val finalMsg = emissions.last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        assertFalse(finalMsg.isReasoningPhase)
        assertNotNull("Expected reasoningTrace to be populated when Reasoning Toggle is ON", finalMsg.reasoningTrace)
        assertTrue(finalMsg.reasoningTrace!!.contains("<|think|>"))

        val parsed = gemmaEngine.splitGemmaReasoningAndAnswer(
            rawOutput = "<|channel>thought\nStep 1: Check user constraints.\nStep 2: Formulate concise answer.<channel|>Here is the final answer.",
            enableThinking = true
        )
        assertFalse(parsed.isStillThinking)
        assertEquals("Step 1: Check user constraints.\nStep 2: Formulate concise answer.", parsed.reasoningTrace)
        assertEquals("Here is the final answer.", parsed.visibleAnswer)
    }

    @Test
    fun `confirmation understands yes and no in other languages`() = runTest {
        val pending = org.fossistant.assistant.engine.PendingConfirmation(
            ToolCallRequest("set_flashlight", mapOf("enabled" to "true")),
            "Turn on the flashlight"
        )
        for (yes in listOf("Ja, bitte", "oui", "tak", "да")) {
            gemmaEngine.pendingConfirmation = pending
            val confirmed = gemmaEngine.processUserTurn(yes).toList().last()
            assertEquals(yes, ModelTier.DIRECT_ACTION, confirmed.modelTier)
            assertEquals(yes, "set_flashlight", confirmed.toolExecutions.firstOrNull()?.toolName)
        }
        for (no in listOf("Nein", "nie", "нет")) {
            gemmaEngine.pendingConfirmation = pending
            val declined = gemmaEngine.processUserTurn(no).toList().last()
            assertEquals(no, ModelTier.DIRECT_ACTION, declined.modelTier)
            assertTrue(no, declined.toolExecutions.isEmpty())
        }
        gemmaEngine.pendingConfirmation = pending
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, gemmaEngine.processUserTurn("Japan weather").toList().last().modelTier)
    }

    @Test
    fun `actions Gemma has not confirmed are never executed`() = runTest {
        val finalMsg = gemmaEngine.processUserTurn("Remind me to call Dr. Smith next Tuesday after lunch").toList().last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        assertTrue("Unconfirmed actions must not run, got ${finalMsg.toolExecutions.map { it.toolName }}", finalMsg.toolExecutions.isEmpty())
    }

    @Test
    fun `call proposed by Gemma runs only after the user says yes`() = runTest {
        val pending = org.fossistant.assistant.engine.PendingConfirmation(
            ToolCallRequest("set_flashlight", mapOf("enabled" to "true")),
            "Turn on the flashlight"
        )
        gemmaEngine.pendingConfirmation = pending
        val confirmed = gemmaEngine.processUserTurn("Yes please").toList().last()
        assertEquals("set_flashlight", confirmed.toolExecutions.firstOrNull()?.toolName)
        assertEquals(null, gemmaEngine.pendingConfirmation)

        gemmaEngine.pendingConfirmation = pending
        val declined = gemmaEngine.processUserTurn("No, don't").toList().last()
        assertTrue(declined.toolExecutions.isEmpty())
        assertTrue(declined.text.contains("won't"))

        gemmaEngine.pendingConfirmation = pending
        val other = gemmaEngine.processUserTurn("Explain how rainbows form").toList().last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, other.modelTier)
        assertEquals(null, gemmaEngine.pendingConfirmation)
    }

    @Test
    fun `Gemma tool set reports tools and gates calls behind confirmation`() {
        val finished = mutableListOf<org.fossistant.assistant.model.ToolExecutionResult>()
        var parked: org.fossistant.assistant.engine.PendingConfirmation? = null
        val toolSet = org.fossistant.assistant.engine.FossistantGemmaToolSet(
            executor = toolExecutor,
            memoryManager = memoryManager,
            onToolFinished = { finished.add(it) },
            onConfirmationNeeded = { parked = it }
        )

        val callReply = toolSet.makePhoneCall("Mom")
        assertTrue("Model must be told the call did not happen yet: $callReply", callReply.startsWith("Not done yet"))
        assertEquals("make_call", parked?.request?.toolName)
        assertEquals("Mom", parked?.request?.arguments?.get("target"))
        assertTrue(finished.last().awaitingConfirmation)

        toolSet.changeDeviceSetting("flashlight", "on", "")
        assertEquals("change_device_setting", finished.last().toolName)
        assertEquals("flashlight on", finished.last().displayDetail)

        toolSet.setTimer(300, "", "")
        assertEquals("5 min", finished.last().displayDetail)

        val unknown = toolSet.assistantFeature("teleport", "")
        assertTrue(unknown.startsWith("Error"))
    }

    @Test
    fun `system prompt carries voice rule, tool guidance, and no shortcut catalog dump`() {
        val text = gemmaEngine.buildSystemPromptText(toolGroups = setOf(GemmaLiteRtEngine.APP_ACTIONS_GROUP))
        gemmaEngine.isVoiceMode = true
        val voice = gemmaEngine.buildSystemPromptText(toolGroups = setOf(GemmaLiteRtEngine.APP_ACTIONS_GROUP))
        gemmaEngine.isVoiceMode = false
        assertEquals("switching voice mode must not change the prompt (no conversation rebuild)", text, voice)
        assertTrue(text.contains("${GemmaLiteRtEngine.VOICE_TAG} is spoken"))
        assertTrue(text.contains("read aloud"))
        assertTrue(text.contains("Web searches"))
        assertTrue(text.contains("action tool's reply"))
        assertTrue(text.contains("never open apps or trigger shortcuts"))
        assertTrue(text.contains("call find_app_actions first"))
        assertFalse(text.contains("App actions for run_app_action"))
    }

    @Test
    fun `Whisper hallucinations are filtered and endpointing uses decoded words`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine
        assertTrue(stt.isWhisperHallucination("Thank you."))
        assertTrue(stt.isWhisperHallucination("you you you you"))
        assertTrue(stt.isWhisperHallucination("   "))
        assertFalse(stt.isWhisperHallucination("Turn on the lights"))
        assertTrue(stt.isFillerUtterance("Hmm."))
        assertTrue(stt.isFillerUtterance("Um, uh..."))
        assertTrue(stt.isFillerUtterance("Mhm"))
        assertFalse(stt.isFillerUtterance("Hmm, what about tomorrow?"))
        assertFalse(stt.isFillerUtterance("Stop"))
        assertTrue(stt.computeDynamicSilenceTimeout("What time is it?") < stt.computeDynamicSilenceTimeout("Set a timer for"))
        assertTrue(stt.computeDynamicSilenceTimeout("What time is it?") <= 800L)

        val engine = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)
        assertEquals("And so my fellow Americans!", engine.cleanWhisperArtifacts("And so my fellow Americans! And so my fellow Americans!"))
        assertEquals("ask what you can do for your country", engine.cleanWhisperArtifacts("ask what you can do for your country"))
        assertEquals("no no no", engine.cleanWhisperArtifacts("no no no"))
    }

    @Test
    fun `self echo check never fires when the assistant has not spoken`() {
        val tts = FastMultilingualTtsEngine(context = null)
        assertFalse(tts.isLikelySelfEcho("stop please"))
        assertFalse(tts.isLikelySelfEcho("what is the weather in Paris"))
        assertFalse(tts.isEchoGuardActive())
    }
}
