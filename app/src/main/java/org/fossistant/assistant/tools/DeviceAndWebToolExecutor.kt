package org.fossistant.assistant.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.model.ToolVisual
import org.fossistant.assistant.model.VisualKind
import org.fossistant.assistant.service.FossistantAgentAccessibilityService
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 1:1 Legacy Android Assistant & Agentic Local Tool Executor.
 *
 * Executes both:
 * - tools Gemma 4 E2B calls via LiteRT-LM `@Tool` annotations, and
 * - actions the user confirmed (calls, texts, sensitive app actions)
 *   (web search, webpage reader, on-device screen inspection, UI automation clicking/scrolling).
 */
class DeviceAndWebToolExecutor(
    private val context: Context?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val apiBridge = AppShortcutsAndAssistantApiBridge(context)

    val homeAssistant = HomeAssistantClient(
        baseUrlProvider = { org.fossistant.assistant.FossistantApp.homeAssistantUrl },
        tokenProvider = { org.fossistant.assistant.FossistantApp.homeAssistantToken }
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val photoSearch by lazy { PhotoSearch(httpClient) }

    /** Brightness, rotation, ringer, volume streams, lock / screenshot / shade, camera, alarms, battery. */
    val systemControls by lazy { SystemControls(context) }

    /** What installed apps can do (App Actions and static shortcuts), for find/run_app_action. */
    val appActions: AppActionIndex? by lazy { context?.let { AppActionIndex(it.applicationContext ?: it) } }


    /**
     * Unified dispatcher for executing any [ToolCallRequest] by name.
     */
    suspend fun execute(request: ToolCallRequest): ToolExecutionResult = withContext(ioDispatcher) {
        if (request.toolName in org.fossistant.assistant.util.DeviceLock.SENSITIVE_TOOLS &&
            org.fossistant.assistant.util.DeviceLock.isLocked(context)
        ) {
            return@withContext ToolExecutionResult(request.toolName, false, org.fossistant.assistant.util.DeviceLock.LOCKED_MESSAGE)
        }
        val result = when (request.toolName) {
            "get_current_time" -> {
                val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                val nowStr = timeFormat.format(java.util.Date())
                ToolExecutionResult("get_current_time", true, "It is $nowStr.")
            }
            "get_current_date" -> {
                val dateFormat = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
                val nowStr = dateFormat.format(java.util.Date())
                ToolExecutionResult("get_current_date", true, "Today is $nowStr.")
            }
            "play_music" -> playMusic(
                query = request.arguments["query"].orEmpty(),
                preferredApp = request.arguments["app"]
            )
            "media_control" -> controlMediaPlayback(
                action = request.arguments["action"] ?: "play",
                value = request.arguments["value"].orEmpty()
            )
            "system_setting" -> systemControls.setting(request.arguments["setting"].orEmpty(), request.arguments["value"].orEmpty())
            "phone_action" -> systemControls.phoneAction(request.arguments["action"].orEmpty())
            "open_app" -> openApp(
                appNameQuery = request.arguments["app_name"].orEmpty()
            )
            "get_weather" -> fetchWeather(
                location = request.arguments["location"] ?: "current_location"
            )
            "set_alarm" -> setAlarm(
                hour = request.arguments["hour"]?.toIntOrNull() ?: 7,
                minute = request.arguments["minute"]?.toIntOrNull() ?: 0,
                label = request.arguments["label"] ?: "Fossistant"
            )
            "set_timer" -> setTimer(
                seconds = request.arguments["seconds"]?.toIntOrNull() ?: 60,
                label = request.arguments["label"] ?: "Fossistant Timer"
            )
            "set_flashlight" -> setFlashlight(
                enabled = request.arguments["enabled"]?.toBooleanStrictOrNull() ?: true
            )
            "set_volume" -> setVolume(
                levelSpec = request.arguments["level"] ?: "50"
            )
            "make_call" -> makePhoneCall(
                target = request.arguments["target"].orEmpty()
            )
            "send_sms" -> sendSms(
                recipient = request.arguments["recipient"].orEmpty(),
                body = request.arguments["body"].orEmpty()
            )
            "navigate_to" -> navigateTo(
                destination = request.arguments["destination"].orEmpty()
            )
            "create_calendar_event" -> createCalendarEvent(
                title = request.arguments["title"] ?: request.arguments["raw_request"] ?: "Event",
                StartTimeEpochMs = request.arguments["start_ms"]?.toLongOrNull()
            )
            "create_note" -> {
                val msg = apiBridge.createNote(
                    title = request.arguments["title"] ?: "Quick Note",
                    content = request.arguments["content"].orEmpty()
                )
                ToolExecutionResult("create_note", true, msg)
            }
            "in_app_search" -> {
                val pkg = request.arguments["app"]?.let { resolvePackageForName(it) }
                val msg = apiBridge.performInAppSearch(
                    query = request.arguments["query"].orEmpty(),
                    targetPackage = pkg
                )
                ToolExecutionResult("in_app_search", true, msg)
            }
            "toggle_setting" -> {
                val msg = apiBridge.toggleSystemSetting(
                    settingName = request.arguments["setting"].orEmpty(),
                    enable = request.arguments["enabled"]?.toBooleanStrictOrNull() ?: true
                )
                ToolExecutionResult("toggle_setting", true, msg)
            }
            "reply_notification" -> {
                val svc = org.fossistant.assistant.service.FossistantNotificationAndMediaService.activeInstance
                val summary = svc?.replyToNotification(
                    senderNameQuery = request.arguments["sender"].orEmpty(),
                    replyBody = request.arguments["body"].orEmpty()
                ) ?: "Notification access not enabled. Enable Fossistant in Settings > Notification access."
                ToolExecutionResult("reply_notification", svc != null, summary)
            }
            "read_notifications" -> {
                val svc = org.fossistant.assistant.service.FossistantNotificationAndMediaService.activeInstance
                val summary = svc?.getRecentMessagesSummary()
                    ?: "Notification access not enabled. Enable Fossistant in Settings > Notification access."
                ToolExecutionResult("read_notifications", svc != null, summary)
            }
            "home_assistant" -> homeAssistant.control(
                action = request.arguments["action"].orEmpty(),
                device = request.arguments["device"].orEmpty(),
                value = request.arguments["value"].orEmpty()
            )
            "get_calendar_events" -> {
                val events = apiBridge.getCalendarEvents(request.arguments["day"] ?: "today")
                ToolExecutionResult("get_calendar_events", events.success, events.message)
            }
            "get_daily_briefing" -> {
                // Weather, today's events and recent notifications in one call; the model writes the briefing.
                val weather = fetchWeather(request.arguments["location"] ?: "current_location")
                val events = apiBridge.getCalendarEvents("today")
                val notifications = org.fossistant.assistant.service.FossistantNotificationAndMediaService.activeInstance
                    ?.getRecentMessagesSummary()
                    ?: "Notification access is off."
                val parts = listOf(
                    briefingTimeNote(),
                    "Weather: ${weather.summary}",
                    "Calendar: ${events.message}",
                    "Notifications: $notifications"
                )
                ToolExecutionResult("get_daily_briefing", true, "Daily briefing data", parts.joinToString("\n"))
            }
            "global_app_search" -> {
                val summary = apiBridge.queryGlobalAppSearch(request.arguments["query"].orEmpty())
                ToolExecutionResult("global_app_search", true, summary)
            }
            "execute_direct_action" -> {
                val actionId = request.arguments["action_id"].orEmpty()
                val session = org.fossistant.assistant.service.FossistantVoiceInteractionSession.activeSession
                val triggered = session?.triggerForegroundDirectAction(actionId) ?: false
                ToolExecutionResult(
                    "execute_direct_action",
                    triggered,
                    if (triggered) "Executed foreground DirectAction '$actionId'"
                    else "No active DirectAction '$actionId' in foreground Activity"
                )
            }
            "play_audiobook" -> {
                val msg = apiBridge.playOrCastAudiobook(
                    titleOrAuthor = request.arguments["query"].orEmpty(),
                    castToDevice = request.arguments["cast"]?.toBooleanStrictOrNull() ?: false
                )
                ToolExecutionResult("play_audiobook", true, msg)
            }
            "set_media_alarm" -> {
                val msg = apiBridge.setMediaOrMusicAlarm(
                    hour = request.arguments["hour"]?.toIntOrNull() ?: 7,
                    minute = request.arguments["minute"]?.toIntOrNull() ?: 0,
                    mediaQuery = request.arguments["media"] ?: "Morning Jazz Radio"
                )
                ToolExecutionResult("set_media_alarm", true, msg)
            }
            "manage_cookbook" -> {
                val msg = apiBridge.manageCookbook(
                    action = request.arguments["action"] ?: "steps",
                    recipeName = request.arguments["recipe"].orEmpty(),
                    stepsText = request.arguments["steps"]
                )
                ToolExecutionResult("manage_cookbook", true, msg)
            }
            "control_stopwatch" -> {
                val msg = apiBridge.controlStopwatch(
                    action = request.arguments["action"] ?: "start"
                )
                ToolExecutionResult("control_stopwatch", true, msg)
            }
            "family_broadcast" -> {
                val msg = apiBridge.broadcastToFamilyOrHome(
                    message = request.arguments["message"].orEmpty(),
                    targetDevice = request.arguments["device"] ?: "all home devices"
                )
                ToolExecutionResult("family_broadcast", msg.success, msg.message)
            }
            "send_email_or_media_message" -> {
                val msg = apiBridge.sendEmailOrMediaMessage(
                    modality = request.arguments["modality"] ?: "email",
                    recipient = request.arguments["recipient"].orEmpty(),
                    subject = request.arguments["subject"] ?: "Voice Message",
                    body = request.arguments["body"].orEmpty()
                )
                ToolExecutionResult("send_email_or_media_message", true, msg)
            }
            "reschedule_calendar_event" -> {
                val msg = apiBridge.rescheduleCalendarEvent(
                    eventTitleQuery = request.arguments["title"].orEmpty(),
                    newTimeDescription = request.arguments["new_time"].orEmpty(),
                    shiftMinutes = request.arguments["shift_minutes"]?.toIntOrNull() ?: 60
                )
                ToolExecutionResult("reschedule_calendar_event", msg.success, msg.message)
            }
            "driving_mode_action" -> {
                val msg = apiBridge.executeDrivingModeAction(
                    subAction = request.arguments["action"] ?: "navigate",
                    destinationOrTarget = request.arguments["target"].orEmpty()
                )
                ToolExecutionResult("driving_mode_action", msg.success, msg.message)
            }
            "manage_family_bell" -> {
                val msg = apiBridge.manageFamilyBell(
                    action = request.arguments["action"] ?: "list",
                    timeSpec = request.arguments["time"].orEmpty(),
                    announcement = request.arguments["announcement"].orEmpty()
                )
                ToolExecutionResult("manage_family_bell", true, msg)
            }
            "start_meditation" -> {
                val msg = apiBridge.startMeditation(
                    topicOrDuration = request.arguments["topic"] ?: "10 minute mindfulness"
                )
                ToolExecutionResult("start_meditation", msg.success, msg.message)
            }
            "control_fitness_activity" -> {
                val msg = apiBridge.controlFitnessActivity(
                    workoutAction = request.arguments["action"] ?: "start",
                    activityType = request.arguments["activity"] ?: "running"
                )
                ToolExecutionResult("control_fitness_activity", msg.success, msg.message)
            }
            "get_sleep_summary" -> if (org.fossistant.assistant.features.HealthData.isSupported) {
                org.fossistant.assistant.features.HealthData.summary(context, "sleep").copy(toolName = "get_sleep_summary")
            } else {
                val msg = apiBridge.getSleepSummary()
                ToolExecutionResult("get_sleep_summary", msg.success, msg.message)
            }
            "get_health_summary" -> org.fossistant.assistant.features.HealthData.summary(context, request.arguments["metric"].orEmpty())
            "create_pdf" -> org.fossistant.assistant.FossistantApp.instance?.features?.createPdfFromTool(
                title = request.arguments["title"].orEmpty(),
                content = request.arguments["content"].orEmpty()
            ) ?: ToolExecutionResult("create_pdf", false, "PDF export isn't available.")
            "schedule_task" -> org.fossistant.assistant.FossistantApp.instance?.features?.scheduleFromTool(
                instruction = request.arguments["instruction"].orEmpty(),
                time = request.arguments["time"].orEmpty(),
                repeat = request.arguments["repeat"].orEmpty()
            ) ?: ToolExecutionResult("schedule_task", false, "Scheduling isn't available.")
            "use_connector" -> org.fossistant.assistant.FossistantApp.instance?.features?.connectors?.callTool(
                toolName = request.arguments["tool"].orEmpty(),
                argumentsJson = request.arguments["arguments"].orEmpty()
            ) ?: ToolExecutionResult("use_connector", false, "No connectors are set up.")
            "make_duo_call" -> {
                val msg = apiBridge.makeCallerIdOrDuoCall(
                    target = request.arguments["target"].orEmpty(),
                    videoCall = request.arguments["video"]?.toBooleanStrictOrNull() ?: true
                )
                ToolExecutionResult("make_duo_call", msg.success, msg.message)
            }
            "get_commute_estimate" -> {
                val msg = apiBridge.getCommuteEstimate(
                    destination = request.arguments["destination"] ?: "Work"
                )
                ToolExecutionResult("get_commute_estimate", msg.success, msg.message)
            }
            "check_flight_status" -> {
                val msg = apiBridge.checkTravelOrFlightStatus(
                    flightOrTripQuery = request.arguments["query"].orEmpty()
                )
                ToolExecutionResult("check_flight_status", msg.success, msg.message)
            }
            "get_contact_info" -> {
                val msg = apiBridge.getContactDetails(
                    contactName = request.arguments["name"].orEmpty()
                )
                ToolExecutionResult("get_contact_info", true, msg)
            }
            "transactional_action" -> {
                val msg = apiBridge.performTransactionalVoiceAction(
                    actionType = request.arguments["type"] ?: "payment",
                    targetOrDetails = request.arguments["details"].orEmpty()
                )
                ToolExecutionResult("transactional_action", true, msg)
            }
            "search_web" -> searchWebLocal(
                query = request.arguments["query"].orEmpty()
            )
            "calculate" -> calculate(
                expression = request.arguments["expression"] ?: request.arguments["query"].orEmpty()
            )
            "fetch_webpage" -> fetchWebpageText(
                url = request.arguments["url"].orEmpty()
            )
            "get_device_location" -> getDeviceLocation()
            "find_app_actions" -> findAppActions(request.arguments["query"].orEmpty())
            "run_app_action" -> runAppAction(request.arguments["action_id"].orEmpty(), request.arguments["inputs"].orEmpty())
            else -> ToolExecutionResult(
                toolName = request.toolName,
                success = false,
                summary = "Unknown tool '${request.toolName}'"
            )
        }
        result
    }

    fun playMusic(query: String, preferredApp: String? = null): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("play_music", true, "Playing '$query' (simulated in test mode)")
        }
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!preferredApp.isNullOrBlank()) {
                    resolvePackageForName(preferredApp)?.let { setPackage(it) }
                }
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fallback to YouTube Music / Spotify / Web search intent
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
            ToolExecutionResult("play_music", true, "Playing \"$query\"")
        } catch (e: Exception) {
            ToolExecutionResult("play_music", false, "Could not start music playback: ${e.message}")
        }
    }

    // ---- App actions (App Actions capabilities and static shortcuts) ----

    private fun findAppActions(query: String): ToolExecutionResult {
        val index = appActions ?: return ToolExecutionResult("find_app_actions", false, "App actions aren't available here.")
        val matches = index.search(query)
        if (matches.isEmpty()) {
            return ToolExecutionResult(
                "find_app_actions", false,
                CANT_DO_THAT
            )
        }
        val lines = matches.joinToString("\n") { a ->
            val inputs = if (a.params.isEmpty()) "" else " (inputs: ${a.params.joinToString(", ") { it.name }})"
            val kind = when (a.source) {
                AppActionIndex.Source.SHORTCUT -> "shortcut"
                AppActionIndex.Source.CAPABILITY -> "app action"
            }
            "${a.key} | ${a.appLabel}: ${a.label}$inputs [$kind]" + (a.description.takeIf { it.isNotBlank() }?.let { " - ${it.take(120)}" } ?: "")
        }
        return ToolExecutionResult(
            "find_app_actions", true,
            "Found ${matches.size} app actions",
            rawPayload = "Run one with run_app_action and its id:\n$lines",
            displayDetail = query
        )
    }

    private fun runAppAction(actionId: String, inputs: String): ToolExecutionResult {
        val ctx = context ?: return ToolExecutionResult("run_app_action", true, "Ran $actionId")
        val index = appActions ?: return ToolExecutionResult("run_app_action", false, "App actions aren't available here.")
        val action = index.byId(actionId)
            ?: return ToolExecutionResult("run_app_action", false, "Unknown action '$actionId'. Call find_app_actions first and use one of its ids.")
        if (launchAppAction(ctx, action, parseActionInputs(inputs, action))) {
            return ToolExecutionResult("run_app_action", true, "Started ${action.label} in ${action.appLabel}.", displayDetail = "${action.appLabel}: ${action.label}")
        }
        // No guessing (a different action, or just opening the app): a plain "can't do that".
        return ToolExecutionResult("run_app_action", false, CANT_DO_THAT)
    }

    /** `name=value; name=value` (or one plain value for the first input) mapped onto the action's inputs. */
    private fun parseActionInputs(inputs: String, action: AppActionIndex.AppAction): Map<String, String> {
        val text = inputs.trim()
        if (text.isEmpty()) return emptyMap()
        if (!text.contains('=')) {
            val first = action.params.firstOrNull()
            return mapOf((first?.key ?: "query") to text)
        }
        return text.split(';', '\n').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            val param = action.params.firstOrNull { it.name.equals(name, true) || it.key.equals(name, true) }
            (param?.key ?: name) to value
        }.toMap()
    }

    private fun launchAppAction(ctx: Context, action: AppActionIndex.AppAction, values: Map<String, String>): Boolean {
        val base = action.intent
        val template = action.urlTemplate
        val intent = when {
            template != null -> Intent(Intent.ACTION_VIEW, Uri.parse(AppActionIndex.expandTemplate(template, values))).apply {
                val component = base?.component
                if (component != null) setComponent(component) else setPackage(action.packageName)
            }
            base != null -> Intent(base).apply { values.forEach { (k, v) -> putExtra(k, v) } }
            else -> return false
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            org.fossistant.assistant.util.AppLogger.w("AppActions", "Couldn't start ${action.id}: ${e.message}")
            false
        }
    }

    /** What Gemma is told (and says) when no app action can do the request. */
    private val CANT_DO_THAT = "I can't do that yet. Tell the user exactly that, without suggesting other ways."

    fun controlMediaPlayback(action: String, value: String = ""): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("media_control", true, "Media action '$action' executed")
        }
        // Prefer rich MediaSessionManager TransportControls when NotificationListener is active
        org.fossistant.assistant.service.FossistantNotificationAndMediaService.activeInstance
            ?.controlActiveMediaSession(action, value = value)
            ?.let { sessionSummary ->
                return ToolExecutionResult("media_control", true, sessionSummary)
            }
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (action.lowercase().replace(' ', '_')) {
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "forward", "fast_forward", "skip_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                "rewind", "skip_back", "skip_backward" -> KeyEvent.KEYCODE_MEDIA_REWIND
                "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                // shuffle, repeat, speed, like, seek, "what's playing" need the playing app's media session
                else -> return ToolExecutionResult(
                    "media_control", false,
                    "For that, Fossistant needs notification access (it reads the playing app's media controls from there)."
                )
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            val done = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused the music."
                KeyEvent.KEYCODE_MEDIA_STOP -> "Stopped the music."
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Skipped ahead."
                KeyEvent.KEYCODE_MEDIA_REWIND -> "Went back a bit."
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Skipped to the next track."
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Went back to the previous track."
                else -> "Resumed the music."
            }
            ToolExecutionResult("media_control", true, done)
        } catch (e: Exception) {
            ToolExecutionResult("media_control", false, "Media control failed: ${e.message}")
        }
    }

    fun openApp(appNameQuery: String): ToolExecutionResult {
        val sanitized = FuzzyAppAndCommandMatcher.sanitizeAppQuery(appNameQuery).ifBlank { appNameQuery.trim() }
        if (context == null) {
            return ToolExecutionResult("open_app", true, "Opened app '$sanitized'")
        }
        return try {
            val matchingApp = FuzzyAppAndCommandMatcher.findBestMatchingApp(context, sanitized)
                ?: FuzzyAppAndCommandMatcher.findBestMatchingApp(context, appNameQuery)
            if (matchingApp != null) {
                val pkg = matchingApp.activityInfo.packageName
                val label = matchingApp.loadLabel(context.packageManager).toString()
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    return ToolExecutionResult("open_app", true, "Opened $label")
                }
            }
            ToolExecutionResult("open_app", false, "App '$sanitized' is not installed on this device.")
        } catch (e: Exception) {
            ToolExecutionResult("open_app", false, "Failed to open '$sanitized': ${e.message}")
        }
    }

    private fun resolvePackageForName(appNameQuery: String): String? {
        val app = FuzzyAppAndCommandMatcher.findBestMatchingApp(context, appNameQuery)
        return app?.activityInfo?.packageName
    }

    private fun readDeviceCoordinates(): Pair<Double, Double>? {
        val ctx = context ?: return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
            val isGpsGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!isGpsGranted) return null
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                ?: requestFreshLocation(lm)
            if (loc != null) Pair(loc.latitude, loc.longitude) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Asks for a current fix (Android 11+) when no last-known location exists, waiting at most 4 seconds. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestFreshLocation(lm: android.location.LocationManager): android.location.Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: android.location.Location? = null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            lm.getCurrentLocation(provider, null, executor) { location ->
                result = location
                latch.countDown()
            }
            latch.await(4, TimeUnit.SECONDS)
            result
        } catch (_: Exception) {
            null
        } finally {
            executor.shutdown()
        }
    }

    fun getDeviceLocation(): ToolExecutionResult {
        val coords = readDeviceCoordinates()
        return if (coords != null) {
            ToolExecutionResult(
                "get_device_location",
                true,
                "Device coordinates: Latitude ${coords.first}, Longitude ${coords.second}",
                """{"latitude":${coords.first},"longitude":${coords.second}}"""
            )
        } else {
            ToolExecutionResult("get_device_location", false, "Location is unavailable (permission off or no fix yet).")
        }
    }

    suspend fun fetchWeather(location: String): ToolExecutionResult = withContext(ioDispatcher) {
        try {
            // Uses free, zero-API-key Open-Meteo geocoding + forecast API
            val isCurrentLoc = location.isBlank() ||
                location.equals("current_location", ignoreCase = true) ||
                location.contains("current location", ignoreCase = true) ||
                location.contains("my location", ignoreCase = true) ||
                location.equals("here", ignoreCase = true)

            var lat = 48.1486
            var lon = 17.1077
            var resolvedName = "Current Location"
            var country = ""

            if (!isCurrentLoc) {
                val encodedCity = URLEncoder.encode(location.trim(), "UTF-8")
                val geoReq = Request.Builder()
                    .url("https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json")
                    .build()

                httpClient.newCall(geoReq).execute().use { geoResp ->
                    val body = geoResp.body?.string().orEmpty()
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val first = results.getJSONObject(0)
                        lat = first.getDouble("latitude")
                        lon = first.getDouble("longitude")
                        resolvedName = first.optString("name", location)
                        country = first.optString("country", "")
                    } else {
                        return@withContext ToolExecutionResult("get_weather", false, "I couldn't find a place called \"$location\".")
                    }
                }
            } else {
                val locCoords = readDeviceCoordinates()
                    ?: return@withContext ToolExecutionResult(
                        "get_weather",
                        false,
                        "I couldn't get your location (permission off or no fix yet). Tell me the city instead."
                    )
                lat = locCoords.first
                lon = locCoords.second
                resolvedName = "Your Location"
            }

            val weatherReq = Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code")
                .build()

            httpClient.newCall(weatherReq).execute().use { wResp ->
                val wJson = JSONObject(wResp.body?.string().orEmpty()).getJSONObject("current")
                val tempC = wJson.getDouble("temperature_2m")
                val humidity = wJson.getInt("relative_humidity_2m")
                val windKmh = wJson.getDouble("wind_speed_10m")
                val condition = decodeWmoWeatherCode(wJson.getInt("weather_code"))
                val countrySuffix = if (country.isNotBlank()) " ($country)" else ""
                val summary = "Weather in $resolvedName$countrySuffix: $condition, ${tempC}°C, humidity $humidity%, wind ${windKmh} km/h."
                ToolExecutionResult("get_weather", true, summary, wJson.toString())
            }
        } catch (e: Exception) {
            ToolExecutionResult("get_weather", false, "Weather lookup unavailable offline (${e.message}).")
        }
    }

    private fun decodeWmoWeatherCode(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snowfall"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Moderate conditions"
    }

    fun setAlarm(hour: Int, minute: Int, label: String): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("set_alarm", true, "Set alarm for %02d:%02d (%s)".format(hour, minute, label))
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult("set_alarm", true, "Alarm set for %02d:%02d".format(hour, minute))
        } catch (e: Exception) {
            ToolExecutionResult("set_alarm", false, "Failed to set alarm: ${e.message}")
        }
    }

    fun setTimer(seconds: Int, label: String): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("set_timer", true, "Set timer for ${seconds}s ($label)")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult("set_timer", true, "Timer started for ${seconds} seconds")
        } catch (e: Exception) {
            ToolExecutionResult("set_timer", false, "Failed to start timer: ${e.message}")
        }
    }

    fun setFlashlight(enabled: Boolean): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("set_flashlight", true, "Flashlight turned ${if (enabled) "ON" else "OFF"}")
        }
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolExecutionResult("set_flashlight", false, "No camera flash unit found")
            cameraManager.setTorchMode(cameraId, enabled)
            ToolExecutionResult("set_flashlight", true, "Flashlight turned ${if (enabled) "on" else "off"}")
        } catch (e: Exception) {
            ToolExecutionResult("set_flashlight", false, "Could not toggle flashlight: ${e.message}")
        }
    }

    fun setVolume(levelSpec: String): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("set_volume", true, "Volume adjusted to $levelSpec%")
        }
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val currentPct = (current * 100) / max
            val targetPct = when {
                levelSpec.startsWith("+") -> (currentPct + (levelSpec.drop(1).toIntOrNull() ?: 20)).coerceIn(0, 100)
                levelSpec.startsWith("-") -> (currentPct - (levelSpec.drop(1).toIntOrNull() ?: 20)).coerceIn(0, 100)
                else -> (levelSpec.toIntOrNull() ?: 50).coerceIn(0, 100)
            }
            val targetIndex = ((targetPct * max) / 100).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, AudioManager.FLAG_SHOW_UI)
            ToolExecutionResult("set_volume", true, "Media volume set to $targetPct%")
        } catch (e: Exception) {
            ToolExecutionResult("set_volume", false, "Failed to adjust volume: ${e.message}")
        }
    }

    fun makePhoneCall(target: String): ToolExecutionResult {
        val contact = apiBridge.resolveContact(target)
        val resolvedNumber = contact.number
            ?: return ToolExecutionResult("make_call", false, contact.problem ?: "No phone number found for $target.")
        if (context == null) {
            return ToolExecutionResult("make_call", true, "Calling $resolvedNumber")
        }
        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCallPermission) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(resolvedNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                ToolExecutionResult("make_call", true, "Calling ${contact.displayName} ($resolvedNumber)")
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(resolvedNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ToolExecutionResult("make_call", true, "Opened dialer for $target ($resolvedNumber). Grant Phone permission to place calls directly.")
            }
        } catch (e: Exception) {
            ToolExecutionResult("make_call", false, "Could not place call: ${e.message}")
        }
    }

    fun sendSms(recipient: String, body: String): ToolExecutionResult {
        val contact = apiBridge.resolveContact(recipient)
        val resolvedNumber = contact.number
            ?: return ToolExecutionResult("send_sms", false, contact.problem ?: "No phone number found for $recipient.")
        if (body.isBlank()) return ToolExecutionResult("send_sms", false, "The message text is empty.")
        if (context == null) {
            return ToolExecutionResult("send_sms", true, "Sent SMS to $resolvedNumber: \"$body\"")
        }
        return try {
            val hasSmsPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasSmsPermission) {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(body)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(resolvedNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(resolvedNumber, null, body, null, null)
                }
                ToolExecutionResult("send_sms", true, "Sent a text to ${contact.displayName} ($resolvedNumber): \"$body\"")
            } else {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(resolvedNumber)}")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult("send_sms", true, "Opened SMS composer for $recipient ($resolvedNumber). Grant SMS permission to send automatically.")
            }
        } catch (e: Exception) {
            ToolExecutionResult("send_sms", false, "Failed to send SMS: ${e.message}")
        }
    }

    fun navigateTo(destination: String, preferredApp: String? = null): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("navigate_to", true, "Navigating to $destination")
        }
        return try {
            val cleanDest = destination.trim()
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(cleanDest)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val requestedApp = preferredApp ?: when {
                    cleanDest.contains("osmand", ignoreCase = true) || cleanDest.contains("osm&", ignoreCase = true) -> "osmand"
                    else -> null
                }
                if (requestedApp != null) {
                    val pkg = resolvePackageForName(requestedApp)
                        ?: if (requestedApp.contains("osmand", ignoreCase = true)) "net.osmand" else null
                    if (pkg != null) setPackage(pkg)
                }
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                intent.setPackage(null)
                context.startActivity(intent)
            }
            ToolExecutionResult("navigate_to", true, "Started navigation to $destination")
        } catch (e: Exception) {
            ToolExecutionResult("navigate_to", false, "Could not start navigation: ${e.message}")
        }
    }

    fun createCalendarEvent(title: String, StartTimeEpochMs: Long?): ToolExecutionResult {
        if (context == null) {
            return ToolExecutionResult("create_calendar_event", true, "Created calendar event '$title'")
        }
        return try {
            val startMs = StartTimeEpochMs ?: (System.currentTimeMillis() + 3_600_000L)
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 3_600_000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult("create_calendar_event", true, "Created calendar event: $title")
        } catch (e: Exception) {
            ToolExecutionResult("create_calendar_event", false, "Calendar error: ${e.message}")
        }
    }

    /**
     * Local Web Search tool for Gemma 4 E2B QAT (scrapes DuckDuckGo Lite/HTML without API keys).
     */
    suspend fun searchWebLocal(query: String): ToolExecutionResult = withContext(ioDispatcher) {
        if (query.isBlank()) {
            return@withContext ToolExecutionResult("search_web", false, "Empty search query.")
        }
        org.fossistant.assistant.util.AppLogger.i("DeviceAndWebToolExecutor", "Executing web search: '${org.fossistant.assistant.util.AppLogger.userText(query)}'")
        // Photos load next to the text results and give up after a few seconds, so they never hold the answer up long.
        val photos = if (context != null && PhotoSearch.isAppropriate(query)) async { photoSearch.find(query) } else null
        val result = try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Android 15; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
                .build()

            var snippets = emptyList<String>()

            httpClient.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val doc = Jsoup.parse(html)
                snippets = doc.select(".result, .web-result").take(4).mapNotNull { element ->
                    val title = element.select(".result__title, .result__a").text().trim()
                    val snippet = element.select(".result__snippet").text().trim()
                    val link = element.select(".result__url").text().trim()
                    if (title.isNotBlank() || snippet.isNotBlank()) {
                        "$title ($link): $snippet".trim()
                    } else null
                }.mapIndexed { index, s -> "${index + 1}. $s" }
            }

            // Fallback to DuckDuckGo Lite if standard HTML had no results
            if (snippets.isEmpty()) {
                val liteRequest = Request.Builder()
                    .url("https://lite.duckduckgo.com/lite/?q=$encoded")
                    .header("User-Agent", "Mozilla/5.0 (Android 15; Mobile)")
                    .build()
                httpClient.newCall(liteRequest).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    val doc = Jsoup.parse(html)
                    val rows = doc.select("tr")
                    val liteResults = mutableListOf<String>()
                    var currentTitle = ""
                    var currentUrl = ""
                    for (row in rows) {
                        val linkElem = row.selectFirst("a.result-link")
                        if (linkElem != null) {
                            currentTitle = linkElem.text().trim()
                            currentUrl = linkElem.attr("href").trim()
                        }
                        val snippetElem = row.selectFirst("td.result-snippet")
                        if (snippetElem != null && currentTitle.isNotBlank()) {
                            val snip = snippetElem.text().trim()
                            liteResults.add("${liteResults.size + 1}. $currentTitle ($currentUrl): $snip")
                            currentTitle = ""
                            if (liteResults.size >= 4) break
                        }
                    }
                    if (liteResults.isNotEmpty()) {
                        snippets = liteResults
                    }
                }
            }

            if (snippets.isEmpty()) {
                org.fossistant.assistant.util.AppLogger.w("DeviceAndWebToolExecutor", "No web results returned for '${org.fossistant.assistant.util.AppLogger.userText(query)}'")
                ToolExecutionResult("search_web", false, "No web results found for '$query'.")
            } else {
                val combined = snippets.joinToString("\n")
                org.fossistant.assistant.util.AppLogger.i("DeviceAndWebToolExecutor", "Web search succeeded: found ${snippets.size} results for '${org.fossistant.assistant.util.AppLogger.userText(query)}'")
                ToolExecutionResult("search_web", true, "Found ${snippets.size} results for '$query'", combined)
            }
        } catch (e: Exception) {
            org.fossistant.assistant.util.AppLogger.e("DeviceAndWebToolExecutor", "Web search failed for '${org.fossistant.assistant.util.AppLogger.userText(query)}'", e)
            ToolExecutionResult("search_web", false, "Web search error: ${e.message}")
        }
        val found = photos?.await().orEmpty()
        if (!result.success || found.isEmpty()) return@withContext result
        org.fossistant.assistant.util.AppLogger.i("DeviceAndWebToolExecutor", "Web search: ${found.size} photos to show")
        result.copy(
            rawPayload = "${found.size} photos for this search are shown to the user with your answer.\n${result.rawPayload.orEmpty()}",
            visual = ToolVisual(VisualKind.PHOTOS, title = query.trim().take(80), photos = found)
        )
    }

    /** Exact arithmetic for the model ([Calculator]); the tool chip shows "expression = result". */
    fun calculate(expression: String): ToolExecutionResult {
        if (expression.isBlank()) return ToolExecutionResult("calculate", false, "Empty expression.")
        return try {
            val line = "${expression.trim().take(80)} = ${Calculator.format(Calculator.evaluate(expression))}"
            ToolExecutionResult("calculate", true, line, displayDetail = line)
        } catch (e: ArithmeticException) {
            ToolExecutionResult("calculate", false, "Can't calculate '$expression': ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult(
                "calculate", false,
                "Can't calculate '$expression': ${e.message}. Use numbers, + - * / ^ ( ) and functions like sqrt(x), e.g. '(12.5 * 4) / 3'."
            )
        }
    }

    /**
     * Local Webpage Reader tool for Gemma 4 E2B QAT (extracts clean article text with Jsoup).
     * Enforces HTTP(S) schemes and blocks loopback/private/link-local addresses (including across redirects)
     * to prevent local file disclosure and SSRF against localhost or LAN devices.
     */
    suspend fun fetchWebpageText(url: String): ToolExecutionResult = withContext(ioDispatcher) {
        try {
            var currentUrl = url.trim()
            var redirects = 0
            while (true) {
                val parsed = runCatching { java.net.URI(currentUrl) }.getOrNull()
                    ?: return@withContext ToolExecutionResult("fetch_webpage", false, "Invalid URL '$url'.")
                val scheme = parsed.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return@withContext ToolExecutionResult("fetch_webpage", false, "Only HTTP and HTTPS URLs are allowed.")
                }
                val host = parsed.host?.trim()?.lowercase().orEmpty()
                if (!isSafePublicHost(host)) {
                    return@withContext ToolExecutionResult("fetch_webpage", false, "Refusing to read private or local network address '$host'.")
                }
                val response = Jsoup.connect(currentUrl)
                    .userAgent("Mozilla/5.0 (Android 15; Mobile)")
                    .timeout(8000)
                    .followRedirects(false)
                    .execute()
                if (response.statusCode() in 300..399) {
                    val location = response.header("Location")?.trim().orEmpty()
                    if (location.isEmpty() || ++redirects > 4) {
                        return@withContext ToolExecutionResult("fetch_webpage", false, "Too many or invalid redirects for $url.")
                    }
                    currentUrl = parsed.resolve(location).toString()
                    continue
                }
                val doc = response.parse()
                doc.select("script, style, nav, footer, header, aside").remove()
                val text = doc.body()?.text().orEmpty().take(6000) // Cap at 6k chars to preserve 8GB RAM KV-cache budget
                return@withContext ToolExecutionResult("fetch_webpage", true, "Extracted ${text.length} chars from $url", text)
            }
            @Suppress("UNREACHABLE_CODE")
            ToolExecutionResult("fetch_webpage", false, "Failed to read URL $url")
        } catch (e: Exception) {
            ToolExecutionResult("fetch_webpage", false, "Failed to read URL $url: ${e.message}")
        }
    }

    internal fun isSafePublicHost(host: String): Boolean {
        if (host.isBlank()) return false
        val clean = host.removeSurrounding("[", "]")
        if (clean == "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local") || clean.endsWith(".internal")) {
            return false
        }
        val addresses = runCatching { java.net.InetAddress.getAllByName(clean) }.getOrNull()
        if (addresses.isNullOrEmpty()) return false
        return addresses.all { addr -> !isPrivateOrLocalAddress(addr) }
    }

    internal fun isPrivateOrLocalAddress(addr: java.net.InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address ?: return true
        return when (bytes.size) {
            4 -> {
                val b0 = bytes[0].toInt() and 0xff
                val b1 = bytes[1].toInt() and 0xff
                // 100.64.0.0/10 (Carrier-Grade NAT / Tailscale) or 0.0.0.0/8
                b0 == 0 || (b0 == 100 && (b1 and 0xc0) == 64)
            }
            16 -> {
                val b0 = bytes[0].toInt() and 0xff
                // Unique Local IPv6 (fc00::/7)
                (b0 and 0xfe) == 0xfc
            }
            else -> true
        }
    }
}


/**
 * Local time and how to sound for the briefing: a morning briefing only between 7:00 and 9:00, otherwise a casual
 * catch-up with a greeting that fits the hour (the model said "Good morning" at 5 pm before).
 */
internal fun briefingTimeNote(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
    val hour = now.hour
    val partOfDay = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }
    val clock = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE HH:mm", java.util.Locale.ENGLISH))
    val framing = if (hour in 7..8) {
        "This is the user's morning briefing: open with a natural good morning."
    } else {
        "It is $partOfDay, not briefing time: don't call this a morning briefing and don't say good morning; just catch them up."
    }
    return "Local time: $clock ($partOfDay). $framing Sound like a friend talking, in two or three relaxed sentences: " +
        "mention only what matters (round temperatures, skip humidity and empty parts unless asked), no lists, " +
        "no 'Is there anything I can help you with?'."
}
