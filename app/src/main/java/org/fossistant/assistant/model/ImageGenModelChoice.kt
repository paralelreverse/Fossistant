package org.fossistant.assistant.model

/** A file an image model needs, downloaded from Hugging Face the first time the model is picked. */
data class ImageGenFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * On-device image generator (stable-diffusion.cpp, CPU). Runs DreamShaper 8 LCM, an SD 1.5 fine-tune with
 * the LCM distillation merged in, using 4 steps and TAESD for fast on-device decoding.
 */
enum class ImageGenModelChoice(
    val displayName: String,
    val shortDescription: String,
    val files: List<ImageGenFile>,
    val steps: Int,
    val cfgScale: Float,
    val sampler: String,
    val scheduler: String,
    val tinyVaeFile: String? = null
) {
    DREAMSHAPER_FAST(
        displayName = "DreamShaper 8",
        shortDescription = "4 steps • Fast on-device Stable Diffusion",
        files = listOf(ImageGenFiles.DREAMSHAPER_8_LCM, ImageGenFiles.TAESD),
        steps = 4,
        cfgScale = 1.0f,
        sampler = "lcm",
        scheduler = "lcm",
        tinyVaeFile = ImageGenFiles.TAESD.fileName
    );

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ImageGenFiles {
    /** Lykon's DreamShaper 8 LCM, IQ4_NL with an importance matrix, quantized for stable-diffusion.cpp (OpenRAIL-M). */
    val DREAMSHAPER_8_LCM = ImageGenFile(
        fileName = "dreamshaper_8LCM-iq4_nl-imv2.gguf",
        url = "https://huggingface.co/stduhpf/dreamshaper-8LCM-im-GGUF-sdcpp/resolve/main/dreamshaper_8LCM-iq4_nl-imv2.gguf",
        sizeBytes = 1_570_742_432L,
        sha256 = "393727e29b3ee0611a2baae1233519bcd22e1a9aa3f20cd93222c4ff0baf8cca"
    )
    val TAESD = ImageGenFile(
        fileName = "taesd.safetensors",
        url = "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors",
        sizeBytes = 9_793_292L,
        sha256 = "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549"
    )

    val all = listOf(DREAMSHAPER_8_LCM, TAESD)
}
