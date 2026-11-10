package org.fossistant.assistant.model

/**
 * Inference runtime for a primary model.
 * - [LITERT_LM]: Google LiteRT-LM (`.litertlm` bundles), GPU via OpenCL / ML Drift.
 * - [LLAMA_CPP]: llama.cpp (`.gguf`), GPU via Vulkan with CPU fallback.
 */
enum class LlmRuntimeEngine(val badge: String) {
    LITERT_LM("LiteRT"),
    LLAMA_CPP("llama.cpp")
}

/**
 * How a model file gets onto the device.
 * - [BUNDLED]: inside the full APK; unpacked to private storage on first launch and reused by slim updates.
 * - [IMPORTED]: not in the full APK (the APK limit is ~4 GB). Copied once from a file the user picks or pushed
 *   with adb; kept across app updates. (No model uses this right now.)
 */
enum class ModelDelivery { BUNDLED, IMPORTED }

/**
 * Primary on-device language models. New, more capable small models only need an entry here: the runtime,
 * context size and capabilities are data, so the rest of the app (routing, tools, chips, voice) is unchanged.
 */
enum class PrimaryLlmModel(
    val filename: String,
    val displayName: String,
    val ramBadge: String,
    val minBytes: Long,
    val supportsThinking: Boolean,
    val supportsVision: Boolean,
    val runtimeEngine: LlmRuntimeEngine,
    val shortDescription: String,
    val detailedExplanation: String,
    val delivery: ModelDelivery = ModelDelivery.BUNDLED,
    val supportsTools: Boolean = true,
    /**
     * The context window starts at [startContextTokens] and doubles whenever less than 2K tokens are free, up to
     * [maxContextTokens] (and 16K on phones with less than 12 GB of RAM). It starts at 16K because the system
     * prompt now carries every installed app's actions (often 1-3K tokens) and still leaves room for the chat.
     */
    val startContextTokens: Int = 16384,
    val maxContextTokens: Int = 32768,
    /** Where to get the file (shown when it has to be imported). */
    val sourceUrl: String = "",
    /** The .litertlm bundle includes a multi-token-prediction drafter (LiteRT speculative decoding). */
    val hasMtpDrafter: Boolean = false,
    /**
     * Download for APKs that don't carry the model (slim). The file is streamed straight into private storage,
     * so the phone keeps exactly one copy. [downloadSha256] pins the exact build (for Gemma: the MTP build that
     * the full APK bundles); empty skips the hash check.
     */
    val downloadUrl: String = "",
    val downloadSizeBytes: Long = 0L,
    val downloadSha256: String = ""
) {
    GEMMA_4_E2B(
        filename = "gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        ramBadge = "1.87GB",
        minBytes = 1_500_000_000L,
        supportsThinking = true,
        supportsVision = true,
        runtimeEngine = LlmRuntimeEngine.LITERT_LM,
        shortDescription = "Google Gemma 4 E2B (GPU • ~1.9GB RAM)",
        detailedExplanation = "Google's 2B model with Multi-Token Prediction, vision and tool calling, accelerated on the GPU with OpenCL.",
        hasMtpDrafter = true,
        // The Gemma 4 E2B build with the MTP drafter section (the same bytes as the two parts in the full APK:
        // 1_293_942_784 + 1_294_204_928). The older 2_008_432_640-byte build has no drafter and is rejected.
        downloadUrl = org.fossistant.assistant.BuildConfig.GEMMA_MODEL_URL,
        downloadSizeBytes = 2_588_147_712L,
        downloadSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    );

    companion object {
        val activeEntries: List<PrimaryLlmModel> = entries.toList()

        fun fromName(name: String?): PrimaryLlmModel = entries.firstOrNull { it.name == name } ?: GEMMA_4_E2B
    }
}
