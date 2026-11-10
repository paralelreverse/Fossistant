package org.fossistant.assistant.engine

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.model.VisualArgs
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.SystemControls

/**
 * An action Gemma asked for that must not run without the user's explicit OK (calls, texts, emails,
 * broadcasts). [DualModelRouter] executes it when the next user turn is a "yes".
 */
data class PendingConfirmation(
    val request: ToolCallRequest,
    val description: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Native LiteRT-LM tool set for Gemma 4 E2B. Gemma decides when to call a tool and writes the arguments
 * itself (for example its own web search query); LiteRT-LM runs the method and feeds the returned text
 * back into the conversation (automatic tool calling). Method names become snake_case tool names
 * (`searchWeb` -> `search_web`).
 *
 * Descriptions are kept short on purpose: every declaration is part of the prompt, and the KV cache is
 * only 4096 tokens on 8 GB phones. The long tail of assistant features is reached through
 * [assistantFeature].
 */
class FossistantGemmaToolSet(
    private val executor: DeviceAndWebToolExecutor,
    private val memoryManager: AiMemoryManager,
    private val onToolFinished: (ToolExecutionResult) -> Unit,
    private val onConfirmationNeeded: (PendingConfirmation) -> Unit,
    private val isDeviceLocked: () -> Boolean = { false },
    private val isTemporaryChat: () -> Boolean = { false }
) : ToolSet {

    @Tool(description = "Search the internet for current information: news, facts, prices, release dates, people, events.")
    fun searchWeb(
        @ToolParam(description = "A few keywords you write for what the user wants to know, not their whole sentence, e.g. 'Gemini 3 release date'") query: String
    ): String = run("search_web", query) {
        executor.execute(ToolCallRequest("search_web", mapOf("query" to query)))
    }

    @Tool(description = "Read the main text of a web page.")
    fun readWebpage(
        @ToolParam(description = "Full http(s) URL") url: String
    ): String = run("read_webpage", url) {
        executor.execute(ToolCallRequest("fetch_webpage", mapOf("url" to url)))
    }

    @Tool(description = "Get the current weather.")
    fun getWeather(
        @ToolParam(description = "City name, or 'current_location'") location: String
    ): String = run("get_weather", location) {
        executor.execute(ToolCallRequest("get_weather", mapOf("location" to location.ifBlank { "current_location" })))
    }

    @Tool(description = "Calculate a math expression exactly. Use it for every calculation instead of working it out yourself.")
    fun calculate(
        @ToolParam(description = "The expression, e.g. '(1250 * 12) / 7', '15% of 80', 'sqrt(2) * pi', '2^10'; angles in radians unless written like 30deg") expression: String
    ): String = run("calculate", null) {
        executor.execute(ToolCallRequest("calculate", mapOf("expression" to expression)))
    }

    @Tool(description = "Show the user a chart of numbers: bar to compare, line for change over time, pie for parts of a whole.")
    fun showChart(
        @ToolParam(description = "bar, line or pie") type: String,
        @ToolParam(description = "Chart title") title: String,
        @ToolParam(description = "Labels separated by ';', e.g. 'Mon; Tue; Wed'") labels: String,
        @ToolParam(description = "Numbers in the same order separated by ';', e.g. '12; 7.5; 20'") values: String,
        @ToolParam(description = "Unit of the values, e.g. '°C' or 'USD'; may be empty") unit: String
    ): String = run("show_chart", title) {
        VisualArgs.chart(type, title, labels, values, unit).fold(
            onSuccess = { visual ->
                ToolExecutionResult(
                    "show_chart", true,
                    "The chart \"${visual.title}\" (${visual.values.size} values) is now shown to the user above your reply. " +
                        "Say in a sentence or two what it shows; don't list the numbers again.",
                    visual = visual
                )
            },
            onFailure = { ToolExecutionResult("show_chart", false, "Chart not shown: ${it.message}") }
        )
    }

    @Tool(description = "Show the user an infographic: the topic in a box with arrows to boxes that break it into parts (breakdown) or show steps in order (flow).")
    fun showDiagram(
        @ToolParam(description = "breakdown or flow") type: String,
        @ToolParam(description = "The topic, shown in the top box") title: String,
        @ToolParam(description = "Boxes separated by ';', each 'Heading: short text', e.g. 'Rent: 40% of the budget; Food: 25%'") items: String
    ): String = run("show_diagram", title) {
        VisualArgs.diagram(type, title, items).fold(
            onSuccess = { visual ->
                ToolExecutionResult(
                    "show_diagram", true,
                    "The infographic \"${visual.title}\" (${visual.boxes.size} boxes) is now shown to the user above your reply. " +
                        "Add a short comment; don't repeat every box.",
                    visual = visual
                )
            },
            onFailure = { ToolExecutionResult("show_diagram", false, "Infographic not shown: ${it.message}") }
        )
    }

    @Tool(description = "Get the current local date and time.")
    fun getDateTime(): String = run("get_date_time", null) {
        val time = executor.execute(ToolCallRequest("get_current_time", emptyMap()))
        val date = executor.execute(ToolCallRequest("get_current_date", emptyMap()))
        ToolExecutionResult("get_date_time", time.success && date.success, "${time.summary} ${date.summary}")
    }

    @Tool(description = "Open an installed app.")
    fun openApp(
        @ToolParam(description = "App name, e.g. 'Spotify'") appName: String
    ): String = run("open_app", appName) {
        executor.execute(ToolCallRequest("open_app", mapOf("app_name" to appName)))
    }

    @Tool(description = "Set an alarm.")
    fun setAlarm(
        @ToolParam(description = "Hour, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int,
        @ToolParam(description = "Label, may be empty") label: String
    ): String = run("set_alarm", "%d:%02d".format(hour, minute)) {
        executor.execute(
            ToolCallRequest("set_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label.ifBlank { "Fossistant" }))
        )
    }

    @Tool(description = "Start a countdown timer.")
    fun setTimer(
        @ToolParam(description = "Duration in seconds") seconds: Int,
        @ToolParam(description = "Label, may be empty") label: String
    ): String = run("set_timer", formatDuration(seconds)) {
        executor.execute(ToolCallRequest("set_timer", mapOf("seconds" to seconds.toString(), "label" to label.ifBlank { "Fossistant Timer" })))
    }

    @Tool(description = "Control the stopwatch.")
    fun controlStopwatch(
        @ToolParam(description = "start, pause, lap, reset or status") action: String
    ): String = run("control_stopwatch", action) {
        executor.execute(ToolCallRequest("control_stopwatch", mapOf("action" to action)))
    }

    @Tool(description = "Play music or control whatever is playing (music, podcasts, videos) in any app.")
    fun controlMedia(
        @ToolParam(description = "play, pause, resume, stop, next, previous, restart, forward, rewind, seek, shuffle_on, shuffle_off, repeat_one, repeat_all, repeat_off, speed, like, dislike or whats_playing") action: String,
        @ToolParam(description = "Song, artist or playlist for 'play'; otherwise empty") query: String,
        @ToolParam(description = "Music app the user named, e.g. 'Spotify'; otherwise empty") app: String = "",
        @ToolParam(description = "forward / rewind: seconds, e.g. '30'; seek: position, e.g. '1:30'; speed: e.g. '1.5'; otherwise empty") value: String = ""
    ): String = run("control_media", query.ifBlank { listOf(action, value).filter { it.isNotBlank() }.joinToString(" ") }) {
        if (action.equals("play", ignoreCase = true) && query.isNotBlank()) {
            executor.execute(ToolCallRequest("play_music", mapOf("query" to query, "app" to app.trim())))
        } else {
            executor.execute(ToolCallRequest("media_control", mapOf("action" to action.lowercase(), "value" to value.trim())))
        }
    }

    @Tool(description = "Find actions installed apps offer (shortcuts and App Actions), e.g. 'start a run in Strava', 'new note in Keep'. Use it when no other tool fits a request about an app.")
    fun findAppActions(
        @ToolParam(description = "What the user wants to do, with the app's name if they said it") query: String
    ): String = run("find_app_actions", query) {
        executor.execute(ToolCallRequest("find_app_actions", mapOf("query" to query)))
    }

    @Tool(description = "Run an installed app's action by its id (from the app actions list or find_app_actions).")
    fun runAppAction(
        @ToolParam(description = "The action id, e.g. 'keep.new_note'") actionId: String,
        @ToolParam(description = "Inputs as 'name=value; name=value', or empty") inputs: String
    ): String {
        val request = ToolCallRequest("run_app_action", mapOf("action_id" to actionId.trim(), "inputs" to inputs))
        val action = executor.appActions?.byId(actionId)
        if (action != null && action.isSensitive) {
            return needsConfirmation(request, "run_app_action", "${action.label.replaceFirstChar { it.uppercase() }} in ${action.appLabel}", action.appLabel)
        }
        return run("run_app_action", action?.let { "${it.appLabel}: ${it.label}" } ?: actionId) { executor.execute(request) }
    }

    @Tool(description = "Change a phone setting: flashlight, volume (media, ring, notification, alarm, call), mute, ringer (sound / vibrate / silent), brightness, adaptive brightness, auto-rotate, screen timeout, Wi-Fi, Bluetooth, mobile data, airplane mode, location, hotspot, NFC, battery saver, Do Not Disturb, dark mode.")
    fun changeDeviceSetting(
        @ToolParam(description = "flashlight, volume, ring_volume, notification_volume, alarm_volume, call_volume, mute, ringer, brightness, auto_brightness, auto_rotate, screen_timeout, wifi, bluetooth, mobile_data, airplane_mode, location, hotspot, nfc, battery_saver, do_not_disturb or dark_mode") setting: String,
        @ToolParam(description = "'on' or 'off'; volume / brightness 0-100, '+20' or '-20'; ringer: normal, vibrate or silent; screen_timeout: e.g. '2 minutes'") value: String
    ): String = run("change_device_setting", "$setting $value") {
        val enable = SystemControls.isOn(value)
        when (setting.trim().lowercase().replace(' ', '_').replace('-', '_')) {
            "flashlight", "torch", "light", "flash" -> executor.execute(ToolCallRequest("set_flashlight", mapOf("enabled" to enable.toString())))
            "volume" -> executor.execute(ToolCallRequest("set_volume", mapOf("level" to value.trim())))
            "wifi", "wi_fi", "bluetooth", "do_not_disturb", "dnd", "dark_mode", "night_mode" ->
                executor.execute(ToolCallRequest("toggle_setting", mapOf("setting" to setting, "enabled" to enable.toString())))
            else -> executor.execute(ToolCallRequest("system_setting", mapOf("setting" to setting, "value" to value)))
        }
    }

    @Tool(description = "Do a phone action: lock the screen, take a screenshot, open notifications, quick settings, recent apps, home, back, the power menu or split screen; open the camera or video camera; show, dismiss or snooze alarms; show timers; or tell the battery level.")
    fun phoneAction(
        @ToolParam(description = "lock_screen, screenshot, notifications, quick_settings, recent_apps, home, back, power_menu, split_screen, camera, video, show_alarms, dismiss_alarm, snooze_alarm, show_timers or battery") action: String
    ): String = run("phone_action", action) {
        executor.execute(ToolCallRequest("phone_action", mapOf("action" to action)))
    }

    @Tool(description = "Control smart home devices through Home Assistant: lights, switches, fans, thermostats, blinds, locks, scenes.")
    fun smartHome(
        @ToolParam(description = "on, off, toggle, status, brightness, temperature, open, close, lock, unlock or activate") action: String,
        @ToolParam(description = "Device or scene name as the user calls it, e.g. 'living room lights'") device: String,
        @ToolParam(description = "Brightness percent or temperature; otherwise empty") value: String
    ): String {
        val request = ToolCallRequest("home_assistant", mapOf("action" to action, "device" to device, "value" to value))
        val verb = action.trim().lowercase()
        // Unlocking, or opening a door / gate / garage, always needs a spoken "yes".
        if (verb == "unlock" || (verb == "open" && Regex("(?i)door|gate|garage").containsMatchIn(device))) {
            return needsConfirmation(request, "smart_home", "${verb.replaceFirstChar { it.uppercase() }} the $device", device)
        }
        return run("smart_home", listOf(action, device, value).filter { it.isNotBlank() }.joinToString(" ")) {
            executor.execute(request)
        }
    }

    @Tool(description = "Start turn-by-turn navigation.")
    fun navigateTo(
        @ToolParam(description = "Destination address or place") destination: String
    ): String = run("navigate_to", destination) {
        executor.execute(ToolCallRequest("navigate_to", mapOf("destination" to destination)))
    }

    @Tool(description = "Create a calendar event.")
    fun createCalendarEvent(
        @ToolParam(description = "What the event is") title: String,
        @ToolParam(description = "When, e.g. 'tomorrow 3pm', may be empty") time: String
    ): String = run("create_calendar_event", listOf(title, time).filter { it.isNotBlank() }.joinToString(", ")) {
        executor.execute(ToolCallRequest("create_calendar_event", mapOf("title" to listOf(title, time).filter { it.isNotBlank() }.joinToString(" "))))
    }

    @Tool(description = "Read the user's calendar events for a day.")
    fun getCalendarEvents(
        @ToolParam(description = "today, tomorrow, a weekday name, or a number of days ahead") day: String
    ): String = run("get_calendar_events", day.ifBlank { "today" }) {
        executor.execute(ToolCallRequest("get_calendar_events", mapOf("day" to day.ifBlank { "today" })))
    }

    @Tool(description = "Get the data for a daily briefing: weather, today's calendar and recent notifications. Only when the user asks for their briefing or how their day looks ('good morning', 'brief me', 'what's my day like'); not for 'hi', 'what's up' or other small talk.")
    fun getDailyBriefing(): String = run("get_daily_briefing", null) {
        executor.execute(ToolCallRequest("get_daily_briefing", emptyMap()))
    }

    @Tool(description = "Save a note.")
    fun createNote(
        @ToolParam(description = "Short title") title: String,
        @ToolParam(description = "Note text") content: String
    ): String = run("create_note", title.ifBlank { content.take(40) }) {
        executor.execute(ToolCallRequest("create_note", mapOf("title" to title.ifBlank { "Quick Note" }, "content" to content)))
    }

    @Tool(description = "Read the user's recent notifications.")
    fun readNotifications(): String = run("read_notifications", null) {
        executor.execute(ToolCallRequest("read_notifications", emptyMap()))
    }

    @Tool(description = "Call a contact or number. The user must confirm before it happens.")
    fun makePhoneCall(
        @ToolParam(description = "Contact name or phone number") contact: String
    ): String = needsConfirmation(
        request = ToolCallRequest("make_call", mapOf("target" to contact)),
        toolName = "make_phone_call",
        description = "Call $contact",
        detail = contact
    )

    @Tool(description = "Send an SMS text message. The user must confirm before it is sent.")
    fun sendTextMessage(
        @ToolParam(description = "Contact name or phone number") recipient: String,
        @ToolParam(description = "Message text") message: String
    ): String = needsConfirmation(
        request = ToolCallRequest("send_sms", mapOf("recipient" to recipient, "body" to message)),
        toolName = "send_text_message",
        description = "Text $recipient: \"$message\"",
        detail = recipient
    )

    @Tool(description = "Remember a fact about the user in long-term memory.")
    fun rememberUserFact(
        @ToolParam(description = "The fact, e.g. 'Lives in Warsaw'") fact: String
    ): String = run("remember_user_fact", fact) {
        if (isDeviceLocked()) {
            ToolExecutionResult("remember_user_fact", false, org.fossistant.assistant.util.DeviceLock.LOCKED_MESSAGE)
        } else if (isTemporaryChat()) {
            ToolExecutionResult("remember_user_fact", false, "This is a temporary chat, so nothing is saved to memory.")
        } else {
            val stored = memoryManager.rememberFact(fact)
            ToolExecutionResult("remember_user_fact", true, "Saved to memory: ${stored.fact}")
        }
    }

    @Tool(description = "Make a PDF document and save it to Downloads. Use it when the user asks for a PDF.")
    fun createPdf(
        @ToolParam(description = "Document title") title: String,
        @ToolParam(description = "The full document text you wrote; '# ' starts a heading, '- ' a bullet. Empty = your previous answer") content: String
    ): String = run("create_pdf", title) {
        executor.execute(ToolCallRequest("create_pdf", mapOf("title" to title, "content" to content)))
    }

    @Tool(description = "Schedule a task to run later or repeatedly, e.g. a daily news summary or a reminder that needs research.")
    fun scheduleTask(
        @ToolParam(description = "What to do when it runs, written as a request, e.g. 'Summarize today's tech news'") instruction: String,
        @ToolParam(description = "When, as HH:mm in 24-hour time") time: String,
        @ToolParam(description = "once, daily, weekdays or hourly") repeat: String
    ): String = run("schedule_task", "$time $repeat") {
        executor.execute(ToolCallRequest("schedule_task", mapOf("instruction" to instruction, "time" to time, "repeat" to repeat)))
    }

    @Tool(description = "Call a tool from the user's connectors (MCP servers) listed in your instructions.")
    fun useConnector(
        @ToolParam(description = "Connector tool name") tool: String,
        @ToolParam(description = "Arguments as a JSON object, e.g. {\"query\":\"milk\"}; '{}' if none") argumentsJson: String
    ): String = run("use_connector", tool) {
        executor.execute(ToolCallRequest("use_connector", mapOf("tool" to tool, "arguments" to argumentsJson)))
    }

    /** Called by the engine at the start of every generation. */
    fun startTurn() {}

    @Tool(description = "Other assistant features: audiobook, recipe, meditation, workout, sleep_summary, health (steps, sleep, heart rate), commute, flight_status, contact_info, driving_mode, video_call, email, family_broadcast.")
    fun assistantFeature(
        @ToolParam(description = "One of the features listed above") feature: String,
        @ToolParam(description = "Details: title, recipe, destination, flight, contact, or 'recipient: message' for email") details: String
    ): String {
        val name = feature.trim().lowercase().replace(' ', '_')
        val request = when (name) {
            "audiobook" -> ToolCallRequest("play_audiobook", mapOf("query" to details))
            "recipe" -> ToolCallRequest("manage_cookbook", mapOf("action" to "steps", "recipe" to details))
            "meditation" -> ToolCallRequest("start_meditation", mapOf("topic" to details.ifBlank { "10 minute mindfulness" }))
            "workout" -> ToolCallRequest("control_fitness_activity", mapOf("action" to "start", "activity" to details.ifBlank { "running" }))
            "sleep_summary" -> ToolCallRequest("get_sleep_summary", emptyMap())
            "health", "steps", "heart_rate" -> ToolCallRequest("get_health_summary", mapOf("metric" to details.ifBlank { name }))
            "commute" -> ToolCallRequest("get_commute_estimate", mapOf("destination" to details.ifBlank { "Work" }))
            "flight_status" -> ToolCallRequest("check_flight_status", mapOf("query" to details))
            "contact_info" -> ToolCallRequest("get_contact_info", mapOf("name" to details))
            "driving_mode" -> ToolCallRequest("driving_mode_action", mapOf("action" to "navigate", "target" to details))
            "video_call" -> return needsConfirmation(
                ToolCallRequest("make_duo_call", mapOf("target" to details, "video" to "true")),
                "assistant_feature", "Video call $details", details
            )
            "email" -> {
                val recipient = details.substringBefore(':').trim()
                val body = details.substringAfter(':', "").trim()
                return needsConfirmation(
                    ToolCallRequest("send_email_or_media_message", mapOf("modality" to "email", "recipient" to recipient, "body" to body)),
                    "assistant_feature", "Email $recipient: \"$body\"", recipient
                )
            }
            "family_broadcast" -> return needsConfirmation(
                ToolCallRequest("family_broadcast", mapOf("message" to details)),
                "assistant_feature", "Broadcast \"$details\" to your home devices", details
            )
            else -> return "Error: unknown feature '$feature'. Use one of: audiobook, recipe, meditation, workout, sleep_summary, health, commute, flight_status, contact_info, driving_mode, video_call, email, family_broadcast."
        }
        return run("assistant_feature", listOf(name, details).filter { it.isNotBlank() }.joinToString(": ")) {
            executor.execute(request)
        }
    }

    /** Runs a tool, reports it for the UI, and returns the text Gemma sees. */
    private fun run(toolName: String, detail: String?, block: suspend () -> ToolExecutionResult): String {
        val result = try {
            runBlocking { block() }
        } catch (t: Throwable) {
            ToolExecutionResult(toolName, false, "Failed: ${t.message ?: t::class.simpleName}")
        }
        onToolFinished(result.copy(toolName = toolName, displayDetail = detail?.takeIf { it.isNotBlank() } ?: result.displayDetail))
        val text = (result.rawPayload?.takeIf { it.isNotBlank() } ?: result.summary).take(MAX_RESULT_CHARS)
        return if (result.success) text else "Error: $text"
    }

    private fun needsConfirmation(request: ToolCallRequest, toolName: String, description: String, detail: String): String {
        onConfirmationNeeded(PendingConfirmation(request, description))
        onToolFinished(
            ToolExecutionResult(
                toolName = toolName,
                success = true,
                summary = description,
                displayDetail = detail,
                awaitingConfirmation = true
            )
        )
        return "Not done yet. Ask the user once, briefly: \"$description?\" It will only happen if they say yes."
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
        seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }

    private companion object {
        // Keeps one tool result to roughly 400 tokens of the 4096-token context.
        const val MAX_RESULT_CHARS = 1500
        const val MAX_SCREEN_ACTIONS_PER_TURN = 12
    }
}
