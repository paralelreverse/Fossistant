package org.fossistant.assistant.features

import java.util.Locale

/**
 * Writing tools for text selected in any app (Gemini / Apple-Intelligence style): the result can replace the
 * selection in editable fields. Runs on the local model through a one-off request, so the chat is untouched.
 */
object WritingTools {

    data class Action(val label: String, val instructions: String)

    val actions: List<Action>
        get() {
            val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
            return listOf(
                Action("Proofread", "Fix spelling, grammar and punctuation. Keep the wording, tone and language. Reply with the corrected text only."),
                Action("Rewrite", "Rewrite the text so it reads clearly and naturally. Keep the meaning and language. Reply with the rewritten text only."),
                Action("Friendly", "Rewrite the text in a warm, friendly tone. Keep the meaning and language. Reply with the rewritten text only."),
                Action("Professional", "Rewrite the text in a polite, professional tone. Keep the meaning and language. Reply with the rewritten text only."),
                Action("Shorter", "Make the text about half as long, keeping every important point. Keep the language. Reply with the shortened text only."),
                Action("Summary", "Summarize the text in 2-4 bullet points in its own language. Reply with the bullet points only."),
                Action("Key points", "List the key facts, names, dates and numbers in the text as bullet points. Reply with the list only."),
                Action("Translate", "Translate the text into $language. Reply with the translation only."),
                Action("Reply", "Write a short, polite reply to this message in the same language. Reply with the reply text only.")
            )
        }

    suspend fun run(action: Action, text: String): String? =
        org.fossistant.assistant.FossistantApp.instance?.gemmaEngine?.completeOnce(
            instructions = action.instructions,
            input = text,
            maxTokens = 900,
            timeoutMs = 90_000
        )
}
