package org.fossistant.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossistant.assistant.model.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Home Assistant REST client (https://developers.home-assistant.io/docs/api/rest/): finds an entity
 * by its friendly name and calls a service on it. Configured with the server address and a long-lived
 * access token from Settings > Integrations.
 */
class HomeAssistantClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = baseUrlProvider().trim().trimEnd('/')
    private val token: String get() = tokenProvider().trim()

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    private data class Entity(val id: String, val name: String, val state: String, val attributes: JSONObject) {
        val domain: String get() = id.substringBefore('.')
    }

    /** Checks the address and token by calling `GET /api/`. */
    suspend fun testConnection(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext notConfigured()
        try {
            http.newCall(request("/api/").get().build()).execute().use { response ->
                when {
                    response.isSuccessful -> ToolExecutionResult("home_assistant", true, "Connected to Home Assistant.")
                    response.code == 401 -> ToolExecutionResult("home_assistant", false, "Home Assistant rejected the access token.")
                    else -> ToolExecutionResult("home_assistant", false, "Home Assistant answered with HTTP ${response.code}.")
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult("home_assistant", false, "Couldn't reach Home Assistant at $baseUrl: ${e.message}")
        }
    }

    /**
     * Runs [action] (on, off, toggle, status, brightness, temperature, open, close, lock, unlock, activate)
     * on the entity whose name best matches [device]; [value] carries brightness % or temperature.
     */
    suspend fun control(action: String, device: String, value: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext notConfigured()
        val verb = normalizeAction(action)
            ?: return@withContext fail("Unknown smart home action '$action'. Use on, off, toggle, status, brightness, temperature, open, close, lock, unlock or activate.")
        val entities = try {
            fetchEntities()
        } catch (e: Exception) {
            return@withContext fail("Couldn't reach Home Assistant at $baseUrl: ${e.message}")
        }
        val candidates = entities.filter { it.domain in domainsFor(verb) }
        val entity = when (val match = bestMatch(candidates, device)) {
            is Match.Found -> match.entity
            is Match.Ambiguous -> return@withContext fail("Several devices match \"$device\": ${match.names.joinToString(", ")}. Which one?")
            Match.None -> return@withContext fail("No ${domainsFor(verb).joinToString("/")} device called \"$device\" in Home Assistant.")
        }
        if (verb == "status") {
            return@withContext ToolExecutionResult("home_assistant", true, describe(entity))
        }
        val (service, data) = serviceFor(verb, entity, value)
            ?: return@withContext fail("${entity.name} doesn't support '$action'.")
        try {
            val body = JSONObject(data).put("entity_id", entity.id).toString()
                .toRequestBody("application/json".toMediaType())
            http.newCall(request("/api/services/${entity.domain}/$service").post(body).build()).execute().use { response ->
                if (response.isSuccessful) {
                    ToolExecutionResult("home_assistant", true, doneMessage(verb, entity, value))
                } else {
                    fail("Home Assistant refused ${entity.domain}.$service for ${entity.name} (HTTP ${response.code}).")
                }
            }
        } catch (e: Exception) {
            fail("Couldn't reach Home Assistant: ${e.message}")
        }
    }

    private fun fetchEntities(): List<Entity> {
        http.newCall(request("/api/states").get().build()).execute().use { response ->
            if (response.code == 401) throw IllegalStateException("the access token was rejected")
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val array = JSONArray(response.body?.string().orEmpty())
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val attributes = obj.optJSONObject("attributes") ?: JSONObject()
                val id = obj.getString("entity_id")
                Entity(id, attributes.optString("friendly_name", id), obj.optString("state"), attributes)
            }
        }
    }

    private sealed class Match {
        data class Found(val entity: Entity) : Match()
        data class Ambiguous(val names: List<String>) : Match()
        object None : Match()
    }

    private fun bestMatch(entities: List<Entity>, device: String): Match {
        val query = normalize(device)
        if (query.isEmpty()) return Match.None
        val queryWords = query.split(' ').toSet()
        val scored = entities.map { entity ->
            val name = normalize(entity.name)
            val idName = normalize(entity.id.substringAfter('.'))
            val score = when {
                name == query || idName == query -> 3.0
                name.contains(query) || query.contains(name) -> 2.0
                else -> {
                    val words = name.split(' ').toSet()
                    val overlap = words.intersect(queryWords).size
                    if (overlap == 0) 0.0 else overlap.toDouble() / maxOf(words.size, queryWords.size)
                }
            }
            entity to score
        }.filter { it.second > 0.0 }
        val top = scored.maxOfOrNull { it.second } ?: return Match.None
        val best = scored.filter { it.second == top }.map { it.first }
        return if (best.size == 1) Match.Found(best.first()) else Match.Ambiguous(best.take(4).map { it.name })
    }

    private fun normalizeAction(action: String): String? = when (action.trim().lowercase().replace(' ', '_')) {
        "on", "turn_on", "switch_on", "start", "enable" -> "on"
        "off", "turn_off", "switch_off", "stop", "disable" -> "off"
        "toggle" -> "toggle"
        "status", "state", "check", "get" -> "status"
        "brightness", "set_brightness", "dim" -> "brightness"
        "temperature", "set_temperature", "heat", "cool" -> "temperature"
        "open", "raise" -> "open"
        "close", "lower", "shut" -> "close"
        "lock" -> "lock"
        "unlock" -> "unlock"
        "activate", "run", "scene", "script" -> "activate"
        else -> null
    }

    private fun domainsFor(verb: String): Set<String> = when (verb) {
        "on", "off", "toggle" -> setOf("light", "switch", "fan", "media_player", "input_boolean", "climate", "automation", "humidifier")
        "brightness" -> setOf("light")
        "temperature" -> setOf("climate")
        "open", "close" -> setOf("cover")
        "lock", "unlock" -> setOf("lock")
        "activate" -> setOf("scene", "script")
        else -> setOf(
            "light", "switch", "fan", "media_player", "input_boolean", "climate", "cover", "lock", "sensor",
            "binary_sensor", "humidifier", "vacuum", "alarm_control_panel"
        )
    }

    private fun serviceFor(verb: String, entity: Entity, value: String): Pair<String, Map<String, Any>>? {
        val number = Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
        return when (verb) {
            "on", "activate" -> "turn_on" to emptyMap()
            "off" -> "turn_off" to emptyMap()
            "toggle" -> "toggle" to emptyMap()
            "brightness" -> number?.let { "turn_on" to mapOf("brightness_pct" to it.toInt().coerceIn(0, 100)) }
            "temperature" -> number?.let { "set_temperature" to mapOf("temperature" to it) }
            "open" -> "open_cover" to emptyMap()
            "close" -> "close_cover" to emptyMap()
            "lock" -> "lock" to emptyMap()
            "unlock" -> "unlock" to emptyMap()
            else -> null
        }
    }

    private fun describe(entity: Entity): String {
        val extras = buildList {
            entity.attributes.optDouble("current_temperature").takeIf { !it.isNaN() }?.let { add("current temperature $it") }
            entity.attributes.optDouble("temperature").takeIf { !it.isNaN() }?.let { add("set to $it") }
            entity.attributes.optInt("brightness", -1).takeIf { it >= 0 }?.let { add("brightness ${it * 100 / 255}%") }
            entity.attributes.optString("unit_of_measurement").takeIf { it.isNotBlank() }?.let { add("unit $it") }
        }
        return "${entity.name} is ${entity.state}" + if (extras.isEmpty()) "." else " (${extras.joinToString(", ")})."
    }

    private fun doneMessage(verb: String, entity: Entity, value: String): String = when (verb) {
        "on" -> "Turned on ${entity.name}."
        "off" -> "Turned off ${entity.name}."
        "toggle" -> "Toggled ${entity.name}."
        "brightness" -> "Set ${entity.name} to $value% brightness."
        "temperature" -> "Set ${entity.name} to $value degrees."
        "open" -> "Opening ${entity.name}."
        "close" -> "Closing ${entity.name}."
        "lock" -> "Locked ${entity.name}."
        "unlock" -> "Unlocked ${entity.name}."
        else -> "Activated ${entity.name}."
    }

    private fun request(path: String) = Request.Builder()
        .url("$baseUrl$path")
        .header("Authorization", "Bearer $token")

    private fun normalize(text: String): String =
        text.lowercase().replace('_', ' ').replace(Regex("[^\\p{L}\\p{Nd} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun notConfigured() = fail("Home Assistant isn't set up. Add its address and a long-lived access token in Settings > Integrations.")

    private fun fail(message: String) = ToolExecutionResult("home_assistant", false, message)
}
