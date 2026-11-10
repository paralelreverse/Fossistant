package org.fossistant.assistant

import org.fossistant.assistant.engine.ImageGenEngine
import org.fossistant.assistant.model.ImageGenFiles
import org.fossistant.assistant.model.ImageGenModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenTest {

    @Test
    fun `edited pictures keep their shape at 512 px in steps of 64`() {
        assertEquals(512 to 512, ImageGenEngine.sizeFor(1024, 1024))
        assertEquals(512 to 384, ImageGenEngine.sizeFor(4000, 3000))
        assertEquals(256 to 512, ImageGenEngine.sizeFor(1080, 2400))
        assertEquals(512 to 512, ImageGenEngine.sizeFor(0, 0))
    }

    @Test
    fun `only the fast 4-step DreamShaper model with tiny decoder is configured`() {
        assertEquals(listOf(ImageGenModelChoice.DREAMSHAPER_FAST), ImageGenModelChoice.entries)
        val fast = ImageGenModelChoice.DREAMSHAPER_FAST
        assertEquals(listOf(ImageGenFiles.DREAMSHAPER_8_LCM, ImageGenFiles.TAESD), fast.files)
        assertEquals(ImageGenFiles.TAESD.fileName, fast.tinyVaeFile)
        assertEquals("lcm", fast.sampler)
        assertEquals(4, fast.steps)
        assertEquals(1.0f, fast.cfgScale, 0.001f)
    }

    @Test
    fun `every download is pinned to a size and SHA-256 on Hugging Face`() {
        val files = ImageGenModelChoice.entries.flatMap { it.files }.distinct()
        assertEquals(ImageGenFiles.all.toSet(), files.toSet())
        files.forEach { file ->
            assertTrue(file.url, file.url.startsWith("https://huggingface.co/") && "/resolve/" in file.url)
            assertTrue(file.fileName, file.sizeBytes > 1_000_000L)
            assertTrue(file.fileName, Regex("[0-9a-f]{64}").matches(file.sha256))
        }
        assertEquals(1_580_535_724L, ImageGenModelChoice.DREAMSHAPER_FAST.totalBytes)
    }
}
