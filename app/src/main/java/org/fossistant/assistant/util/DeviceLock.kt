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
        "get_contact_info", "get_calendar_events", "get_daily_briefing",
        "create_calendar_event", "reschedule_calendar_event",
        "toggle_setting", "system_setting", "transactional_action", "get_device_location",
        "home_assistant", "remember_user_fact", "get_health_summary", "get_sleep_summary", "search_photos",
        "use_connector", "schedule_task", "execute_direct_action", "create_note", "run_app_action"
    )

    const val LOCKED_MESSAGE = "Unlock your phone first, I can't do that from the lock screen."

    fun isLocked(context: Context?): Boolean =
        runCatching { context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }
            .getOrDefault(false)
}
