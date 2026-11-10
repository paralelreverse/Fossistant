package org.fossistant.assistant.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.features.Project
import org.fossistant.assistant.features.Skill
import org.fossistant.assistant.engine.ImageGenEngine
import org.fossistant.assistant.model.BlobMoodTheme
import org.fossistant.assistant.model.ImageGenModelChoice

/** What the plus menu shows as switched on. */
data class PlusMenuState(
    val isResearchArmed: Boolean,
    val isReasoningOn: Boolean,
    val isTemporaryChat: Boolean,
    val activeProjectId: String?,
    val activeProjectName: String?,
    val imageGenArmed: ImageGenModelChoice? = null,
    val imageGenReady: Set<ImageGenModelChoice> = emptySet(),
    val imageGenDownloads: Map<ImageGenModelChoice, ImageGenEngine.DownloadProgress> = emptyMap()
)

/** What the plus menu can do; every callback closes the sheet first except the switches. */
data class PlusMenuActions(
    val onCamera: () -> Unit = {},
    val onPhotos: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onAudio: () -> Unit = {},
    val onVideo: () -> Unit = {},
    val onToggleResearch: (Boolean) -> Unit = {},
    val onToggleReasoning: () -> Unit = {},
    val onToggleTemporary: (Boolean) -> Unit = {},
    val onPickSkill: (Skill) -> Unit = {},
    val onPickProject: (Project?) -> Unit = {},
    val onMakePdf: () -> Unit = {},
    /** Opens the Tools screen, optionally at a section ("SKILLS", "PROJECTS"). */
    val onOpenTools: (String?) -> Unit = {},
    /** Switches image mode on/off with a downloaded model, or starts downloading a missing one. */
    val onPickImageModel: (ImageGenModelChoice) -> Unit = {},
    val onCancelImageDownload: (ImageGenModelChoice) -> Unit = {}
)

private enum class PlusPage { MAIN, SKILLS, PROJECTS }

private val SheetBackground = Color(0xFF0D111C)
private val TileBackground = Color(0xFF161B29)
private val GroupBackground = Color(0xFF131826)
private val TitleColor = Color(0xFFE8ECF4)
private val SubtitleColor = Color(0xFF8A94A8)

/**
 * The plus button's sheet: quick tiles for adding a photo or file, then grouped options for how the next
 * message is handled (Deep Research, reasoning), skills, the chat's project, temporary chat, PDF and more tools.
 */
@Composable
fun PlusMenuSheet(
    state: PlusMenuState,
    actions: PlusMenuActions,
    moodTheme: BlobMoodTheme,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(PlusPage.MAIN) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBackground,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 44.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(moodTheme.primaryAccent, moodTheme.secondaryAccent)))
            )
        }
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            when (page) {
                PlusPage.MAIN -> MainPage(state, actions, moodTheme, onDismiss) { page = it }
                PlusPage.SKILLS -> SkillsPage(moodTheme, onBack = { page = PlusPage.MAIN }, onManage = {
                    onDismiss(); actions.onOpenTools("SKILLS")
                }) { skill -> onDismiss(); actions.onPickSkill(skill) }
                PlusPage.PROJECTS -> ProjectsPage(state.activeProjectId, moodTheme, onBack = { page = PlusPage.MAIN }, onManage = {
                    onDismiss(); actions.onOpenTools("PROJECTS")
                }) { project -> onDismiss(); actions.onPickProject(project) }
            }
        }
    }
}

@Composable
private fun MainPage(
    state: PlusMenuState,
    actions: PlusMenuActions,
    moodTheme: BlobMoodTheme,
    onDismiss: () -> Unit,
    onNavigate: (PlusPage) -> Unit
) {
    val skillCount by (FossistantApp.instance?.features?.skills?.items ?: MutableStateFlow(emptyList())).collectAsState()
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Quick add tiles
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
        ) {
            val tiles = listOf(
                Triple("Camera", Icons.Default.PhotoCamera, actions.onCamera),
                Triple("Photos", Icons.Default.Image, actions.onPhotos),
                Triple("Files", Icons.Default.UploadFile, actions.onFiles),
                Triple("Audio", Icons.Default.AudioFile, actions.onAudio),
                Triple("Video", Icons.Default.SmartDisplay, actions.onVideo)
            )
            items(tiles) { (label, icon, action) ->
                QuickTile(label, icon, moodTheme) { onDismiss(); action() }
            }
        }

        // How the next message is answered
        Group {
            ToggleRow(
                icon = Icons.Default.TravelExplore,
                title = "Deep research",
                subtitle = "Searches the web, reads the sources and writes a cited report",
                checked = state.isResearchArmed,
                moodTheme = moodTheme
            ) { actions.onToggleResearch(it) }
            Divider()
            ToggleRow(
                icon = Icons.Default.Psychology,
                title = "Think it through",
                subtitle = "Reasons step by step before answering (slower)",
                checked = state.isReasoningOn,
                moodTheme = moodTheme
            ) { actions.onToggleReasoning() }
        }

        // Image generation on the phone (direct 1-tap toggle for the fast 4-step model)
        Group {
            CreateImageRow(
                state = state,
                actions = actions,
                moodTheme = moodTheme,
                onDismiss = onDismiss
            )
        }

        Group {
            NavRow(
                icon = Icons.Default.AutoAwesome,
                title = "Skills",
                subtitle = "${skillCount.count { it.enabled }} ready to use",
                moodTheme = moodTheme
            ) { onNavigate(PlusPage.SKILLS) }
            Divider()
            NavRow(
                icon = Icons.Default.Folder,
                title = "Project",
                subtitle = state.activeProjectName ?: "None",
                moodTheme = moodTheme
            ) { onNavigate(PlusPage.PROJECTS) }
        }

        Group {
            ToggleRow(
                icon = Icons.Default.VisibilityOff,
                title = "Temporary chat",
                subtitle = "Not saved, nothing goes to memory",
                checked = state.isTemporaryChat,
                moodTheme = moodTheme
            ) { actions.onToggleTemporary(it) }

            Divider()
            NavRow(
                icon = Icons.Default.Apps,
                title = "More tools",
                subtitle = "Interpreter, recorder, scheduled tasks, connectors",
                moodTheme = moodTheme
            ) { onDismiss(); actions.onOpenTools(null) }
        }
    }
}

@Composable
private fun SkillsPage(moodTheme: BlobMoodTheme, onBack: () -> Unit, onManage: () -> Unit, onPick: (Skill) -> Unit) {
    val skills by (FossistantApp.instance?.features?.skills?.items ?: MutableStateFlow(emptyList())).collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = skills.filter { it.enabled }.filter {
        query.isBlank() || it.name.contains(query, true) || it.instructions.contains(query, true) ||
            it.triggers.any { t -> t.contains(query, true) }
    }
    PageHeader("Skills", onBack)
    SearchField(query, "Search skills", moodTheme) { query = it }
    LazyColumn(modifier = Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
        items(shown, key = { it.id }) { skill ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(skill) }
                    .padding(vertical = 12.dp, horizontal = 6.dp)
            ) {
                Text(skill.triggers.firstOrNull()?.let { "/$it" } ?: skill.name, color = TitleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(skill.name, color = moodTheme.secondaryAccent, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text(skill.instructions, color = SubtitleColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item { ManageRow("Manage skills", moodTheme, onManage) }
    }
}

@Composable
private fun ProjectsPage(
    activeProjectId: String?,
    moodTheme: BlobMoodTheme,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onPick: (Project?) -> Unit
) {
    val projects by (FossistantApp.instance?.features?.projects?.items ?: MutableStateFlow(emptyList())).collectAsState()
    PageHeader("Project", onBack)
    LazyColumn(modifier = Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
        item { ProjectRow("None", "Not in a project", activeProjectId == null, moodTheme) { onPick(null) } }
        items(projects, key = { it.id }) { project ->
            ProjectRow(
                project.name,
                "${project.files.size} file(s)" + if (project.instructions.isNotBlank()) " • instructions" else "",
                project.id == activeProjectId,
                moodTheme
            ) { onPick(project) }
        }
        item { ManageRow(if (projects.isEmpty()) "Create a project" else "Manage projects", moodTheme, onManage) }
    }
}

@Composable
private fun CreateImageRow(
    state: PlusMenuState,
    actions: PlusMenuActions,
    moodTheme: BlobMoodTheme,
    onDismiss: () -> Unit
) {
    val choice = ImageGenModelChoice.DREAMSHAPER_FAST
    val engine = FossistantApp.instance?.imageGenEngine
    val download = state.imageGenDownloads[choice]
    val ready = choice in state.imageGenReady || (engine?.isReady(choice) == true)
    val isArmed = state.imageGenArmed == choice
    val subtitle = when {
        isArmed -> "On • Type what you want to see (or attach a photo to edit)"
        download != null && download.error == null ->
            "Downloading ${formatSize(download.downloadedBytes)} of ${formatSize(download.totalBytes)}…"
        download?.error != null -> download.error
        ready -> "Fast 4-step Stable Diffusion on this phone"
        else -> "Download once (${formatSize(engine?.missingBytes(choice) ?: choice.totalBytes)}) • Fast 4-step Stable Diffusion"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = download == null || download.error != null) {
                if (ready && !isArmed) onDismiss()
                actions.onPickImageModel(choice)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Default.Palette, moodTheme)
            Spacer(Modifier.width(14.dp))
            RowText("Create image", subtitle, Modifier.weight(1f))
            when {
                download != null && download.error == null -> Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel download",
                    tint = SubtitleColor,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { actions.onCancelImageDownload(choice) }
                        .padding(8.dp)
                )
                ready -> Switch(
                    checked = isArmed,
                    onCheckedChange = {
                        if (!isArmed) onDismiss()
                        actions.onPickImageModel(choice)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = moodTheme.secondaryAccent.copy(alpha = 0.75f),
                        uncheckedThumbColor = Color(0xFFCBD5E1),
                        uncheckedTrackColor = Color(0xFF262D3F),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
                else -> Text(
                    if (download?.error != null) "Retry" else "Download",
                    color = moodTheme.secondaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (download != null && download.error == null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { download.fraction },
                color = moodTheme.secondaryAccent,
                trackColor = Color(0xFF262D3F),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"

// ---- Building blocks ------------------------------------------------------------------------------------

@Composable
private fun QuickTile(label: String, icon: ImageVector, moodTheme: BlobMoodTheme, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(98.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(TileBackground)
            .border(1.dp, moodTheme.secondaryAccent.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(moodTheme.coreNebulaColor.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TitleColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GroupBackground),
        content = content
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .padding(start = 68.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF1E2434))
    )
}

@Composable
private fun RowIcon(icon: ImageVector, moodTheme: BlobMoodTheme) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFF1C2232)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = moodTheme.secondaryAccent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(title, color = TitleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = SubtitleColor, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    moodTheme: BlobMoodTheme,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, moodTheme)
        Spacer(Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = moodTheme.secondaryAccent.copy(alpha = 0.75f),
                uncheckedThumbColor = Color(0xFFCBD5E1),
                uncheckedTrackColor = Color(0xFF262D3F),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    moodTheme: BlobMoodTheme,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, moodTheme)
        Spacer(Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        if (showChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SubtitleColor)
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TitleColor)
        }
        Text(title, color = TitleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SearchField(query: String, hint: String, moodTheme: BlobMoodTheme, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(TileBackground)
            .border(1.dp, moodTheme.secondaryAccent.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = SubtitleColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = SubtitleColor, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TitleColor, fontSize = 15.sp),
                cursorBrush = SolidColor(moodTheme.secondaryAccent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProjectRow(name: String, detail: String, selected: Boolean, moodTheme: BlobMoodTheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = TitleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = SubtitleColor, fontSize = 12.5.sp)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = moodTheme.secondaryAccent)
    }
}

@Composable
private fun ManageRow(label: String, moodTheme: BlobMoodTheme, onClick: () -> Unit) {
    Text(
        label,
        color = moodTheme.secondaryAccent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp)
    )
}
