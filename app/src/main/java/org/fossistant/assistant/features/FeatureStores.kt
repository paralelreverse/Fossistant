package org.fossistant.assistant.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A list of items saved as JSON in the app's private storage, written on a background thread. Used for
 * skills, projects, scheduled tasks, connectors and recordings.
 */
class JsonListStore<T>(
    storageDir: File?,
    fileName: String,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    defaults: () -> List<T> = { emptyList() }
) {
    private val file: File? = storageDir?.let { File(it, "features").apply { mkdirs() }.resolve(fileName) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(serializer)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "FeatureStore-$fileName").apply { isDaemon = true } }

    private val _items = MutableStateFlow(load() ?: defaults())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    fun get(id: String): T? = _items.value.firstOrNull { idOf(it) == id }

    fun upsert(item: T) {
        _items.update { current ->
            val index = current.indexOfFirst { idOf(it) == idOf(item) }
            if (index >= 0) current.toMutableList().apply { set(index, item) } else current + item
        }
        persist()
    }

    fun delete(id: String) {
        _items.update { current -> current.filterNot { idOf(it) == id } }
        persist()
    }

    private fun load(): List<T>? = try {
        file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(listSerializer, it) }
    } catch (t: Throwable) {
        org.fossistant.assistant.util.AppLogger.w("JsonListStore", "Could not read ${file?.name}: ${t.message}")
        null
    }

    private fun persist() {
        val target = file ?: return
        val snapshot = _items.value
        writer.execute {
            try {
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(json.encodeToString(listSerializer, snapshot))
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            } catch (t: Throwable) {
                org.fossistant.assistant.util.AppLogger.w("JsonListStore", "Could not save ${target.name}: ${t.message}")
            }
        }
    }
}

fun newId(): String = UUID.randomUUID().toString()

/**
 * Saved instructions that apply when a prompt starts with or contains one of the [triggers] ("proofread:",
 * "plan a trip"), like Gemini Skills or custom GPTs.
 */
@Serializable
data class Skill(
    val id: String = newId(),
    val name: String,
    val triggers: List<String>,
    val instructions: String,
    val enabled: Boolean = true
) {
    fun matches(prompt: String): Boolean {
        val text = prompt.trim().lowercase()
        return enabled && triggers.any { trigger ->
            val t = trigger.trim().lowercase()
            t.isNotEmpty() && (text.startsWith(t) || Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(text))
        }
    }

    companion object {
        val DEFAULTS = listOf(
            Skill(
                name = "Proofreader",
                triggers = listOf("proofread", "fix my grammar"),
                instructions = "Correct spelling, grammar and punctuation in the user's text. Keep their wording and tone. Reply with the corrected text only, then one short line listing the main fixes."
            ),
            Skill(
                name = "Trip planner",
                triggers = listOf("plan a trip", "itinerary"),
                instructions = "Plan the trip day by day with morning, afternoon and evening ideas, travel times between places, and a rough budget. Search the web for opening hours and prices when useful."
            ),
            Skill(
                name = "Explain simply",
                triggers = listOf("eli5", "explain like i'm 5", "explain simply"),
                instructions = "Explain it to a curious 10-year-old: short sentences, one everyday analogy, no jargon."
            ),
            Skill(
                name = "Make a PDF",
                triggers = listOf("make a pdf", "create a pdf", "save as pdf", "as a pdf", "into a pdf"),
                instructions = "Write the complete document the user asks for (title, '# ' headings, '- ' bullets), then call create_pdf with that full text so it is saved as a PDF in Downloads. Reply with one short line saying it's saved."
            ),
            Skill(
                name = "Quiz me",
                triggers = listOf("quiz me", "test me on"),
                instructions = "Act as a tutor. Ask one question at a time about the topic, wait for the answer, say whether it was right and why, then ask the next one. Get harder as the user gets answers right."
            )
        )
    }
}

/** A chat workspace with its own instructions and files, like ChatGPT Projects or Perplexity Spaces. */
@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val instructions: String = "",
    val files: List<ProjectFile> = emptyList()
)

@Serializable
data class ProjectFile(
    val id: String = newId(),
    val name: String,
    val chunks: List<String>,
    val charCount: Int = chunks.sumOf { it.length }
)

/** How often a [ScheduledTask] repeats. */
@Serializable
enum class TaskRepeat(val label: String) {
    ONCE("Once"), DAILY("Every day"), WEEKDAYS("Weekdays"), HOURLY("Every hour");

    companion object {
        fun parse(text: String?): TaskRepeat = when (text?.trim()?.lowercase()) {
            "daily", "every day", "everyday" -> DAILY
            "weekdays", "workdays" -> WEEKDAYS
            "hourly", "every hour" -> HOURLY
            else -> ONCE
        }
    }
}

/** A request the assistant runs by itself at a set time (ChatGPT Tasks, Gemini scheduled actions). */
@Serializable
data class ScheduledTask(
    val id: String = newId(),
    val title: String,
    val instruction: String,
    val hour: Int,
    val minute: Int,
    val repeat: TaskRepeat = TaskRepeat.DAILY,
    val enabled: Boolean = true,
    val speakResult: Boolean = false,
    val lastRunMs: Long = 0L,
    val lastResult: String = ""
)

/** An MCP server reachable over HTTP (Streamable HTTP transport). */
@Serializable
data class Connector(
    val id: String = newId(),
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true,
    val tools: List<ConnectorTool> = emptyList(),
    val lastError: String = ""
)

@Serializable
data class ConnectorTool(
    val name: String,
    val description: String,
    val inputSchema: String = "{}",
    val parameterNames: List<String> = emptyList()
)

/** A recorded meeting, lecture or memo with its on-device transcript and summary. */
@Serializable
data class Recording(
    val id: String = newId(),
    val title: String,
    val createdMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val audioPath: String,
    val transcript: String = "",
    val summary: String = "",
    val status: String = ""
)
