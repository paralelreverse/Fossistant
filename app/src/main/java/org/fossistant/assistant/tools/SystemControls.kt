package org.fossistant.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.service.FossistantAgentAccessibilityService

/**
 * Phone controls an ordinary app may use, beyond what the older toggle_setting code covers.
 *
 * What Android lets a non-system app do directly:
 * - **Brightness, auto-rotate, screen timeout**: with "Modify system settings" (WRITE_SETTINGS), which the user
 *   grants once on a settings page this opens the first time.
 * - **Ringer mode and every volume stream** (media, ring, notifications, alarm, call): AudioManager; silent mode
 *   needs Do Not Disturb access.
 * - **Lock screen, screenshot, notification shade, quick settings, recent apps, home, back, power menu, split
 *   screen**: the global actions of Fossistant's accessibility service, when the user has turned it on.
 * - **Camera, video, alarms list, dismiss / snooze an alarm, battery level**: plain intents / BatteryManager.
 *
 * What it can't (Android reserves these for system apps since Android 10): switching Wi-Fi, Bluetooth, mobile
 * data, airplane mode, location, hotspot, NFC or battery saver itself. For those the matching quick panel or
 * settings page opens so the user can flip it with one tap.
 */
class SystemControls(private val context: Context?) {

    // ---- Settings ----

    fun setting(name: String, value: String): ToolExecutionResult {
        val key = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        val v = value.trim().lowercase()
        val ctx = context ?: return ToolExecutionResult("system_setting", true, "Set $key to $v")
        return try {
            when (key) {
                "brightness" -> brightness(ctx, v)
                "auto_brightness", "adaptive_brightness" -> writeSystem(ctx, "auto_brightness") {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (isOn(v)) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    "Adaptive brightness ${onOff(v)}."
                }
                "auto_rotate", "rotation", "screen_rotation" -> writeSystem(ctx, "auto_rotate") {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (isOn(v)) 1 else 0)
                    "Auto-rotate ${onOff(v)}."
                }
                "screen_timeout", "screen_off_timeout" -> writeSystem(ctx, "screen_timeout") {
                    val seconds = parseSeconds(v) ?: return@writeSystem "Say how long, e.g. 30 seconds or 2 minutes."
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
                    "Screen timeout set to ${describeSeconds(seconds)}."
                }
                "ringer", "ringer_mode", "sound_mode", "silent", "vibrate" -> ringer(ctx, if (key == "silent" || key == "vibrate") (if (isOn(v)) key else "normal") else v)
                "mute", "mute_media" -> streamMute(ctx, AudioManager.STREAM_MUSIC, isOn(v) || v.isEmpty(), "Media")
                "media_volume", "music_volume" -> volume(ctx, AudioManager.STREAM_MUSIC, v, "Media")
                "ring_volume", "ringtone_volume" -> volume(ctx, AudioManager.STREAM_RING, v, "Ring")
                "notification_volume" -> volume(ctx, AudioManager.STREAM_NOTIFICATION, v, "Notification")
                "alarm_volume" -> volume(ctx, AudioManager.STREAM_ALARM, v, "Alarm")
                "call_volume", "voice_call_volume" -> volume(ctx, AudioManager.STREAM_VOICE_CALL, v, "Call")
                "mobile_data", "data", "internet" -> panel(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "mobile data and Wi-Fi")
                "airplane", "airplane_mode", "flight_mode" -> page(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane mode")
                "location", "gps" -> page(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location")
                "hotspot", "tethering" -> page(ctx, Settings.ACTION_WIRELESS_SETTINGS, "hotspot")
                "nfc" -> panel(ctx, Settings.Panel.ACTION_NFC, "NFC")
                "battery_saver", "power_saver" -> page(ctx, Settings.ACTION_BATTERY_SAVER_SETTINGS, "battery saver")
                else -> ToolExecutionResult("system_setting", false, "I can't change $name yet.")
            }
        } catch (e: Exception) {
            ToolExecutionResult("system_setting", false, "Couldn't change $name: ${e.message}")
        }
    }

    private fun brightness(ctx: Context, v: String): ToolExecutionResult = writeSystem(ctx, "brightness") {
        if (v == "auto" || v == "adaptive") {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return@writeSystem "Adaptive brightness on."
        }
        val current = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val target = relativePercent(v, current) ?: return@writeSystem "Say a brightness from 0 to 100, or +20 / -20."
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (target * 255 / 100).coerceIn(1, 255))
        "Brightness set to $target%."
    }

    private fun ringer(ctx: Context, mode: String): ToolExecutionResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (ringerMode, label) = when {
            mode.startsWith("sil") || mode == "mute" || mode == "off" -> AudioManager.RINGER_MODE_SILENT to "silent"
            mode.startsWith("vib") -> AudioManager.RINGER_MODE_VIBRATE to "vibrate"
            else -> AudioManager.RINGER_MODE_NORMAL to "sound on"
        }
        // Leaving or entering silent changes Do Not Disturb, which needs DND access.
        if (!nm.isNotificationPolicyAccessGranted && (ringerMode == AudioManager.RINGER_MODE_SILENT || am.ringerMode == AudioManager.RINGER_MODE_SILENT)) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ToolExecutionResult("system_setting", false, "Fossistant needs Do Not Disturb access for that. I opened the page: allow Fossistant, then ask again.")
        }
        am.ringerMode = ringerMode
        return ToolExecutionResult("system_setting", true, "Phone set to $label.", displayDetail = "Ringer: $label")
    }

    private fun volume(ctx: Context, stream: Int, v: String, label: String): ToolExecutionResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = am.getStreamVolume(stream) * 100 / max
        val target = relativePercent(v, current) ?: return ToolExecutionResult("system_setting", false, "Say a volume from 0 to 100, or +20 / -20.")
        am.setStreamVolume(stream, (target * max / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        return ToolExecutionResult("system_setting", true, "$label volume set to $target%.", displayDetail = "$label volume $target%")
    }

    private fun streamMute(ctx: Context, stream: Int, mute: Boolean, label: String): ToolExecutionResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return ToolExecutionResult("system_setting", true, "$label ${if (mute) "muted" else "unmuted"}.")
    }

    /** Runs [write] when Fossistant may modify system settings; otherwise opens the page where the user allows it. */
    private inline fun writeSystem(ctx: Context, what: String, write: () -> String): ToolExecutionResult {
        if (!Settings.System.canWrite(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return ToolExecutionResult(
                "system_setting", false,
                "Fossistant needs \"Modify system settings\" to change $what. I opened the page: allow it, then ask again."
            )
        }
        return ToolExecutionResult("system_setting", true, write(), displayDetail = what.replace('_', ' '))
    }

    private fun panel(ctx: Context, action: String, what: String): ToolExecutionResult {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolExecutionResult("system_setting", true, "Android doesn't let apps switch $what, so I opened its quick panel.", displayDetail = what)
    }

    private fun page(ctx: Context, action: String, what: String): ToolExecutionResult {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolExecutionResult("system_setting", true, "Android doesn't let apps switch $what, so I opened its settings.", displayDetail = what)
    }

    // ---- Phone actions ----

    fun phoneAction(name: String): ToolExecutionResult {
        val key = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        val ctx = context ?: return ToolExecutionResult("phone_action", true, "Done: $key")
        return try {
            when (key) {
                "lock", "lock_screen", "lock_phone" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked the phone.", Build.VERSION_CODES.P)
                "screenshot", "take_screenshot" -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Took a screenshot.", Build.VERSION_CODES.P)
                "notifications", "notification_shade", "show_notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opened the notifications.")
                "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Opened quick settings.")
                "recents", "recent_apps", "app_switcher" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Opened recent apps.")
                "home", "go_home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Went to the home screen.")
                "back", "go_back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Went back.")
                "power_menu", "power_dialog" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Opened the power menu.")
                "split_screen" -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, "Toggled split screen.")
                "camera", "take_photo", "photo" -> start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opened the camera.")
                "video", "record_video" -> start(ctx, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), "Opened the video camera.")
                "show_alarms", "alarms" -> start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened your alarms.")
                "dismiss_alarm", "stop_alarm" -> start(ctx, Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT), "Dismissed the alarm.")
                "snooze_alarm", "snooze" -> start(ctx, Intent(AlarmClock.ACTION_SNOOZE_ALARM), "Snoozed the alarm.")
                "show_timers", "timers" -> start(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Opened your timers.")
                "battery", "battery_level", "battery_status" -> battery(ctx)
                else -> ToolExecutionResult("phone_action", false, "I can't do $name yet.")
            }
        } catch (e: Exception) {
            ToolExecutionResult("phone_action", false, "Couldn't do $name: ${e.message}")
        }
    }

    private fun global(action: Int, done: String, minSdk: Int = 1): ToolExecutionResult {
        if (Build.VERSION.SDK_INT < minSdk) return ToolExecutionResult("phone_action", false, "This phone's Android version can't do that.")
        val service = FossistantAgentAccessibilityService.activeInstance
        if (service == null) {
            context?.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ToolExecutionResult(
                "phone_action", false,
                "That needs Fossistant's accessibility service. I opened Accessibility settings: turn on Fossistant, then ask again."
            )
        }
        return if (service.globalAction(action)) ToolExecutionResult("phone_action", true, done)
        else ToolExecutionResult("phone_action", false, "Android refused that action.")
    }

    private fun start(ctx: Context, intent: Intent, done: String): ToolExecutionResult {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolExecutionResult("phone_action", true, done)
    }

    private fun battery(ctx: Context): ToolExecutionResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val left = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && charging) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { ", full in about ${describeSeconds((it / 1000).toInt())}" }.orEmpty()
        } else ""
        return ToolExecutionResult("phone_action", true, "Battery is at $level%${if (charging) ", charging$left" else ""}.")
    }

    companion object {
        private val ON_WORDS = setOf("on", "true", "enable", "enabled", "yes", "1", "start")

        fun isOn(v: String) = v.trim().lowercase() in ON_WORDS
        private fun onOff(v: String) = if (isOn(v)) "on" else "off"

        /** `70` -> 70, `+20` / `-20` relative to [current], `max` / `min` / `half`; null when unreadable. */
        fun relativePercent(v: String, current: Int): Int? {
            val s = v.trim().lowercase().removeSuffix("%").trim()
            return when {
                s == "max" || s == "full" || s == "maximum" -> 100
                s == "min" || s == "minimum" || s == "lowest" -> 0
                s == "half" -> 50
                s.startsWith("+") -> s.drop(1).trim().toIntOrNull()?.let { current + it }
                s.startsWith("-") -> s.drop(1).trim().toIntOrNull()?.let { current - it }
                else -> s.toIntOrNull()
            }?.coerceIn(0, 100)
        }

        /** `30`, `30s`, `30 seconds`, `2 min`, `2 minutes`, `1h` -> seconds. */
        fun parseSeconds(v: String): Int? {
            val m = Regex("^(\\d+)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hour|hours)?$").find(v.trim().lowercase()) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return when (m.groupValues[2].firstOrNull()) {
                'm' -> n * 60
                'h' -> n * 3600
                else -> n
            }.takeIf { it in 5..(24 * 3600) }
        }

        fun describeSeconds(s: Int): String = when {
            s >= 3600 && s % 3600 == 0 -> "${s / 3600} hour${if (s >= 7200) "s" else ""}"
            s >= 60 && s % 60 == 0 -> "${s / 60} minute${if (s >= 120) "s" else ""}"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s seconds"
        }
    }
}
