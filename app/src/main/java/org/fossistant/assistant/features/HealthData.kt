package org.fossistant.assistant.features

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fossistant.assistant.model.ToolExecutionResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Steps, sleep and heart rate from Health Connect, using the API built into Android 14+ (no extra library).
 * Data from watches and fitness apps that write to Health Connect shows up here.
 */
object HealthData {

    const val READ_STEPS = "android.permission.health.READ_STEPS"
    const val READ_SLEEP = "android.permission.health.READ_SLEEP"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    val PERMISSIONS = arrayOf(READ_STEPS, READ_SLEEP, READ_HEART_RATE)

    private val executor = Executors.newSingleThreadExecutor()

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun hasPermissions(context: Context): Boolean = isSupported &&
        PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Today's steps, last night's sleep and today's heart rate, as a short text for the model and the UI. */
    suspend fun summary(context: Context?, metric: String = "all"): ToolExecutionResult {
        val toolName = "get_health_summary"
        if (context == null) return ToolExecutionResult(toolName, false, "No Android context.")
        if (!isSupported) {
            return ToolExecutionResult(toolName, false, "Health data needs Android 14 or newer (Health Connect).")
        }
        if (!hasPermissions(context)) {
            return ToolExecutionResult(
                toolName, false,
                "Health Connect access isn't granted yet. Open Fossistant > Tools > Health and allow steps, sleep and heart rate."
            )
        }
        return try {
            val parts = mutableListOf<String>()
            val wanted = metric.lowercase()
            val all = wanted.isBlank() || wanted == "all" || wanted == "health"
            if (all || "step" in wanted) parts.add(stepsToday(context))
            if (all || "sleep" in wanted) parts.add(sleepLastNight(context))
            if (all || "heart" in wanted) parts.add(heartRateToday(context))
            ToolExecutionResult(toolName, true, parts.joinToString(" "))
        } catch (t: Throwable) {
            ToolExecutionResult(toolName, false, "Couldn't read Health Connect: ${t.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun stepsToday(context: Context): String {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val request = AggregateRecordsRequest.Builder<Long>(range(start, Instant.now()))
            .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
            .build()
        val response = suspendCancellableCoroutine<AggregateRecordsResponse<Long>> { cont ->
            manager.aggregate(request, executor, receiver(cont))
        }
        val steps = response.get(StepsRecord.STEPS_COUNT_TOTAL)
        return if (steps == null) "No step data for today." else "Steps today: $steps."
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun sleepLastNight(context: Context): String {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val end = Instant.now()
        val request = ReadRecordsRequestUsingFilters.Builder(SleepSessionRecord::class.java)
            .setTimeRangeFilter(range(end.minus(Duration.ofHours(24)), end))
            .build()
        val response = suspendCancellableCoroutine<ReadRecordsResponse<SleepSessionRecord>> { cont ->
            manager.readRecords(request, executor, receiver(cont))
        }
        val sessions = response.records
        if (sessions.isEmpty()) return "No sleep recorded in the last 24 hours."
        val total = sessions.fold(Duration.ZERO) { acc, s -> acc.plus(Duration.between(s.startTime, s.endTime)) }
        val last = sessions.maxBy { it.endTime }
        val zone = ZoneId.systemDefault()
        return "Sleep: ${total.toHours()} h ${total.toMinutesPart()} min, " +
            "from ${last.startTime.atZone(zone).toLocalTime().withNano(0).withSecond(0)} to ${last.endTime.atZone(zone).toLocalTime().withNano(0).withSecond(0)}."
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun heartRateToday(context: Context): String {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val request = AggregateRecordsRequest.Builder<Long>(range(start, Instant.now()))
            .addAggregationType(HeartRateRecord.BPM_AVG)
            .addAggregationType(HeartRateRecord.BPM_MIN)
            .addAggregationType(HeartRateRecord.BPM_MAX)
            .build()
        val response = suspendCancellableCoroutine<AggregateRecordsResponse<Long>> { cont ->
            manager.aggregate(request, executor, receiver(cont))
        }
        val avg = response.get(HeartRateRecord.BPM_AVG) ?: return "No heart rate data for today."
        return "Heart rate today: average $avg bpm (${response.get(HeartRateRecord.BPM_MIN)}–${response.get(HeartRateRecord.BPM_MAX)})."
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun range(start: Instant, end: Instant) =
        TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()

    private fun <T> receiver(cont: kotlinx.coroutines.CancellableContinuation<T>) =
        object : OutcomeReceiver<T, HealthConnectException> {
            override fun onResult(result: T) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onError(error: HealthConnectException) {
                if (cont.isActive) cont.resumeWithException(error)
            }
        }
}
