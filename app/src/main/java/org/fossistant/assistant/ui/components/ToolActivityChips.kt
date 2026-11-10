package org.fossistant.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.model.VisualKind

private data class ToolPresentation(val icon: ImageVector, val doneLabel: String, val runningLabel: String)

private fun presentationFor(toolName: String): ToolPresentation = when (toolName) {
    "search_web" -> ToolPresentation(Icons.Filled.Search, "Searched the web", "Searching the web")
    "read_webpage", "fetch_webpage" -> ToolPresentation(Icons.Filled.Language, "Read web page", "Reading web page")
    "get_weather" -> ToolPresentation(Icons.Filled.Cloud, "Weather", "Checking the weather")
    "get_date_time", "get_current_time", "get_current_date" -> ToolPresentation(Icons.Filled.Schedule, "Date & time", "Checking the time")
    "open_app" -> ToolPresentation(Icons.Filled.Apps, "Opened app", "Opening app")
    "set_alarm", "set_media_alarm" -> ToolPresentation(Icons.Filled.Alarm, "Alarm set", "Setting alarm")
    "set_timer", "control_stopwatch" -> ToolPresentation(Icons.Filled.Timer, "Timer", "Starting timer")
    "control_media", "play_music", "media_control", "play_audiobook" -> ToolPresentation(Icons.Filled.MusicNote, "Media", "Starting playback")
    "change_device_setting", "set_flashlight", "set_volume", "toggle_setting" -> ToolPresentation(Icons.Filled.Tune, "Device setting", "Changing setting")
    "navigate_to", "driving_mode_action" -> ToolPresentation(Icons.Filled.Navigation, "Navigation", "Starting navigation")
    "create_calendar_event", "reschedule_calendar_event" -> ToolPresentation(Icons.Filled.Event, "Calendar", "Adding to calendar")
    "get_calendar_events" -> ToolPresentation(Icons.Filled.Event, "Calendar", "Checking your calendar")
    "get_daily_briefing" -> ToolPresentation(Icons.Filled.WbSunny, "Daily briefing", "Preparing your briefing")
    "smart_home", "home_assistant" -> ToolPresentation(Icons.Filled.Home, "Smart home", "Talking to Home Assistant")
    "create_note" -> ToolPresentation(Icons.Filled.EditNote, "Note saved", "Saving note")
    "read_notifications", "reply_notification" -> ToolPresentation(Icons.Filled.Notifications, "Notifications", "Reading notifications")
    "make_phone_call", "make_call", "make_duo_call" -> ToolPresentation(Icons.Filled.Call, "Phone call", "Preparing call")
    "send_text_message", "send_sms", "send_email_or_media_message" -> ToolPresentation(Icons.Filled.Sms, "Message", "Preparing message")
    "remember_user_fact" -> ToolPresentation(Icons.Filled.Psychology, "Remembered", "Saving to memory")
    "control_screen", "screen_agent_action" -> ToolPresentation(Icons.Filled.TouchApp, "Screen control", "Using the screen")
    "assistant_feature" -> ToolPresentation(Icons.Filled.Extension, "Assistant", "Working on it")
    "calculate" -> ToolPresentation(Icons.Filled.Calculate, "Calculated", "Calculating")
    "show_chart" -> ToolPresentation(Icons.Filled.BarChart, "Chart", "Drawing chart")
    "show_diagram" -> ToolPresentation(Icons.Filled.AccountTree, "Infographic", "Drawing infographic")
    else -> ToolPresentation(Icons.Filled.Bolt, toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }, "Working on it")
}

/**
 * Compact chips showing which tools the assistant used for a reply: an icon, a plain-language label,
 * and what it was used for (search query, city, app...). Running tools show a spinner, failed ones a red
 * marker, and calls / messages waiting for the user's "yes" an amber "Waiting for your OK".
 */
@Composable
fun ToolActivityChips(
    tools: List<ToolExecutionResult>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    // A chart or infographic that is drawn needs no chip of its own.
    val shown = tools.filterNot { it.visual != null && it.visual.kind != VisualKind.PHOTOS }
    if (shown.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { tool -> ToolActivityChip(tool, accent) }
    }
}

@Composable
private fun ToolActivityChip(tool: ToolExecutionResult, accent: Color) {
    val presentation = presentationFor(tool.toolName)
    val failed = !tool.success && !tool.isPending
    val waitingColor = Color(0xFFFBBF24)
    val errorColor = Color(0xFFF87171)
    val tint = when {
        failed -> errorColor
        tool.awaitingConfirmation -> waitingColor
        else -> accent
    }
    val label = when {
        tool.isPending -> "${presentation.runningLabel}…"
        tool.awaitingConfirmation -> "Waiting for your OK"
        failed -> "${presentation.doneLabel} failed"
        else -> presentation.doneLabel
    }
    val detail = when {
        failed -> tool.summary.takeIf { it.isNotBlank() } ?: tool.displayDetail
        tool.awaitingConfirmation -> tool.summary.takeIf { it.isNotBlank() } ?: tool.displayDetail
        else -> tool.displayDetail
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.30f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (tool.isPending) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(13.dp)
            )
        } else {
            Icon(
                imageVector = if (failed) Icons.Filled.ErrorOutline else presentation.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        if (!detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = detail,
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
