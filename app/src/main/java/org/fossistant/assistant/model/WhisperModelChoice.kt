package org.fossistant.assistant.model

/**
 * On-Device Multilingual Speech-to-Text Model choices.
 * All models run locally on Android GPU / CPU via whisper.cpp native engine.
 * APKs without the voice assets (slim) download the file from whisper.cpp's Hugging Face repo, pinned by size
 * and SHA-256 (the same bytes the full APK bundles).
 */
enum class WhisperModelChoice(
    val filename: String,
    val displayName: String,
    val ramBadge: String,
    val minBytes: Long,
    val shortDescription: String,
    val detailedExplanation: String,
    val downloadUrl: String,
    val downloadSizeBytes: Long,
    val downloadSha256: String
) {
    WHISPER_TINY(
        filename = "whisper-tiny.bin",
        displayName = "Whisper Tiny (Fastest)",
        ramBadge = "75MB",
        minBytes = 70_000_000L,
        shortDescription = "Fast multilingual STT (~75MB • 39M params), the default",
        detailedExplanation = "Lightweight on-device speech model. Transcribes several times faster than Small with the lowest RAM and battery use; less accurate with heavy accents and noise.",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        downloadSizeBytes = 77_691_713L,
        downloadSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    ),
    WHISPER_SMALL_Q5(
        filename = "whisper-small-q5_1.bin",
        displayName = "Whisper Small Q5 (High Accuracy)",
        ramBadge = "181MB",
        minBytes = 180_000_000L,
        shortDescription = "High-Accuracy 244M Multilingual STT (~181MB • Q5_1)",
        detailedExplanation = "Standard 5-bit quantized 244M parameter Whisper Small model. Significantly lower Word Error Rate (WER) across 99+ languages, heavy accents, and noisy environments with OpenCL GPU acceleration at ~181MB.",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        downloadSizeBytes = 190_085_487L,
        downloadSha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    )
}
