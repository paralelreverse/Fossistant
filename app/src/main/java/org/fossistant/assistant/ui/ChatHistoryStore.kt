package org.fossistant.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Saved chats, persisted to `filesDir/chats/chats.json` so they survive app restarts. One instance lives in
 * [org.fossistant.assistant.FossistantApp] and is shared by the main screen and the assistant popup, which
 * have separate view models.
 */
class ChatHistoryStore(storageDir: File?) {

    private val file: File? = storageDir?.let { File(it, "chats").apply { mkdirs() }.resolve("chats.json") }
    private val backupFile: File? = file?.let { File(it.parentFile, "chats.json.bak") }
    private val lastChatFile: File? = file?.let { File(it.parentFile, "last_chat.txt") }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(PreviousChatSession.serializer())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ChatHistoryWriter").apply { isDaemon = true }
    }

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<PreviousChatSession>> = _sessions.asStateFlow()

    /** Inserts or replaces a chat and moves it to the top of the list. */
    fun upsert(session: PreviousChatSession) {
        val cleaned = session.copy(
            messages = session.messages.takeLast(MAX_MESSAGES_PER_CHAT).map { message ->
                message.copy(
                    isStreaming = false,
                    isReasoningPhase = false,
                    toolExecutions = message.toolExecutions.filterNot { it.isPending }
                )
            }
        )
        _sessions.update { current ->
            (listOf(cleaned) + current.filterNot { it.id == session.id }).take(MAX_CHATS)
        }
        persist()
    }

    /**
     * The chat that was open last and when it was last used ("id|millis" on disk). It's reopened only when the app
     * comes back within a few minutes; see [org.fossistant.assistant.ui.AssistantViewModel.restoreLastChat].
     */
    var lastOpenedChatId: String?
        get() = readLastChat()?.first
        set(value) {
            val target = lastChatFile ?: return
            val line = value?.let { "$it|${System.currentTimeMillis()}" }
            writer.execute { runCatching { if (line == null) target.delete() else target.writeText(line) } }
        }

    /** When the last chat was last used (0 if unknown). */
    val lastChatUsedAtMs: Long get() = readLastChat()?.second ?: 0L

    /** Marks the last chat as used just now (e.g. when the app goes to the background). */
    fun touchLastChat() {
        lastOpenedChatId?.let { lastOpenedChatId = it }
    }

    private fun readLastChat(): Pair<String, Long>? {
        val text = runCatching { lastChatFile?.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        if (text.isNullOrBlank()) return null
        val id = text.substringBefore('|')
        val time = text.substringAfter('|', "0").toLongOrNull() ?: 0L
        return id to time
    }

    fun delete(sessionId: String) {
        _sessions.update { current -> current.filterNot { it.id == sessionId } }
        persist()
    }

    fun clear() {
        _sessions.value = emptyList()
        persist()
    }

    /**
     * Reads saved chats one by one, so a chat that a newer or older app version can't read is skipped instead of
     * losing all of them (the next save would otherwise overwrite the file with an empty list). Falls back to the
     * previous good copy if the file itself is damaged.
     */
    private fun load(): List<PreviousChatSession> {
        val main = file ?: return emptyList()
        for (source in listOfNotNull(main, backupFile)) {
            if (!source.exists()) continue
            val sessions = decodeLeniently(runCatching { source.readText() }.getOrNull() ?: continue) ?: continue
            if (source != main) {
                org.fossistant.assistant.util.AppLogger.w("ChatHistoryStore", "Saved chats were damaged; restored ${sessions.size} from the backup")
            }
            return sessions
        }
        return emptyList()
    }

    /** All chats that decode; null if the text isn't a JSON list at all. */
    internal fun decodeLeniently(text: String): List<PreviousChatSession>? {
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        var skipped = 0
        val sessions = array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(PreviousChatSession.serializer(), element) }
                .onFailure { skipped++ }
                .getOrNull()
        }
        if (skipped > 0) {
            org.fossistant.assistant.util.AppLogger.w("ChatHistoryStore", "Skipped $skipped saved chat(s) this version can't read")
            // Keep the original file so nothing is lost for good.
            runCatching { file?.copyTo(File(file.parentFile, "chats.unreadable-${System.currentTimeMillis()}.json")) }
        }
        return sessions
    }

    private fun persist() {
        val target = file ?: return
        val snapshot = _sessions.value
        writer.execute {
            try {
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(json.encodeToString(serializer, snapshot))
                // The previous version becomes the backup before it's replaced.
                if (target.exists() && target.length() > 2) backupFile?.let { target.copyTo(it, overwrite = true) }
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            } catch (t: Throwable) {
                org.fossistant.assistant.util.AppLogger.w("ChatHistoryStore", "Could not save chats: ${t.message}")
            }
        }
    }

    private companion object {
        const val MAX_CHATS = 60
        const val MAX_MESSAGES_PER_CHAT = 200
    }
}
