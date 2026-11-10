package org.fossistant.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fossistant.assistant.features.DataBackup
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
