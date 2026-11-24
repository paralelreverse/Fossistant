package org.fossistant.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fossistant.assistant.features.DataBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataBackupTest {
    @Test
    fun rewritesOnlyAttachmentUris() {
        val chats = Json.parseToJsonElement(
            """[{"id":"a","title":"uriString","messages":[{"text":"hi","attachments":[{"uriString":"content://x/1","label":"pic"}]}]}]"""
        )
        val out = DataBackup.rewriteUris(chats) { if (it == "content://x/1") "fossistant-backup:attachments/0000_1" else null }
        val chat = out.jsonArray[0].jsonObject
        assertEquals("uriString", chat["title"]!!.jsonPrimitive.content)
        val attachment = chat["messages"]!!.jsonArray[0].jsonObject["attachments"]!!.jsonArray[0].jsonObject
        assertEquals("fossistant-backup:attachments/0000_1", attachment["uriString"]!!.jsonPrimitive.content)
        assertEquals("pic", attachment["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun keepsUriWhenMapReturnsNull() {
        val chats = Json.parseToJsonElement("""[{"attachments":[{"uriString":"screen://now"}]}]""")
        val out = DataBackup.rewriteUris(chats) { null }
        assertEquals(chats, out)
    }

    @Test
    fun extractZipSafelyExtractsValidZip() {
        val staging = Files.createTempDirectory("backup_valid").toFile().apply { deleteOnExit() }
        val bytes = buildZip("manifest.json" to """{"app":"Fossistant"}""".toByteArray(), "attachments/pic.jpg" to byteArrayOf(1, 2, 3))
        DataBackup.extractZipSafely(ByteArrayInputStream(bytes), staging)
        assertEquals("""{"app":"Fossistant"}""", File(staging, "manifest.json").readText())
        assertTrue(File(staging, "attachments/pic.jpg").exists())
    }

    @Test
    fun extractZipSafelyRejectsZipSlip() {
        val staging = Files.createTempDirectory("backup_zipslip").toFile().apply { deleteOnExit() }
        val bytes = buildZip("../../evil.txt" to "pwn".toByteArray())
        try {
            DataBackup.extractZipSafely(ByteArrayInputStream(bytes), staging)
            fail("Expected zip-slip rejection")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun extractZipSafelyRejectsOversizedOrTooManyEntries() {
        val staging = Files.createTempDirectory("backup_bomb").toFile().apply { deleteOnExit() }
        val large = buildZip("big.txt" to ByteArray(64) { 'a'.code.toByte() })
        try {
            DataBackup.extractZipSafely(ByteArrayInputStream(large), staging, maxTotalBytes = 16L)
            fail("Expected total uncompressed size limit enforcement")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        val many = buildZip("a.txt" to byteArrayOf(1), "b.txt" to byteArrayOf(2))
        try {
            DataBackup.extractZipSafely(ByteArrayInputStream(many), staging, maxEntries = 1)
            fail("Expected entry count limit enforcement")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun sanitizeAttachmentFileNameRejectsTraversal() {
        assertEquals("0001_pic.jpg", DataBackup.sanitizeAttachmentFileName("0001_pic.jpg"))
        assertNull(DataBackup.sanitizeAttachmentFileName("../evil.jpg"))
        assertNull(DataBackup.sanitizeAttachmentFileName("dir/pic.jpg"))
        assertNull(DataBackup.sanitizeAttachmentFileName("dir\\pic.jpg"))
        assertNull(DataBackup.sanitizeAttachmentFileName(".."))
        assertNull(DataBackup.sanitizeAttachmentFileName("."))
        assertNull(DataBackup.sanitizeAttachmentFileName(""))
    }

    @Test
    fun sanitizeConnectorBackupJsonStripsTokens() {
        val input = """[{"id":"1","name":"HA","url":"http://homeassistant.local:8123/api/mcp","token":"secret-abc"}]"""
        val sanitized = Json.parseToJsonElement(DataBackup.sanitizeConnectorBackupJson(input)).jsonArray[0].jsonObject
        assertEquals("HA", sanitized["name"]!!.jsonPrimitive.content)
        assertEquals("", sanitized["token"]!!.jsonPrimitive.content)
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
