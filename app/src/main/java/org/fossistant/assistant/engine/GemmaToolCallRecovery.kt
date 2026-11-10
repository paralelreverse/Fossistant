package org.fossistant.assistant.engine

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
