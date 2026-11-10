package org.fossistant.assistant.features

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor

/**
 * The assistant features beyond chat: skills, projects, scheduled tasks, connectors, recordings and Deep
 * Research. One instance lives in [org.fossistant.assistant.FossistantApp].
 */
class Features(
    private val context: Context,
    private val engine: GemmaLiteRtEngine,
    executor: DeviceAndWebToolExecutor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = context.filesDir

    val skills = JsonListStore(dir, "skills.json", Skill.serializer(), { it.id }) { Skill.DEFAULTS }
    val projects = JsonListStore(dir, "projects.json", Project.serializer(), { it.id })
    val tasks = JsonListStore(dir, "tasks.json", ScheduledTask.serializer(), { it.id })
    val recordings = JsonListStore(dir, "recordings.json", Recording.serializer(), { it.id })
    val connectors = ConnectorManager(
        JsonListStore(dir, "connectors.json", Connector.serializer(), { it.id })
    ) { summary -> engine.connectorToolsSummary = summary }
    val research = DeepResearch(engine, executor)

    private val recordingMutex = Mutex()

    init {
        // Skills added in later versions reach people who already have a saved skill list (once).
        val prefs = context.getSharedPreferences("fossistant_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("skill_pdf_added", false)) {
            Skill.DEFAULTS.firstOrNull { it.name == "Make a PDF" }?.let { pdf ->
                if (skills.items.value.none { it.name == pdf.name }) skills.upsert(pdf)
            }
            prefs.edit().putBoolean("skill_pdf_added", true).apply()
        }
    }

    /** The skill whose trigger matches [prompt], if any. */
    fun matchingSkill(prompt: String): Skill? = skills.items.value.firstOrNull { it.matches(prompt) }

    /**
     * Extra context for one chat turn: the matching skill's instructions and, inside a project, its
     * instructions and the most relevant excerpts from its files. Null when there's nothing to add.
     */
    fun turnContext(prompt: String, projectId: String?): String? {
        val parts = mutableListOf<String>()
        projectId?.let { projects.get(it) }?.let { parts.add(ProjectFiles.turnContext(it, prompt)) }
        matchingSkill(prompt)?.let { parts.add("Follow the \"${it.name}\" skill for this request: ${it.instructions}") }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    fun processRecording(recordingId: String) {
        scope.launch {
            recordingMutex.withLock {
                runCatching {
                    RecordingProcessor.process(recordingId)
                }.onFailure { t ->
                    recordings.get(recordingId)?.let { rec ->
                        recordings.upsert(rec.copy(status = "Couldn't transcribe: ${t.message ?: "unexpected error"}"))
                    }
                }
            }
        }
    }

    /** Backs the `schedule_task` tool. */
    fun scheduleFromTool(instruction: String, time: String, repeat: String): ToolExecutionResult {
        val (hour, minute) = TaskScheduler.parseTime(time)
            ?: return ToolExecutionResult("schedule_task", false, "I need a time like 07:30 for the task.")
        if (instruction.isBlank()) return ToolExecutionResult("schedule_task", false, "The task needs an instruction.")
        val task = ScheduledTask(
            title = instruction.trim().take(40),
            instruction = instruction.trim(),
            hour = hour,
            minute = minute,
            repeat = TaskRepeat.parse(repeat)
        )
        tasks.upsert(task)
        TaskScheduler.scheduleAll(context)
        val whenText = when (task.repeat) {
            TaskRepeat.HOURLY -> "every hour at :%02d".format(minute)
            else -> "%s at %d:%02d".format(task.repeat.label.lowercase(), hour, minute)
        }
        return ToolExecutionResult("schedule_task", true, "Scheduled \"${task.title}\" $whenText. You can change it in Tools > Scheduled tasks.")
    }

    /** Backs the `create_pdf` tool; empty [content] means the last answer in the chat. */
    fun createPdfFromTool(title: String, content: String): ToolExecutionResult {
        val text = content.ifBlank { engine.lastAssistantText() }
        if (text.isBlank()) return ToolExecutionResult("create_pdf", false, "There's no text to put in the PDF yet.")
        return PdfExporter.export(context, title, text).fold(
            onSuccess = { saved ->
                PdfExporter.open(context, saved.uri)
                ToolExecutionResult("create_pdf", true, "Saved the PDF to ${saved.displayPath} and opened it.")
            },
            onFailure = { ToolExecutionResult("create_pdf", false, "Couldn't make the PDF: ${it.message}") }
        )
    }
}
