package org.fossistant.assistant.util

import android.app.KeyguardManager
import android.content.Context

/**
 * Lock-screen policy. The assistant can be opened from the lock screen, so while the device is locked
 * it must not reveal personal data (notifications, contacts, calendar, memory, chat history) or act on
 * the user's behalf (calls, messages, screen control, settings).
 */
object DeviceLock {

    /** Tools that read personal data or act on the user's behalf; blocked until the phone is unlocked. */
    val SENSITIVE_TOOLS = setOf(
        "make_call", "make_duo_call", "send_sms", "send_email_or_media_message", "family_broadcast",
        "read_notifications", "reply_notification",
        "screen_agent_action", "control_screen",
        "get_contact_info", "get_calendar_events", "get_daily_briefing",
        "create_calendar_event", "reschedule_calendar_event",
        "toggle_setting", "system_setting", "transactional_action", "get_device_location",
        "home_assistant", "remember_user_fact", "get_health_summary", "get_sleep_summary", "search_photos",
        "use_connector", "schedule_task", "execute_direct_action", "create_note", "run_app_action",
        "create_pdf", "open_app", "phone_action", "in_app_search", "global_app_search", "find_app_actions",
        "media_control", "play_music", "play_audiobook", "set_volume", "set_alarm", "set_media_alarm",
        "navigate_to", "driving_mode_action", "get_commute_estimate", "check_flight_status",
        "manage_family_bell", "manage_cookbook", "control_fitness_activity", "start_meditation"
    )

    const val LOCKED_MESSAGE = "Unlock your phone first, I can't do that from the lock screen."

    fun isLocked(context: Context?): Boolean =
        runCatching { context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }
            .getOrDefault(false)
}
