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
 * The `reply` input of every action tool. Required, so Gemma always writes it (strict tool calls skip optional
 * inputs); what it should say is explained once in the system prompt.
 */
private const val REPLY_HELP = "Done message"

/**
 * An action Gemma asked for that must not run without the user's explicit OK (calls, texts, emails,
 * broadcasts). [GemmaLiteRtEngine] executes it when the next user turn is a "yes".
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
 * Every declaration is part of the prompt, so descriptions are terse and each list of options is written once
 * (in the input that takes it). Tools that only work on some phones live in their own groups
 * ([smartHomeTools], [connectorTools], [appActionTools]) and are declared only where they can work.
 */
class FossistantGemmaToolSet(
    private val executor: DeviceAndWebToolExecutor,
    private val memoryManager: AiMemoryManager,
    private val onToolFinished: (ToolExecutionResult) -> Unit,
    private val onConfirmationNeeded: (PendingConfirmation) -> Unit,
    private val isDeviceLocked: () -> Boolean = { false },
    private val isTemporaryChat: () -> Boolean = { false },
    /** A finished action's own reply: shown at once, so the user doesn't wait for Gemma's second pass. */
    private val onInstantReply: (String) -> Unit = {},
    /** True once this turn's answer is shown: tools then do nothing (Gemma's hidden second pass). */
    private val isTurnAnswered: () -> Boolean = { false }
) : ToolSet {

    /** Home Assistant control: declared only when Home Assistant is set up. */
    val smartHomeTools = SmartHomeTools()

    /** The user's MCP connectors: declared only when they have some. */
    val connectorTools = ConnectorTools()

    /** Installed apps' shortcuts and App Actions: declared only when the phone has any. */
    val appActionTools = AppActionTools()

    /** Screen reading & UI automation: declared only when Accessibility Service or screen context is active. */
    val screenAgentTools = ScreenAgentTools()

    /** Notification reading & inline reply: declared only when Notification Listener access is active. */
    val notificationTools = NotificationTools()

    /** Every tool group, for running unparsed Gemma tool calls by name ([ToolSetInvoker]). */
    val allToolSets: List<ToolSet>
        get() = listOf(this, smartHomeTools, connectorTools, appActionTools, screenAgentTools, notificationTools)

    @Tool(description = "Search the web for current information.")
    fun searchWeb(
        @ToolParam(description = "A few keywords") query: String
    ): String = run("search_web", query) {
        executor.execute(ToolCallRequest("search_web", mapOf("query" to query)))
    }

    @Tool(description = "Read a web page.")
    fun readWebpage(
        @ToolParam(description = "http(s) URL") url: String
    ): String = run("read_webpage", url) {
        executor.execute(ToolCallRequest("fetch_webpage", mapOf("url" to url)))
    }

    @Tool(description = "Current weather.")
    fun getWeather(
        @ToolParam(description = "City or 'current_location'") location: String
    ): String = run("get_weather", location) {
        executor.execute(ToolCallRequest("get_weather", mapOf("location" to location.ifBlank { "current_location" })))
    }

    @Tool(description = "Exact math. Use it for every calculation.")
    fun calculate(
        @ToolParam(description = "e.g. '(1250*12)/7', '15% of 80', 'sqrt(2)*pi', 'sin(30deg)'") expression: String
    ): String = run("calculate", null) {
        executor.execute(ToolCallRequest("calculate", mapOf("expression" to expression)))
    }

    @Tool(description = "Draw a chart.")
    fun showChart(
        @ToolParam(description = "bar, line or pie") type: String,
        @ToolParam(description = "Title") title: String,
        @ToolParam(description = "Labels, ';'-separated") labels: String,
        @ToolParam(description = "Numbers, ';'-separated") values: String,
        @ToolParam(description = "Unit or empty") unit: String
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

    @Tool(description = "Draw an infographic: a topic box with arrows to its parts or steps.")
    fun showDiagram(
        @ToolParam(description = "breakdown or flow") type: String,
        @ToolParam(description = "Topic") title: String,
        @ToolParam(description = "Boxes, ';'-separated, each 'Heading: short text'") items: String
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

    @Tool(description = "Current date and time.")
    fun getDateTime(): String = run("get_date_time", null) {
        val time = executor.execute(ToolCallRequest("get_current_time", emptyMap()))
        val date = executor.execute(ToolCallRequest("get_current_date", emptyMap()))
        ToolExecutionResult("get_date_time", time.success && date.success, "${time.summary} ${date.summary}")
    }

    @Tool(description = "Open an app.")
    fun openApp(
        @ToolParam(description = "App name") appName: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("open_app", appName, reply) {
        executor.execute(ToolCallRequest("open_app", mapOf("app_name" to appName)))
    }

    @Tool(description = "Set an alarm.")
    fun setAlarm(
        @ToolParam(description = "0-23") hour: Int,
        @ToolParam(description = "0-59") minute: Int,
        @ToolParam(description = "Label or empty") label: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("set_alarm", "%d:%02d".format(hour, minute), reply) {
        executor.execute(
            ToolCallRequest("set_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label.ifBlank { "Fossistant" }))
        )
    }

    @Tool(description = "Start a timer.")
    fun setTimer(
        @ToolParam(description = "Seconds") seconds: Int,
        @ToolParam(description = "Label or empty") label: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("set_timer", formatDuration(seconds), reply) {
        executor.execute(ToolCallRequest("set_timer", mapOf("seconds" to seconds.toString(), "label" to label.ifBlank { "Fossistant Timer" })))
    }

    @Tool(description = "Stopwatch.")
    fun controlStopwatch(
        @ToolParam(description = "start, pause, lap, reset or status") action: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("control_stopwatch", action, reply.unlessAsking(action, "status")) {
        executor.execute(ToolCallRequest("control_stopwatch", mapOf("action" to action)))
    }

    @Tool(description = "Play music or control what's playing in any app.")
    fun controlMedia(
        @ToolParam(description = "play, pause, resume, stop, next, previous, restart, forward, rewind, seek, shuffle_on, shuffle_off, repeat_one, repeat_all, repeat_off, speed, like, dislike or whats_playing") action: String,
        @ToolParam(description = "For play: song, artist or playlist; else empty") query: String,
        @ToolParam(description = REPLY_HELP) reply: String,
        @ToolParam(description = "Music app the user named") app: String = "",
        @ToolParam(description = "Seconds for forward/rewind, '1:30' for seek, '1.5' for speed") value: String = ""
    ): String = run("control_media", query.ifBlank { listOf(action, value).filter { it.isNotBlank() }.joinToString(" ") }, reply.unlessAsking(action, "whats_playing")) {
        if (action.equals("play", ignoreCase = true) && query.isNotBlank()) {
            executor.execute(ToolCallRequest("play_music", mapOf("query" to query, "app" to app.trim())))
        } else {
            executor.execute(ToolCallRequest("media_control", mapOf("action" to action.lowercase(), "value" to value.trim())))
        }
    }

    @Tool(description = "Change a phone setting.")
    fun changeDeviceSetting(
        @ToolParam(description = "flashlight, volume, ring_volume, notification_volume, alarm_volume, call_volume, mute, ringer, brightness, auto_brightness, auto_rotate, screen_timeout, wifi, bluetooth, mobile_data, airplane_mode, location, hotspot, nfc, battery_saver, do_not_disturb or dark_mode") setting: String,
        @ToolParam(description = "on/off; volume, brightness: 0-100, +20, -20; ringer: normal, vibrate, silent; screen_timeout: e.g. '2 minutes'") value: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("change_device_setting", "$setting $value", reply) {
        val enable = SystemControls.isOn(value)
        when (setting.trim().lowercase().replace(' ', '_').replace('-', '_')) {
            "flashlight", "torch", "light", "flash" -> executor.execute(ToolCallRequest("set_flashlight", mapOf("enabled" to enable.toString())))
            "volume" -> executor.execute(ToolCallRequest("set_volume", mapOf("level" to value.trim())))
            "wifi", "wi_fi", "bluetooth", "do_not_disturb", "dnd", "dark_mode", "night_mode" ->
                executor.execute(ToolCallRequest("toggle_setting", mapOf("setting" to setting, "enabled" to enable.toString())))
            else -> executor.execute(ToolCallRequest("system_setting", mapOf("setting" to setting, "value" to value)))
        }
    }

    @Tool(description = "Phone action or battery level.")
    fun phoneAction(
        @ToolParam(description = "lock_screen, screenshot, notifications, quick_settings, recent_apps, home, back, power_menu, split_screen, camera, video, show_alarms, dismiss_alarm, snooze_alarm, show_timers or battery") action: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("phone_action", action, reply.unlessAsking(action, "battery")) {
        executor.execute(ToolCallRequest("phone_action", mapOf("action" to action)))
    }

    @Tool(description = "Start navigation.")
    fun navigateTo(
        @ToolParam(description = "Place or address") destination: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("navigate_to", destination, reply) {
        executor.execute(ToolCallRequest("navigate_to", mapOf("destination" to destination)))
    }

    @Tool(description = "Add a calendar event.")
    fun createCalendarEvent(
        @ToolParam(description = "What") title: String,
        @ToolParam(description = "When, e.g. 'tomorrow 3pm', or empty") time: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("create_calendar_event", listOf(title, time).filter { it.isNotBlank() }.joinToString(", "), reply) {
        executor.execute(ToolCallRequest("create_calendar_event", mapOf("title" to listOf(title, time).filter { it.isNotBlank() }.joinToString(" "))))
    }

    @Tool(description = "Move a calendar event to a new time.")
    fun rescheduleCalendarEvent(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "New time, e.g. 'tomorrow 11am'") newTime: String,
        @ToolParam(description = "Minutes to move, e.g. 60") shiftMinutes: Int,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("reschedule_calendar_event", listOf(title, newTime).filter { it.isNotBlank() }.joinToString(", "), reply) {
        val shift = if (shiftMinutes > 0) shiftMinutes else 60
        executor.execute(
            ToolCallRequest(
                "reschedule_calendar_event",
                mapOf("title" to title, "new_time" to newTime, "shift_minutes" to shift.toString())
            )
        )
    }

    @Tool(description = "Read calendar events.")
    fun getCalendarEvents(
        @ToolParam(description = "today, tomorrow, a weekday, or days ahead") day: String
    ): String = run("get_calendar_events", day.ifBlank { "today" }) {
        executor.execute(ToolCallRequest("get_calendar_events", mapOf("day" to day.ifBlank { "today" })))
    }

    @Tool(description = "Weather, calendar and notifications for a briefing. Only when asked for a briefing or about their day, not for greetings.")
    fun getDailyBriefing(): String = run("get_daily_briefing", null) {
        executor.execute(ToolCallRequest("get_daily_briefing", emptyMap()))
    }

    @Tool(description = "Save a note.")
    fun createNote(
        @ToolParam(description = "Title") title: String,
        @ToolParam(description = "Text") content: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("create_note", title.ifBlank { content.take(40) }, reply) {
        executor.execute(ToolCallRequest("create_note", mapOf("title" to title.ifBlank { "Quick Note" }, "content" to content)))
    }

    @Tool(description = "Call someone (the user confirms first).")
    fun makePhoneCall(
        @ToolParam(description = "Name or number") contact: String
    ): String = needsConfirmation(
        request = ToolCallRequest("make_call", mapOf("target" to contact)),
        toolName = "make_phone_call",
        description = "Call $contact",
        detail = contact
    )

    @Tool(description = "Send an SMS (the user confirms first).")
    fun sendTextMessage(
        @ToolParam(description = "Name or number") recipient: String,
        @ToolParam(description = "Text") message: String
    ): String = needsConfirmation(
        request = ToolCallRequest("send_sms", mapOf("recipient" to recipient, "body" to message)),
        toolName = "send_text_message",
        description = "Text $recipient: \"$message\"",
        detail = recipient
    )

    @Tool(description = "Remember a fact about the user.")
    fun rememberUserFact(
        @ToolParam(description = "e.g. 'Lives in Warsaw'") fact: String,
        @ToolParam(description = REPLY_HELP) reply: String
    ): String = run("remember_user_fact", fact, reply) {
        if (isDeviceLocked()) {
            ToolExecutionResult("remember_user_fact", false, org.fossistant.assistant.util.DeviceLock.LOCKED_MESSAGE)
        } else if (isTemporaryChat()) {
            ToolExecutionResult("remember_user_fact", false, "This is a temporary chat, so nothing is saved to memory.")
        } else {
            val stored = memoryManager.rememberFact(fact)
            ToolExecutionResult("remember_user_fact", true, "Saved to memory: ${stored.fact}")
        }
    }

    @Tool(description = "Save a PDF you write to Downloads.")
    fun createPdf(
        @ToolParam(description = "Title") title: String,
        @ToolParam(description = "Full text ('# ' heading, '- ' bullet); empty = your last answer") content: String
    ): String = run("create_pdf", title) {
        executor.execute(ToolCallRequest("create_pdf", mapOf("title" to title, "content" to content)))
    }

    @Tool(description = "Run a request later or repeatedly.")
    fun scheduleTask(
        @ToolParam(description = "Request, e.g. 'Summarize tech news'") instruction: String,
        @ToolParam(description = "HH:mm, 24h") time: String,
        @ToolParam(description = "once, daily, weekdays or hourly") repeat: String
    ): String = run("schedule_task", "$time $repeat") {
        executor.execute(ToolCallRequest("schedule_task", mapOf("instruction" to instruction, "time" to time, "repeat" to repeat)))
    }

    @Tool(description = "Other features.")
    fun assistantFeature(
        @ToolParam(description = "audiobook, recipe, meditation, workout, sleep_summary, health, commute, flight_status, contact_info, driving_mode, video_call, email, family_broadcast, in_app_search, global_app_search, direct_action, media_alarm, family_bell, transactional_action or device_location") feature: String,
        @ToolParam(description = "Query, place, contact, 'app: query', 'time: details', or 'recipient: text'") details: String
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
            "in_app_search" -> {
                val app = if (':' in details) details.substringBefore(':').trim() else ""
                val query = if (':' in details) details.substringAfter(':').trim() else details.trim()
                ToolCallRequest("in_app_search", buildMap {
                    put("query", query)
                    if (app.isNotBlank()) put("app", app)
                })
            }
            "global_app_search", "app_search" -> ToolCallRequest("global_app_search", mapOf("query" to details))
            "execute_direct_action", "direct_action" -> ToolCallRequest("execute_direct_action", mapOf("action_id" to details.trim()))
            "set_media_alarm", "media_alarm", "music_alarm" -> {
                val (hour, minute, media) = splitTimeAndDetails(details, defaultHour = 7, defaultMinute = 0, defaultText = "Morning Jazz Radio")
                ToolCallRequest("set_media_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString(), "media" to media))
            }
            "manage_family_bell", "family_bell" -> {
                if (details.isBlank() || details.trim().equals("list", ignoreCase = true)) {
                    ToolCallRequest("manage_family_bell", mapOf("action" to "list"))
                } else {
                    val (hour, minute, announcement) = splitTimeAndDetails(details, defaultHour = 18, defaultMinute = 0, defaultText = "Family Bell Reminder")
                    val timeSpec = "%02d:%02d".format(hour, minute)
                    ToolCallRequest("manage_family_bell", mapOf("action" to "add", "time" to timeSpec, "announcement" to announcement))
                }
            }
            "reschedule_calendar_event", "reschedule_event" -> {
                val title = details.substringBefore(':').trim()
                val newTime = details.substringAfter(':', "").trim()
                ToolCallRequest("reschedule_calendar_event", mapOf("title" to title, "new_time" to newTime, "shift_minutes" to "60"))
            }
            "device_location", "location" -> ToolCallRequest("get_device_location", emptyMap())
            "reply_notification" -> {
                val sender = if (':' in details) details.substringBefore(':').trim() else ""
                val body = if (':' in details) details.substringAfter(':').trim() else details.trim()
                return needsConfirmation(
                    ToolCallRequest("reply_notification", mapOf("sender" to sender, "body" to body)),
                    "reply_notification",
                    "Reply to ${sender.ifBlank { "latest message" }}: \"$body\"",
                    sender.ifBlank { "Notification" }
                )
            }
            "transactional_action", "payment", "reservation", "social_post" -> {
                val type = when {
                    name != "transactional_action" -> name
                    ':' in details -> details.substringBefore(':').trim().lowercase()
                    else -> "payment"
                }
                val targetDetails = if (name == "transactional_action" && ':' in details) details.substringAfter(':').trim() else details.trim()
                return needsConfirmation(
                    ToolCallRequest("transactional_action", mapOf("type" to type, "details" to targetDetails)),
                    "assistant_feature",
                    "${type.replace('_', ' ').replaceFirstChar { it.uppercase() }}: \"$targetDetails\"",
                    targetDetails
                )
            }
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
            else -> return "Error: unknown feature '$feature'. Use one of: audiobook, recipe, meditation, workout, sleep_summary, health, commute, flight_status, contact_info, driving_mode, video_call, email, family_broadcast, reply_notification, reschedule_calendar_event, in_app_search, global_app_search, direct_action, media_alarm, family_bell, transactional_action, device_location."
        }
        return run("assistant_feature", listOf(name, details).filter { it.isNotBlank() }.joinToString(": ")) {
            executor.execute(request)
        }
    }

    @Volatile
    private var screenActionsThisTurn = 0

    /** Called by the engine at the start of every generation. */
    fun startTurn() {
        screenActionsThisTurn = 0
    }

    inner class SmartHomeTools : ToolSet {
        @Tool(description = "Control Home Assistant devices and scenes.")
        fun smartHome(
            @ToolParam(description = "on, off, toggle, status, brightness, temperature, open, close, lock, unlock or activate") action: String,
            @ToolParam(description = "Name as the user says it") device: String,
            @ToolParam(description = "Brightness % or temperature, or empty") value: String,
            @ToolParam(description = REPLY_HELP) reply: String
        ): String {
            val request = ToolCallRequest("home_assistant", mapOf("action" to action, "device" to device, "value" to value))
            val verb = action.trim().lowercase()
            // Unlocking, or opening a door / gate / garage, always needs a spoken "yes".
            if (verb == "unlock" || (verb == "open" && Regex("(?i)door|gate|garage").containsMatchIn(device))) {
                return needsConfirmation(request, "smart_home", "${verb.replaceFirstChar { it.uppercase() }} the $device", device)
            }
            return run("smart_home", listOf(action, device, value).filter { it.isNotBlank() }.joinToString(" "), reply.unlessAsking(action, "status")) {
                executor.execute(request)
            }
        }
    }

    inner class ConnectorTools : ToolSet {
        @Tool(description = "Call a connector tool listed in your instructions.")
        fun useConnector(
            @ToolParam(description = "Tool name") tool: String,
            @ToolParam(description = "JSON object, '{}' if none") argumentsJson: String
        ): String = run("use_connector", tool) {
            executor.execute(ToolCallRequest("use_connector", mapOf("tool" to tool, "arguments" to argumentsJson)))
        }
    }

    inner class AppActionTools : ToolSet {
        @Tool(description = "Find app actions that aren't listed.")
        fun findAppActions(
            @ToolParam(description = "What the user wants, with the app if named") query: String
        ): String = run("find_app_actions", query) {
            executor.execute(ToolCallRequest("find_app_actions", mapOf("query" to query)))
        }

        @Tool(description = "Run an app action.")
        fun runAppAction(
            @ToolParam(description = "app.action id") actionId: String,
            @ToolParam(description = "'name=value; ...' or empty") inputs: String,
            @ToolParam(description = REPLY_HELP) reply: String
        ): String {
            val request = ToolCallRequest("run_app_action", mapOf("action_id" to actionId.trim(), "inputs" to inputs))
            val action = executor.appActions?.byId(actionId)
            if (action != null && action.isSensitive) {
                return needsConfirmation(request, "run_app_action", "${action.label.replaceFirstChar { it.uppercase() }} in ${action.appLabel}", action.appLabel)
            }
            return run("run_app_action", action?.let { "${it.appLabel}: ${it.label}" } ?: actionId, reply) { executor.execute(request) }
        }
    }

    inner class ScreenAgentTools : ToolSet {
        @Tool(description = "Read or operate the current screen.")
        fun controlScreen(
            @ToolParam(description = "read_screen, click, type, scroll_down, scroll_up, navigate_back or navigate_home") action: String,
            @ToolParam(description = "Element text, text to type, or empty") targetText: String
        ): String {
            if (isTurnAnswered()) return ALREADY_ANSWERED
            val request = ToolCallRequest("screen_agent_action", mapOf("action" to action, "target_text" to targetText))
            if (++screenActionsThisTurn > MAX_SCREEN_ACTIONS_PER_TURN) {
                return "Error: step limit of $MAX_SCREEN_ACTIONS_PER_TURN screen actions reached. Tell the user what you did so far and ask how to continue."
            }
            val verb = action.trim().lowercase()
            if ((verb == "click" || verb == "tap") && SENSITIVE_TAP.containsMatchIn(targetText)) {
                return needsConfirmation(request, "control_screen", "Tap \"$targetText\"", targetText)
            }
            return run("control_screen", listOf(action, targetText).filter { it.isNotBlank() }.joinToString(": ")) {
                executor.execute(request)
            }
        }
    }

    inner class NotificationTools : ToolSet {
        @Tool(description = "Read recent notifications.")
        fun readNotifications(): String = run("read_notifications", null) {
            executor.execute(ToolCallRequest("read_notifications", emptyMap()))
        }

        @Tool(description = "Reply to a recent notification (user confirms first).")
        fun replyNotification(
            @ToolParam(description = "Sender or empty for latest") sender: String,
            @ToolParam(description = "Reply text") message: String
        ): String = needsConfirmation(
            request = ToolCallRequest("reply_notification", mapOf("sender" to sender, "body" to message)),
            toolName = "reply_notification",
            description = "Reply to ${sender.ifBlank { "latest message" }}: \"$message\"",
            detail = sender.ifBlank { "Notification" }
        )
    }

    /**
     * Runs a tool, reports it for the UI, and returns the text Gemma sees. A non-blank [reply] (the sentence Gemma
     * wrote for the user inside the call) becomes the answer the moment the tool succeeds; on failure the real
     * error is shown instead.
     */
    private fun run(toolName: String, detail: String?, reply: String = "", block: suspend () -> ToolExecutionResult): String {
        if (isTurnAnswered()) return ALREADY_ANSWERED
        val result = try {
            runBlocking { block() }
        } catch (t: Throwable) {
            ToolExecutionResult(toolName, false, "Failed: ${t.message ?: t::class.simpleName}")
        }
        onToolFinished(result.copy(toolName = toolName, displayDetail = detail?.takeIf { it.isNotBlank() } ?: result.displayDetail))
        val text = (result.rawPayload?.takeIf { it.isNotBlank() } ?: result.summary).take(MAX_RESULT_CHARS)
        if (result.success && !result.awaitingConfirmation && reply.isNotBlank()) onInstantReply(reply.trim())
        return if (result.success) text else "Error: $text"
    }

    /** No instant reply for the sub-actions that return information Gemma has to put into words. */
    private fun String.unlessAsking(action: String, vararg informational: String): String =
        if (action.trim().lowercase().replace(' ', '_') in informational) "" else this

    private fun needsConfirmation(request: ToolCallRequest, toolName: String, description: String, detail: String): String {
        if (isTurnAnswered()) return ALREADY_ANSWERED
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

    private fun splitTimeAndDetails(
        details: String,
        defaultHour: Int,
        defaultMinute: Int,
        defaultText: String
    ): Triple<Int, Int, String> {
        val trimmed = details.trim()
        val match = TIME_PREFIX_REGEX.find(trimmed)
        val parsed = match?.let { org.fossistant.assistant.features.TaskScheduler.parseTime(it.groupValues[1]) }
        return if (match != null && parsed != null) {
            val rest = trimmed.removeRange(match.range).trim().trimStart(':', '-', ',', ';').trim()
            Triple(parsed.first, parsed.second, rest.ifBlank { defaultText })
        } else {
            Triple(defaultHour, defaultMinute, trimmed.ifBlank { defaultText })
        }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
        seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }

    private companion object {
        /** What a tool call in Gemma's hidden second pass gets: the turn's answer is already shown. */
        const val ALREADY_ANSWERED = "Already done and the user has your reply. Do nothing more."
        // Keeps one tool result to roughly 400 tokens of the 4096-token context.
        const val MAX_RESULT_CHARS = 1500
        const val MAX_SCREEN_ACTIONS_PER_TURN = 12
        val TIME_PREFIX_REGEX = Regex(
            "^(\\d{1,2}(?:[:.h]\\d{2}(?:\\s*(?:am|pm))?|\\s*(?:am|pm)))(?=[\\s,:;-]|$)",
            RegexOption.IGNORE_CASE
        )
        val SENSITIVE_TAP = Regex(
            "(?i)\\b(pay|buy|purchase|order|checkout|send|transfer|delete|remove|uninstall|install|confirm|subscribe|" +
                "sign out|log ?out|reset|erase|format|accept|allow|grant|share|post|publish|call)\\b"
        )
    }
}
