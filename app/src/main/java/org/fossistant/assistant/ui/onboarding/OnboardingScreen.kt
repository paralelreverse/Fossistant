package org.fossistant.assistant.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.fossistant.assistant.engine.FastMultilingualTtsEngine
import org.fossistant.assistant.engine.ModelDownloadManager
import org.fossistant.assistant.engine.ModelDownloadManager.Phase
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.ui.AssistantUiState
import org.fossistant.assistant.ui.AssistantViewModel
import org.fossistant.assistant.ui.components.BlobActivityState
import org.fossistant.assistant.ui.components.CosmicAiBlob

private val CardBg = Color(0xDD151B28)
private val Muted = Color(0xFF94A3B8)
private val Body = Color(0xFFE2E8F0)

/**
 * First-launch setup: welcome & privacy, language & voice, on-device models (download for slim builds),
 * permissions explained one by one, and a short tour. Reopened from Settings > Setup & guide.
 */
@Composable
fun OnboardingScreen(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 5
    val accent = uiState.moodTheme.primaryAccent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        // Step dots
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(steps) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (i == step) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (i <= step) accent else Color(0xFF334155))
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> WelcomeStep(uiState)
                1 -> LanguageStep(uiState, viewModel)
                2 -> ModelStep(uiState, viewModel)
                3 -> PermissionsStep()
                else -> TourStep()
            }
        }

        val modelsReady = step != 2 || viewModel.areModelsReady()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                TextButton(onClick = { step-- }) { Text("Back", color = Muted) }
            }
            Spacer(Modifier.weight(1f))
            if (step == 2 && !modelsReady) {
                // The model can finish downloading later; the chat explains what's missing until then.
                TextButton(onClick = { step++ }) { Text("Later", color = Muted) }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { if (step < steps - 1) step++ else viewModel.finishOnboarding() },
                enabled = modelsReady,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)
            ) {
                Text(
                    when (step) {
                        0 -> "Get started"
                        steps - 1 -> "Start using ${uiState.aiName.ifBlank { "Fossistant" }}"
                        else -> "Next"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(uiState: AssistantUiState) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        CosmicAiBlob(
            modifier = Modifier.size(220.dp),
            activityState = BlobActivityState.IDLE,
            moodTheme = uiState.moodTheme
        )
        Spacer(Modifier.height(16.dp))
        Text("Welcome to ${uiState.aiName.ifBlank { "Fossistant" }}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Private. Intelligent. Entirely on your phone.", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        InfoCard(Icons.Default.Lock, "Nothing leaves your phone", "Gemma 4 and Whisper run on your phone's GPU and CPU. No account, no cloud, no tracking.")
        InfoCard(Icons.Default.Mic, "Talk or type", "Speak naturally in 100 languages, interrupt it mid-sentence, or chat by text.")
        InfoCard(Icons.Default.CheckCircle, "Works offline", "Only web search and downloads use the internet, and only when you ask.")
    }
}

@Composable
private fun LanguageStep(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(uiState.aiName) }
    val languages = SupportedLanguage.entries.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    val voices = FastMultilingualTtsEngine.profilesForLanguage(uiState.selectedLanguage)

    Column(modifier = Modifier.fillMaxSize()) {
        StepTitle("Language & voice", "Whisper listens in this language and replies are spoken in it. Automatic detects what you speak each time.")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${SupportedLanguage.entries.size - 1} languages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
        ) {
            items(languages, key = { it.whisperCode }) { lang ->
                val selected = lang == uiState.selectedLanguage
                Text(
                    text = lang.displayName,
                    color = if (selected) uiState.moodTheme.secondaryAccent else Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectLanguage(lang) }
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                )
            }
        }
        Text("Voice", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            voices.take(3).forEach { profile ->
                val selected = profile == uiState.selectedVoiceProfile
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) uiState.moodTheme.primaryAccent.copy(alpha = 0.25f) else CardBg)
                        .border(1.dp, if (selected) uiState.moodTheme.primaryAccent else Color(0xFF334155), RoundedCornerShape(12.dp))
                        .clickable { viewModel.selectVoiceProfile(profile) }
                        .padding(10.dp)
                ) {
                    Text(profile.name.substringBefore(" ("), color = Color.White, fontSize = 13.sp)
                }
            }
        }
        OutlinedButton(onClick = { viewModel.previewSelectedVoice() }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Hear this voice")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text("Assistant name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // Saved when the user stops typing.
        LaunchedEffect(name) {
            delay(500)
            if (name.isNotBlank() && name != uiState.aiName) viewModel.setAiName(name)
        }
    }
}

@Composable
private fun ModelStep(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    val progress = uiState.modelDownload
    val ready = viewModel.areModelsReady() && progress.phase != Phase.PREPARING
    val toDownload = remember(progress.phase) { viewModel.modelBytesToDownload() }
    val free = remember(progress.phase) { viewModel.freeStorageBytes() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("On-device AI", "${uiState.primaryModelDisplayName} with Multi-Token Prediction answers your questions, Whisper understands your voice.")
        if (ready) {
            InfoCard(Icons.Default.CheckCircle, "Models are ready", "${uiState.primaryModelDisplayName} (MTP) and speech recognition are stored on this phone.")
            return@Column
        }
        InfoCard(
            Icons.Default.Download,
            "One download, one copy",
            "To keep the app small, the models aren't inside it. They download once (${ModelDownloadManager.formatBytes(toDownload)}) " +
                "straight into the app's private storage, so your phone only ever keeps one copy. App updates keep them."
        )
        Text(
            "Free space: ${ModelDownloadManager.formatBytes(free)} • needed: ${ModelDownloadManager.formatBytes(toDownload + ModelDownloadManager.SPACE_MARGIN_BYTES)}",
            color = if (free < toDownload + ModelDownloadManager.SPACE_MARGIN_BYTES) Color(0xFFF87171) else Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Download over Wi-Fi only", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = uiState.downloadOverWifiOnly, onCheckedChange = { viewModel.setDownloadOverWifiOnly(it) })
        }
        Spacer(Modifier.height(12.dp))
        when (progress.phase) {
            Phase.IDLE, Phase.DONE -> Button(onClick = { viewModel.startModelDownload() }, modifier = Modifier.fillMaxWidth()) {
                Text("Download models")
            }
            Phase.WAITING_FOR_WIFI -> {
                Text("Connect to Wi-Fi, or switch off \"Wi-Fi only\" to use mobile data.", color = Color(0xFFFBBF24), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.startModelDownload() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            Phase.ERROR -> {
                Text(progress.error ?: "Download failed", color = Color(0xFFF87171), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.startModelDownload() }, modifier = Modifier.fillMaxWidth()) { Text("Resume download") }
            }
            Phase.DOWNLOADING, Phase.VERIFYING, Phase.PREPARING -> {
                Text(
                    when (progress.phase) {
                        Phase.DOWNLOADING -> "Downloading ${progress.label}"
                        Phase.VERIFYING -> "Checking ${progress.label}"
                        else -> progress.label
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                if (progress.phase == Phase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    val eta = if (progress.bytesPerSecond > 0) (progress.totalBytes - progress.downloadedBytes) / progress.bytesPerSecond else -1
                    Text(
                        "${ModelDownloadManager.formatBytes(progress.downloadedBytes)} of ${ModelDownloadManager.formatBytes(progress.totalBytes)}" +
                            (if (progress.bytesPerSecond > 0) " • ${ModelDownloadManager.formatBytes(progress.bytesPerSecond)}/s" else "") +
                            (if (eta >= 0) " • ${eta / 60} min ${eta % 60} s left" else ""),
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (progress.phase == Phase.PREPARING) {
                        Text("Building GPU programs for this phone (once, up to a minute).", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (progress.phase == Phase.DOWNLOADING) {
                    TextButton(onClick = { viewModel.cancelModelDownload() }) { Text("Pause", color = Muted) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Keep the app open while it downloads; a paused download continues where it stopped.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    fun granted(vararg perms: String) = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("Permissions", "Each one is optional and only used for what's described. You can change them later in Android settings.")
        // `refresh` re-reads the grant state after each request.
        key(refresh) {
            PermissionCard(Icons.Default.Mic, "Microphone", "Voice conversations and the wake word.", granted(Manifest.permission.RECORD_AUDIO)) {
                launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            PermissionCard(Icons.Default.Phone, "Calls & messages", "\"Call Alice\", \"Text Mom I'm on my way\". Always confirmed first.",
                granted(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)) {
                launcher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS))
            }
            PermissionCard(Icons.Default.CalendarMonth, "Contacts, calendar & location", "Calling by name, your schedule, the morning briefing and local weather.",
                granted(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            PermissionCard(Icons.Default.CameraAlt, "Camera", "Ask about what the live camera sees, or take a photo to discuss.", granted(Manifest.permission.CAMERA)) {
                launcher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(Icons.Default.Notifications, "Notifications", "Reminders, scheduled tasks and the wake-word notice.", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }
    }
}

@Composable
private fun TourStep() {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("How it works", "A few things worth knowing.")
        InfoCard(Icons.Default.Mic, "Voice mode", "Tap the microphone and just talk. Start speaking while it answers to interrupt it, like a phone call. The red button ends the call.")
        InfoCard(Icons.Default.CheckCircle, "Everyday tools", "Alarms, timers, calls, messages, weather, calculations, charts, web search, notes, smart home and pictures, all from one sentence.")
        InfoCard(Icons.Default.Lock, "Memory you control", "It remembers facts you share so answers get personal. See or delete them in Settings > Memory, or use a temporary chat.")
        InfoCard(Icons.Default.PlayArrow, "Over any app", "Make it your default assistant to open it with the power button or a corner swipe, and ask about what's on screen.")
        OutlinedButton(
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Set as default assistant") }
        Spacer(Modifier.height(8.dp))
        Text("You can reopen this guide any time in Settings > Setup & guide.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun key(value: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(value) { content() }

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF5EEAD4), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Body, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF5EEAD4), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = Body, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Allowed", tint = Color(0xFF34D399))
        } else {
            TextButton(onClick = onGrant) { Text("Allow") }
        }
    }
}
