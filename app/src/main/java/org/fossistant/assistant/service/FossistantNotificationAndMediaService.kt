package org.fossistant.assistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides two critical capabilities that the original Google Assistant used via system bindings:
 *
 * 1. **Direct Messaging Notification Reading & Inline Reply (`RemoteInput`)**:
 *    - Reads incoming notifications from WhatsApp, Telegram, Signal, Messages, etc.
 *    - Extracts `Notification.Action` entries containing `RemoteInput` so the user can say
 *      *"Reply to Alex saying I'll be there in 5 minutes"* without ever opening the app.
 *
 * 2. **Global Active `MediaSessionManager` Control**:
 *    - Queries `MediaSessionManager.getActiveSessions(ComponentName)` to obtain rich
 *      `MediaController.TransportControls` (exact track metadata, seek, playFromSearch, skip, pause).
 */
class FossistantNotificationAndMediaService : NotificationListenerService() {

    data class ReplyableNotification(
        val key: String,
        val packageName: String,
        val senderTitle: String,
        val messageText: String,
        val timestampMs: Long,
        val action: Notification.Action,
        val remoteInputs: List<RemoteInput>
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        VoiceWakeWordService.startIfEnabled(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        replyableNotifications.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val actions = notification.actions ?: return
        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                replyableNotifications.entries.removeIf { now - it.value.timestampMs > MAX_NOTIFICATION_AGE_MS }
                replyableNotifications[sbn.key] = ReplyableNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    senderTitle = title,
                    messageText = text,
                    timestampMs = sbn.postTime,
                    action = action,
                    remoteInputs = remoteInputs.toList()
                )
                while (replyableNotifications.size > MAX_CACHED_NOTIFICATIONS) {
                    val oldestKey = replyableNotifications.values.minByOrNull { it.timestampMs }?.key ?: break
                    replyableNotifications.remove(oldestKey)
                }
                break
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { replyableNotifications.remove(it) }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    /**
     * Replies directly to the most recent notification matching [senderNameQuery] (or the latest
     * messaging notification if [senderNameQuery] is blank) using Android's `RemoteInput` API.
     */
    fun replyToNotification(senderNameQuery: String, replyBody: String): String {
        val candidates = replyableNotifications.values.sortedByDescending { it.timestampMs }
        if (candidates.isEmpty()) {
            return "No recent replyable messaging notifications found."
        }
        val target = if (senderNameQuery.isBlank()) {
            candidates.first()
        } else {
            candidates.firstOrNull {
                it.senderTitle.contains(senderNameQuery, ignoreCase = true)
            } ?: return "No recent notification from '$senderNameQuery' with direct reply support."
        }

        return try {
            val fillInIntent = Intent()
            val resultsBundle = Bundle()
            for (remoteInput in target.remoteInputs) {
                resultsBundle.putCharSequence(remoteInput.resultKey, replyBody)
            }
            RemoteInput.addResultsToIntent(target.remoteInputs.toTypedArray(), fillInIntent, resultsBundle)
            target.action.actionIntent.send(this, 0, fillInIntent)
            "Replied to ${target.senderTitle}: \"$replyBody\""
        } catch (e: Exception) {
            "Failed to send notification reply: ${e.message}"
        }
    }

    /**
     * Summarizes the latest unread messaging notifications for *"Read my messages"* commands.
     */
    fun getRecentMessagesSummary(limit: Int = 5): String {
        val recent = replyableNotifications.values
            .sortedByDescending { it.timestampMs }
            .take(limit)
        if (recent.isEmpty()) return "You have no recent unread messaging notifications."
        return recent.joinToString("\n") { "• ${it.senderTitle}: ${it.messageText}" }
    }

    /**
     * Controls the active `MediaController` via `MediaSessionManager.getActiveSessions`.
     */
    fun controlActiveMediaSession(action: String, seekPositionMs: Long = 0L, value: String = ""): String? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(this, FossistantNotificationAndMediaService::class.java)
        val controllers: List<MediaController> = try {
            msm.getActiveSessions(component)
        } catch (_: SecurityException) {
            return null
        }
        // The app that is actually playing, not just the top session (a paused app can rank above it).
        val activeController = controllers.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return null
        val metadata = activeController.metadata
        val trackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val track = trackTitle?.let { if (artist.isNotEmpty()) "$it by $artist" else it } ?: "the music"

        val controls = activeController.transportControls
        val state = activeController.playbackState
        // Where playback is now (the saved position moves on while playing).
        fun position(): Long {
            val s = state ?: return 0L
            val elapsed = if (s.state == android.media.session.PlaybackState.STATE_PLAYING) {
                ((android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            } else 0L
            return (s.position + elapsed).coerceAtLeast(0L)
        }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        fun seekBy(deltaMs: Long): String {
            val target = (position() + deltaMs).let { if (duration != null) it.coerceAtMost(duration) else it }.coerceAtLeast(0L)
            controls.seekTo(target)
            return "${if (deltaMs >= 0) "Skipped ahead" else "Went back"} ${kotlin.math.abs(deltaMs) / 1000} seconds."
        }
        val amountMs = parseDurationMs(value)

        return when (action.trim().lowercase().replace(' ', '_')) {
            "pause" -> { controls.pause(); "Paused $track." }
            "stop" -> { controls.stop(); "Stopped $track." }
            "play", "resume" -> { controls.play(); "Playing $track." }
            "next", "skip" -> { controls.skipToNext(); "Skipped to the next track." }
            "previous", "prev", "back" -> { controls.skipToPrevious(); "Went back to the previous track." }
            "restart", "replay", "start_over" -> { controls.seekTo(0L); controls.play(); "Started $track from the beginning." }
            "forward", "fast_forward", "skip_forward" -> seekBy(amountMs ?: 30_000L)
            "rewind", "skip_back", "skip_backward" -> seekBy(-(amountMs ?: 15_000L))
            "seek" -> {
                val target = amountMs ?: seekPositionMs
                controls.seekTo(target)
                "Moved to ${target / 60_000}:${"%02d".format((target / 1000) % 60)} in $track."
            }
            "shuffle_on", "shuffle" -> { compatMode(controls, "SHUFFLE", 1); "Shuffle on." }
            "shuffle_off" -> { compatMode(controls, "SHUFFLE", 0); "Shuffle off." }
            "repeat_one" -> { compatMode(controls, "REPEAT", 1); "Repeating this track." }
            "repeat", "repeat_all", "repeat_on" -> { compatMode(controls, "REPEAT", 2); "Repeat on." }
            "repeat_off" -> { compatMode(controls, "REPEAT", 0); "Repeat off." }
            "speed" -> {
                val speed = value.trim().removeSuffix("x").toFloatOrNull()?.coerceIn(0.25f, 4f) ?: return "Say a speed like 1.5."
                controls.setPlaybackSpeed(speed)
                "Playback speed set to ${speed}x."
            }
            "like", "thumbs_up" -> { controls.setRating(android.media.Rating.newThumbRating(true)); "Liked $track." }
            "dislike", "thumbs_down" -> { controls.setRating(android.media.Rating.newThumbRating(false)); "Disliked $track." }
            "whats_playing", "now_playing" -> if (trackTitle != null) {
                val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(activeController.packageName, 0)) }.getOrNull()
                "Now playing: $track${app?.let { " in $it" }.orEmpty()}."
            } else "Nothing is playing right now."
            else -> null
        }
    }

    /**
     * Shuffle / repeat: the framework controller has no call for them, but apps built on MediaSessionCompat or
     * Media3 (nearly all players) accept the support library's custom actions. [kind] is SHUFFLE (0 off, 1 all)
     * or REPEAT (0 off, 1 one, 2 all).
     */
    private fun compatMode(controls: MediaController.TransportControls, kind: String, mode: Int) {
        val prefix = "android.support.v4.media.session.action"
        controls.sendCustomAction("$prefix.SET_${kind}_MODE", Bundle().apply { putInt("$prefix.ARGUMENT_${kind}_MODE", mode) })
    }

    companion object {
        /** `30`, `30s`, `2 min`, `1:30` -> milliseconds; null when empty or unreadable. */
        fun parseDurationMs(value: String): Long? {
            val v = value.trim().lowercase()
            if (v.isEmpty()) return null
            Regex("^(\\d+):(\\d{1,2})$").find(v)?.let { return (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000 }
            val m = Regex("^(\\d+)\\s*(s|sec|secs|seconds?|m|min|mins|minutes?)?$").find(v) ?: return null
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2].startsWith("m")) n * 60_000 else n * 1000
        }

        private const val MAX_CACHED_NOTIFICATIONS = 25
        private const val MAX_NOTIFICATION_AGE_MS = 24 * 60 * 60 * 1000L

        @Volatile
        var activeInstance: FossistantNotificationAndMediaService? = null
            private set

        private val replyableNotifications = ConcurrentHashMap<String, ReplyableNotification>()
    }
}
