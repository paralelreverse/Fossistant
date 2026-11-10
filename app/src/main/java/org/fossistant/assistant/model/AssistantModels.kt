package org.fossistant.assistant.model

import kotlinx.serialization.Serializable

/**
 * Identifies which model tier handled or is currently handling a request.
 */
@Serializable
enum class ModelTier(val displayName: String, val ramFootprintMb: Int) {
    /** A confirmed call / text / app action, run without Gemma (old chats saved it under the router's name). */
    @kotlinx.serialization.SerialName("AMAZON_MASSIVE_ROUTER")
    DIRECT_ACTION("Direct action", 0),
    GEMMA_E2B_QAT_MTP("Gemma 4 E2B QAT (LiteRT-LM)", 1050),
    LFM3_VL_2B("Liquid LFM3-VL-2B (llama.cpp + libmtmd)", 1450)
}

/**
 * Structured tool call output produced by Gemma.
 */
@Serializable
data class ToolCallRequest(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

/**
 * Result of executing a tool locally on the Android device.
 */
@Serializable
data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val summary: String,
    val rawPayload: String? = null,
    /** What the tool was used for, shown on the tool chip (search query, city, app name, ...). */
    val displayDetail: String? = null,
    /** The model requested the tool and it is still running. */
    val isPending: Boolean = false,
    /** A call / message / email that waits for the user's "yes" before it is executed. */
    val awaitingConfirmation: Boolean = false,
    /** A chart, infographic or search photos drawn with the answer. */
    val visual: ToolVisual? = null
)

/**
 * Supported multimodal attachment types for large text, audio, video, and image processing.
 */
enum class MediaModality {
    IMAGE,
    AUDIO,
    VIDEO,
    LARGE_TEXT
}

@Serializable
data class MediaAttachment(
    val id: String,
    val uriString: String,
    val fileName: String,
    val modality: MediaModality,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val extractedTextPreview: String? = null
)

/**
 * Represents a single turn message in the ChatGPT-like conversation UI.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val attachments: List<MediaAttachment> = emptyList(),
    val modelTier: ModelTier? = null,
    val toolExecutions: List<ToolExecutionResult> = emptyList(),
    val decodeSpeedTokPerSec: Float? = null,
    val isStreaming: Boolean = false,
    val isReasoningPhase: Boolean = false,
    val reasoningTrace: String? = null,
    val errorMessage: String? = null,
    /** The model that wrote this answer (kept when the user switches models later). */
    val modelName: String? = null
) {
}
