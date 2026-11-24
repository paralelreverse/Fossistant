package org.fossistant.assistant.engine

import com.google.ai.edge.litertlm.Tool
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.valueParameters

/**
 * Rescues tool calls LiteRT-LM refuses to parse. Gemma 4 writes calls as
 * `call:name{key:<|"|>text<|"|>,count:5}`, but the small model sometimes drops the string delimiters
 * (`call:control_media{action:stop}`), and LiteRT then fails the whole answer with
 * "Failed to parse tool calls from code block: ...". This reads the call out of that error message
 * leniently so the app can still run it.
 */
object GemmaToolCallRecovery {

    data class Call(val name: String, val arguments: Map<String, String>)

    private const val STRING_DELIMITER = "<|\"|>"
    private val CALL_START = Regex("""call:([A-Za-z_][A-Za-z0-9_]*)\s*\{""")
    private val NEXT_KEY = Regex("""^\s*,\s*[A-Za-z_][A-Za-z0-9_]*\s*:""")

    /** True when [message] is LiteRT's "couldn't parse the model's tool call" error. */
    fun isToolParseError(message: String?): Boolean =
        message != null && message.contains("Failed to parse", ignoreCase = true) && message.contains("call:")

    /** The calls in a LiteRT tool-parse error message, or an empty list when it isn't one. */
    fun parseFromError(message: String?): List<Call> {
        if (!isToolParseError(message)) return emptyList()
        // The message holds the code block and then the full response; both contain the same calls.
        val source = message!!.substringBefore("full response:").ifBlank { message }
        return parseCalls(source).distinct()
    }

    fun parseCalls(text: String): List<Call> {
        val calls = mutableListOf<Call>()
        var searchFrom = 0
        while (true) {
            val match = CALL_START.find(text, searchFrom) ?: break
            val bodyStart = match.range.last + 1
            val bodyEnd = findClosingBrace(text, bodyStart) ?: break
            calls.add(Call(match.groupValues[1], parseArguments(text.substring(bodyStart, bodyEnd))))
            searchFrom = bodyEnd + 1
        }
        return calls
    }

    /** Index of the `}` that closes the call body starting at [from], skipping delimited strings and nested braces. */
    private fun findClosingBrace(text: String, from: Int): Int? {
        var depth = 0
        var i = from
        while (i < text.length) {
            if (text.startsWith(STRING_DELIMITER, i)) {
                val end = text.indexOf(STRING_DELIMITER, i + STRING_DELIMITER.length)
                if (end < 0) return null
                i = end + STRING_DELIMITER.length
                continue
            }
            when (text[i]) {
                '{', '[' -> depth++
                '}', ']' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return null
    }

    private fun parseArguments(body: String): Map<String, String> {
        val args = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            val colon = body.indexOf(':', i)
            if (colon < 0) break
            val key = body.substring(i, colon).trim().trim('"', '\'')
            i = colon + 1
            while (i < body.length && body[i].isWhitespace()) i++
            val value: String
            if (body.startsWith(STRING_DELIMITER, i)) {
                val start = i + STRING_DELIMITER.length
                val end = body.indexOf(STRING_DELIMITER, start).let { if (it < 0) body.length else it }
                value = body.substring(start, end)
                i = (end + STRING_DELIMITER.length).coerceAtMost(body.length)
            } else {
                // Bare value: runs to the next ", key:" at depth 0 (so commas inside a sentence stay in it).
                var depth = 0
                var j = i
                while (j < body.length) {
                    val c = body[j]
                    if (c == '{' || c == '[') depth++
                    if (c == '}' || c == ']') depth--
                    if (c == ',' && depth <= 0 && NEXT_KEY.containsMatchIn(body.substring(j))) break
                    j++
                }
                value = body.substring(i, j).trim().removeSurrounding("\"").removeSurrounding("'")
                i = j
            }
            if (key.isNotEmpty()) args[key] = value.trim()
        }
        return args
    }
}

/**
 * Invokes `@Tool` methods of [FossistantGemmaToolSet] (every group of it) by snake_case name when
 * LiteRT fails to parse a `call:name{...}` block emitted by Gemma.
 */
class ToolSetInvoker(toolSet: FossistantGemmaToolSet) {
    private data class Entry(val owner: Any, val function: KFunction<*>)

    private val toolSets = toolSet.allToolSets

    private val byName: Map<String, Entry> by lazy {
        val map = linkedMapOf<String, Entry>()
        for (owner in toolSets) {
            for (fn in owner::class.declaredMemberFunctions) {
                fn.findAnnotation<Tool>() ?: continue
                val snake = fn.name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
                map[snake] = Entry(owner, fn)
            }
        }
        map
    }

    fun invoke(name: String, arguments: Map<String, String>): String {
        val entry = byName[name] ?: return "Error: unknown tool '$name'."
        val fn = entry.function
        return try {
            val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()
            fn.instanceParameter?.let { args[it] = entry.owner }
            fn.valueParameters.forEach { param ->
                val raw = arguments[param.name].orEmpty().trim()
                args[param] = when (param.type.classifier) {
                    Int::class -> raw.toDoubleOrNull()?.toInt() ?: 0
                    Long::class -> raw.toDoubleOrNull()?.toLong() ?: 0L
                    Double::class -> raw.toDoubleOrNull() ?: 0.0
                    Boolean::class -> raw.lowercase() in setOf("true", "on", "yes", "1")
                    else -> raw
                }
            }
            fn.callBy(args)?.toString().orEmpty()
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            "Error running $name: ${cause.message ?: cause::class.simpleName}"
        }
    }
}
