package org.fossistant.assistant.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossistant.assistant.model.VisualPhoto
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Photos drawn above the answer to a web search. DuckDuckGo image search (safe search on) comes first, its thumbnails
 * loaded through DuckDuckGo's image proxy so the photo sites never see the phone; Wikipedia's lead images are the
 * fallback. Everything has to finish within [BUDGET_MS] of starting, so photos never hold the answer up for long.
 */
class PhotoSearch(private val client: OkHttpClient) {

    /** Up to [MAX_PHOTOS] photos for [query]; empty when the query isn't about something to look at or nothing came in time. */
    fun find(query: String): List<VisualPhoto> {
        if (!isAppropriate(query)) return emptyList()
        val deadline = System.currentTimeMillis() + BUDGET_MS
        val photos = runCatching { duckDuckGo(query, deadline) }.getOrDefault(emptyList())
        if (photos.isNotEmpty()) return photos
        return runCatching { wikipedia(query, deadline) }.getOrDefault(emptyList())
    }

    private fun duckDuckGo(query: String, deadline: Long): List<VisualPhoto> {
        val q = encode(query)
        val page = get("https://duckduckgo.com/?q=$q&iax=images&ia=images", deadline, BROWSER_AGENT) ?: return emptyList()
        val vqd = vqdToken(page) ?: return emptyList()
        val json = get(
            "https://duckduckgo.com/i.js?l=wt-wt&o=json&q=$q&vqd=${encode(vqd)}&f=,,,,,&p=1&kp=1",
            deadline, BROWSER_AGENT, referer = "https://duckduckgo.com/"
        ) ?: return emptyList()
        return parseDuckDuckGo(json)
    }

    private fun wikipedia(query: String, deadline: Long): List<VisualPhoto> {
        val json = get(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search" +
                "&gsrsearch=${encode(query)}&gsrlimit=$MAX_PHOTOS&prop=pageimages%7Cinfo&inprop=url" +
                "&piprop=thumbnail&pithumbsize=400&pilimit=$MAX_PHOTOS",
            deadline, WIKIPEDIA_AGENT
        ) ?: return emptyList()
        return parseWikipedia(json)
    }

    /** The body of a GET, or null on an HTTP error or once [deadline] has passed. */
    private fun get(url: String, deadline: Long, userAgent: String, referer: String? = null): String? {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return null
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        return call.execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }

    companion object {
        const val MAX_PHOTOS = 4
        const val BUDGET_MS = 4_000L

        private const val BROWSER_AGENT = "Mozilla/5.0 (Android 15; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"
        private const val WIKIPEDIA_AGENT = "Fossistant/1.0 (on-device Android assistant)"

        /** Photos help with people, places, animals, things and food; not with prices, how-tos, news, scores or numbers. */
        fun isAppropriate(query: String): Boolean {
            val q = query.lowercase().trim()
            if (q.isEmpty() || q.split(Regex("\\s+")).size > 8) return false
            return !NO_PHOTOS.containsMatchIn(q)
        }

        /** The search token DuckDuckGo puts into its result page (vqd="4-123..."), needed for the image API. */
        fun vqdToken(html: String): String? = VQD.find(html)?.groupValues?.get(1)

        /** Photos from DuckDuckGo's image API (`i.js`); thumbnails go through its image proxy. */
        fun parseDuckDuckGo(json: String): List<VisualPhoto> {
            val results = (parse(json) as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
            return results.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val thumbnail = obj.string("thumbnail")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val page = obj.string("url").orEmpty()
                VisualPhoto(
                    imageUrl = "https://external-content.duckduckgo.com/iu/?u=${encode(thumbnail)}&f=1",
                    pageUrl = page,
                    title = obj.string("title").orEmpty().take(120),
                    source = hostOf(page)
                )
            }.distinctBy { it.pageUrl.ifBlank { it.imageUrl } }.take(MAX_PHOTOS)
        }

        /** Lead images of the Wikipedia articles a search found, in search order. */
        fun parseWikipedia(json: String): List<VisualPhoto> {
            val pages = ((parse(json) as? JsonObject)?.get("query") as? JsonObject)?.get("pages") as? JsonArray
                ?: return emptyList()
            return pages.mapNotNull { it as? JsonObject }
                .sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
                .mapNotNull { page ->
                    val image = (page["thumbnail"] as? JsonObject)?.string("source") ?: return@mapNotNull null
                    VisualPhoto(
                        imageUrl = image,
                        pageUrl = page.string("fullurl").orEmpty(),
                        title = page.string("title").orEmpty(),
                        source = "Wikipedia"
                    )
                }
                .take(MAX_PHOTOS)
        }

        private fun parse(json: String) = runCatching { Json.parseToJsonElement(json) }.getOrNull()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun hostOf(url: String): String =
            runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

        private val VQD = Regex("vqd=[\"']?([\\w-]+)")

        private val NO_PHOTOS = Regex(
            "\\b(prices?|costs?|cheap|deals?|how (?:to|do|does|much|many|long|old)|why|when|weather|forecast|temperature|" +
                "news|latest|today|tonight|yesterday|tomorrow|scores?|results?|standings|fixtures|stocks?|shares|exchange rate|" +
                "convert|conversion|currency|translate|translation|meaning|definition|define|synonyms?|errors?|bug|crash|" +
                "code|api|sdk|documentation|docs|tutorial|release date|released|schedule|opening hours|hours|open now|time|" +
                "date|phone number|salary|population|gdp|statistics|stats|percent|percentage|formula|lyrics|quotes?|" +
                "election|polls?|version|update|changelog|download|install|settings|login|password|benchmark|difference)\\b"
        )
    }
}
