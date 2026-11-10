package org.fossistant.assistant.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fossistant.assistant.model.MemoryFact
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent On-Device AI Memory System (`AiMemoryManager`).
 *
 * Capabilities:
 * 1. **Automatic Fact Extraction**: Detects personal facts, preferences, names, locations, and
 *    explicit *"Remember that..."* instructions from user prompts and saves them automatically
 *    when `isMemoryEnabled == true`.
 * 2. **Persistent Local Storage**: Saves and loads JSON facts to `ai_memory_store.json`
 *    (100% private and offline on the device).
 * 3. **Contextual Memory Injection**: Retrieves relevant facts for the current turn and formats
 *    them into the model's system context so Gemma 4 E2B remembers the user across sessions.
 */
class AiMemoryManager(
    private val storageDir: File? = null,
    private val context: Context? = null
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val internalList = CopyOnWriteArrayList<MemoryFact>()

    private val _facts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val facts: StateFlow<List<MemoryFact>> = _facts.asStateFlow()

    @Volatile
    var isMemoryEnabled: Boolean = true

    init {
        loadFromDisk()
        _facts.value = internalList.toList()
    }

    private fun memoryFile(): File? {
        val dir = storageDir ?: context?.filesDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "ai_memory_store.json")
    }

    /**
     * Inspects a user prompt and automatically extracts any personal facts or explicit memory requests.
     * Returns the newly extracted [MemoryFact] if one was found and saved.
     */
    fun autoExtractAndStoreFromPrompt(userPrompt: String): MemoryFact? {
        if (!isMemoryEnabled) return null
        val trimmed = userPrompt.trim()
        if (trimmed.length < 5) return null

        // Only unambiguous statements are stored automatically; everything else is left to Gemma's
        // remember_user_fact tool. Broad patterns used to save "call me a taxi" as the user's name and
        // "I don't remember the movie" as a fact, and memory facts now go into every system prompt.
        if (trimmed.endsWith("?")) return null
        val patterns = listOf(
            Regex("(?i)^(?:please\\s+)?(?:remember|don't forget|keep in mind|note)\\s+that\\s+(.+)") to "EXPLICIT_MEMORY",
            Regex("(?i)^(?:please\\s+)?remember\\s+(my\\s+.+)") to "EXPLICIT_MEMORY",
            Regex("(?i)^(?:hi|hello|hey)?[,!.\\s]*my name is\\s+([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*)?)(?:\\s*[,.!]|\\s+and\\s+(.+)|$)") to "IDENTITY",
            Regex("(?i)(?:^|\\band\\s+)i live in\\s+([\\p{L}][\\p{L} ,'-]{1,40}?)[.!]?$") to "LOCATION",
            Regex("(?i)^my favou?rite\\s+(.+\\s+is\\s+.+)") to "PREFERENCE",
            Regex("(?i)^i(?:'m| am) allergic to\\s+(.+)") to "HEALTH"
        )

        var lastSaved: MemoryFact? = null
        for ((regex, category) in patterns) {
            val match = regex.find(trimmed)
            if (match != null) {
                val extractedFact = when (category) {
                    "EXPLICIT_MEMORY" -> match.groupValues[1].trim().removeSuffix(".")
                    "IDENTITY" -> {
                        val namePart = "User's name is ${match.groupValues[1].trim()}"
                        val extraPart = match.groupValues.getOrNull(2)?.trim().orEmpty()
                        if (extraPart.isNotEmpty()) "$namePart ($extraPart)" else namePart
                    }
                    "LOCATION" -> "User lives in ${match.groupValues[1].trim()}"
                    "WORK" -> "User work info: $trimmed"
                    else -> "User stated: \"$trimmed\""
                }
                lastSaved = rememberFact(extractedFact, category)
            }
        }
        return lastSaved
    }

    fun rememberFact(factText: String, category: String = "USER_FACT"): MemoryFact {
        val cleaned = factText.trim()
        val existing = internalList.firstOrNull { it.fact.equals(cleaned, ignoreCase = true) }
        if (existing != null) return existing

        val entry = MemoryFact(
            id = UUID.randomUUID().toString(),
            fact = cleaned,
            category = category
        )
        internalList.add(0, entry)
        while (internalList.size > MAX_MEMORY_FACTS) {
            internalList.removeAt(internalList.size - 1)
        }
        _facts.value = internalList.toList()
        saveToDisk()
        return entry
    }

    fun addMemoryFact(factText: String, category: String = "USER_FACT"): MemoryFact =
        rememberFact(factText, category)

    fun removeFact(id: String) {
        internalList.removeAll { it.id == id }
        _facts.value = internalList.toList()
        saveToDisk()
    }

    fun removeMemoryFact(id: String) = removeFact(id)

    fun clearAll() {
        internalList.clear()
        _facts.value = emptyList()
        saveToDisk()
    }

    fun clearAllMemories() = clearAll()

    fun getAllMemories(): List<MemoryFact> = internalList.toList()

    /**
     * Selects the most relevant stored memories for [query] to inject into the LLM prompt.
     */
    fun retrieveRelevantFacts(query: String, topK: Int = 5): List<MemoryFact> {
        if (!isMemoryEnabled || internalList.isEmpty()) return emptyList()
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        if (terms.isEmpty()) return emptyList()

        return internalList
            .map { fact ->
                val lower = fact.fact.lowercase()
                val matchCount = terms.count { lower.contains(it) }
                val categoryBoost = if (fact.category in setOf("IDENTITY", "EXPLICIT_MEMORY", "PREFERENCE")) 1 else 0
                fact to (matchCount * 2 + categoryBoost)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    fun retrieveRelevantMemories(query: String, maxFacts: Int = 5): List<MemoryFact> =
        retrieveRelevantFacts(query, maxFacts)

    /**
     * Formats stored memories into a compact context string for Gemma 4 E2B QAT.
     */
    fun buildMemoryContextBlock(query: String): String {
        if (!isMemoryEnabled) return ""
        val relevant = retrieveRelevantFacts(query, topK = 5)
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("[Persistent AI Memory Facts about User]:")
            relevant.forEach { item ->
                appendLine("- (${item.category}) ${item.fact}")
            }
        }.trim()
    }

    private fun saveToDisk() {
        try {
            val file = memoryFile() ?: return
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(internalList.toList()))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Throwable) {}
    }

    private fun loadFromDisk() {
        try {
            val file = memoryFile() ?: return
            if (file.exists()) {
                val loaded = json.decodeFromString<List<MemoryFact>>(file.readText()).take(MAX_MEMORY_FACTS)
                internalList.clear()
                internalList.addAll(loaded)
                _facts.value = internalList.toList()
            }
        } catch (_: Throwable) {}
    }

    companion object {
        const val MAX_MEMORY_FACTS = 300
    }
}
