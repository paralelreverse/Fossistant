package org.fossistant.assistant

import kotlinx.coroutines.test.runTest
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.service.FossistantNotificationAndMediaService
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.SystemControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemControlsTest {

    @Test
    fun readsPercentagesAbsoluteAndRelative() {
        assertEquals(70, SystemControls.relativePercent("70", 40))
        assertEquals(70, SystemControls.relativePercent("70%", 40))
        assertEquals(60, SystemControls.relativePercent("+20", 40))
        assertEquals(20, SystemControls.relativePercent("-20", 40))
        assertEquals(100, SystemControls.relativePercent("+90", 40))
        assertEquals(0, SystemControls.relativePercent("-90", 40))
        assertEquals(100, SystemControls.relativePercent("max", 40))
        assertEquals(50, SystemControls.relativePercent("half", 40))
        assertNull(SystemControls.relativePercent("loud", 40))
    }

    @Test
    fun readsScreenTimeouts() {
        assertEquals(30, SystemControls.parseSeconds("30"))
        assertEquals(30, SystemControls.parseSeconds("30 seconds"))
        assertEquals(120, SystemControls.parseSeconds("2 minutes"))
        assertEquals(120, SystemControls.parseSeconds("2 min"))
        assertEquals(3600, SystemControls.parseSeconds("1 hour"))
        assertNull(SystemControls.parseSeconds("1"))       // under 5 s would make the phone unusable
        assertNull(SystemControls.parseSeconds("forever"))
        assertEquals("2 minutes", SystemControls.describeSeconds(120))
        assertEquals("30 seconds", SystemControls.describeSeconds(30))
    }

    @Test
    fun readsMediaPositionsAndJumps() {
        assertEquals(30_000L, FossistantNotificationAndMediaService.parseDurationMs("30"))
        assertEquals(30_000L, FossistantNotificationAndMediaService.parseDurationMs("30s"))
        assertEquals(120_000L, FossistantNotificationAndMediaService.parseDurationMs("2 min"))
        assertEquals(90_000L, FossistantNotificationAndMediaService.parseDurationMs("1:30"))
        assertNull(FossistantNotificationAndMediaService.parseDurationMs(""))
        assertNull(FossistantNotificationAndMediaService.parseDurationMs("a bit"))
    }

    @Test
    fun newToolsAreRoutedAndUnknownOnesSayCantDo() = runTest {
        val executor = DeviceAndWebToolExecutor(context = null)
        for (setting in listOf("brightness", "auto_rotate", "screen_timeout", "ringer", "alarm_volume", "airplane_mode", "location")) {
            val result = executor.execute(ToolCallRequest("system_setting", mapOf("setting" to setting, "value" to "50")))
            assertTrue(setting, result.success)
        }
        for (action in listOf("lock_screen", "screenshot", "notifications", "camera", "dismiss_alarm", "battery")) {
            val result = executor.execute(ToolCallRequest("phone_action", mapOf("action" to action)))
            assertTrue(action, result.success)
        }
        assertFalse(executor.execute(ToolCallRequest("media_control", mapOf("action" to "pause"))).summary.startsWith("Unknown tool"))
    }
}
