package org.fossistant.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.DualModelRouter
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
import org.fossistant.assistant.model.WhisperModelChoice
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.FuzzyAppAndCommandMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DualModelRouterTest {

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
    private val router = DualModelRouter(
        gemmaEngine = gemmaEngine,
        toolExecutor = toolExecutor
    )

    @Test
    fun `on-device AI memory system extracts, persists, and recalls user facts across turns`() = runTest {
        memoryManager.clearAll()

        // Turn 1: User shares personal facts naturally in conversation
        router.processUserTurn("My name is Lukas and I live in Warsaw").toList()
        router.processUserTurn("Remember that my favorite programming language is Kotlin").toList()

        val storedFacts = memoryManager.facts.value
        assertTrue("Expected at least 2 extracted facts, got: $storedFacts", storedFacts.size >= 2)
        assertTrue(storedFacts.any { it.fact.contains("Lukas", ignoreCase = true) })
        assertTrue(storedFacts.any { it.fact.contains("Kotlin", ignoreCase = true) })

        // Verify persistence by reloading a fresh AiMemoryManager instance from the same directory
        val reloadedManager = AiMemoryManager(storageDir = tempMemoryDir)
        assertEquals(storedFacts.size, reloadedManager.facts.value.size)

        // Turn 2: Ask the model what it remembers
        val recallEmissions = router.processUserTurn("What do you remember about my name and my favorite programming language?").toList()
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

        // Test SHORTER + SHARP_TECHNICAL customization
        gemmaEngine.updateCustomization(
            persona = AiCharacterPersona.SHARP_TECHNICAL,
            lengthPref = ResponseLengthPreference.SHORTER
        )
        val shorterPromptSys = gemmaEngine.buildSystemPromptText(enableThinking = true)
        assertTrue(shorterPromptSys.startsWith("<|think|>"))
        assertTrue(shorterPromptSys.contains("1 to 2 sentences maximum"))

        val shortReply = router.processUserTurn("Tell me about quantum computing").toList().last().text

        // Test LONGER + EMPATHETIC_COMPANION customization
        gemmaEngine.updateCustomization(
            persona = AiCharacterPersona.EMPATHETIC_COMPANION,
            lengthPref = ResponseLengthPreference.LONGER
        )
        val longReply = router.processUserTurn("Tell me about quantum computing").toList().last().text

        assertTrue(
            "Expected LONGER response (${longReply.length} chars) to be longer than SHORTER response (${shortReply.length} chars)",
            longReply.length > shortReply.length
        )
    }

    /**
     * Gemma ships in the full APK split into `gemma-4-E2B-it.partN.litertlm` (assets over 2 GB can't be packaged);
     * the app joins them when it unpacks. Total size of the parts in the project, or 0.
     */
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
        val emissions = router.processUserTurn("Summarize the Multi-Token Prediction section", listOf(attachment)).toList()
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

        // Gemma picks the tool from the request (in any language); here we check every tool still exists.
        for ((prompt, tool) in testCases) {
            val result = toolExecutor.execute(org.fossistant.assistant.model.ToolCallRequest(tool))
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

        // With thinking on, LiteRT sends the thoughts in their own channel: untagged message text is the answer.
        val channelAnswer = gemmaEngine.splitGemmaReasoningAndAnswer(rawOutput = "Kotlin, Python and more.", enableThinking = true)
        assertFalse(channelAnswer.isStillThinking)
        assertEquals("Kotlin, Python and more.", channelAnswer.visibleAnswer)

        val reply = router.processUserTurn("Explain quantum entanglement simply").toList().last()
        assertFalse("Placeholder must never be emitted", reply.text.contains("I'm right here with you. Regarding"))
    }

    @Test
    fun `conversational greetings and capabilities generate real helpful responses without debug text`() = runTest {
        val helloTurn = router.processUserTurn("hello").toList().last()
        assertTrue(helloTurn.text.contains("Hello") || helloTurn.text.contains("Fossistant"))
        assertFalse(helloTurn.text.contains("processed your request"))

        val capabilitiesTurn = router.processUserTurn("what can you do").toList().last()
        assertTrue(capabilitiesTurn.text.contains("Open any installed app") || capabilitiesTurn.text.contains("alarms"))
        assertFalse(capabilitiesTurn.text.contains("processed your request"))
    }

    @Test
    fun `Whisper STT and Fast TTS compute real audio levels and clean Whisper blank markers`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)
        assertNotNull("Expected whisper-tiny.bin (the default) to resolve", stt.resolveOrExtractModelFile())
        assertNotNull(
            "Expected whisper-small-q5_1.bin to resolve",
            stt.resolveOrExtractModelFile(org.fossistant.assistant.model.WhisperModelChoice.WHISPER_SMALL_Q5)
        )
        assertEquals("What time is it?", stt.cleanWhisperArtifacts(" [BLANK_AUDIO] What time is it? (inaudible) "))

        val silentSamples = ShortArray(1024) { 4 }
        val (silentDb, silentNorm) = stt.computePcmLevels(silentSamples, silentSamples.size)
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

        val greetingTurn = router.processUserTurn("hello").toList().last()
        assertTrue("Expected greeting to use custom AI name Nova, got: ${greetingTurn.text}", greetingTurn.text.contains("Nova"))

        // Verify all 6 BlobMoodTheme styles map to distinct custom launcher icon resources
        val iconResIds = org.fossistant.assistant.model.BlobMoodTheme.entries.map { theme ->
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
        assertTrue(gemmaSys.contains("Gemma 4 E2B"))

        val gemmaTurn = router.processUserTurn("Explain the laws of physics simply").toList().last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, gemmaTurn.modelTier)
        assertTrue(gemmaTurn.text.isNotBlank())
    }

    @Test
    fun `FuzzyAppAndCommandMatcher autocorrects phonetic speech mishearings and matches apps`() {
        // Test phonetic mishearing corrections
        val correctedFast = FuzzyAppAndCommandMatcher.autocorrectTranscript("open fast assistent please")
        assertTrue("Expected 'Fossistant' in '$correctedFast'", correctedFast.contains("Fossistant", ignoreCase = true))

        val correctedSpotfy = FuzzyAppAndCommandMatcher.autocorrectTranscript("play jazz on spotfy")
        assertTrue("Expected 'spotify' in '$correctedSpotfy'", correctedSpotfy.contains("spotify", ignoreCase = true))

        val correctedFlash = FuzzyAppAndCommandMatcher.autocorrectTranscript("turn on flash lite")
        assertTrue("Expected 'flashlight' in '$correctedFlash'", correctedFlash.contains("flashlight", ignoreCase = true))

        val correctedOsm = FuzzyAppAndCommandMatcher.autocorrectTranscript("open osm& and navigate")
        assertTrue("Expected 'osmand' in '$correctedOsm'", correctedOsm.contains("osmand", ignoreCase = true))

        // Test Levenshtein distance
        val dist1 = FuzzyAppAndCommandMatcher.levenshteinDistance("spotify", "spotfy")
        assertEquals(1, dist1)

        val dist2 = FuzzyAppAndCommandMatcher.levenshteinDistance("fossistant", "fastassistent")
        assertTrue(dist2 < 6)

        // Test closest app candidate matching
        val candidates = listOf(
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Fossistant", "org.fossistant.assistant"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Spotify", "com.spotify.music"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("WhatsApp", "com.whatsapp"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("OsmAnd", "net.osmand"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Google Maps", "com.google.android.apps.maps")
        )

        val matchFastAssistant = FuzzyAppAndCommandMatcher.findBestMatchCandidate("fast assistent", candidates)
        assertNotNull(matchFastAssistant)
        assertEquals("org.fossistant.assistant", matchFastAssistant?.packageName)

        val matchSpotfy = FuzzyAppAndCommandMatcher.findBestMatchCandidate("spotfy", candidates)
        assertNotNull(matchSpotfy)
        assertEquals("com.spotify.music", matchSpotfy?.packageName)

        val matchOsmAnd = FuzzyAppAndCommandMatcher.findBestMatchCandidate("osm&", candidates)
        assertNotNull(matchOsmAnd)
        assertEquals("net.osmand", matchOsmAnd?.packageName)

        val matchMaps = FuzzyAppAndCommandMatcher.findBestMatchCandidate("maps", candidates)
        assertNotNull(matchMaps)
        assertEquals("com.google.android.apps.maps", matchMaps?.packageName)
    }

    @Test
    fun `Whisper Tiny and Small Q5 exist and resolve valid files`() {
        val stt = org.fossistant.assistant.engine.WhisperMultilingualSttEngine(context = null)

        // Whisper Tiny (the fast default) and Whisper Small Q5 (more accurate)
        val choices = WhisperModelChoice.entries
        assertEquals(listOf(WhisperModelChoice.WHISPER_TINY, WhisperModelChoice.WHISPER_SMALL_Q5), choices)
        assertEquals(WhisperModelChoice.WHISPER_TINY, stt.activeModel)

        // Verify each model file resolves in assets/models
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
        // In test mode (context = null), direct calling and SMS gracefully simulate success
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
    fun `fuzzy app matching resolves typos, punctuation, and app suffix`() {
        val candidates = listOf(
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Photos", "com.google.android.apps.photos"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Spotify", "com.spotify.music"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Fossistant", "org.fossistant.assistant"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("Gallery", "com.simplemobiletools.gallery"),
            FuzzyAppAndCommandMatcher.AppMatchCandidate("OsmAnd", "net.osmand")
        )

        // 1. "open phptos app." -> Photos
        val match1 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("open phptos app.", candidates)
        assertNotNull("Expected match for 'open phptos app.'", match1)
        assertEquals("Photos", match1!!.label)

        // 2. "opem photos app." -> Photos
        val match2 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("opem photos app.", candidates)
        assertNotNull("Expected match for 'opem photos app.'", match2)
        assertEquals("Photos", match2!!.label)

        // 3. "open fast assistent" -> Fossistant
        val match3 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("open fast assistent", candidates)
        assertNotNull("Expected match for 'open fast assistent'", match3)
        assertEquals("Fossistant", match3!!.label)

        // 4. "spotfy" -> Spotify
        val match4 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("spotfy", candidates)
        assertNotNull("Expected match for 'spotfy'", match4)
        assertEquals("Spotify", match4!!.label)

        // 5. "open gallary app" -> Gallery
        val match5 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("open gallary app", candidates)
        assertNotNull("Expected match for 'open gallary app'", match5)
        assertEquals("Gallery", match5!!.label)

        // 6. "osm&" -> OsmAnd
        val match6 = FuzzyAppAndCommandMatcher.findBestMatchCandidate("osm&", candidates)
        assertNotNull("Expected match for 'osm&'", match6)
        assertEquals("OsmAnd", match6!!.label)
    }

    @Test
    fun `every command goes to Gemma, in any language`() = runTest {
        for (prompt in listOf("Play Daft Punk on Spotify", "Stell einen Wecker auf 7 Uhr", "otwórz zdjęcia")) {
            val finalMsg = router.processUserTurn(prompt).toList().last()
            assertEquals(prompt, ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        }
    }

    @Test
    fun `reasoning toggle ON runs Gemma with think token and reasoning phase`() = runTest {
        val emissions = router.processUserTurn(
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
            org.fossistant.assistant.model.ToolCallRequest("set_flashlight", mapOf("enabled" to "true")),
            "Turn on the flashlight"
        )
        for (yes in listOf("Ja, bitte", "oui", "tak", "да")) {
            gemmaEngine.pendingConfirmation = pending
            val confirmed = router.processUserTurn(yes).toList().last()
            assertEquals(yes, ModelTier.DIRECT_ACTION, confirmed.modelTier)
            assertEquals(yes, "set_flashlight", confirmed.toolExecutions.firstOrNull()?.toolName)
        }
        for (no in listOf("Nein", "nie", "нет")) {
            gemmaEngine.pendingConfirmation = pending
            val declined = router.processUserTurn(no).toList().last()
            assertEquals(no, ModelTier.DIRECT_ACTION, declined.modelTier)
            assertTrue(no, declined.toolExecutions.isEmpty())
        }
        // "Japan" is not "ja": the pending action is dropped and the request goes to Gemma
        gemmaEngine.pendingConfirmation = pending
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, router.processUserTurn("Japan weather").toList().last().modelTier)
    }

    @Test
    fun `actions Gemma has not confirmed are never executed`() = runTest {
        val finalMsg = router.processUserTurn("Remind me to call Dr. Smith next Tuesday after lunch").toList().last()
        assertEquals(ModelTier.GEMMA_E2B_QAT_MTP, finalMsg.modelTier)
        assertTrue("Unconfirmed actions must not run, got ${finalMsg.toolExecutions.map { it.toolName }}", finalMsg.toolExecutions.isEmpty())
    }

    @Test
    fun `call proposed by Gemma runs only after the user says yes`() = runTest {
        val pending = org.fossistant.assistant.engine.PendingConfirmation(
            org.fossistant.assistant.model.ToolCallRequest("set_flashlight", mapOf("enabled" to "true")),
            "Turn on the flashlight"
        )
        gemmaEngine.pendingConfirmation = pending
        val confirmed = router.processUserTurn("Yes please").toList().last()
        assertEquals("set_flashlight", confirmed.toolExecutions.firstOrNull()?.toolName)
        assertEquals(null, gemmaEngine.pendingConfirmation)

        gemmaEngine.pendingConfirmation = pending
        val declined = router.processUserTurn("No, don't").toList().last()
        assertTrue(declined.toolExecutions.isEmpty())
        assertTrue(declined.text.contains("won't"))

        // Anything else drops the pending action and is handled as a new request
        gemmaEngine.pendingConfirmation = pending
        val other = router.processUserTurn("Explain how rainbows form").toList().last()
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

        toolSet.changeDeviceSetting("flashlight", "on")
        assertEquals("change_device_setting", finished.last().toolName)
        assertEquals("flashlight on", finished.last().displayDetail)

        toolSet.setTimer(300, "")
        assertEquals("5 min", finished.last().displayDetail)

        val unknown = toolSet.assistantFeature("teleport", "")
        assertTrue(unknown.startsWith("Error"))
    }

    @Test
    fun `system prompt carries voice rules and tool guidance instead of per-turn injections`() {
        val voice = gemmaEngine.buildSystemPromptText(voiceMode = true)
        assertTrue(voice.contains("read aloud"))
        assertTrue(voice.contains("web search"))
        val text = gemmaEngine.buildSystemPromptText(voiceMode = false)
        assertFalse(text.contains("read aloud"))
        assertFalse(text.contains("["))
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
