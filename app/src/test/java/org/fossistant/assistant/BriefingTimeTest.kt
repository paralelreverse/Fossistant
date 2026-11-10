package org.fossistant.assistant

import org.fossistant.assistant.tools.briefingTimeNote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BriefingTimeTest {
    @Test
    fun morningWindowGetsAMorningBriefing() {
        val note = briefingTimeNote(LocalDateTime.of(2026, 10, 26, 7, 45))
        assertTrue(note.contains("morning briefing: open with a natural good morning"))
    }

    @Test
    fun afternoonIsNotAMorningBriefing() {
        val note = briefingTimeNote(LocalDateTime.of(2026, 10, 25, 17, 49))
        assertTrue(note.contains("Sunday 17:49 (evening)"))
        assertTrue(note.contains("don't say good morning"))
        assertFalse(note.contains("open with a natural good morning"))
    }

    @Test
    fun tenAmIsMorningButPastBriefingTime() {
        val note = briefingTimeNote(LocalDateTime.of(2026, 10, 26, 10, 0))
        assertTrue(note.contains("(morning)"))
        assertTrue(note.contains("not briefing time"))
    }
}
