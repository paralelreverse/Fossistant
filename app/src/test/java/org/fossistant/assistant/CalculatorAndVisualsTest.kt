package org.fossistant.assistant

import org.fossistant.assistant.engine.AiMemoryManager
import org.fossistant.assistant.engine.FossistantGemmaToolSet
import org.fossistant.assistant.model.ToolExecutionResult
import org.fossistant.assistant.model.ToolVisual
import org.fossistant.assistant.model.VisualArgs
import org.fossistant.assistant.model.VisualKind
import org.fossistant.assistant.model.VisualPhoto
import org.fossistant.assistant.tools.Calculator
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.fossistant.assistant.tools.PhotoSearch
import org.fossistant.assistant.ui.components.formatValue
import org.fossistant.assistant.ui.components.hasToolVisuals
import org.fossistant.assistant.ui.components.niceTicks
import org.fossistant.assistant.ui.components.visualsToShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class CalculatorAndVisualsTest {

    private fun calc(expression: String) = Calculator.format(Calculator.evaluate(expression))

    @Test
    fun `calculator does exact arithmetic with the usual precedence`() {
        assertEquals("4", calc("2 + 2"))
        assertEquals("0.3", calc("0.1 + 0.2"))
        assertEquals("1", calc("5 - 3 - 1"))
        assertEquals("2", calc("8 / 2 / 2"))
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("2142.85714285714", calc("(1250 * 12) / 7"))
        assertEquals("1024", calc("2^10"))
        assertEquals("1024", calc("2**10"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("123456789012345678900", calc("12345678901234567890 * 10"))
        assertEquals("1e-9", calc("1e-9 * 1"))
    }

    @Test
    fun `calculator understands percentages, modulo, functions and written forms`() {
        assertEquals("12", calc("15% of 80"))
        assertEquals("110", calc("100 + 10%"))
        assertEquals("150", calc("200 - 25%"))
        assertEquals("0.5", calc("50%"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("1", calc("10 mod 3"))
        assertEquals("1", calc("mod(10, 3)"))
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("4", calc("√16"))
        assertEquals("-3", calc("cbrt(-27)"))
        assertEquals("120", calc("5!"))
        assertEquals("0.5", calc("sin(30deg)"))
        assertEquals("0", calc("sin(pi)"))
        assertEquals("6.28318530717959", calc("2pi"))
        assertEquals("2469", calc("1,234.5 * 2"))
        assertEquals("12", calc("3 x 4"))
        assertEquals("35", calc("5 times 7"))
        assertEquals("7", calc("max(3, 7, 2)"))
        assertEquals("3", calc("avg(2, 4)"))
        assertEquals("9", calc("3 squared"))
    }

    @Test
    fun `calculator rejects what it can't evaluate`() {
        listOf("1/0", "abc", "(2 + 3", "", "sqrt(-4)").forEach { expression ->
            try {
                Calculator.evaluate(expression)
                fail("'$expression' should not evaluate")
            } catch (e: Calculator.CalculationException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `calculate tool reports the expression with its result`() {
        val executor = DeviceAndWebToolExecutor(context = null)
        val ok = executor.calculate("15% of 80")
        assertTrue(ok.success)
        assertEquals("15% of 80 = 12", ok.summary)
        assertEquals("15% of 80 = 12", ok.displayDetail)
        val bad = executor.calculate("2 +")
        assertFalse(bad.success)
        assertTrue(bad.summary.startsWith("Can't calculate"))
    }

    @Test
    fun `chart arguments in the shapes small models write them`() {
        val bars = VisualArgs.chart("bar", "Rain", "Mon; Tue; Wed", "3; 5.5; 0", "mm").getOrThrow()
        assertEquals(VisualKind.BAR_CHART, bars.kind)
        assertEquals(listOf("Mon", "Tue", "Wed"), bars.labels)
        assertEquals(listOf(3.0, 5.5, 0.0), bars.values)
        assertEquals("mm", bars.unit)

        val line = VisualArgs.chart("line", "Sales", "[\"Jan\", \"Feb\"]", "[1200, 1350]", "USD").getOrThrow()
        assertEquals(VisualKind.LINE_CHART, line.kind)
        assertEquals(listOf(1200.0, 1350.0), line.values)

        // Thousands separators inside a comma-separated list.
        assertEquals(listOf(1250.0, 3400.0), VisualArgs.chart("bar", "x", "A, B", "1,250, 3,400", "").getOrThrow().values)
        // Missing labels are numbered.
        assertEquals(listOf("#1", "#2"), VisualArgs.chart("bar", "x", "", "1; 2", "").getOrThrow().labels)
    }

    @Test
    fun `pie charts keep the biggest slices and fold the rest into Other`() {
        val pie = VisualArgs.chart("pie", "Share", "A;B;C;D;E;F;G;H;I;J", "10;9;8;7;6;5;4;3;2;1", "").getOrThrow()
        assertEquals(VisualKind.PIE_CHART, pie.kind)
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "Other"), pie.labels)
        assertEquals(listOf(10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 6.0), pie.values)

        assertTrue(VisualArgs.chart("pie", "x", "A;B", "5;-1", "").isFailure)
        assertTrue(VisualArgs.chart("line", "x", "A", "5", "").isFailure)
        assertTrue(VisualArgs.chart("bar", "x", "A;B", "five; 6", "").isFailure)
    }

    @Test
    fun `numbers are read with currencies, units, decimal commas and suffixes`() {
        assertEquals(1200.0, VisualArgs.parseNumber("$1,200")!!, 1e-9)
        assertEquals(12.5, VisualArgs.parseNumber("12,5")!!, 1e-9)
        assertEquals(45.0, VisualArgs.parseNumber("45%")!!, 1e-9)
        assertEquals(20.0, VisualArgs.parseNumber("20 °C")!!, 1e-9)
        assertEquals(1200.0, VisualArgs.parseNumber("1.2k")!!, 1e-6)
        assertEquals(3.4e6, VisualArgs.parseNumber("3.4M")!!, 1e-3)
        assertEquals(5e9, VisualArgs.parseNumber("5 billion")!!, 1e-3)
        assertEquals(2.5e9, VisualArgs.parseNumber("€2.5bn")!!, 1e-3)
        assertEquals(10.0, VisualArgs.parseNumber("10 km")!!, 1e-9)
        assertEquals(-3.0, VisualArgs.parseNumber("-3")!!, 1e-9)
        assertNull(VisualArgs.parseNumber("n/a"))
    }

    @Test
    fun `diagram items become boxes with a heading and text`() {
        val breakdown = VisualArgs.diagram("breakdown", "Budget", "Rent: 40% of income; Food – 25%; Savings").getOrThrow()
        assertEquals(VisualKind.BREAKDOWN, breakdown.kind)
        assertEquals(listOf("Rent", "Food", "Savings"), breakdown.boxes.map { it.heading })
        assertEquals(listOf("40% of income", "25%", ""), breakdown.boxes.map { it.text })

        val flow = VisualArgs.diagram("steps", "Make tea", "Boil water\nAdd a tea bag\nWait 3 minutes").getOrThrow()
        assertEquals(VisualKind.FLOW, flow.kind)
        assertEquals(3, flow.boxes.size)

        assertTrue(VisualArgs.diagram("breakdown", "x", " ").isFailure)
    }

    @Test
    fun `chart and diagram tools hand the visual to the chat and a short note to the model`() {
        val finished = mutableListOf<ToolExecutionResult>()
        val tempDir = Files.createTempDirectory("fossistant_visuals_test").toFile().apply { deleteOnExit() }
        val toolSet = FossistantGemmaToolSet(
            DeviceAndWebToolExecutor(context = null), AiMemoryManager(storageDir = tempDir),
            onToolFinished = { finished.add(it) }, onConfirmationNeeded = {}
        )

        val chartReply = toolSet.showChart("bar", "Rain", "Mon; Tue", "3; 5", "mm")
        assertTrue(chartReply, chartReply.contains("now shown to the user"))
        assertEquals("show_chart", finished.last().toolName)
        assertEquals(VisualKind.BAR_CHART, finished.last().visual?.kind)

        val diagramReply = toolSet.showDiagram("flow", "Make tea", "Boil; Steep; Drink")
        assertTrue(diagramReply.contains("now shown to the user"))
        assertEquals(3, finished.last().visual?.boxes?.size)

        val failed = toolSet.showChart("pie", "x", "A", "-1", "")
        assertTrue(failed, failed.startsWith("Error: Chart not shown"))
        assertFalse(finished.last().success)
        assertNull(finished.last().visual)

        assertEquals("2 + 2 = 4", toolSet.calculate("2 + 2"))
        assertEquals("2 + 2 = 4", finished.last().displayDetail)
    }

    @Test
    fun `photo search reads DuckDuckGo and Wikipedia answers`() {
        assertEquals("4-123456789", PhotoSearch.vqdToken("<script>vqd=\"4-123456789\";</script>"))
        assertEquals("4-99", PhotoSearch.vqdToken("x vqd='4-99' y"))
        assertEquals("4-55", PhotoSearch.vqdToken("/d.js?q=a&vqd=4-55&p=1"))
        assertNull(PhotoSearch.vqdToken("<html>nothing here</html>"))

        val ddg = """
            {"results":[
              {"title":"Tower","image":"https://img.example.com/a.jpg","thumbnail":"https://tse1.mm.bing.net/th?id=A&pid=Api","url":"https://www.example.com/tower"},
              {"title":"No thumb","image":"https://img.example.com/b.jpg","url":"https://b.example.org/"},
              {"title":"Same page","thumbnail":"https://tse2.mm.bing.net/th?id=B","url":"https://www.example.com/tower"},
              {"title":"Night","thumbnail":"https://tse3.mm.bing.net/th?id=C","url":"https://photos.example.net/night"},
              {"title":"Day","thumbnail":"https://tse4.mm.bing.net/th?id=D","url":"https://d.example.net/"},
              {"title":"Snow","thumbnail":"https://tse5.mm.bing.net/th?id=E","url":"https://e.example.net/"},
              {"title":"Rain","thumbnail":"https://tse6.mm.bing.net/th?id=F","url":"https://f.example.net/"}
            ]}
        """.trimIndent()
        val photos = PhotoSearch.parseDuckDuckGo(ddg)
        assertEquals(PhotoSearch.MAX_PHOTOS, photos.size)
        assertEquals(listOf("Tower", "Night", "Day", "Snow"), photos.map { it.title })
        assertEquals("example.com", photos.first().source)
        assertEquals(
            "https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Ftse1.mm.bing.net%2Fth%3Fid%3DA%26pid%3DApi&f=1",
            photos.first().imageUrl
        )
        assertTrue(PhotoSearch.parseDuckDuckGo("not json").isEmpty())

        val wiki = """
            {"query":{"pages":[
              {"pageid":2,"title":"Eiffel Tower (film)","index":2,"fullurl":"https://en.wikipedia.org/wiki/Film"},
              {"pageid":3,"title":"Paris","index":3,"thumbnail":{"source":"https://upload.wikimedia.org/p.jpg"},"fullurl":"https://en.wikipedia.org/wiki/Paris"},
              {"pageid":1,"title":"Eiffel Tower","index":1,"thumbnail":{"source":"https://upload.wikimedia.org/e.jpg"},"fullurl":"https://en.wikipedia.org/wiki/Eiffel_Tower"}
            ]}}
        """.trimIndent()
        val wikiPhotos = PhotoSearch.parseWikipedia(wiki)
        assertEquals(listOf("Eiffel Tower", "Paris"), wikiPhotos.map { it.title })
        assertEquals("https://upload.wikimedia.org/e.jpg", wikiPhotos.first().imageUrl)
        assertEquals("Wikipedia", wikiPhotos.first().source)
    }

    @Test
    fun `photos come only with searches about something to look at`() {
        listOf("Eiffel Tower", "capybara", "Pixel 10 Pro", "best pizza in Naples").forEach {
            assertTrue("$it should get photos", PhotoSearch.isAppropriate(it))
        }
        listOf(
            "bitcoin price today", "how to boil an egg", "weather in Paris", "Gemini 3 release date",
            "euro to dollar exchange rate", "one two three four five six seven eight nine"
        ).forEach { assertFalse("$it should not get photos", PhotoSearch.isAppropriate(it)) }
    }

    @Test
    fun `chart labels and axis ticks are short and round`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("$1,200", formatValue(1200.0, "$"))
            assertEquals("-€3.5", formatValue(-3.5, "€"))
            assertEquals("45%", formatValue(45.0, "%"))
            assertEquals("20°C", formatValue(20.0, "°C"))
            assertEquals("12.5K", formatValue(12_500.0, ""))
            assertEquals("250K", formatValue(250_000.0, ""))
            assertEquals("3.4M km", formatValue(3_400_000.0, "km"))
            assertEquals("0.123", formatValue(0.1234, ""))
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), niceTicks(3.0, 97.0))
        assertEquals(listOf(-20.0, 0.0, 20.0, 40.0), niceTicks(-12.0, 30.0))
        assertEquals(listOf(4.5, 4.75, 5.0, 5.25, 5.5), niceTicks(5.0, 5.0))
    }

    @Test
    fun `an answer shows one photo strip at most`() {
        val photos = ToolVisual(VisualKind.PHOTOS, photos = listOf(VisualPhoto("https://x/1.jpg")))
        val chart = ToolVisual(VisualKind.BAR_CHART, labels = listOf("a"), values = listOf(1.0))
        val tools = listOf(
            ToolExecutionResult("search_web", true, "", visual = photos),
            ToolExecutionResult("search_web", true, "", visual = photos),
            ToolExecutionResult("show_chart", true, "", visual = chart)
        )
        assertEquals(listOf(photos, chart), visualsToShow(tools))
        assertTrue(hasToolVisuals(tools))
        assertFalse(hasToolVisuals(listOf(ToolExecutionResult("get_weather", true, "Sunny"))))
    }
}
