package org.fossistant.assistant.features

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.R
import org.fossistant.assistant.model.SupportedLanguage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date
import kotlin.math.sqrt

/**
 * Record mode (ChatGPT Record / meeting notes): records 16 kHz mono audio in a foreground service so it keeps
 * going with the screen off, then transcribes it on-device with Whisper and summarizes it with the local model.
 * Nothing leaves the phone.
 */
class RecordingService : Service() {

    data class State(val isRecording: Boolean = false, val elapsedMs: Long = 0L, val level: Float = 0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        startInForeground()
        job = scope.launch { record() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_assistant)
            .setContentTitle("Fossistant is recording")
            .setContentText("Transcribed on your phone when you stop")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val app = FossistantApp.instance ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val startMs = System.currentTimeMillis()
        val file = File(dir, "rec_$startMs.wav")
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE)
        var recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
            }.getOrNull()
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            _state.value = State()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val buffer = ShortArray(SAMPLE_RATE / 10)
        val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var dataBytes = 0L
        _state.value = State(isRecording = true)
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(WAV_HEADER_BYTES))
            try {
                recorder.startRecording()
                while (job?.isActive == true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until read) {
                        bytes.putShort(buffer[i])
                        sum += buffer[i].toDouble() * buffer[i]
                    }
                    out.write(bytes.array(), 0, read * 2)
                    dataBytes += read * 2
                    _state.value = State(
                        isRecording = true,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        level = (sqrt(sum / read) / 6000.0).toFloat().coerceIn(0f, 1f)
                    )
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                writeWavHeader(out, dataBytes)
            }
        }
        val durationMs = dataBytes / 2 * 1000 / SAMPLE_RATE
        _state.value = State()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (durationMs < 1000) {
            file.delete()
            return
        }
        val recording = Recording(
            title = "Recording " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startMs)),
            createdMs = startMs,
            durationMs = durationMs,
            audioPath = file.absolutePath,
            status = "Waiting to transcribe…"
        )
        app.features.recordings.upsert(recording)
        app.features.processRecording(recording.id)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_BYTES = 44
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_STOP = "org.fossistant.assistant.STOP_RECORDING"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        private fun writeWavHeader(out: RandomAccessFile, dataBytes: Long) {
            val safeDataBytes = dataBytes.coerceIn(0L, Int.MAX_VALUE.toLong() - 36L).toInt()
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + safeDataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(safeDataBytes)
            }
            out.seek(0)
            out.write(header.array())
        }
    }
}

/** Turns a recording into a transcript and a summary with action items. */
internal object RecordingProcessor {

    private const val CHUNK_SECONDS = 30
    private const val SUMMARY_INPUT_CHARS = 6000

    const val SUMMARY_INSTRUCTIONS =
        "You summarize a transcript of a recording (a meeting, lecture, call or voice memo). Write: a one-line " +
            "title after 'Title: ', then '## Summary' with 3-6 bullet points, '## Action items' (who does what, or " +
            "'None'), and '## Key details' with names, numbers and dates mentioned. Use the transcript's language."

    private const val PARTIAL_INSTRUCTIONS =
        "Summarize this part of a longer transcript in up to 6 bullet points, keeping names, numbers, dates and " +
            "decisions. Use the transcript's language."

    suspend fun process(recordingId: String) {
        val app = FossistantApp.instance ?: return
        val store = app.features.recordings
        var recording = store.get(recordingId) ?: return
        val file = File(recording.audioPath)
        if (!file.exists()) {
            store.upsert(recording.copy(status = "The audio file is missing."))
            return
        }
        if (recording.transcript.isBlank()) {
            val totalSamples = ((file.length() - RecordingService.WAV_HEADER_BYTES).coerceAtLeast(0L) / 2L).toInt()
            val chunkSamples = RecordingService.SAMPLE_RATE * CHUNK_SECONDS
            val chunks = if (totalSamples == 0) 0 else (totalSamples + chunkSamples - 1) / chunkSamples
            val transcript = StringBuilder()
            RandomAccessFile(file, "r").use { input ->
                for (index in 0 until chunks) {
                    store.upsert(recording.copy(status = "Transcribing ${index + 1}/$chunks…"))
                    val start = index.toLong() * chunkSamples
                    val count = minOf(chunkSamples, totalSamples - start.toInt())
                    val raw = ByteArray(count * 2)
                    input.seek(RecordingService.WAV_HEADER_BYTES + start * 2)
                    input.readFully(raw)
                    val samples = ShortArray(count)
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    val text = app.sttEngine.transcribePcm16(samples, count, SupportedLanguage.AUTO)
                    if (text.isNotBlank()) transcript.append(text.trim()).append(' ')
                }
            }
            recording = recording.copy(transcript = transcript.toString().trim().ifBlank { "(No speech recognized.)" })
            store.upsert(recording.copy(status = "Summarizing…"))
        }
        val summary = summarize(recording.transcript) { status -> store.upsert(recording.copy(status = status)) }
        val title = summary?.lineSequence()?.firstOrNull { it.startsWith("Title:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }
        store.upsert(
            recording.copy(
                title = title ?: recording.title,
                summary = summary?.lines()?.filterNot { it.startsWith("Title:", ignoreCase = true) }?.joinToString("\n")?.trim().orEmpty(),
                status = if (summary == null) "Transcribed. The summary couldn't be made: ${app.gemmaEngine.lastEngineError ?: "model unavailable"}." else ""
            )
        )
    }

    /** Long transcripts are summarized in parts first, so each request fits the model's context. */
    private suspend fun summarize(transcript: String, onStatus: (String) -> Unit): String? {
        val engine = FossistantApp.instance?.gemmaEngine ?: return null
        if (transcript.length <= SUMMARY_INPUT_CHARS) {
            return engine.completeOnce(SUMMARY_INSTRUCTIONS, transcript, maxTokens = 900)
        }
        val parts = transcript.chunked(SUMMARY_INPUT_CHARS - 500)
        val partials = parts.mapIndexedNotNull { i, part ->
            onStatus("Summarizing part ${i + 1}/${parts.size}…")
            engine.completeOnce(PARTIAL_INSTRUCTIONS, part, maxTokens = 400)
        }
        if (partials.isEmpty()) return null
        onStatus("Writing the summary…")
        return engine.completeOnce(SUMMARY_INSTRUCTIONS, partials.joinToString("\n\n").take(SUMMARY_INPUT_CHARS), maxTokens = 900)
    }
}
