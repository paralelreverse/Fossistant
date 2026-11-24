package org.fossistant.assistant.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of everything the user created in the app, as one .zip: chats (with the pictures and files sent
 * in them), memories, settings, skills, projects, scheduled tasks, connectors and recordings. Models and caches are
 * left out. Credentials (Home Assistant token, MCP access tokens) are scrubbed from exports for safety.
 */
object DataBackup {

    data class Summary(val chats: Int, val attachments: Int, val files: Int)

    private const val FORMAT = 1
    private const val PREFS_NAME = "fossistant_prefs"
    private const val ATTACHMENT_PREFIX = "fossistant-backup:attachments/"
    private const val HOME_ASSISTANT_TOKEN_KEY = "home_assistant_token"
    internal const val MAX_ZIP_ENTRIES = 2_000
    internal const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
    internal const val MAX_SINGLE_ENTRY_BYTES = 32L * 1024 * 1024
    private val UNSAFE_FILENAME_REGEX = Regex("[^A-Za-z0-9._-]")
    private val json = Json { ignoreUnknownKeys = true }

    /** App data under filesDir that goes into a backup (relative paths; folders include their contents). */
    private val DATA_PATHS = listOf("chats/chats.json", "features", "ai_memory_store.json", "recordings")

    suspend fun export(context: Context, target: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val files = context.filesDir
            var chatCount = 0
            var attachmentCount = 0
            var fileCount = 0
            val resolver = context.contentResolver
            resolver.openOutputStream(target)?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    // Chats: copy every attachment into the backup and point the chat at that copy.
                    val chatsFile = File(files, "chats/chats.json")
                    if (chatsFile.exists()) {
                        val copied = HashMap<String, String>()
                        val chats = rewriteUris(json.parseToJsonElement(chatsFile.readText())) { uri ->
                            copied[uri] ?: copyAttachment(context, uri, zip, copied.size)?.also {
                                copied[uri] = it
                                attachmentCount++
                            }
                        }
                        chatCount = (chats as? JsonArray)?.size ?: 0
                        zip.putNextEntry(ZipEntry("chats/chats.json"))
                        zip.write(chats.toString().toByteArray())
                        zip.closeEntry()
                    }
                    for (path in DATA_PATHS.drop(1)) {
                        val source = File(files, path)
                        if (!source.exists()) continue
                        source.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                            val relPath = file.relativeTo(files).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry("files/$relPath"))
                            if (relPath == "features/connectors.json") {
                                zip.write(sanitizeConnectorBackupJson(file.readText()).toByteArray())
                            } else {
                                file.inputStream().use { it.copyTo(zip) }
                            }
                            zip.closeEntry()
                            fileCount++
                        }
                    }
                    zip.putNextEntry(ZipEntry("settings.json"))
                    zip.write(exportPrefs(context).toString().toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(buildJsonObject {
                        put("app", "Fossistant")
                        put("format", FORMAT)
                        put("createdMs", System.currentTimeMillis())
                        put("filesDir", files.absolutePath)
                        put("chats", chatCount)
                    }.toString().toByteArray())
                    zip.closeEntry()
                }
            } ?: error("Couldn't write the backup file.")
            Summary(chatCount, attachmentCount, fileCount)
        }
    }

    /** Restores a backup over the current data. The app restarts afterwards to load it. */
    suspend fun import(context: Context, source: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val files = context.filesDir
            val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
            try {
                context.contentResolver.openInputStream(source)?.use { raw ->
                    extractZipSafely(raw, staging)
                } ?: error("Couldn't open the backup file.")
                val manifest = File(staging, "manifest.json").takeIf { it.exists() }
                    ?.let { json.parseToJsonElement(it.readText()).jsonObject }
                    ?: error("This isn't a Fossistant backup.")
                require(manifest["app"]?.jsonPrimitive?.contentOrNull == "Fossistant") { "This isn't a Fossistant backup." }
                val oldFilesDir = manifest["filesDir"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val attachmentsDir = File(files, "attachments").apply { mkdirs() }
                // Parse and validate chats.json and settings.json in staging BEFORE overwriting any live files in filesDir.
                val parsedChats = File(staging, "chats/chats.json").takeIf { it.exists() }?.let { file ->
                    rewriteUris(json.parseToJsonElement(file.readText())) { uri ->
                        if (uri.startsWith(ATTACHMENT_PREFIX)) {
                            val safeName = sanitizeAttachmentFileName(uri.removePrefix(ATTACHMENT_PREFIX))
                            if (safeName != null) File(attachmentsDir, safeName).absolutePath else ""
                        } else uri
                    }
                }
                val parsedSettings = File(staging, "settings.json").takeIf { it.exists() }?.let { file ->
                    json.parseToJsonElement(file.readText()).jsonObject
                }

                // Attachments first, so chats can point at them.
                var attachmentCount = 0
                val attachmentsCanonicalPrefix = attachmentsDir.canonicalFile.path + File.separator
                File(staging, "attachments").listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    val safeName = sanitizeAttachmentFileName(file.name) ?: return@forEach
                    val target = File(attachmentsDir, safeName).canonicalFile
                    if (!target.path.startsWith(attachmentsCanonicalPrefix)) return@forEach
                    file.copyTo(target, overwrite = true)
                    attachmentCount++
                }
                var fileCount = 0
                val stagingFilesRoot = File(staging, "files")
                val filesCanonicalPrefix = files.canonicalFile.path + File.separator
                stagingFilesRoot.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.forEach { file ->
                    val rel = file.relativeTo(stagingFilesRoot).invariantSeparatorsPath
                    val isAllowedDataPath = DATA_PATHS.any { allowed ->
                        rel == allowed || rel.startsWith("$allowed/")
                    }
                    if (!isAllowedDataPath) return@forEach
                    val target = File(files, rel).canonicalFile
                    if (!target.path.startsWith(filesCanonicalPrefix)) return@forEach
                    target.parentFile?.mkdirs()
                    // Recordings store absolute paths: move them to this phone's folder.
                    if (file.name.endsWith(".json") && oldFilesDir.isNotEmpty()) {
                        target.writeText(file.readText().replace(oldFilesDir, files.absolutePath))
                    } else {
                        file.copyTo(target, overwrite = true)
                    }
                    fileCount++
                }
                var chatCount = 0
                if (parsedChats != null) {
                    chatCount = (parsedChats as? JsonArray)?.size ?: 0
                    File(files, "chats").mkdirs()
                    File(files, "chats/chats.json").writeText(parsedChats.toString())
                }
                if (parsedSettings != null) {
                    importPrefs(context, parsedSettings)
                }
                Summary(chatCount, attachmentCount, fileCount)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** Safely unpacks [input] into [stagingDir], enforcing zip-slip and zip-bomb bounds. */
    internal fun extractZipSafely(
        input: InputStream,
        stagingDir: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
        maxEntryBytes: Long = MAX_SINGLE_ENTRY_BYTES
    ) {
        val stagingCanonicalDir = stagingDir.canonicalFile
        val stagingPrefix = stagingCanonicalDir.path + File.separator
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(16 * 1024)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= maxEntries) { "Backup contains too many entries ($entries)." }
                val out = File(stagingCanonicalDir, entry.name).canonicalFile
                require(out == stagingCanonicalDir || out.path.startsWith(stagingPrefix)) {
                    "Invalid backup entry ${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    var entryBytes = 0L
                    out.outputStream().use { dest ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= maxEntryBytes) {
                                "Backup entry ${entry.name} exceeds size limit."
                            }
                            require(totalBytes <= maxTotalBytes) {
                                "Backup exceeds uncompressed size limit."
                            }
                            dest.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    internal fun sanitizeAttachmentFileName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if ('/' in trimmed || '\\' in trimmed || '\u0000' in trimmed || ".." in trimmed) return null
        val sanitized = trimmed.replace(UNSAFE_FILENAME_REGEX, "_").takeLast(120)
        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") return null
        return sanitized
    }

    internal fun sanitizeConnectorBackupJson(rawJson: String): String {
        val array = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull() ?: return "[]"
        val stripped = JsonArray(array.map { element ->
            val obj = element as? JsonObject ?: return@map element
            JsonObject(obj.mapValues { (k, v) -> if (k == "token") JsonPrimitive("") else v })
        })
        return stripped.toString()
    }

    /** Relaunches the app so every store reloads the restored data. */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /** Copies one attachment (content:// or file path) into the zip; returns its backup reference or null. */
    private fun copyAttachment(context: Context, uri: String, zip: ZipOutputStream, index: Int): String? {
        if (uri.startsWith(ATTACHMENT_PREFIX) || uri.startsWith("screen://")) return null
        val input = runCatching {
            when {
                uri.startsWith("/") -> File(uri).takeIf { it.exists() }?.inputStream()
                uri.startsWith("file:") -> Uri.parse(uri).path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
                else -> context.contentResolver.openInputStream(Uri.parse(uri))
            }
        }.getOrNull() ?: return null
        val name = "%04d_%s".format(index, uri.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60))
        input.use {
            zip.putNextEntry(ZipEntry("attachments/$name"))
            it.copyTo(zip)
            zip.closeEntry()
        }
        return ATTACHMENT_PREFIX + name
    }

    /** Replaces every "uriString" value in the chat JSON with [map]'s result (when not null). */
    internal fun rewriteUris(element: JsonElement, map: (String) -> String?): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            val uri = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (key == "uriString" && uri != null) JsonPrimitive(map(uri) ?: uri) else rewriteUris(value, map)
        })
        is JsonArray -> JsonArray(element.map { rewriteUris(it, map) })
        else -> element
    }

    private fun exportPrefs(context: Context): JsonObject = buildJsonObject {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (key == HOME_ASSISTANT_TOKEN_KEY) return@forEach
            when (value) {
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value)
                is String -> put(key, value)
                else -> Unit
            }
        }
    }

    private fun importPrefs(context: Context, values: JsonObject) {
        val current = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val types = current.all
        val editor = current.edit()
        values.forEach { (key, element) ->
            val primitive = element as? JsonPrimitive ?: return@forEach
            when (types[key]) {
                is Boolean -> primitive.booleanOrNull?.let { editor.putBoolean(key, it) }
                is Int -> primitive.intOrNull?.let { editor.putInt(key, it) }
                is Long -> primitive.longOrNull?.let { editor.putLong(key, it) }
                is Float -> primitive.doubleOrNull?.let { editor.putFloat(key, it.toFloat()) }
                is String -> editor.putString(key, primitive.content)
                else -> when {
                    primitive.isString -> editor.putString(key, primitive.content)
                    primitive.booleanOrNull != null -> editor.putBoolean(key, primitive.booleanOrNull!!)
                    primitive.content.contains('.') -> primitive.doubleOrNull?.let { editor.putFloat(key, it.toFloat()) }
                    else -> primitive.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) editor.putInt(key, it.toInt()) else editor.putLong(key, it) }
                }
            }
        }
        editor.commit()
    }
}
