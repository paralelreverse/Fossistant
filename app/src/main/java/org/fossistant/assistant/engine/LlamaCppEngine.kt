package org.fossistant.assistant.engine

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.valueParameters

/**
 * llama.cpp runtime for GGUF models (PrismML Bonsai 2 and future GGUF models), through `libllama_jni.so`.
 * GPU offload uses Vulkan (works on Mali and Adreno, with kernels for Bonsai's ternary Q2_0); the native side
 * falls back to the CPU when the GPU can't load the model. All calls are blocking: run them on an IO thread.
 */
class LlamaCppEngine {

    /** Receives generated text as complete UTF-8 chunks; return false to stop generating. */
    fun interface ByteCallback {
        fun onBytes(bytes: ByteArray): Boolean
    }

    @Volatile
    private var handle = 0L

    @Volatile
    var loadedPath: String? = null
        private set

    @Volatile
    var backendName: String = "Not loaded"
        private set

    val isLoaded: Boolean get() = handle != 0L

    private external fun nativeLoad(path: String, nCtx: Int, nGpuLayers: Int, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, callback: ByteCallback
    ): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeBackend(handle: Long): String
    private external fun nativeTokenCount(handle: Long): Int
    private external fun nativeContextSize(handle: Long): Int
    private external fun nativeCountTokens(handle: Long, text: String): Int
    private external fun nativeRecentLog(): String
    private external fun nativeSetCacheDir(handle: Long, dir: String)
    private external fun nativeProbe(handle: Long, prompt: String): String

    /**
     * Loads [path] with a [contextTokens] window; [useGpu] offloads every layer to the Vulkan GPU. [threads] = 0
     * lets the library pick (4 on phones); fewer threads keep the phone responsive.
     */
    fun load(path: String, contextTokens: Int, useGpu: Boolean, threads: Int = 0, stateCacheDir: String? = null): Result<String> {
        if (!ensureLibrary()) return Result.failure(IllegalStateException("libllama_jni.so is missing from this build"))
        release()
        val h = nativeLoad(path, contextTokens, if (useGpu) -1 else 0, threads)
        if (h == 0L) return Result.failure(IllegalStateException("llama.cpp could not load $path"))
        handle = h
        loadedPath = path
        stateCacheDir?.let { nativeSetCacheDir(h, it) }
        backendName = nativeBackend(h)
        return Result.success(backendName)
    }

    /** Greedy answer (a few tokens) to a fully formatted [prompt]; used to check the backend computes correctly. */
    fun probe(prompt: String): String {
        val h = handle
        return if (h == 0L) "" else nativeProbe(h, prompt)
    }

    /** Exact token count of [text] with the loaded model's tokenizer, or -1 if no model is loaded. */
    fun countTokens(text: String): Int {
        val h = handle
        return if (h == 0L) -1 else nativeCountTokens(h, text)
    }

    /** Recent llama.cpp / ggml warnings and GPU (Vulkan) messages since the last call ("" if none). */
    fun recentLog(): String = if (ensureLibrary()) runCatching { nativeRecentLog() }.getOrDefault("") else ""

    /** Generates a reply for a fully formatted prompt; returns "stop", "length", "cancelled" or "error: ...". */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, onText: (String) -> Boolean): String {
        val h = handle
        if (h == 0L) return "error: no model loaded"
        return nativeGenerate(h, prompt, maxTokens, temperature, topP, topK) { bytes -> onText(String(bytes, Charsets.UTF_8)) }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    fun reset() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    val cachedTokens: Int get() = handle.takeIf { it != 0L }?.let { nativeTokenCount(it) } ?: 0
    val contextSize: Int get() = handle.takeIf { it != 0L }?.let { nativeContextSize(it) } ?: 0

    fun release() {
        val h = handle
        handle = 0L
        loadedPath = null
        backendName = "Not loaded"
        if (h != 0L) nativeFree(h)
    }

    private companion object {
        @Volatile
        var libraryLoaded = false

        fun ensureLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("llama_jni")
                libraryLoaded = true
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * Exposes the `@Tool` methods of [FossistantGemmaToolSet] to llama.cpp models: JSON schemas for the prompt,
 * and execution of the calls the model writes. Gemma (LiteRT-LM) and GGUF models therefore share one tool
 * set, with the same chips, confirmations and lock-screen rules.
 */
class LlamaToolCatalog(
    private val toolSet: FossistantGemmaToolSet,
    /** Only these tool names (snake_case); null = all. Small models get a short list to keep the prompt light. */
    private val only: Set<String>? = null
) {

    private data class Entry(val function: KFunction<*>, val schema: JsonObject)

    private val entries: Map<String, Entry> by lazy {
        toolSet::class.declaredMemberFunctions.mapNotNull { function ->
            val tool = function.findAnnotation<Tool>() ?: return@mapNotNull null
            val name = camelToSnake(function.name)
            if (only != null && name !in only) return@mapNotNull null
            val params = function.valueParameters.filter { it.name != null }
            val schema = buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", tool.description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            params.forEach { param ->
                                put(param.name!!, buildJsonObject {
                                    put("type", jsonType(param.type.classifier))
                                    put("description", param.findAnnotation<ToolParam>()?.description.orEmpty())
                                })
                            }
                        })
                        put("required", buildJsonArray { params.forEach { add(JsonPrimitive(it.name!!)) } })
                    })
                })
            }
            name to Entry(function, schema)
        }.toMap()
    }

    /** One JSON object per line, as the Qwen3.5 template renders `tools | tojson`. */
    val schemaLines: List<String> get() = entries.values.map { it.schema.toString() }

    /** Runs tool [name] with string [arguments] (converted to the declared parameter types). */
    fun invoke(name: String, arguments: Map<String, String>): String {
        val entry = entries[name] ?: return "Error: unknown tool '$name'."
        return try {
            val function = entry.function
            val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()
            function.instanceParameter?.let { args[it] = toolSet }
            function.valueParameters.forEach { param ->
                val raw = arguments[param.name].orEmpty().trim()
                args[param] = when (param.type.classifier) {
                    Int::class -> raw.toDoubleOrNull()?.toInt() ?: 0
                    Boolean::class -> raw.lowercase() in setOf("true", "on", "yes", "1")
                    else -> raw
                }
            }
            function.callBy(args)?.toString().orEmpty()
        } catch (t: Throwable) {
            "Error: ${t.cause?.message ?: t.message}"
        }
    }

    private fun jsonType(classifier: Any?): String = when (classifier) {
        Int::class, Long::class -> "integer"
        Boolean::class -> "boolean"
        Float::class, Double::class -> "number"
        else -> "string"
    }

    private fun camelToSnake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}

/**
 * Chat formatting and tool-call parsing for Qwen3.5-family GGUF models (Bonsai 2), mirroring the chat template
 * embedded in the model: tools as JSON in the system turn, calls as
 * `<tool_call><function=name><parameter=p>value</parameter></function></tool_call>`, results as
 * `<tool_response>` user turns. Rendering is deterministic so llama.cpp can reuse the cached prefix.
 */
object QwenChatFormat {

    data class ToolCall(val name: String, val arguments: Map<String, String>)

    /** A finished tool round: what the assistant generated, and the tool results that followed. */
    data class ToolRound(val assistantText: String, val results: List<String>)

    /** A question every working Bonsai answers with "Paris" (greedy), for checking a backend computes correctly. */
    const val PROBE_PROMPT =
        "<|im_start|>user\nWhat is the capital of France? Answer with one word.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    private const val TOOL_INSTRUCTIONS =
        "\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:\n\n<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n<parameter=example_parameter_2>\nThis is the value for the second parameter\nthat can span\nmultiple lines\n</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\nReminder:\n- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n- Required parameters MUST be specified\n- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n</IMPORTANT>"

    fun generationPrefix(enableThinking: Boolean): String =
        "<|im_start|>assistant\n" + if (enableThinking) "<think>\n" else "<think>\n\n</think>\n\n"

    fun render(
        systemPrompt: String,
        toolSchemas: List<String>,
        history: List<Pair<String, String>>,
        userText: String,
        toolRounds: List<ToolRound>,
        enableThinking: Boolean
    ): String = buildString {
        append("<|im_start|>system\n")
        if (toolSchemas.isNotEmpty()) {
            append("# Tools\n\nYou have access to the following functions:\n\n<tools>")
            toolSchemas.forEach { append('\n').append(it) }
            append("\n</tools>")
            append(TOOL_INSTRUCTIONS)
            append("\n\n")
        }
        append(systemPrompt.trim())
        append("<|im_end|>\n")
        history.forEach { (user, assistant) ->
            append("<|im_start|>user\n").append(user.trim()).append("<|im_end|>\n")
            append("<|im_start|>assistant\n").append(assistant.trim()).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(userText.trim()).append("<|im_end|>\n")
        toolRounds.forEach { round ->
            append(generationPrefix(enableThinking)).append(round.assistantText).append("<|im_end|>\n")
            append("<|im_start|>user")
            round.results.forEach { append("\n<tool_response>\n").append(it).append("\n</tool_response>") }
            append("<|im_end|>\n")
        }
        append(generationPrefix(enableThinking))
    }

    private val TOOL_CALL = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val FUNCTION = Regex("<function=([^>\\s]+)>(.*?)</function>", RegexOption.DOT_MATCHES_ALL)
    private val PARAMETER = Regex("<parameter=([^>\\s]+)>\\n?(.*?)\\n?</parameter>", RegexOption.DOT_MATCHES_ALL)

    /** Extracts tool calls; also accepts the JSON `{"name":..,"arguments":{..}}` style some models fall back to. */
    fun parseToolCalls(text: String): List<ToolCall> = TOOL_CALL.findAll(text).mapNotNull { block ->
        val body = block.groupValues[1]
        FUNCTION.find(body)?.let { fn ->
            ToolCall(fn.groupValues[1], PARAMETER.findAll(fn.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() })
        } ?: runCatching {
            val json = Json.parseToJsonElement(body).jsonObject
            val args = (json["arguments"] as? JsonObject).orEmpty()
            ToolCall(
                json.getValue("name").jsonPrimitive.content,
                args.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }
            )
        }.getOrNull()
    }.toList()

    /**
     * Text to show while streaming: reasoning (before `</think>` when thinking is on) and tool-call markup are
     * hidden. Returns the visible answer and whether the model is still reasoning.
     */
    fun visibleText(raw: String, enableThinking: Boolean): Pair<String, Boolean> {
        var text = raw
        if (enableThinking) {
            val end = text.indexOf("</think>")
            if (end < 0) return "" to true
            text = text.substring(end + "</think>".length)
        }
        text = TOOL_CALL.replace(text, "")
        val openCall = text.indexOf("<tool_call>")
        if (openCall >= 0) text = text.substring(0, openCall)
        // Hide a tag that is still being streamed ("<tool_ca").
        val lastLt = text.lastIndexOf('<')
        if (lastLt >= 0 && !text.substring(lastLt).contains('>') && "<tool_call>".startsWith(text.substring(lastLt))) {
            text = text.substring(0, lastLt)
        }
        return text.replace("<|im_end|>", "").trim() to false
    }

    /** Reasoning text of a thinking-mode reply (between the generation prefix and `</think>`). */
    fun reasoning(raw: String): String? = raw.substringBefore("</think>", "").trim().takeIf { it.isNotEmpty() }
}
