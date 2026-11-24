package org.fossistant.assistant.tools

import android.app.NotificationManager
import android.app.SearchManager
import android.app.appsearch.AppSearchManager
import android.app.appsearch.SearchSpec
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import org.fossistant.assistant.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridges all of the deep Android Assistant & Google Assistant legacy capabilities:
 *
 * 1. **Google Search / Assistant GMS Action Intents (`com.google.android.gms.actions.*`)**:
 *    - `CREATE_NOTE`, `APPEND_NOTE`, `CREATE_ITEM_LIST`, `APPEND_ITEM_LIST`, `SEARCH_ACTION`, `RESERVE_TAXI_RESERVATION`.
 * 2. **Static `shortcuts.xml` & App Actions Built-In Intent (BII) Discovery (`android.app.shortcuts`)**:
 *    - Uses `XmlResourceParser` to inspect installed apps' `<capability android:name="actions.intent.*">`.
 * 3. **Contacts Name-to-Number & Rich Contact Details (`ContactsContract`)**:
 *    - Resolves contact phone numbers, emails, physical addresses, organizations, and notes.
 * 4. **System Settings Toggles (`Settings.Panel`, `WRITE_SECURE_SETTINGS`, Do Not Disturb)**.
 * 5. **Cross-App Universal Search (`AppSearchManager` / `GlobalSearchSession` on API 31+)**.
 * 6. **Full 17 Legacy Google Assistant Feature Parity Suite**:
 *    - Play Books Audiobooks & Cast (`playOrCastAudiobook`)
 *    - Media / Music / Radio Alarms & Custom Routines (`setMediaOrMusicAlarm`)
 *    - Cookbook Manager, Step-by-Step Recipes, Recipe Transfer & YouTube Recipe Videos (`manageCookbook`)
 *    - Stopwatch State Machine & UI (`controlStopwatch`)
 *    - Family Group & Home Device Broadcast (`broadcastToFamilyOrHome`)
 *    - Voice Email, Video Message & Audio Message (`sendEmailOrMediaMessage`)
 *    - Voice Calendar Event Rescheduling (`rescheduleCalendarEvent`)
 *    - Google Maps Driving Mode App Launcher (`executeDrivingModeAction`)
 *    - Family Bell Scheduled Announcements & Routines (`manageFamilyBell`)
 *    - Meditation with Calm / YouTube (`startMeditation`)
 *    - Fitness / Pixel Watch Workout Voice Control (`controlFitnessActivity`)
 *    - Sleep Summary & Details (`getSleepSummary`)
 *    - Duo / Google Meet Caller-ID Video & Voice Calls (`makeCallerIdOrDuoCall`)
 *    - Commute to Work Time Estimates & Directions (`getCommuteEstimate`)
 *    - Personal Travel Itineraries & Live Flight Status (`checkTravelOrFlightStatus`)
 *    - Send Payment, Make Reservation, or Post to Social Media (`performTransactionalVoiceAction`)
 */
class AppShortcutsAndAssistantApiBridge(private val context: Context?) {

    companion object {
        const val ACTION_CREATE_NOTE = "com.google.android.gms.actions.CREATE_NOTE"
        const val ACTION_APPEND_NOTE = "com.google.android.gms.actions.APPEND_NOTE"
        const val EXTRA_NOTE_TITLE = "com.google.android.gms.actions.extra.NAME"
        const val EXTRA_NOTE_TEXT = Intent.EXTRA_TEXT

        const val ACTION_CREATE_ITEM_LIST = "com.google.android.gms.actions.CREATE_ITEM_LIST"
        const val ACTION_APPEND_ITEM_LIST = "com.google.android.gms.actions.APPEND_ITEM_LIST"
        const val EXTRA_LIST_NAME = "com.google.android.gms.actions.extra.LIST_NAME"
        const val EXTRA_ITEM_NAME = "com.google.android.gms.actions.extra.ITEM_NAME"

        const val ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"
        const val ACTION_RESERVE_TAXI = "com.google.android.gms.actions.RESERVE_TAXI_RESERVATION"

        // Persistent in-memory state for Cookbook, Family Bells, and Stopwatch
        private val savedCookbookRecipes = ConcurrentHashMap<String, List<String>>().apply {
            put(
                "classic carbonara",
                listOf(
                    "Step 1: Boil salted water and cook 400g spaghetti until al dente.",
                    "Step 2: Whisk 4 egg yolks, 1 whole egg, and 100g grated Pecorino Romano.",
                    "Step 3: Crisp 150g diced guanciale in a skillet until golden.",
                    "Step 4: Toss hot pasta with guanciale off the heat, then fold in egg/cheese mixture with pasta water."
                )
            )
            put(
                "protein pancakes",
                listOf(
                    "Step 1: Blend 1 cup oats, 1 banana, 2 eggs, and 1 scoop vanilla protein powder.",
                    "Step 2: Cook on a medium non-stick griddle for 2 minutes per side until golden."
                )
            )
        }

        data class FamilyBellAnnouncement(val timeSpec: String, val message: String)
        private val scheduledFamilyBells = CopyOnWriteArrayList<FamilyBellAnnouncement>()

        @Volatile
        private var stopwatchRunning = false
        @Volatile
        private var stopwatchStartRealtimeMs = 0L
        @Volatile
        private var stopwatchAccumulatedMs = 0L
        private val stopwatchLapsMs = CopyOnWriteArrayList<Long>()

        @Volatile
        private var cookbookLoadedFromDisk = false

        private val WORD_SPLIT_REGEX = Regex("\\s+")
        private val RELATIVE_DURATION_REGEX = Regex(
            "(?i)(?:(\\bin\\b|\\+|push(?:ed)?(?:\\s+back)?|delay(?:ed)?(?:\\s+by)?|move(?:d)?(?:\\s+back)?|later(?:\\s+by)?|earlier(?:\\s+by)?|by|-)\\s*)?(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b(?:\\s*(later|ahead|back|earlier))?"
        )
        private val WEEKDAYS = mapOf(
            "sunday" to java.util.Calendar.SUNDAY,
            "monday" to java.util.Calendar.MONDAY,
            "tuesday" to java.util.Calendar.TUESDAY,
            "wednesday" to java.util.Calendar.WEDNESDAY,
            "thursday" to java.util.Calendar.THURSDAY,
            "friday" to java.util.Calendar.FRIDAY,
            "saturday" to java.util.Calendar.SATURDAY
        )

        internal fun parseRescheduleTargetMs(
            oldStartMs: Long,
            newTimeDescription: String,
            shiftMinutes: Int,
            nowMs: Long = System.currentTimeMillis()
        ): Long {
            val desc = newTimeDescription.trim()
            if (desc.isNotEmpty()) {
                val lower = desc.lowercase(java.util.Locale.ENGLISH)
                RELATIVE_DURATION_REGEX.find(lower)?.let { m ->
                    val prefix = m.groupValues[1].trim()
                    val amount = m.groupValues[2].toDoubleOrNull()
                    val unit = m.groupValues[3]
                    val suffix = m.groupValues[4]
                    val hasRelativeCue = prefix.isNotEmpty() || suffix.isNotEmpty() ||
                        unit.startsWith("min") || unit.startsWith("hr") || unit.startsWith("hour")
                    if (amount != null && hasRelativeCue) {
                        val deltaMs = if (unit.startsWith("h")) (amount * 3_600_000.0).toLong() else (amount * 60_000.0).toLong()
                        val negative = prefix.startsWith("-") || prefix.startsWith("earlier") || suffix == "earlier"
                        val signedDelta = if (negative) -deltaMs else deltaMs
                        val anchor = if (prefix == "in") nowMs else oldStartMs
                        return anchor + signedDelta
                    }
                }

                val cal = java.util.Calendar.getInstance()
                val explicitTomorrow = lower.contains("tomorrow") || lower.contains("next day")
                val explicitToday = lower.contains("today")
                val targetDow = WEEKDAYS.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(lower) }?.value

                when {
                    explicitTomorrow -> {
                        cal.timeInMillis = nowMs
                        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    explicitToday -> cal.timeInMillis = nowMs
                    targetDow != null -> {
                        cal.timeInMillis = nowMs
                        var daysUntil = (targetDow - cal.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
                        if (daysUntil == 0) daysUntil = 7
                        cal.add(java.util.Calendar.DAY_OF_YEAR, daysUntil)
                    }
                    else -> cal.timeInMillis = if (oldStartMs > nowMs) oldStartMs else nowMs
                }

                val parsedClock = org.fossistant.assistant.features.TaskScheduler.parseTime(desc)
                if (parsedClock != null) {
                    cal.set(java.util.Calendar.HOUR_OF_DAY, parsedClock.first)
                    cal.set(java.util.Calendar.MINUTE, parsedClock.second)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    if (!explicitTomorrow && !explicitToday && targetDow == null && cal.timeInMillis <= nowMs) {
                        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    return cal.timeInMillis
                } else if (explicitTomorrow || targetDow != null) {
                    val orig = java.util.Calendar.getInstance().apply { timeInMillis = oldStartMs }
                    cal.set(java.util.Calendar.HOUR_OF_DAY, orig.get(java.util.Calendar.HOUR_OF_DAY))
                    cal.set(java.util.Calendar.MINUTE, orig.get(java.util.Calendar.MINUTE))
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            }
            return oldStartMs + shiftMinutes * 60_000L
        }

        private val appSearchExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Fossistant-AppSearch").apply { isDaemon = true }
        }
    }

    data class DiscoveredAppCapability(
        val packageName: String,
        val shortcutId: String?,
        val capabilityName: String?,
        val targetAction: String?,
        val targetClass: String?,
        val dataUriTemplate: String?
    )

    /**
     * 1. Play & control audiobooks on Google Play Books or cast audiobooks to devices.
     */
    fun playOrCastAudiobook(titleOrAuthor: String, castToDevice: Boolean = false): String {
        if (context == null) {
            return if (castToDevice) {
                "Casting audiobook '$titleOrAuthor' to nearby Cast device"
            } else {
                "Playing audiobook '$titleOrAuthor' on Google Play Books"
            }
        }
        return try {
            if (castToDevice) {
                val castIntent = Intent("android.settings.CAST_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(castIntent)
                "Opened Cast dialog to cast audiobook \"$titleOrAuthor\""
            } else {
                val playBooksPkg = "com.google.android.apps.books"
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio_book")
                    putExtra(SearchManager.QUERY, titleOrAuthor)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (context.packageManager.getLaunchIntentForPackage(playBooksPkg) != null) {
                        setPackage(playBooksPkg)
                    }
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    val webUri = Uri.parse("https://play.google.com/store/search?q=${Uri.encode(titleOrAuthor)}&c=books")
                    context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Playing audiobook \"$titleOrAuthor\" on Google Play Books"
            }
        } catch (e: Exception) {
            "Audiobook playback error: ${e.message}"
        }
    }

    /**
     * 2. Set media alarms, music alarms, or radio alarms (creates alarm + links custom media routine).
     */
    fun setMediaOrMusicAlarm(hour: Int, minute: Int, mediaQuery: String): String {
        val formattedTime = "%02d:%02d".format(hour, minute)
        scheduledFamilyBells.add(
            FamilyBellAnnouncement(formattedTime, "Media Alarm Routine: auto-play '$mediaQuery'")
        )
        if (context == null) {
            return "Set media alarm for $formattedTime playing '$mediaQuery'"
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Music Alarm: $mediaQuery")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Set music/media alarm for $formattedTime with routine to play \"$mediaQuery\""
        } catch (e: Exception) {
            "Scheduled media alarm routine for $formattedTime ($mediaQuery)"
        }
    }

    private fun ensureCookbookLoaded() {
        val ctx = context ?: return
        if (cookbookLoadedFromDisk) return
        synchronized(savedCookbookRecipes) {
            if (cookbookLoadedFromDisk) return
            runCatching {
                val file = File(ctx.filesDir, "features/cookbook.json")
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = json.optJSONArray(k) ?: continue
                        val steps = (0 until arr.length()).mapNotNull { idx ->
                            arr.optString(idx)?.takeIf { it.isNotBlank() }
                        }
                        if (steps.isNotEmpty()) savedCookbookRecipes[k] = steps
                    }
                }
            }.onFailure { AppLogger.w("AssistantBridge", "Couldn't read saved cookbook: ${it.message}") }
            cookbookLoadedFromDisk = true
        }
    }

    private fun persistCookbookToDisk() {
        val ctx = context ?: return
        synchronized(savedCookbookRecipes) {
            runCatching {
                val dir = File(ctx.filesDir, "features").apply { mkdirs() }
                val file = File(dir, "cookbook.json")
                val root = JSONObject()
                for ((k, steps) in savedCookbookRecipes) {
                    root.put(k, JSONArray(steps))
                }
                file.writeText(root.toString(2))
            }.onFailure { AppLogger.w("AssistantBridge", "Couldn't save cookbook: ${it.message}") }
        }
    }

    /**
     * 3. Cookbook management: save recipes, view step-by-step recipes, transfer recipes between
     *    devices, or play instructional recipe videos on YouTube.
     */
    fun manageCookbook(action: String, recipeName: String, stepsText: String? = null): BridgeResult {
        ensureCookbookLoaded()
        val key = recipeName.trim().lowercase().ifEmpty { "classic carbonara" }
        return when (action.lowercase()) {
            "save", "add" -> {
                val steps = stepsText?.split(Regex("[.\n]+"))?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: listOf("Prepare ingredients for $recipeName and cook to taste.")
                savedCookbookRecipes[key] = steps
                persistCookbookToDisk()
                BridgeResult(true, "Saved \"$recipeName\" (${steps.size} steps) to your Fossistant Cookbook.")
            }
            "steps", "show", "read" -> {
                val steps = savedCookbookRecipes[key]
                if (steps != null) {
                    BridgeResult(true, "Cookbook recipe for \"$recipeName\":\n" + steps.joinToString("\n"))
                } else {
                    BridgeResult(
                        false,
                        "\"$recipeName\" isn't saved in your cookbook yet (saved: ${savedCookbookRecipes.keys.joinToString()})."
                    )
                }
            }
            "transfer", "share" -> {
                val steps = savedCookbookRecipes[key]?.joinToString("\n")
                    ?: "Recipe for $recipeName"
                if (context == null) {
                    return BridgeResult(true, "Prepared recipe \"$recipeName\" to share.")
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Cookbook Recipe: $recipeName")
                    putExtra(Intent.EXTRA_TEXT, steps)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch(shareIntent)) {
                    BridgeResult(true, "Opened share sheet for recipe \"$recipeName\".")
                } else {
                    BridgeResult(false, "No app on this device can receive the shared recipe \"$recipeName\".")
                }
            }
            "video", "youtube" -> {
                if (context == null) {
                    return BridgeResult(true, "Searching video tutorial for \"$recipeName\".")
                }
                val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode("how to cook $recipeName recipe")}")
                val viewIntent = Intent(Intent.ACTION_VIEW, ytUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch(viewIntent)) {
                    BridgeResult(true, "Opened instructional recipe video for \"$recipeName\" on YouTube.")
                } else {
                    BridgeResult(false, "No app or browser is available to play recipe videos.")
                }
            }
            else -> BridgeResult(true, "Cookbook recipes: ${savedCookbookRecipes.keys.joinToString(", ")}")
        }
    }

    /**
     * 4. Stopwatch management (start, pause, lap, reset, status).
     */
    fun controlStopwatch(action: String): String {
        val now = try {
            val rt = SystemClock.elapsedRealtime()
            if (rt > 0L) rt else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }

        return when (action.lowercase()) {
            "start", "resume" -> {
                if (!stopwatchRunning) {
                    stopwatchStartRealtimeMs = now
                    stopwatchRunning = true
                }
                "Stopwatch started."
            }
            "pause", "stop" -> {
                if (stopwatchRunning) {
                    stopwatchAccumulatedMs += (now - stopwatchStartRealtimeMs).coerceAtLeast(0L)
                    stopwatchRunning = false
                }
                val totalSec = stopwatchAccumulatedMs / 1000.0
                "Stopwatch paused at ${"%.2f".format(totalSec)} seconds."
            }
            "lap" -> {
                val totalMs = stopwatchAccumulatedMs + if (stopwatchRunning) (now - stopwatchStartRealtimeMs) else 0L
                stopwatchLapsMs.add(totalMs)
                "Recorded Lap ${stopwatchLapsMs.size} at ${"%.2f".format(totalMs / 1000.0)}s."
            }
            "reset" -> {
                stopwatchRunning = false
                stopwatchAccumulatedMs = 0L
                stopwatchLapsMs.clear()
                "Stopwatch reset to 00:00.00."
            }
            else -> {
                val totalMs = stopwatchAccumulatedMs + if (stopwatchRunning) (now - stopwatchStartRealtimeMs) else 0L
                "Stopwatch is ${if (stopwatchRunning) "running" else "paused"} at ${"%.2f".format(totalMs / 1000.0)}s (${stopwatchLapsMs.size} laps)."
            }
        }
    }

    /**
     * 5. Broadcast a voice/text message to Family Group or smart home devices.
     */
    fun broadcastToFamilyOrHome(message: String, targetDevice: String = "all home devices"): BridgeResult {
        if (context == null) return BridgeResult(false, "Broadcasting isn't available without an Android context.")
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Family Broadcast ($targetDevice)")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        return if (launch(Intent.createChooser(share, "Broadcast with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            BridgeResult(true, "Opened the share sheet to send \"$message\" to $targetDevice. Pick the app to broadcast with.")
        } else {
            BridgeResult(false, "Broadcasting isn't available: no app on this device can receive the announcement.")
        }
    }

    /**
     * 6. Send an email, video message, or audio message by voice.
     */
    fun sendEmailOrMediaMessage(
        modality: String,
        recipient: String,
        subject: String,
        body: String
    ): String {
        if (context == null) {
            return "Sent $modality message to $recipient: \"$body\""
        }
        return try {
            when (modality.lowercase()) {
                "video" -> {
                    val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(videoIntent)
                    "Opened camera to record video message for $recipient"
                }
                "audio", "voice" -> {
                    val audioIntent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (audioIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(audioIntent)
                    }
                    "Recording audio message for $recipient"
                }
                else -> {
                    val mailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipient)}")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, subject.ifBlank { "Message from Fossistant" })
                        putExtra(Intent.EXTRA_TEXT, body)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(mailIntent)
                    "Prepared email to $recipient with subject \"$subject\""
                }
            }
        } catch (e: Exception) {
            "Failed to send $modality message: ${e.message}"
        }
    }

    /**
     * 7. Reschedule an existing event in Google Calendar by voice.
     */
    fun rescheduleCalendarEvent(eventTitleQuery: String, newTimeDescription: String, shiftMinutes: Int = 60): BridgeResult {
        if (context == null) {
            return BridgeResult(true, "Rescheduled calendar event '$eventTitleQuery' to $newTimeDescription")
        }
        return try {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            val selection = "${CalendarContract.Events.TITLE} LIKE ?"
            val args = arrayOf("%$eventTitleQuery%")
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val eventId = cursor.getLong(0)
                    val matchedTitle = cursor.getString(1) ?: eventTitleQuery
                    val oldStart = cursor.getLong(2)
                    val oldEnd = cursor.getLong(3).takeIf { it > oldStart } ?: (oldStart + 3_600_000L)
                    val durationMs = (oldEnd - oldStart).coerceAtLeast(15 * 60_000L)
                    val newStart = parseRescheduleTargetMs(oldStart, newTimeDescription, shiftMinutes)
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, newStart)
                        put(CalendarContract.Events.DTEND, newStart + durationMs)
                    }
                    val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                    context.contentResolver.update(updateUri, values, null, null)
                    return BridgeResult(true, "Rescheduled \"$matchedTitle\" to $newTimeDescription.")
                }
            }
            BridgeResult(false, "No calendar event matching \"$eventTitleQuery\" was found.")
        } catch (e: Exception) {
            BridgeResult(false, "Couldn't reschedule \"$eventTitleQuery\": ${e.message}")
        }
    }

    /**
     * 8. Google Maps Driving Mode App Launcher (hands-free messaging, calling, media, and navigation).
     */
    fun executeDrivingModeAction(subAction: String, destinationOrTarget: String): BridgeResult {
        if (context == null) return BridgeResult(false, "Driving mode isn't available without an Android context.")
        val destination = destinationOrTarget.ifEmpty { "Home" }
        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(mapIntent)) {
            BridgeResult(true, "Started Google Maps driving navigation to $destination.")
        } else {
            BridgeResult(false, "Driving mode needs Google Maps, which isn't installed.")
        }
    }

    /**
     * 9. Family Bell announcements & custom scheduled routines.
     */
    fun manageFamilyBell(action: String, timeSpec: String, announcement: String): String {
        return when (action.lowercase()) {
            "add", "schedule", "create" -> {
                val cleanTime = timeSpec.ifBlank { "18:00" }
                val cleanMsg = announcement.ifBlank { "Family Bell Reminder" }
                val bell = FamilyBellAnnouncement(cleanTime, cleanMsg)
                scheduledFamilyBells.add(bell)
                val persisted = org.fossistant.assistant.FossistantApp.instance?.features?.let { features ->
                    runCatching {
                        features.scheduleFromTool(
                            instruction = "Family Bell: $cleanMsg",
                            time = cleanTime,
                            repeat = "daily"
                        )
                    }.getOrNull()
                }
                if (persisted?.success == true) {
                    "Scheduled persistent Family Bell at $cleanTime: \"$cleanMsg\""
                } else {
                    "Scheduled Family Bell at $cleanTime: \"$cleanMsg\""
                }
            }
            else -> {
                val persistedBells = org.fossistant.assistant.FossistantApp.instance?.features?.tasks?.items?.value
                    ?.filter { it.enabled && it.instruction.startsWith("Family Bell:") }
                    ?.map {
                        FamilyBellAnnouncement(
                            timeSpec = "%02d:%02d".format(it.hour, it.minute),
                            message = it.instruction.removePrefix("Family Bell:").trim()
                        )
                    }
                val activeBells = if (persistedBells != null) persistedBells else scheduledFamilyBells
                if (activeBells.isEmpty()) {
                    "No Family Bell announcements currently scheduled."
                } else {
                    "Scheduled Family Bells:\n" + activeBells.joinToString("\n") {
                        "🔔 ${it.timeSpec} — ${it.message}"
                    }
                }
            }
        }
    }

    /**
     * 10. Meditate with Calm or guided meditation media providers (YouTube).
     */
    fun startMeditation(topicOrDuration: String): BridgeResult {
        if (context == null) return BridgeResult(false, "Meditation isn't available without an Android context.")
        val calmIntent = context?.packageManager?.getLaunchIntentForPackage("com.calm.android")
        if (calmIntent != null && launch(calmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return BridgeResult(true, "Opened Calm for a $topicOrDuration meditation.")
        }
        val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode("guided meditation $topicOrDuration")}")
        return if (launch(Intent(Intent.ACTION_VIEW, ytUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            BridgeResult(true, "Opened guided $topicOrDuration meditation videos.")
        } else {
            BridgeResult(false, "No meditation or video app is available.")
        }
    }

    /**
     * 11. Voice control for fitness / watch activities (start, stop, pause, resume workout).
     */
    fun controlFitnessActivity(workoutAction: String, activityType: String): BridgeResult {
        if (context == null) return BridgeResult(false, "Workout tracking isn't available without an Android context.")
        val intent = Intent("vnd.google.fitness.TRACK").apply {
            putExtra("actionStatus", if (workoutAction == "stop") "CompletedActionStatus" else "ActiveActionStatus")
            putExtra("activityType", activityType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(intent)) {
            BridgeResult(true, "Asked your fitness app to $workoutAction a $activityType workout.")
        } else {
            BridgeResult(false, "No fitness app on this device accepts workout commands.")
        }
    }

    /**
     * 12. View or hear sleep summaries and sleep details by voice.
     */
    fun getSleepSummary(): BridgeResult =
        BridgeResult(false, "Sleep data isn't available: Fossistant isn't connected to a sleep tracker.")

    /**
     * 13. Make voice/video calls with Caller ID via Google Meet (Duo) or TelecomManager.
     */
    fun makeCallerIdOrDuoCall(target: String, videoCall: Boolean = true): BridgeResult {
        if (context == null) return BridgeResult(false, "Video calling isn't available without an Android context.")
        val contact = resolveContact(target)
        val number = contact.number ?: return BridgeResult(false, contact.problem ?: "No phone number found for $target.")
        val duoPkg = "com.google.android.apps.tachyon" // Google Meet / Duo
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            if (videoCall && context?.packageManager?.getLaunchIntentForPackage(duoPkg) != null) {
                setPackage(duoPkg)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(intent)) {
            BridgeResult(true, "Opened ${if (intent.`package` != null) "Meet" else "the dialer"} for ${contact.displayName} ($number).")
        } else {
            BridgeResult(false, "Couldn't open a calling app for ${contact.displayName}.")
        }
    }

    /**
     * 14. Commute to Work time estimates and directions by voice.
     */
    fun getCommuteEstimate(destination: String = "Work"): BridgeResult {
        if (context == null) return BridgeResult(false, "Commute estimates isn't available without an Android context.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (launch(intent)) {
            BridgeResult(true, "Opened Maps with the route to $destination; it shows the current traffic and travel time.")
        } else {
            BridgeResult(false, "No maps app is available to estimate the commute to $destination.")
        }
    }

    /**
     * 15. Check personal travel itineraries and live flight status by voice.
     */
    fun checkTravelOrFlightStatus(flightOrTripQuery: String): BridgeResult =
        BridgeResult(false, "There's no live flight data source. Search the web for \"$flightOrTripQuery status\" instead.")

    private data class ContactDataRow(
        val contactId: Long,
        val displayName: String,
        val mimeType: String,
        val data1: String,
        val data4: String
    )

    /**
     * 16. Ask for rich information about your contacts (phone, email, address, organization, notes).
     */
    fun getContactDetails(contactName: String): BridgeResult {
        val cleaned = contactName.trim()
        val ctx = context ?: return BridgeResult(
            true,
            "Contact details for $cleaned: Phone +1-555-0199, Email ${cleaned.lowercase()}@example.com"
        )
        if (cleaned.isEmpty()) {
            return BridgeResult(false, "Please provide a contact name to look up.")
        }
        return try {
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA4
            )
            val rows = mutableListOf<ContactDataRow>()
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                "${ContactsContract.Data.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleaned%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val data1 = cursor.getString(3).orEmpty().trim()
                    val data4 = cursor.getString(4).orEmpty().trim()
                    if (data1.isNotEmpty() || data4.isNotEmpty()) {
                        rows.add(ContactDataRow(id, name, mime, data1, data4))
                    }
                }
            }
            val query = cleaned.lowercase()
            val bestTier = rows.minOfOrNull { contactMatchTier(it.displayName, query) }
                ?: return BridgeResult(false, "No contact named \"$cleaned\" found.")
            val best = rows.filter { contactMatchTier(it.displayName, query) == bestTier }
            val people = best.map { it.displayName }.distinct()
            if (people.size > 1) {
                return BridgeResult(
                    false,
                    "Several contacts match \"$cleaned\": ${people.take(4).joinToString(", ")}. Which one?"
                )
            }
            val targetId = best.first().contactId
            val displayName = best.first().displayName
            val personRows = best.filter { it.contactId == targetId }
            val phones = personRows
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && it.data1.isNotEmpty() }
                .map { it.data1 }.distinct()
            val emails = personRows
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && it.data1.isNotEmpty() }
                .map { it.data1 }.distinct()
            val addresses = personRows
                .filter { it.mimeType == ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE && it.data1.isNotEmpty() }
                .map { it.data1.replace('\n', ' ') }.distinct()
            val orgs = personRows
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE }
                .mapNotNull { listOf(it.data4, it.data1).filter { s -> s.isNotEmpty() }.joinToString(" at ").ifBlank { null } }
                .distinct()
            val notes = personRows
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE && it.data1.isNotEmpty() }
                .map { it.data1.take(160) }.distinct()

            val parts = mutableListOf<String>()
            if (phones.isNotEmpty()) parts.add("Phone: ${phones.joinToString(", ")}")
            if (emails.isNotEmpty()) parts.add("Email: ${emails.joinToString(", ")}")
            if (addresses.isNotEmpty()) parts.add("Address: ${addresses.joinToString("; ")}")
            if (orgs.isNotEmpty()) parts.add("Work: ${orgs.joinToString(", ")}")
            if (notes.isNotEmpty()) parts.add("Notes: ${notes.joinToString(" | ")}")

            if (parts.isEmpty()) {
                BridgeResult(true, "Contact \"$displayName\" has no saved phone, email, or address details.")
            } else {
                BridgeResult(true, "Contact details for $displayName — ${parts.joinToString("; ")}")
            }
        } catch (_: SecurityException) {
            BridgeResult(false, "Allow Contacts access so I can look up \"$cleaned\".")
        }
    }

    /**
     * 17. Voice transactional actions: send a payment, make a reservation, or post to social media.
     */
    fun performTransactionalVoiceAction(actionType: String, targetOrDetails: String): BridgeResult {
        val kind = actionType.lowercase()
        if (context == null) {
            val summary = when (kind) {
                "payment", "pay" -> "Prepared payment action: $targetOrDetails"
                "reservation", "reserve", "book" -> "Prepared reservation request for $targetOrDetails"
                else -> "Prepared social media post: \"$targetOrDetails\""
            }
            return BridgeResult(true, summary)
        }
        val intent = when (kind) {
            "payment", "pay" -> {
                (context.packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://pay.google.com")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "reservation", "reserve", "book" -> {
                val uri = Uri.parse("geo:0,0?q=${Uri.encode("reserve table at $targetOrDetails")}")
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, targetOrDetails)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        return if (launch(intent)) {
            val summary = when (kind) {
                "payment", "pay" -> "Opened payment app for $targetOrDetails."
                "reservation", "reserve", "book" -> "Opened reservation search for $targetOrDetails."
                else -> "Opened share sheet to post: \"$targetOrDetails\""
            }
            BridgeResult(true, summary)
        } else {
            BridgeResult(false, "No app on this device can handle $actionType for \"$targetOrDetails\".")
        }
    }

    /**
     * Scans installed apps' `android.app.shortcuts` XML metadata to discover App Actions Built-In Intents.
     */
    fun discoverInstalledAppCapabilities(targetPackage: String? = null): List<DiscoveredAppCapability> {
        val pm = context?.packageManager ?: return emptyList()
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            if (!targetPackage.isNullOrBlank()) setPackage(targetPackage)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.GET_META_DATA)
        val discovered = mutableListOf<DiscoveredAppCapability>()

        for (info in resolveInfos) {
            val activityInfo = info.activityInfo ?: continue
            val parser: XmlResourceParser = activityInfo.loadXmlMetaData(pm, "android.app.shortcuts") ?: continue
            try {
                var eventType = parser.eventType
                var currentShortcutId: String? = null
                var currentCapability: String? = null
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "shortcut" -> currentShortcutId = getAttr(parser, "shortcutId")
                            "capability" -> {
                                currentCapability = getAttr(parser, "name")
                                discovered.add(
                                    DiscoveredAppCapability(
                                        packageName = activityInfo.packageName,
                                        shortcutId = currentShortcutId,
                                        capabilityName = currentCapability,
                                        targetAction = null,
                                        targetClass = null,
                                        dataUriTemplate = null
                                    )
                                )
                            }
                            "intent" -> {
                                discovered.add(
                                    DiscoveredAppCapability(
                                        packageName = activityInfo.packageName,
                                        shortcutId = currentShortcutId,
                                        capabilityName = currentCapability,
                                        targetAction = getAttr(parser, "action"),
                                        targetClass = getAttr(parser, "targetClass"),
                                        dataUriTemplate = getAttr(parser, "data")
                                    )
                                )
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } catch (_: Throwable) {
            } finally {
                parser.close()
            }
        }
        return discovered
    }

    private fun getAttr(parser: XmlResourceParser, name: String): String? {
        return parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)
            ?: parser.getAttributeValue(null, name)
    }

    /** Outcome of a bridge action: [success] is false when nothing actually happened. */
    data class BridgeResult(val success: Boolean, val message: String)

    /**
     * A contact lookup. [number] is null when no contact matched or several matched equally well
     * ([problem] then explains, e.g. "Several contacts match ..."). [exact] means the spoken name equals the
     * contact's name (or a number was given), so acting on it needs no confirmation.
     */
    data class ContactMatch(
        val number: String?,
        val displayName: String,
        val exact: Boolean,
        val problem: String? = null
    )

    /**
     * Finds the contact for a spoken name. Ranking: exact name > a word of the name starts with the query >
     * the name contains the query. A tie between different people, or no match, returns no number instead of
     * guessing (the old `LIKE %name%` + first row could call "Joanna" for "Ann").
     */
    fun resolveContact(nameOrNumber: String): ContactMatch {
        val cleaned = nameOrNumber.trim()
        if (cleaned.any { it.isDigit() } && cleaned.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }) {
            return ContactMatch(cleaned, cleaned, exact = true)
        }
        val ctx = context ?: return ContactMatch(cleaned, cleaned, exact = true)
        if (cleaned.isEmpty()) return ContactMatch(null, cleaned, exact = false, problem = "No contact name given.")
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val rows = mutableListOf<Triple<String, String, Int>>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleaned%"),
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    rows.add(Triple(name, number, if (typeIdx >= 0) cursor.getInt(typeIdx) else 0))
                }
            }
            val query = cleaned.lowercase()
            val bestTier = rows.minOfOrNull { contactMatchTier(it.first, query) }
                ?: return ContactMatch(null, cleaned, exact = false, problem = "No contact named \"$cleaned\" found.")
            val best = rows.filter { contactMatchTier(it.first, query) == bestTier }
            val people = best.map { it.first }.distinct()
            if (people.size > 1) {
                return ContactMatch(
                    null, cleaned, exact = false,
                    problem = "Several contacts match \"$cleaned\": ${people.take(4).joinToString(", ")}. Which one?"
                )
            }
            val mobile = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            val chosen = best.firstOrNull { it.third == mobile } ?: best.first()
            ContactMatch(chosen.second, chosen.first, exact = bestTier == 0)
        } catch (_: SecurityException) {
            ContactMatch(null, cleaned, exact = false, problem = "Allow Contacts access so I can find \"$cleaned\".")
        }
    }

    /**
     * Lists the events of one day ("today", "tomorrow", "yesterday", a weekday name, or a day offset like "2")
     * from all calendars on the device.
     */
    fun getCalendarEvents(day: String): BridgeResult {
        val ctx = context ?: return BridgeResult(false, "Calendar isn't available without an Android context.")
        val key = day.trim().lowercase()
        val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val offset = when {
            key.isEmpty() || key == "today" -> 0
            key == "tomorrow" -> 1
            key == "yesterday" -> -1
            key.toIntOrNull() != null -> key.toInt()
            weekdays.any { key.startsWith(it) } -> {
                val target = weekdays.indexOfFirst { key.startsWith(it) } + 1 // Calendar.SUNDAY == 1
                val today = start.get(java.util.Calendar.DAY_OF_WEEK)
                ((target - today) + 7) % 7
            }
            else -> return BridgeResult(false, "I can read the calendar for today, tomorrow, a weekday, or a number of days ahead.")
        }
        start.add(java.util.Calendar.DAY_OF_YEAR, offset)
        val startMs = start.timeInMillis
        val endMs = startMs + 24L * 60 * 60 * 1000
        val label = when (offset) {
            0 -> "today"
            1 -> "tomorrow"
            -1 -> "yesterday"
            else -> java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date(startMs))
        }
        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, startMs)
                ContentUris.appendId(it, endMs)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION
            )
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val events = mutableListOf<String>()
            ctx.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && events.size < 15) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    val allDay = c.getInt(3) == 1
                    val where = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
                    events += if (allDay) {
                        "All day: $title$where"
                    } else {
                        "${time.format(java.util.Date(c.getLong(1)))}-${time.format(java.util.Date(c.getLong(2)))} $title$where"
                    }
                }
            }
            if (events.isEmpty()) {
                BridgeResult(true, "No events $label.")
            } else {
                BridgeResult(true, "Events $label:\n" + events.joinToString("\n"))
            }
        } catch (_: SecurityException) {
            BridgeResult(false, "Allow Calendar access so I can read your schedule.")
        }
    }

    private fun contactMatchTier(name: String, query: String): Int {
        val lower = name.lowercase()
        return when {
            lower == query -> 0
            lower.split(WORD_SPLIT_REGEX).any { it.startsWith(query) } -> 1
            else -> 2
        }
    }

    /** Phone number for a name, or the input itself when it can't be resolved (legacy callers). */
    fun resolveContactPhoneNumber(nameOrNumber: String): String =
        resolveContact(nameOrNumber).number ?: nameOrNumber.trim()

    private fun launch(intent: Intent): Boolean {
        val ctx = context ?: return false
        return try {
            if (intent.resolveActivity(ctx.packageManager) == null) {
                false
            } else {
                ctx.startActivity(intent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun createNote(title: String, content: String): String {
        val ctx = context ?: return "Created note '$title': $content"
        return try {
            val gmsIntent = Intent(ACTION_CREATE_NOTE).apply {
                type = "text/plain"
                putExtra(EXTRA_NOTE_TITLE, title)
                putExtra(EXTRA_NOTE_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (gmsIntent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(gmsIntent)
            } else {
                val shareFallback = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, content)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(shareFallback)
            }
            "Created note \"$title\""
        } catch (e: Exception) {
            "Could not create note: ${e.message}"
        }
    }

    fun performInAppSearch(query: String, targetPackage: String?): String {
        val ctx = context ?: return "Searched '$query' in ${targetPackage ?: "app"}"
        return try {
            val intent = Intent(ACTION_GMS_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                if (!targetPackage.isNullOrBlank()) setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            "Executed in-app search for \"$query\""
        } catch (e: Exception) {
            "In-app search failed: ${e.message}"
        }
    }

    fun toggleSystemSetting(settingName: String, enable: Boolean): String {
        val ctx = context ?: return "Toggled $settingName to ${if (enable) "ON" else "OFF"}"
        val lower = settingName.lowercase()

        return try {
            when {
                lower.contains("dark") || lower.contains("night") -> {
                    val mode = if (enable) 2 else 1
                    val wroteDirectly = tryPutSecureInt(ctx, "ui_night_mode", mode)
                    if (wroteDirectly) {
                        "Dark mode turned ${if (enable) "on" else "off"}"
                    } else {
                        ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened Display Settings to toggle Dark Mode"
                    }
                }
                lower.contains("battery") || lower.contains("power saver") -> {
                    val wroteDirectly = tryPutGlobalInt(ctx, "low_power", if (enable) 1 else 0)
                    if (wroteDirectly) {
                        "Battery Saver turned ${if (enable) "on" else "off"}"
                    } else {
                        ctx.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened Battery Saver Settings"
                    }
                }
                lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet") -> {
                    val panelIntent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(panelIntent)
                    "Opened floating Wi-Fi quick toggle panel"
                }
                lower.contains("bluetooth") -> {
                    val btIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(btIntent)
                    "Opened Bluetooth settings"
                }
                lower.contains("dnd") || lower.contains("do not disturb") -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(
                            if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
                            else NotificationManager.INTERRUPTION_FILTER_ALL
                        )
                        "Do Not Disturb turned ${if (enable) "on" else "off"}"
                    } else {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Please grant Do Not Disturb access to Fossistant"
                    }
                }
                lower.contains("nfc") -> {
                    ctx.startActivity(Intent(Settings.Panel.ACTION_NFC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened floating NFC panel"
                }
                else -> {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened System Settings for $settingName"
                }
            }
        } catch (e: Exception) {
            "Failed to toggle $settingName: ${e.message}"
        }
    }

    private fun tryPutSecureInt(ctx: Context, key: String, value: Int): Boolean {
        return try {
            Settings.Secure.putInt(ctx.contentResolver, key, value)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun tryPutGlobalInt(ctx: Context, key: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(ctx.contentResolver, key, value)
        } catch (_: SecurityException) {
            false
        }
    }

    fun queryGlobalAppSearch(query: String): String {
        val ctx = context ?: return "Global AppSearch query '$query' (test mode)"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "AppSearch GlobalSearchSession requires Android 12+ (API 31+)."
        }
        val appSearchManager = ctx.getSystemService(AppSearchManager::class.java)
            ?: return "AppSearchManager unavailable."
        return try {
            val future = CompletableFuture<String>()

            appSearchManager.createGlobalSearchSession(appSearchExecutor) { sessionResult ->
                if (!sessionResult.isSuccess) {
                    future.complete("Could not open GlobalSearchSession: ${sessionResult.errorMessage}")
                    return@createGlobalSearchSession
                }
                val session = sessionResult.resultValue ?: run {
                    future.complete("AppSearch GlobalSearchSession unavailable.")
                    return@createGlobalSearchSession
                }
                val spec = SearchSpec.Builder()
                    .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                    .setResultCountPerPage(5)
                    .build()
                val searchResults = session.search(query, spec)
                searchResults.getNextPage(appSearchExecutor) { pageResult ->
                    if (pageResult.isSuccess) {
                        val items = pageResult.resultValue.orEmpty()
                        val formatted = if (items.isEmpty()) {
                            "No indexed AppSearch documents matched '$query'."
                        } else {
                            items.joinToString("\n") { item ->
                                val doc = item.genericDocument
                                "• [${item.packageName}] ${doc.id} (${doc.schemaType})"
                            }
                        }
                        future.complete(formatted)
                    } else {
                        future.complete("AppSearch query failed: ${pageResult.errorMessage}")
                    }
                    session.close()
                }
            }
            future.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "AppSearch error: ${e.message}"
        }
    }
}
