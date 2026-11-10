package org.fossistant.assistant.features

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.engine.GemmaLiteRtEngine
import org.fossistant.assistant.model.ToolCallRequest
import org.fossistant.assistant.tools.DeviceAndWebToolExecutor
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Deep Research (Perplexity / ChatGPT / Gemini style) with an on-device model: plan several searches, read the
 * best pages, take notes on each one separately (so a 4K-token context is enough), then write a report with
 * numbered citations, a source list and follow-up questions.
 */
class DeepResearch(
    private val engine: GemmaLiteRtEngine,
    private val executor: DeviceAndWebToolExecutor
) {
    data class Source(val index: Int, val title: String, val url: String)

    data class Update(
        val status: String,
        val report: String? = null,
        val sources: List<Source> = emptyList(),
        val done: Boolean = false,
        val failed: Boolean = false
    )

    data class WebResult(val title: String, val url: String, val snippet: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun run(question: String): Flow<Update> = flow {
        emit(Update("Planning the research…"))
        val plan = engine.completeOnce(PLAN_INSTRUCTIONS, question, maxTokens = 200)
        val queries = parseQueries(plan, question)

        val results = LinkedHashMap<String, WebResult>()
        for (query in queries) {
            emit(Update("Searching: $query"))
            search(query).forEach { result -> results.putIfAbsent(normalizeUrl(result.url), result) }
        }
        if (results.isEmpty()) {
            emit(Update("I couldn't reach any search results. Check the internet connection and try again.", done = true, failed = true))
            return@flow
        }

        val notes = mutableListOf<Pair<Source, String>>()
        val candidates = results.values.take(MAX_PAGES_TRIED)
        for ((i, result) in candidates.withIndex()) {
            if (notes.size >= MAX_SOURCES) break
            emit(Update("Reading ${i + 1}/${candidates.size}: ${host(result.url)}"))
            val page = executor.execute(ToolCallRequest("fetch_webpage", mapOf("url" to result.url)))
            val text = (page.rawPayload?.takeIf { page.success } ?: result.snippet).take(PAGE_CHARS)
            if (text.length < 200) continue
            val note = engine.completeOnce(
                NOTES_INSTRUCTIONS,
                "Question: $question\n\nSource: ${result.title}\n\n$text",
                maxTokens = 320
            )?.trim().orEmpty()
            if (note.isBlank() || note.startsWith(IRRELEVANT, ignoreCase = true)) continue
            notes.add(Source(notes.size + 1, result.title.ifBlank { host(result.url) }, result.url) to note.take(NOTE_CHARS))
        }
        if (notes.isEmpty()) {
            emit(Update("The pages I found didn't answer the question. Try rephrasing it.", done = true, failed = true))
            return@flow
        }

        emit(Update("Writing the report from ${notes.size} sources…", sources = notes.map { it.first }))
        val notesText = notes.joinToString("\n\n") { (source, note) -> "[${source.index}] ${source.title}\n$note" }
        val report = engine.completeOnce(REPORT_INSTRUCTIONS, "Question: $question\n\nNotes:\n$notesText", maxTokens = 1400)
            ?: notesText
        val sources = notes.map { it.first }
        emit(Update("Done", report = withSourceList(report, sources), sources = sources, done = true))
    }

    /** DuckDuckGo HTML results with the real target URLs (DuckDuckGo wraps them in a redirect). */
    suspend fun search(query: String): List<WebResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
                .header("User-Agent", "Mozilla/5.0 (Android 15; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
                .build()
            http.newCall(request).execute().use { response ->
                val doc = Jsoup.parse(response.body?.string().orEmpty())
                doc.select(".result").mapNotNull { element ->
                    val link = element.selectFirst("a.result__a") ?: return@mapNotNull null
                    val url = resolveDuckDuckGoUrl(link.attr("href")) ?: return@mapNotNull null
                    if ("duckduckgo.com" in url) return@mapNotNull null
                    WebResult(link.text().trim(), url, element.select(".result__snippet").text().trim())
                }.take(RESULTS_PER_QUERY)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_QUERIES = 4
        private const val RESULTS_PER_QUERY = 4
        private const val MAX_PAGES_TRIED = 8
        private const val MAX_SOURCES = 5
        private const val PAGE_CHARS = 6000
        private const val NOTE_CHARS = 900
        private const val IRRELEVANT = "IRRELEVANT"

        private const val PLAN_INSTRUCTIONS =
            "You plan web research. Write 3 or 4 short, different search queries (a few keywords each) that " +
                "together answer the user's question. One query per line, nothing else."

        private const val NOTES_INSTRUCTIONS =
            "You take research notes. From the source text, write up to 6 short bullet points of facts that help " +
                "answer the question: numbers, dates, names, claims. Only facts stated in the source. If the source " +
                "doesn't help, reply with exactly $IRRELEVANT."

        private const val REPORT_INSTRUCTIONS =
            "You write a research report from numbered notes. Answer the question directly in the first paragraph, " +
                "then explain the details under short '## ' headings. Put the source number in brackets after each " +
                "fact, like [2]. Use only the notes; say when sources disagree or something is unknown. End with a " +
                "'## Follow-up questions' section of 3 bullet points. Do not list the sources yourself."

        internal fun parseQueries(plan: String?, question: String): List<String> {
            val lines = plan.orEmpty().lines()
                .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"', ' ') }
                .filter { it.length in 3..120 }
                .distinct()
                .take(MAX_QUERIES)
            return lines.ifEmpty { listOf(question.take(120)) }
        }

        internal fun resolveDuckDuckGoUrl(href: String): String? {
            val full = if (href.startsWith("//")) "https:$href" else href
            if ("uddg=" in full) {
                val encoded = full.substringAfter("uddg=").substringBefore('&')
                return URLDecoder.decode(encoded, "UTF-8")
            }
            return full.takeIf { it.startsWith("http") }
        }

        internal fun withSourceList(report: String, sources: List<Source>): String = buildString {
            append(report.trim())
            append("\n\n## Sources\n")
            sources.forEach { append("[${it.index}] ${it.title} — ${it.url}\n") }
        }.trim()

        private fun normalizeUrl(url: String) = url.substringBefore('#').trimEnd('/')

        private fun host(url: String) = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
    }
}
