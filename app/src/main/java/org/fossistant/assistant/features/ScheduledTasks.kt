package org.fossistant.assistant.features

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.R
import org.fossistant.assistant.model.ChatMessage
import org.fossistant.assistant.ui.MainActivity
import org.fossistant.assistant.ui.PreviousChatSession
import java.util.Calendar
import java.util.UUID

/**
 * Scheduled tasks (ChatGPT Tasks / Gemini scheduled actions): exact alarms wake [TaskAlarmReceiver], which
 * starts [TaskRunnerService]; the model runs the request with its tools (search, weather, calendar,
 * notifications) and the answer arrives as a notification that opens it as a chat.
 */
object TaskScheduler {

    const val CHANNEL_RESULTS = "scheduled_results"
    const val CHANNEL_RUNNING = "scheduled_running"
    const val NOTHING_TO_REPORT = "NOTHING"

    /** Instructions every scheduled run gets; the task's own request is the user turn. */
    const val TASK_INSTRUCTIONS =
        "You are Fossistant running a scheduled task for the user while they are away. Use your tools to get " +
            "current information (web search, weather, calendar, notifications) instead of guessing. Write the " +
            "result as a short, skimmable message the user reads in a notification: no preamble, no questions back."

    fun morningBrief(topics: String) = ScheduledTask(
        title = "Morning brief",
        instruction = "Give me my morning brief: today's weather here, my calendar for today, any notifications " +
            "that need action, and the top news" + (if (topics.isNotBlank()) " about $topics" else "") +
            ". Use a short heading for each part.",
        hour = 7, minute = 0, repeat = TaskRepeat.DAILY
    )

    val NOTIFICATION_TRIAGE_INSTRUCTION =
        "Read my notifications. List the ones that need me to do something, most urgent first, each with one line " +
            "saying what to do. Then one line summarizing everything else. If nothing needs action, reply with " +
            "exactly $NOTHING_TO_REPORT."

    fun notificationDigest() = ScheduledTask(
        title = "Notification triage",
        instruction = NOTIFICATION_TRIAGE_INSTRUCTION,
        hour = 9, minute = 0, repeat = TaskRepeat.HOURLY
    )

    /** Next time [task] should run after [nowMs], or null for a finished one-off task. */
    internal fun nextRunMs(task: ScheduledTask, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!task.enabled) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return when (task.repeat) {
            TaskRepeat.HOURLY -> {
                cal.set(Calendar.MINUTE, task.minute)
                if (cal.timeInMillis <= nowMs) cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.timeInMillis
            }
            TaskRepeat.ONCE, TaskRepeat.DAILY, TaskRepeat.WEEKDAYS -> {
                cal.set(Calendar.HOUR_OF_DAY, task.hour)
                cal.set(Calendar.MINUTE, task.minute)
                if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
                if (task.repeat == TaskRepeat.WEEKDAYS) {
                    while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                if (task.repeat == TaskRepeat.ONCE && task.lastRunMs > 0) null else cal.timeInMillis
            }
        }
    }

    /** (Re)arms an alarm for every enabled task and cancels the rest. */
    fun scheduleAll(context: Context) {
        val store = FossistantApp.instance?.features?.tasks ?: return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        for (task in store.items.value) {
            val pending = alarmIntent(context, task.id)
            val next = nextRunMs(task)
            if (next == null) {
                alarms.cancel(pending)
                continue
            }
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exactAllowed) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        }
    }

    fun cancel(context: Context, taskId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, taskId))
    }

    private fun alarmIntent(context: Context, taskId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskAlarmReceiver::class.java)
            .setData(android.net.Uri.parse("fossistant://task/$taskId"))
            .putExtra(TaskRunnerService.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Parses "7:30", "07:30", "7.30", "19" into hour and minute. */
    internal fun parseTime(text: String): Pair<Int, Int>? {
        val match = Regex("(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        when (match.groupValues[3].lowercase()) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Scheduled task results", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Running tasks", NotificationManager.IMPORTANCE_MIN)
        )
    }
}

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskRunnerService.EXTRA_TASK_ID) ?: return
        runCatching {
            context.startForegroundService(
                Intent(context, TaskRunnerService::class.java).putExtra(TaskRunnerService.EXTRA_TASK_ID, taskId)
            )
        }.onFailure { org.fossistant.assistant.util.AppLogger.w("TaskAlarmReceiver", "Couldn't start task runner: ${it.message}") }
    }
}

/** Re-arms scheduled tasks after a reboot or an app update (alarms don't survive either). */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        TaskScheduler.scheduleAll(context)
    }
}

/** Runs due tasks one at a time in the foreground, posts each result, then stops. */
class TaskRunnerService : Service() {

    // Queue bookkeeping runs on the main thread (like onStartCommand); the model work switches threads itself.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<String>()
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskScheduler.ensureChannels(this)
        val notification = Notification.Builder(this, TaskScheduler.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_tile_assistant)
            .setContentTitle("Running a scheduled task")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        intent?.getStringExtra(EXTRA_TASK_ID)?.let { queue.addLast(it) }
        if (!running) {
            running = true
            scope.launch {
                while (true) {
                    val id = queue.removeFirstOrNull() ?: break
                    runTask(id)
                }
                running = false
                TaskScheduler.scheduleAll(this@TaskRunnerService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(taskId: String) {
        val app = FossistantApp.instance ?: return
        val store = app.features.tasks
        val task = store.get(taskId) ?: return
        org.fossistant.assistant.util.AppLogger.i("TaskRunner", "Running scheduled task '${task.title}'")
        val result = app.gemmaEngine.completeOnce(
            instructions = TaskScheduler.TASK_INSTRUCTIONS,
            input = task.instruction,
            withTools = true,
            maxTokens = 1024
        ) ?: "The task couldn't run: ${app.gemmaEngine.lastEngineError ?: "the model isn't available."}"
        store.upsert(
            task.copy(
                lastRunMs = System.currentTimeMillis(),
                lastResult = result.take(4000),
                enabled = task.enabled && task.repeat != TaskRepeat.ONCE
            )
        )
        if (result.trim().trimEnd('.').equals(TaskScheduler.NOTHING_TO_REPORT, ignoreCase = true)) return
        val chatId = UUID.randomUUID().toString()
        app.chatHistoryStore.upsert(
            PreviousChatSession(
                id = chatId,
                title = task.title,
                messages = listOf(
                    ChatMessage(id = UUID.randomUUID().toString(), isUser = true, text = task.instruction),
                    ChatMessage(id = UUID.randomUUID().toString(), isUser = false, text = result)
                )
            )
        )
        postResult(task, result, chatId)
        if (task.speakResult) app.ttsEngine.speakFast(result, flushQueue = true)
    }

    private fun postResult(task: ScheduledTask, result: String, chatId: String) {
        val open = PendingIntent.getActivity(
            this,
            chatId.hashCode(),
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT_ID, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, TaskScheduler.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_tile_assistant)
            .setContentTitle(task.title)
            .setContentText(result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120))
            .setStyle(Notification.BigTextStyle().bigText(result.take(2000)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(task.id.hashCode(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID = 4107
    }
}
