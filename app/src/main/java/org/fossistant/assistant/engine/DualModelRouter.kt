package org.fossistant.assistant.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.model.MediaAttachment
import org.fossistant.assistant.model.ModelTier
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import java.util.UUID

/**
 * Sends every user turn to Gemma, which understands any language and calls the phone's tools itself.
 *
 * (There used to be a "Reflex" tier of hand-written English and Slavic rules in front of Gemma; it only
 * caught exact phrasings, so it was removed and Gemma routes everything.)
 *
 * The one thing that runs without Gemma is the user's "yes" / "no" to a call, text or app action Gemma
 * asked to confirm.
 */
class DualModelRouter(
    private val gemmaEngine: GemmaLiteRtEngine,
    private val toolExecutor: DeviceAndWebToolExecutor,
    /** Runs confirmed actions; replaceable in tests to simulate failures. */
    private val runAction: suspend (ToolCallRequest) -> ToolExecutionResult = { toolExecutor.execute(it) }
) {

    /**
     * Processes a user turn and emits progressive [ChatMessage] updates for the UI.
     */
    fun processUserTurn(
        prompt: String,
        attachments: List<MediaAttachment> = emptyList(),
        enableReasoning: Boolean = false,
        turnContext: String? = null
    ): Flow<ChatMessage> = flow {
        val responseId = UUID.randomUUID().toString()
        val correctedPrompt = org.fossistant.assistant.tools.FuzzyAppAndCommandMatcher.autocorrectTranscript(prompt).trim()

        if (gemmaEngine.isMemoryEnabled && !gemmaEngine.isTemporaryChat && correctedPrompt.isNotBlank()) {
            gemmaEngine.memoryManager.autoExtractAndStoreFromPrompt(correctedPrompt)
        }

        // A call / text / app action Gemma proposed earlier runs only on an explicit "yes".
        gemmaEngine.takePendingConfirmation()?.let { pending ->
            val reply = correctedPrompt.lowercase()
            val answer = when {
                AFFIRMATIVE.containsMatchIn(reply) -> {
                    org.fossistant.assistant.util.AppLogger.i("DualModelRouter", "User confirmed: ${org.fossistant.assistant.util.AppLogger.userText(pending.description)}")
                    val result = runAction(pending.request)
                    ChatMessage(
                        id = responseId,
                        isUser = false,
                        text = result.summary,
                        modelTier = ModelTier.DIRECT_ACTION,
                        toolExecutions = listOf(result.copy(displayDetail = pending.description)),
                        isStreaming = false,
                        errorMessage = if (result.success) null else result.summary
                    )
                }
                NEGATIVE.containsMatchIn(reply) -> ChatMessage(
                    id = responseId,
                    isUser = false,
                    text = "Okay, I won't.",
                    modelTier = ModelTier.DIRECT_ACTION,
                    isStreaming = false
                )
                else -> null // Something else entirely: drop the pending action and handle the new request.
            }
            if (answer != null) {
                gemmaEngine.recordExternalTurn(prompt, answer.text)
                emit(answer)
                return@flow
            }
        }

        val computeKind = gemmaEngine.computeKind
        org.fossistant.assistant.util.AppLogger.i(
            "DualModelRouter",
            "[Compute: $computeKind] ${gemmaEngine.activeModel.displayName} (Reasoning=$enableReasoning, prompt='${org.fossistant.assistant.util.AppLogger.userText(correctedPrompt, 40)}')"
        )

        gemmaEngine.generateStream(
            prompt = correctedPrompt,
            attachments = attachments,
            enableThinking = enableReasoning,
            turnContext = turnContext
        ).collect { streamUpdate ->
            emit(
                ChatMessage(
                    id = responseId,
                    isUser = false,
                    text = streamUpdate.accumulatedText,
                    modelTier = ModelTier.GEMMA_E2B_QAT_MTP,
                    modelName = gemmaEngine.activeModel.displayName,
                    toolExecutions = streamUpdate.toolExecutions,
                    decodeSpeedTokPerSec = streamUpdate.decodeSpeedTokPerSec,
                    isStreaming = !streamUpdate.isDone,
                    isReasoningPhase = streamUpdate.isReasoningPhase,
                    reasoningTrace = streamUpdate.reasoningTrace,
                    errorMessage = streamUpdate.errorMessage
                )
            )
        }
    }

    private companion object {
        // English plus the plain yes / no of other common languages (German, French, Spanish, Italian, Portuguese,
        // Dutch, Polish, Czech, Slovak, Slovenian, Croatian, Russian, Ukrainian, Bulgarian, Hungarian, Turkish, ...).
        val AFFIRMATIVE = Regex(
            "^(?:yes|yeah|yep|yup|sure|ok|okay|do it|go ahead|confirm(?:ed)?|please do|correct|right|send it|call (?:them|him|her)|" +
                "ja|jawohl|klar|oui|sí|si|sì|certo|sim|claro|tak|ano|áno|jo|da|igen|evet|да|так|ага|є|はい|네|是|好|对)(?=$|[\\s,.!?])",
            RegexOption.IGNORE_CASE
        )
        val NEGATIVE = Regex(
            "^(?:no|nope|nah|cancel|don'?t|do not|stop|never ?mind|forget it|" +
                "nein|nee|non|não|nie|ne|nem|hayır|нет|ні|не|いいえ|아니|不)(?=$|[\\s,.!?])",
            RegexOption.IGNORE_CASE
        )
    }
}
