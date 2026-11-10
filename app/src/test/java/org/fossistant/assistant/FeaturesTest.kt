package org.fossistant.assistant

import org.fossistant.assistant.features.DeepResearch
import org.fossistant.assistant.features.InterpreterSession
import org.fossistant.assistant.features.PdfExporter
import org.fossistant.assistant.features.Project
import org.fossistant.assistant.features.ProjectFile
import org.fossistant.assistant.features.ProjectFiles
import org.fossistant.assistant.features.ScheduledTask
import org.fossistant.assistant.features.Skill
import org.fossistant.assistant.features.TaskRepeat
import org.fossistant.assistant.features.TaskScheduler
import org.fossistant.assistant.model.SupportedLanguage
import org.fossistant.assistant.ui.AssistantViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FeaturesTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    @Test
    fun `scheduled tasks pick the next run time`() {
        val friday2130 = at(2026, 10, 23, 21, 30) // a Friday
        val daily = ScheduledTask(title = "Brief", instruction = "x", hour = 7, minute = 0, repeat = TaskRepeat.DAILY)
        assertEquals(at(2026, 10, 24, 7, 0), TaskScheduler.nextRunMs(daily, friday2130))

        val weekdays = daily.copy(repeat = TaskRepeat.WEEKDAYS)
        assertEquals("Friday night skips the weekend", at(2026, 10, 26, 7, 0), TaskScheduler.nextRunMs(weekdays, friday2130))

        val hourly = daily.copy(repeat = TaskRepeat.HOURLY, minute = 15)
        assertEquals(at(2026, 10, 23, 22, 15), TaskScheduler.nextRunMs(hourly, friday2130))

        val once = daily.copy(repeat = TaskRepeat.ONCE, hour = 22, minute = 0)
        assertEquals(at(2026, 10, 23, 22, 0), TaskScheduler.nextRunMs(once, friday2130))
        assertNull("a one-off task that ran is finished", TaskScheduler.nextRunMs(once.copy(lastRunMs = 1L), friday2130))
        assertNull(TaskScheduler.nextRunMs(daily.copy(enabled = false), friday2130))
    }

    @Test
    fun `task times and repeats parse from what the model writes`() {
        assertEquals(7 to 30, TaskScheduler.parseTime("07:30"))
        assertEquals(19 to 0, TaskScheduler.parseTime("7pm"))
        assertEquals(0 to 15, TaskScheduler.parseTime("12:15 am"))
        assertEquals(8 to 5, TaskScheduler.parseTime("8.05"))
        assertNull(TaskScheduler.parseTime("25:00"))
        assertNull(TaskScheduler.parseTime("soon"))
        assertEquals(TaskRepeat.WEEKDAYS, TaskRepeat.parse("weekdays"))
        assertEquals(TaskRepeat.DAILY, TaskRepeat.parse("every day"))
        assertEquals(TaskRepeat.ONCE, TaskRepeat.parse(null))
    }

    @Test
    fun `skills trigger on their phrases only`() {
        val proofreader = Skill.DEFAULTS.first { it.name == "Proofreader" }
        assertTrue(proofreader.matches("Proofread: their going to the store"))
        assertTrue(proofreader.matches("can you fix my grammar here"))
        assertFalse(proofreader.matches("what's the weather"))
        assertFalse(proofreader.copy(enabled = false).matches("proofread this"))
        assertFalse("whole words only", Skill(name = "x", triggers = listOf("eli5"), instructions = "y").matches("deli5ous"))
    }

    @Test
    fun `project search finds the relevant excerpt`() {
        val text = "The router has a reset button on the back.\n\n" +
            "Warranty: two years from the date of purchase, keep the receipt.\n\n" +
            "The Wi-Fi password is printed on the sticker under the device."
        val project = Project(name = "Router", files = listOf(ProjectFile(name = "manual.txt", chunks = text.split("\n\n"))))
        val hits = ProjectFiles.search(project, "how long is the warranty?", maxChars = 80)
        assertEquals("manual.txt", hits.first().first)
        assertTrue(hits.first().second.startsWith("Warranty"))
        val context = ProjectFiles.turnContext(project.copy(instructions = "Answer briefly."), "wifi password")
        assertTrue(context.contains("Answer briefly."))
        assertTrue(context.contains("sticker"))
    }

    @Test
    fun `long documents are chunked within size`() {
        val text = (1..400).joinToString(" ") { "Sentence number $it is here." }
        val chunks = ProjectFiles.chunk(text)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.length <= 900 })
        assertTrue(chunks.joinToString(" ").contains("Sentence number 400"))
    }

    @Test
    fun `PDF text is split into headings, bullets and paragraphs`() {
        val blocks = PdfExporter.parseBlocks("# Trip plan\nDay one is **busy**.\nStill day one.\n\n- Museum\n2. Lunch\n## Costs")
        assertEquals(PdfExporter.Style.HEADING, blocks[0].style)
        assertEquals("Trip plan", blocks[0].text)
        assertEquals(PdfExporter.Block("Day one is busy. Still day one.", PdfExporter.Style.BODY), blocks[1])
        assertEquals(PdfExporter.Block("•  Museum", PdfExporter.Style.BULLET), blocks[2])
        assertEquals(PdfExporter.Style.BULLET, blocks[3].style)
        assertEquals(PdfExporter.Style.SUBHEADING, blocks[4].style)
    }

    @Test
    fun `deep research parses plans, result links and sources`() {
        val queries = DeepResearch.parseQueries("1. GTA 6 release date\n- \"GTA 6 delay news\"\n\nGTA 6 platforms\nGTA 6 price\nextra", "When is GTA 6 out?")
        assertEquals(listOf("GTA 6 release date", "GTA 6 delay news", "GTA 6 platforms", "GTA 6 price"), queries)
        assertEquals(listOf("Why?"), DeepResearch.parseQueries(null, "Why?"))
        assertEquals(
            "https://example.com/a?b=1",
            DeepResearch.resolveDuckDuckGoUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&rut=x")
        )
        val report = DeepResearch.withSourceList("Answer [1].", listOf(DeepResearch.Source(1, "Example", "https://example.com")))
        assertTrue(report.endsWith("[1] Example — https://example.com"))
    }

    @Test
    fun `interpreter translates toward the other language`() {
        val english = SupportedLanguage.ENGLISH
        val spanish = SupportedLanguage.fromCode("es")
        assertEquals(spanish to english, InterpreterSession.directionFor("es", english, spanish))
        assertEquals(english to spanish, InterpreterSession.directionFor("en", english, spanish))
        assertEquals(english to spanish, InterpreterSession.directionFor(null, english, spanish))
    }

    @Test
    fun `models over 2 GB are found as numbered parts`() {
        val names = setOf("Big.part1.gguf", "Big.part2.gguf", "Small.gguf", "Gap.part2.gguf")
        assertEquals(listOf("Big.part1.gguf", "Big.part2.gguf"), org.fossistant.assistant.util.BundledModels.partNames("Big.gguf", names))
        assertTrue(org.fossistant.assistant.util.BundledModels.partNames("Small.gguf", names).isEmpty())
        assertTrue("parts must start at 1", org.fossistant.assistant.util.BundledModels.partNames("Gap.gguf", names).isEmpty())
    }

    @Test
    fun `research prefix starts deep research`() {
        assertNotNull(AssistantViewModel.DEEP_RESEARCH_PREFIX.find("Research: best e-bikes 2026"))
        assertNotNull(AssistantViewModel.DEEP_RESEARCH_PREFIX.find("deep research - fusion power"))
        assertNull(AssistantViewModel.DEEP_RESEARCH_PREFIX.find("research shows coffee is fine"))
    }
}
