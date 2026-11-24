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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val context: Context? = null,
    eagerLoad: Boolean = true,
    private val asyncPersist: Boolean = false
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeLock = Any()
    private val internalList = CopyOnWriteArrayList<MemoryFact>()
    private val diskLoaded = AtomicBoolean(false)
    @Volatile
    private var clearedBeforeLoad = false
    private val removedIdsBeforeLoad = java.util.Collections.synchronizedSet(HashSet<String>())
    private val diskWriter by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AiMemoryWriter").apply { isDaemon = true }
        }
    }

    private val _facts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val facts: StateFlow<List<MemoryFact>> = _facts.asStateFlow()

    @Volatile
    var isMemoryEnabled: Boolean = true

    init {
        if (eagerLoad) loadIfNeeded()
    }

    /** Loads persisted memory facts once from disk (safe to run off the Main thread). */
    fun loadIfNeeded() {
        if (!diskLoaded.compareAndSet(false, true)) return
        loadFromDisk()
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

        var lastSaved: MemoryFact? = null
        for ((regex, category) in AUTO_EXTRACT_PATTERNS) {
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
        synchronized(storeLock) {
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
            saveToDiskLocked()
            return entry
        }
    }

    fun addMemoryFact(factText: String, category: String = "USER_FACT"): MemoryFact =
        rememberFact(factText, category)

    fun removeFact(id: String) {
        synchronized(storeLock) {
            if (!diskLoaded.get()) removedIdsBeforeLoad.add(id)
            internalList.removeAll { it.id == id }
            _facts.value = internalList.toList()
            saveToDiskLocked()
        }
    }

    fun removeMemoryFact(id: String) = removeFact(id)

    fun clearAll() {
        synchronized(storeLock) {
            if (!diskLoaded.get()) clearedBeforeLoad = true
            removedIdsBeforeLoad.clear()
            internalList.clear()
            _facts.value = emptyList()
            saveToDiskLocked()
        }
    }

    fun clearAllMemories() = clearAll()

    fun getAllMemories(): List<MemoryFact> = internalList.toList()

    /**
     * Selects the most relevant stored memories for [query] to inject into the LLM prompt.
     */
    fun retrieveRelevantFacts(query: String, topK: Int = 5): List<MemoryFact> {
        if (!isMemoryEnabled || internalList.isEmpty()) return emptyList()
        val terms = query.lowercase()
            .split(TOKEN_SPLIT_REGEX)
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

    private fun saveToDiskLocked() {
        val snapshot = internalList.toList()
        if (asyncPersist) {
            diskWriter.execute { writeSnapshotToDisk(snapshot) }
        } else {
            writeSnapshotToDisk(snapshot)
        }
    }

    private fun writeSnapshotToDisk(snapshot: List<MemoryFact>) {
        try {
            val file = memoryFile() ?: return
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { org.fossistant.assistant.util.AppLogger.w("AiMemoryManager", "Failed saving AI memory: ${t.message}", t) }
        }
    }

    private fun loadFromDisk() {
        synchronized(storeLock) {
            if (clearedBeforeLoad) return
            try {
                val file = memoryFile() ?: return
                if (file.exists()) {
                    val loaded = json.decodeFromString<List<MemoryFact>>(file.readText()).take(MAX_MEMORY_FACTS)
                    val existing = internalList.toList()
                    val existingIds = existing.mapTo(HashSet()) { it.id }
                    val merged = (existing + loaded.filter { it.id !in existingIds && it.id !in removedIdsBeforeLoad })
                        .take(MAX_MEMORY_FACTS)
                    internalList.clear()
                    internalList.addAll(merged)
                    _facts.value = internalList.toList()
                }
            } catch (t: Throwable) {
                runCatching { org.fossistant.assistant.util.AppLogger.w("AiMemoryManager", "Failed loading AI memory: ${t.message}", t) }
            }
        }
    }

    companion object {
        const val MAX_MEMORY_FACTS = 300
        private val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val AUTO_EXTRACT_PATTERNS = listOf(
            Regex("(?i)^(?:please\\s+)?(?:remember|don't forget|keep in mind|note)\\s+that\\s+(.+)") to "EXPLICIT_MEMORY",
            Regex("(?i)^(?:please\\s+)?remember\\s+(my\\s+.+)") to "EXPLICIT_MEMORY",
            Regex("(?i)^(?:hi|hello|hey)?[,!.\\s]*my name is\\s+([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*)?)(?:\\s*[,.!]|\\s+and\\s+(.+)|$)") to "IDENTITY",
            Regex("(?i)(?:^|\\band\\s+)i live in\\s+([\\p{L}][\\p{L} ,'-]{1,40}?)[.!]?$") to "LOCATION",
            Regex("(?i)^my favou?rite\\s+(.+\\s+is\\s+.+)") to "PREFERENCE",
            Regex("(?i)^i(?:'m| am) allergic to\\s+(.+)") to "HEALTH"
        )
    }
}
