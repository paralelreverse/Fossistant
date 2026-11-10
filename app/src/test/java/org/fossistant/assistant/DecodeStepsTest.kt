package org.fossistant.assistant

import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeStepsTest {
    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun piecesArrivingTogetherFormOneStep() {
        val timeline = listOf(
            ms(0) to "Hi",
            ms(60) to " there",
            ms(61) to "!",          // accepted draft: same moment as " there"
            ms(62) to " I'm",       // accepted draft
            ms(130) to " Fossistant"
        )
        val steps = GemmaLiteRtEngine.groupDecodeSteps(timeline, 6)
        assertEquals(listOf("Hi", " there! I'm", " Fossistant"), steps.map { it.text })
        assertEquals(listOf(1, 3, 1), steps.map { it.pieces })
    }

    @Test
    fun evenlySpacedPiecesAreOneStepEach() {
        val timeline = (0 until 5).map { ms(it * 70L) to "w$it" }
        assertEquals(5, GemmaLiteRtEngine.groupDecodeSteps(timeline, 6).size)
    }
}
