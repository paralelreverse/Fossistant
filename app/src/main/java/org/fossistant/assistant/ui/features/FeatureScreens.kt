package org.fossistant.assistant.ui.features

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.features.Connector
import org.fossistant.assistant.features.InterpreterSession
import org.fossistant.assistant.features.PdfExporter
import org.fossistant.assistant.features.Project
import org.fossistant.assistant.features.ProjectFiles
import org.fossistant.assistant.features.Recording
import org.fossistant.assistant.features.RecordingService
import org.fossistant.assistant.features.ScheduledTask
import org.fossistant.assistant.features.Skill
import org.fossistant.assistant.features.TaskRepeat
import org.fossistant.assistant.features.TaskRunnerService
import org.fossistant.assistant.features.TaskScheduler
import org.fossistant.assistant.model.SupportedLanguage
import java.text.DateFormat
import java.util.Date

private val features get() = FossistantApp.instance!!.features

// ---- Interpreter -------------------------------------------------------------------------------------------

@Composable
internal fun InterpreterScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { InterpreterSession(scope) }
    val state by session.state.collectAsState()
    var first by rememberSaveable { mutableStateOf(SupportedLanguage.fromCode(java.util.Locale.getDefault().language).whisperCode) }
    var second by rememberSaveable { mutableStateOf(if (first == "en") "es" else "en") }
    var picking by remember { mutableStateOf<Int?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session.start(SupportedLanguage.fromCode(first), SupportedLanguage.fromCode(second))
    }
    DisposableEffect(Unit) { onDispose { session.stop() } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(SupportedLanguage.fromCode(first).displayName, Modifier.weight(1f), enabled = !state.running) { picking = 1 }
            Text("⇄", color = TextSecondary, fontSize = 20.sp)
            SecondaryButton(SupportedLanguage.fromCode(second).displayName, Modifier.weight(1f), enabled = !state.running) { picking = 2 }
        }
        PrimaryButton(if (state.running) "Stop" else "Start interpreting", Modifier.fillMaxWidth()) {
            if (state.running) {
                session.stop()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                session.start(SupportedLanguage.fromCode(first), SupportedLanguage.fromCode(second))
            } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (state.status.isNotBlank()) SectionText(state.status)
        if (state.lines.isEmpty()) {
            SectionText(
                "Put the phone between you. Each person speaks their language; Fossistant detects which one it " +
                    "was and says it in the other. Install voices for both languages in Android's text-to-speech settings."
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(state.lines.asReversed()) { line ->
                FeatureCard {
                    Text("${line.fromLanguage} → ${line.toLanguage}", color = TextSecondary, fontSize = 11.sp)
                    Text(line.original, color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(line.translation, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    picking?.let { which ->
        LanguagePickerDialog(onPick = { lang ->
            if (which == 1) first = lang.whisperCode else second = lang.whisperCode
            picking = null
        }, onDismiss = { picking = null })
    }
}

@Composable
private fun LanguagePickerDialog(onPick: (SupportedLanguage) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val languages = SupportedLanguage.entries.filterNot { it.isAuto }
        .filter { query.isBlank() || it.displayName.contains(query, true) || it.englishName.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Language", color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, colors = FieldColors())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(languages) { lang ->
                        Text(
                            "${lang.displayName}  (${lang.englishName})",
                            color = TextPrimary,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(lang) }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ---- Record ------------------------------------------------------------------------------------------------

@Composable
internal fun RecorderScreen(onAskInChat: (String) -> Unit) {
    val context = LocalContext.current
    val recState by RecordingService.state.collectAsState()
    val recordings by features.recordings.items.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RecordingService.start(context)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(if (recState.isRecording) Danger else Color(0xFF4C1D95), CircleShape)
                        .clickable {
                            when {
                                recState.isRecording -> RecordingService.stop(context)
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                                    RecordingService.start(context)
                                else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (recState.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (recState.isRecording) "Recording  ${formatDuration(recState.elapsedMs)}" else "Tap to record",
                        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    if (recState.isRecording) {
                        LinearProgressIndicator(progress = { recState.level }, modifier = Modifier.fillMaxWidth())
                    } else {
                        SectionText("Keeps recording with the screen off. Transcribed and summarized on your phone.")
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(recordings.sortedByDescending { it.createdMs }, key = { it.id }) { rec ->
                FeatureCard(onClick = { open = rec.id }) {
                    Text(rec.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SectionText(
                        listOf(formatDuration(rec.durationMs), rec.status.ifBlank { if (rec.summary.isNotBlank()) "Summarized" else "Transcribed" })
                            .joinToString(" • ")
                    )
                }
            }
        }
    }
    open?.let { id ->
        recordings.firstOrNull { it.id == id }?.let { rec ->
            RecordingDialog(rec, onAskInChat = {
                open = null
                onAskInChat(
                    "Here is the transcript of my recording \"${rec.title}\":\n\n${rec.transcript.take(6000)}\n\n" +
                        "Remember it so I can ask questions about it. Reply only with a short confirmation."
                )
            }, onDismiss = { open = null })
        } ?: run { open = null }
    }
}

@Composable
private fun RecordingDialog(rec: Recording, onAskInChat: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showTranscript by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(rec.title, color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rec.status.isNotBlank()) SectionText(rec.status)
                if (rec.summary.isNotBlank()) Text(rec.summary, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Hide transcript" else "Show transcript") }
                if (showTranscript) Text(rec.transcript.ifBlank { "Not transcribed yet." }, color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton("PDF", enabled = rec.transcript.isNotBlank()) {
                        val text = "# Summary\n${rec.summary}\n\n# Transcript\n${rec.transcript}"
                        PdfExporter.export(context, rec.title, text).onSuccess { PdfExporter.open(context, it.uri) }
                    }
                    SecondaryButton("Ask", enabled = rec.transcript.isNotBlank(), onClick = onAskInChat)
                    // Offered when the summary failed or never ran (not while it's still working).
                    if (rec.summary.isBlank() && !rec.status.endsWith("…")) {
                        SecondaryButton("Retry") { features.processRecording(rec.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                java.io.File(rec.audioPath).delete()
                features.recordings.delete(rec.id)
                onDismiss()
            }) { Text("Delete", color = Danger) }
        }
    )
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

// ---- Scheduled tasks ---------------------------------------------------------------------------------------

@Composable
internal fun TasksScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by features.tasks.items.collectAsState()
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var briefTopics by rememberSaveable { mutableStateOf("") }
    var triageResult by remember { mutableStateOf("") }
    var triageBusy by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun save(task: ScheduledTask) {
        features.tasks.upsert(task)
        TaskScheduler.scheduleAll(context)
        ensureNotificationPermission()
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FeatureCard {
                Text("Notification triage", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                SectionText("Reads your notifications and tells you what needs action. Needs notification access (Settings > Integrations).")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (triageBusy) "Reading…" else "Check now", enabled = !triageBusy) {
                        triageBusy = true
                        scope.launch {
                            val engine = FossistantApp.instance!!.gemmaEngine
                            triageResult = engine.completeOnce(TaskScheduler.TASK_INSTRUCTIONS, TaskScheduler.NOTIFICATION_TRIAGE_INSTRUCTION, withTools = true)
                                ?.let { if (it.trim().trimEnd('.').equals(TaskScheduler.NOTHING_TO_REPORT, true)) "Nothing needs your attention right now." else it }
                                ?: "Couldn't check: ${engine.lastEngineError ?: "model unavailable"}"
                            triageBusy = false
                        }
                    }
                    SecondaryButton("Every hour") { save(TaskScheduler.notificationDigest()) }
                }
                if (triageResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(triageResult, color = TextPrimary, fontSize = 14.sp)
                }
            }
        }
        item {
            FeatureCard {
                Text("Morning brief", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                SectionText("Weather, today's calendar, notifications that need action and news, every morning at 7:00 (change it after adding).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = briefTopics, onValueChange = { briefTopics = it },
                    label = { Text("News topics (optional)") }, singleLine = true, colors = FieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Add morning brief") { save(TaskScheduler.morningBrief(briefTopics.trim())) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your tasks", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = ScheduledTask(title = "", instruction = "", hour = 8, minute = 0) }) { Text("+ New task") }
            }
            if (tasks.isEmpty()) SectionText("You can also say \"every weekday at 8 summarize tech news\" in a chat.")
        }
        items(tasks, key = { it.id }) { task ->
            FeatureCard(onClick = { editing = task }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        SectionText(
                            if (task.repeat == TaskRepeat.HOURLY) "Every hour at :%02d".format(task.minute)
                            else "${task.repeat.label} at %d:%02d".format(task.hour, task.minute)
                        )
                    }
                    Switch(checked = task.enabled, onCheckedChange = { save(task.copy(enabled = it, lastRunMs = if (it) 0L else task.lastRunMs)) })
                }
                if (task.lastResult.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    SectionText("Last run ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.lastRunMs))}: ${task.lastResult.take(160)}")
                }
            }
        }
    }
    editing?.let { task ->
        TaskEditorDialog(
            initial = task,
            onSave = { save(it); editing = null },
            onRunNow = { t ->
                save(t)
                context.startForegroundService(Intent(context, TaskRunnerService::class.java).putExtra(TaskRunnerService.EXTRA_TASK_ID, t.id))
                editing = null
            },
            onDelete = {
                TaskScheduler.cancel(context, task.id)
                features.tasks.delete(task.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun TaskEditorDialog(
    initial: ScheduledTask,
    onSave: (ScheduledTask) -> Unit,
    onRunNow: (ScheduledTask) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var instruction by remember { mutableStateOf(initial.instruction) }
    var time by remember { mutableStateOf("%d:%02d".format(initial.hour, initial.minute)) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var speak by remember { mutableStateOf(initial.speakResult) }
    val parsed = TaskScheduler.parseTime(time)
    fun build() = initial.copy(
        title = title.trim().ifBlank { instruction.trim().take(40) },
        instruction = instruction.trim(),
        hour = parsed?.first ?: initial.hour,
        minute = parsed?.second ?: initial.minute,
        repeat = repeat,
        speakResult = speak,
        enabled = true,
        lastRunMs = if (repeat == TaskRepeat.ONCE) 0L else initial.lastRunMs
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (initial.instruction.isBlank()) "New task" else "Edit task", color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name") }, singleLine = true, colors = FieldColors())
                OutlinedTextField(value = instruction, onValueChange = { instruction = it }, label = { Text("What should I do?") }, minLines = 3, colors = FieldColors())
                OutlinedTextField(
                    value = time, onValueChange = { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true,
                    isError = parsed == null, colors = FieldColors()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TaskRepeat.entries.forEach { option ->
                        FilterChip(selected = repeat == option, onClick = { repeat = option }, label = { Text(option.label, fontSize = 11.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read the result aloud", color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = speak, onCheckedChange = { speak = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Run now", enabled = instruction.isNotBlank() && parsed != null) { onRunNow(build()) }
                    if (initial.instruction.isNotBlank()) TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build()) }, enabled = instruction.isNotBlank() && parsed != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---- Skills ------------------------------------------------------------------------------------------------

@Composable
internal fun SkillsScreen() {
    val skills by features.skills.items.collectAsState()
    var editing by remember { mutableStateOf<Skill?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionText("A skill adds its instructions whenever your message starts with or contains one of its trigger phrases, e.g. \"proofread: …\".")
            TextButton(onClick = { editing = Skill(name = "", triggers = emptyList(), instructions = "") }) { Text("+ New skill") }
        }
        items(skills, key = { it.id }) { skill ->
            FeatureCard(onClick = { editing = skill }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        SectionText("Says: " + skill.triggers.joinToString(", ") { "\"$it\"" })
                    }
                    Switch(checked = skill.enabled, onCheckedChange = { features.skills.upsert(skill.copy(enabled = it)) })
                }
            }
        }
    }
    editing?.let { skill ->
        var name by remember(skill.id) { mutableStateOf(skill.name) }
        var triggers by remember(skill.id) { mutableStateOf(skill.triggers.joinToString(", ")) }
        var instructions by remember(skill.id) { mutableStateOf(skill.instructions) }
        val valid = name.isNotBlank() && triggers.isNotBlank() && instructions.isNotBlank()
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Card,
            title = { Text(if (skill.name.isBlank()) "New skill" else "Edit skill", color = TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = FieldColors())
                    OutlinedTextField(value = triggers, onValueChange = { triggers = it }, label = { Text("Trigger phrases, comma separated") }, colors = FieldColors())
                    OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions") }, minLines = 4, colors = FieldColors())
                    if (skill.name.isNotBlank()) {
                        TextButton(onClick = { features.skills.delete(skill.id); editing = null }) { Text("Delete", color = Danger) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    features.skills.upsert(
                        skill.copy(
                            name = name.trim(),
                            triggers = triggers.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            instructions = instructions.trim()
                        )
                    )
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

// ---- Projects ----------------------------------------------------------------------------------------------

@Composable
internal fun ProjectsScreen(onStartChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by features.projects.items.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val project = openId?.let { id -> projects.firstOrNull { it.id == id } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = project ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProjectFiles.import(context, uri) }
            result.onSuccess { file ->
                val latest = features.projects.get(target.id) ?: target
                features.projects.upsert(latest.copy(files = latest.files + file))
                importError = null
            }.onFailure { importError = it.message }
            importing = false
        }
    }

    if (project == null) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionText("Each project has its own instructions and files. Chats in a project get the most relevant parts of its files with every question.")
                TextButton(onClick = { creating = true }) { Text("+ New project") }
            }
            items(projects, key = { it.id }) { p ->
                FeatureCard(onClick = { openId = p.id }) {
                    Text(p.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    SectionText("${p.files.size} file(s)" + if (p.instructions.isNotBlank()) " • has instructions" else "")
                }
            }
        }
    } else {
        var instructions by remember(project.id) { mutableStateOf(project.instructions) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(project.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            PrimaryButton("Start a chat in this project", Modifier.fillMaxWidth()) { onStartChat(project.id) }
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions for this project") },
                minLines = 3,
                colors = FieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton("Save instructions", enabled = instructions != project.instructions) {
                features.projects.upsert(project.copy(instructions = instructions.trim()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Files", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = !importing, onClick = { filePicker.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/json")) }) {
                    Text(if (importing) "Adding…" else "+ Add file")
                }
            }
            importError?.let { Text(it, color = Danger, fontSize = 13.sp) }
            SectionText("Text, Markdown, CSV, HTML, Word (.docx) and PDF (Android 15+).")
            project.files.forEach { file ->
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SectionText("${file.charCount / 1000}k characters")
                        }
                        Icon(
                            Icons.Default.Delete, contentDescription = "Remove", tint = TextSecondary,
                            modifier = Modifier.clickable { features.projects.upsert(project.copy(files = project.files.filterNot { it.id == file.id })) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("All projects") { openId = null }
                TextButton(onClick = { features.projects.delete(project.id); openId = null }) { Text("Delete project", color = Danger) }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = Card,
            title = { Text("New project", color = TextPrimary) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = FieldColors()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val created = Project(name = name.trim())
                    features.projects.upsert(created)
                    creating = false
                    openId = created.id
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }
}

// ---- Connectors --------------------------------------------------------------------------------------------

@Composable
internal fun ConnectorsScreen() {
    val scope = rememberCoroutineScope()
    val connectors by features.connectors.store.items.collectAsState()
    var editing by remember { mutableStateOf<Connector?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionText(
                "Connect MCP servers (Model Context Protocol, Streamable HTTP) to give Fossistant more tools. For Home " +
                    "Assistant, add the MCP Server integration and use http://YOUR-HA:8123/api/mcp with a long-lived " +
                    "access token. Up to 15 tools are offered to the model."
            )
            TextButton(onClick = { editing = Connector(name = "", url = "") }) { Text("+ Add connector") }
        }
        items(connectors, key = { it.id }) { c ->
            FeatureCard(onClick = { editing = c }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        SectionText(
                            when {
                                busyId == c.id -> "Connecting…"
                                c.lastError.isNotBlank() -> "Error: ${c.lastError}"
                                else -> "${c.tools.size} tool(s): " + c.tools.take(6).joinToString(", ") { it.name }
                            }
                        )
                    }
                    Switch(checked = c.enabled, onCheckedChange = { features.connectors.save(c.copy(enabled = it)) })
                }
                Spacer(Modifier.height(6.dp))
                SecondaryButton("Refresh tools", enabled = busyId == null) {
                    busyId = c.id
                    scope.launch { features.connectors.refresh(c); busyId = null }
                }
            }
        }
    }
    editing?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var url by remember(c.id) { mutableStateOf(c.url) }
        var token by remember(c.id) { mutableStateOf(c.token) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Card,
            title = { Text(if (c.url.isBlank()) "Add connector" else "Edit connector", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = FieldColors())
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server URL") }, singleLine = true, colors = FieldColors())
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Access token (optional)") }, singleLine = true, colors = FieldColors())
                    if (c.url.isNotBlank()) TextButton(onClick = { features.connectors.delete(c.id); editing = null }) { Text("Delete", color = Danger) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && url.startsWith("http"), onClick = {
                    val updated = c.copy(name = name.trim(), url = url.trim(), token = token.trim())
                    features.connectors.save(updated)
                    editing = null
                    busyId = updated.id
                    scope.launch { features.connectors.refresh(updated); busyId = null }
                }) { Text("Save & connect") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}
