package org.fossistant.assistant.features

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossistant.assistant.model.ToolExecutionResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP client (Model Context Protocol, Streamable HTTP transport) so the assistant can use tools from
 * servers the user runs: Home Assistant's MCP server, self-hosted note or task apps, etc. Tools are listed in
 * the system prompt and called through the `use_connector` tool.
 */
class ConnectorManager(
    val store: JsonListStore<Connector>,
    private val onToolsChanged: (String) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val requestIds = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<String, String>()

    init {
        publishToolSummary()
    }

    /** Connects to [connector], lists its tools and saves them. Returns the updated connector. */
    suspend fun refresh(connector: Connector): Connector = withContext(Dispatchers.IO) {
        val updated = try {
            sessions.remove(connector.id)
            val tools = mutableListOf<ConnectorTool>()
            var cursor: String? = null
            do {
                val params = buildJsonObject { cursor?.let { put("cursor", it) } }
                val result = call(connector, "tools/list", params)
                result["tools"]?.jsonArray?.forEach { element ->
                    val tool = element.jsonObject
                    val schema = tool["inputSchema"]?.jsonObject
                    tools.add(
                        ConnectorTool(
                            name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach,
                            description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200),
                            inputSchema = schema?.toString() ?: "{}",
                            parameterNames = schema?.get("properties")?.jsonObject?.keys?.toList().orEmpty()
                        )
                    )
                }
                cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            } while (cursor != null && tools.size < 200)
            connector.copy(tools = tools, lastError = "")
        } catch (t: Throwable) {
            connector.copy(lastError = t.message ?: t::class.java.simpleName)
        }
        store.upsert(updated)
        publishToolSummary()
        updated
    }

    fun save(connector: Connector) {
        store.upsert(connector)
        publishToolSummary()
    }

    fun delete(id: String) {
        store.delete(id)
        sessions.remove(id)
        publishToolSummary()
    }

    /** Runs connector tool [toolName] with JSON [argumentsJson]; used by the `use_connector` tool. */
    suspend fun callTool(toolName: String, argumentsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val name = toolName.trim()
        val connector = store.items.value.firstOrNull { c -> c.enabled && c.tools.any { it.name == name } }
            ?: return@withContext ToolExecutionResult("use_connector", false, "No connector has a tool called '$name'.")
        val arguments = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }
            .getOrElse { return@withContext ToolExecutionResult("use_connector", false, "Arguments must be a JSON object.") }
        try {
            val result = call(connector, "tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
            val text = result["content"]?.jsonArray.orEmpty().joinToString("\n") { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    "resource" -> obj["resource"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    else -> "[${obj["type"]?.jsonPrimitive?.contentOrNull ?: "content"}]"
                }
            }.ifBlank { result["structuredContent"]?.toString().orEmpty() }
            val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
            ToolExecutionResult("use_connector", !isError, "${connector.name}: $name", text.take(4000))
        } catch (t: Throwable) {
            ToolExecutionResult("use_connector", false, "${connector.name} failed: ${t.message}")
        }
    }

    /** Lists the enabled tools for the system prompt, compactly ("name(a, b): description"). */
    private fun publishToolSummary() {
        val summary = store.items.value.filter { it.enabled }
            .flatMap { it.tools }
            .take(MAX_TOOLS_IN_PROMPT)
            .joinToString("; ") { tool ->
                "${tool.name}(${tool.parameterNames.joinToString(", ")}): ${tool.description.substringBefore('.').take(90)}"
            }
        onToolsChanged(summary)
    }

    private fun call(connector: Connector, method: String, params: JsonObject, retry: Boolean = true): JsonObject {
        val sessionId = sessions[connector.id] ?: initialize(connector)
        return try {
            request(connector, method, params, sessionId)
        } catch (e: SessionExpired) {
            sessions.remove(connector.id)
            if (!retry) throw e
            call(connector, method, params, retry = false)
        }
    }

    private fun initialize(connector: Connector): String {
        val id = requestIds.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "Fossistant")
                    put("version", "1.0")
                })
            })
        }
        val (response, sessionId) = post(connector, body, null, id)
        response ?: error("The server didn't answer the MCP handshake.")
        val session = sessionId ?: ""
        // The handshake ends with an "initialized" notification (no response expected).
        post(connector, buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }, session.ifEmpty { null }, null)
        sessions[connector.id] = session
        return session
    }

    private fun request(connector: Connector, method: String, params: JsonObject, sessionId: String): JsonObject {
        val id = requestIds.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val (response, _) = post(connector, body, sessionId.ifEmpty { null }, id)
        response ?: error("No response to $method.")
        response["error"]?.takeIf { it !is JsonNull }?.jsonObject?.let { err ->
            error(err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error")
        }
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** POSTs one JSON-RPC message; reads a JSON or SSE reply and returns the message with [expectId]. */
    private fun post(connector: Connector, body: JsonObject, sessionId: String?, expectId: Int?): Pair<JsonObject?, String?> {
        val builder = Request.Builder()
            .url(connector.url.trim())
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (connector.token.isNotBlank()) builder.header("Authorization", "Bearer ${connector.token.trim()}")
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId)
        http.newCall(builder.build()).execute().use { response ->
            if (response.code == 404 && sessionId != null) throw SessionExpired()
            if (!response.isSuccessful && response.code != 202) {
                error("HTTP ${response.code} ${response.message}".trim())
            }
            val newSession = response.header("Mcp-Session-Id")
            if (expectId == null) return null to newSession
            val contentType = response.header("Content-Type").orEmpty()
            val source = response.body ?: return null to newSession
            if (contentType.contains("text/event-stream")) {
                val reader = source.charStream().buffered()
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        line.isEmpty() && data.isNotEmpty() -> {
                            val message = parseMessage(data.toString())
                            data.clear()
                            if (message != null && message.idMatches(expectId)) return message to newSession
                        }
                    }
                }
                if (data.isNotEmpty()) parseMessage(data.toString())?.takeIf { it.idMatches(expectId) }?.let { return it to newSession }
                return null to newSession
            }
            val text = source.string()
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            val message = when (parsed) {
                is JsonObject -> parsed
                is JsonArray -> parsed.mapNotNull { it as? JsonObject }.firstOrNull { it.idMatches(expectId) }
                else -> null
            }
            return message to newSession
        }
    }

    private fun parseMessage(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.idMatches(id: Int): Boolean {
        val value: JsonElement = this["id"] ?: return false
        return (value as? JsonPrimitive)?.let { it.intOrNull == id || it.contentOrNull == id.toString() } == true
    }

    private class SessionExpired : Exception("MCP session expired")

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        // Each listed tool costs prompt tokens on every turn.
        const val MAX_TOOLS_IN_PROMPT = 15
    }
}
