package org.fossistant.assistant.ui.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.features.HealthData
import org.fossistant.assistant.features.WritingTools
import org.fossistant.assistant.ui.MainActivity

/** Screens of the Tools hub. */
internal enum class ToolScreen(val title: String) {
    HUB("Tools"),
    INTERPRETER("Interpreter"),
    RECORDER("Record"),
    TASKS("Scheduled tasks"),
    RESEARCH("Deep Research"),
    SKILLS("Skills"),
    PROJECTS("Projects"),
    CONNECTORS("Connectors"),
    HEALTH("Health"),
    WRITING("Writing tools")
}

/**
 * The Tools hub: interpreter, record mode, scheduled tasks and briefs, Deep Research, skills, projects,
 * connectors (MCP), Health Connect and writing tools.
 */
class FeaturesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startScreen = when {
            intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE -> ToolScreen.HEALTH
            else -> intent?.getStringExtra(EXTRA_SCREEN)?.let { name -> ToolScreen.entries.firstOrNull { it.name == name } }
                ?: ToolScreen.HUB
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Accent, background = Background, surface = Card)) {
                var screen by rememberSaveable { mutableStateOf(startScreen) }
                BackHandler(enabled = screen != ToolScreen.HUB) { screen = ToolScreen.HUB }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    TopBar(screen.title) { if (screen == ToolScreen.HUB) finish() else screen = ToolScreen.HUB }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (screen) {
                            ToolScreen.HUB -> HubScreen { screen = it }
                            ToolScreen.INTERPRETER -> InterpreterScreen()
                            ToolScreen.RECORDER -> RecorderScreen(::openChatWithPrompt)
                            ToolScreen.TASKS -> TasksScreen()
                            ToolScreen.RESEARCH -> ResearchScreen { question ->
                                FossistantApp.pendingResearchQuestion = question
                                openMain()
                            }
                            ToolScreen.SKILLS -> SkillsScreen()
                            ToolScreen.PROJECTS -> ProjectsScreen { projectId ->
                                FossistantApp.pendingProjectChatId = projectId
                                openMain()
                            }
                            ToolScreen.CONNECTORS -> ConnectorsScreen()
                            ToolScreen.HEALTH -> HealthScreen()
                            ToolScreen.WRITING -> WritingScreen()
                        }
                    }
                }
            }
        }
    }

    private fun openChatWithPrompt(prompt: String) {
        FossistantApp.pendingSharedPrompt = prompt
        openMain()
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        const val EXTRA_SCREEN = "screen"

        fun open(context: Context, screen: String? = null) {
            context.startActivity(
                Intent(context, FeaturesActivity::class.java)
                    .apply { screen?.let { putExtra(EXTRA_SCREEN, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

// ---- Shared look -------------------------------------------------------------------------------------------

internal val Background = Color(0xFF0B0F1A)
internal val Card = Color(0xFF141A2A)
internal val CardBorder = Color(0xFF263049)
internal val Accent = Color(0xFFA78BFA)
internal val TextPrimary = Color(0xFFE2E8F0)
internal val TextSecondary = Color(0xFF94A3B8)
internal val Danger = Color(0xFFF87171)

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FeatureCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content
    )
}

@Composable
internal fun SectionText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier)
}

@Composable
internal fun FieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = Accent,
    unfocusedBorderColor = CardBorder,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextSecondary,
    cursorColor = Accent
)

@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C1D95), contentColor = Color.White)
    ) { Text(text) }
}

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text, color = if (enabled) TextPrimary else TextSecondary) }
}

internal fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Fossistant", text))
}

// ---- Hub ---------------------------------------------------------------------------------------------------

private data class HubEntry(val screen: ToolScreen, val icon: ImageVector, val description: String)

@Composable
private fun HubScreen(onOpen: (ToolScreen) -> Unit) {
    val entries = listOf(
        HubEntry(ToolScreen.INTERPRETER, Icons.Default.Translate, "Two people, two languages: speak and hear each other translated."),
        HubEntry(ToolScreen.RECORDER, Icons.Default.Mic, "Record a meeting or lecture. Transcribed and summarized on your phone."),
        HubEntry(ToolScreen.TASKS, Icons.Default.Schedule, "Morning brief, notification triage, and requests that run on a schedule."),
        HubEntry(ToolScreen.RESEARCH, Icons.Default.Search, "Searches several sources, reads them and writes a cited report."),
        HubEntry(ToolScreen.SKILLS, Icons.Default.AutoAwesome, "Saved instructions that kick in when you say a phrase."),
        HubEntry(ToolScreen.PROJECTS, Icons.Default.Folder, "Chats with their own instructions and files."),
        HubEntry(ToolScreen.CONNECTORS, Icons.Default.Cable, "Add tools from your own MCP servers, like Home Assistant."),
        HubEntry(ToolScreen.HEALTH, Icons.Default.Favorite, "Steps, sleep and heart rate from Health Connect."),
        HubEntry(ToolScreen.WRITING, Icons.Default.EditNote, "Proofread, rewrite or translate selected text in any app.")
    )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries) { entry ->
            FeatureCard(onClick = { onOpen(entry.screen) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).background(Color(0xFF1F1B3A), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(entry.icon, contentDescription = null, tint = Accent) }
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(entry.screen.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        SectionText(entry.description)
                    }
                }
            }
        }
    }
}

// ---- Deep Research -----------------------------------------------------------------------------------------

@Composable
private fun ResearchScreen(onStart: (String) -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionText(
            "Fossistant plans a few searches, reads the best pages one by one, takes notes, and writes a report with " +
                "numbered sources and follow-up questions. It takes a few minutes on the phone. You can also type " +
                "\"research: your question\" in any chat."
        )
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("What should I research?") },
            minLines = 3,
            colors = FieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        PrimaryButton("Start research", Modifier.fillMaxWidth(), enabled = question.isNotBlank()) { onStart(question.trim()) }
        SectionText("Reports can be saved as PDF from the chat.")
    }
}

// ---- Health ------------------------------------------------------------------------------------------------

@Composable
private fun HealthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(HealthData.hasPermissions(context)) }
    var summary by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = HealthData.hasPermissions(context)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard {
            Text("How Fossistant uses health data", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SectionText(
                "Fossistant reads your steps, sleep and heart rate from Health Connect only when you ask about them " +
                    "(\"how did I sleep?\", \"how many steps today?\") or in your morning brief. It never writes " +
                    "health data, and nothing leaves your phone: the answer is made by the on-device model."
            )
        }
        when {
            !HealthData.isSupported -> SectionText("Health Connect is built into Android 14 and newer. This phone runs an older version.")
            !granted -> PrimaryButton("Allow access in Health Connect", Modifier.fillMaxWidth()) { launcher.launch(HealthData.PERMISSIONS) }
            else -> {
                PrimaryButton("Show today's summary", Modifier.fillMaxWidth()) {
                    scope.launch { summary = HealthData.summary(context).summary }
                }
                if (summary.isNotBlank()) FeatureCard { Text(summary, color = TextPrimary) }
            }
        }
    }
}

// ---- Writing tools -----------------------------------------------------------------------------------------

@Composable
private fun WritingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionText(
            "In any app, select text and pick Fossistant from the menu (or share text to it). You can proofread, " +
                "rewrite, shorten, summarize, translate or draft a reply, and in text fields replace the selection " +
                "with the result. Try it here:"
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Your text") },
            minLines = 4,
            colors = FieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        WritingActionButtons(enabled = text.isNotBlank() && !busy) { action ->
            busy = true
            result = "Working…"
            scope.launch {
                result = WritingTools.run(action, text) ?: "Couldn't do that: ${FossistantApp.instance?.gemmaEngine?.lastEngineError ?: "model unavailable"}"
                busy = false
            }
        }
        if (result.isNotBlank()) {
            FeatureCard {
                Text(result, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Copy", enabled = !busy) { copyToClipboard(context, result) }
            }
        }
    }
}

/** The writing actions as a wrapping row of buttons; shared with the text-selection popup. */
@Composable
fun WritingActionButtons(enabled: Boolean, onAction: (WritingTools.Action) -> Unit) {
    val actions = WritingTools.actions
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    Button(
                        onClick = { onAction(action) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF312E81), contentColor = Color.White)
                    ) { Text(action.label, fontSize = 12.sp, maxLines = 1) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
