package org.fossistant.assistant.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Defines a complete Biomorphic Color & Mood Theme that simultaneously styles:
 * 1. The 3D [CosmicAiBlob] (lobes, cosmic nebula core, starfield, concentric rings, and voice ribbon).
 * 2. The mood of the entire app UI (iridescent glass borders, user & assistant chat bubbles,
 *    glowing shadows, hamburger menu icon, and interactive switches).
 */
enum class BlobMoodTheme(
    val displayName: String,
    val moodSubtitle: String,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val tertiaryAccent: Color,
    val coreNebulaColor: Color,
    val userBubbleStart: Color,
    val userBubbleEnd: Color,
    val aiBubbleStart: Color,
    val aiBubbleEnd: Color
) {
    COSMIC_NEBULA(
        displayName = "Cosmic Nebula",
        moodSubtitle = "Mystic Violet & Mint-Cyan",
        primaryAccent = Color(0xFFA78BFA),
        secondaryAccent = Color(0xFF5EEAD4),
        tertiaryAccent = Color(0xFF3B82F6),
        coreNebulaColor = Color(0xFF1E1B4B),
        userBubbleStart = Color(0xDD1E1B4B),
        userBubbleEnd = Color(0xDD17153B),
        aiBubbleStart = Color(0xDD0D2628),
        aiBubbleEnd = Color(0xDD111C2D)
    ),
    CRIMSON_EMBER(
        displayName = "Crimson Ember",
        moodSubtitle = "Warm Ruby, Amber & Rose Gold",
        primaryAccent = Color(0xFFFB7185),
        secondaryAccent = Color(0xFFFBBF24),
        tertiaryAccent = Color(0xFFEF4444),
        coreNebulaColor = Color(0xFF3B0A18),
        userBubbleStart = Color(0xDD3B0D1E),
        userBubbleEnd = Color(0xDD2A0A14),
        aiBubbleStart = Color(0xDD2E1A0B),
        aiBubbleEnd = Color(0xDD1F1209)
    ),
    EMERALD_AURORA(
        displayName = "Emerald Aurora",
        moodSubtitle = "Calm Forest Jade & Borealis Teal",
        primaryAccent = Color(0xFF34D399),
        secondaryAccent = Color(0xFF38BDF8),
        tertiaryAccent = Color(0xFF10B981),
        coreNebulaColor = Color(0xFF062C22),
        userBubbleStart = Color(0xDD083328),
        userBubbleEnd = Color(0xDD06241D),
        aiBubbleStart = Color(0xDD0B2538),
        aiBubbleEnd = Color(0xDD081B29)
    ),
    SOLAR_GOLD(
        displayName = "Solar Gold",
        moodSubtitle = "Radiant Honey Gold & Sunset Coral",
        primaryAccent = Color(0xFFFBBF24),
        secondaryAccent = Color(0xFFFB923C),
        tertiaryAccent = Color(0xFFFDE047),
        coreNebulaColor = Color(0xFF332006),
        userBubbleStart = Color(0xDD362409),
        userBubbleEnd = Color(0xDD261806),
        aiBubbleStart = Color(0xDD2E160B),
        aiBubbleEnd = Color(0xDD1F0F08)
    ),
    CYBER_ICE(
        displayName = "Cyber Ice",
        moodSubtitle = "Crisp Glacial Cyan & Electric Cobalt",
        primaryAccent = Color(0xFF38BDF8),
        secondaryAccent = Color(0xFF818CF8),
        tertiaryAccent = Color(0xFF22D3EE),
        coreNebulaColor = Color(0xFF08203E),
        userBubbleStart = Color(0xDD0C2847),
        userBubbleEnd = Color(0xDD091D36),
        aiBubbleStart = Color(0xDD111C3D),
        aiBubbleEnd = Color(0xDD0C142B)
    ),
    MIDNIGHT_ORCHID(
        displayName = "Midnight Orchid",
        moodSubtitle = "Vibrant Magenta, Sakura & Lilac",
        primaryAccent = Color(0xFFE879F9),
        secondaryAccent = Color(0xFFF472B6),
        tertiaryAccent = Color(0xFFC084FC),
        coreNebulaColor = Color(0xFF340B38),
        userBubbleStart = Color(0xDD38103D),
        userBubbleEnd = Color(0xDD270B2B),
        aiBubbleStart = Color(0xDD2B1233),
        aiBubbleEnd = Color(0xDD1C0C24)
    )
}

/**
 * Controls whether the model responds with shorter or longer answers.
 */
enum class ResponseLengthPreference(
    val displayName: String,
    val maxTokensBudget: Int,
    val promptInstruction: String
) {
    SHORTER(
        displayName = "Shorter (Concise)",
        maxTokensBudget = 180,
        promptInstruction = "Keep your responses very short, punchy, and direct (1 to 2 sentences maximum). Skip unnecessary preamble."
    ),
    BALANCED(
        displayName = "Balanced",
        maxTokensBudget = 512,
        promptInstruction = "Provide clear, natural, well-structured answers of moderate length."
    ),
    LONGER(
        displayName = "Longer (Detailed)",
        maxTokensBudget = 1536,
        promptInstruction = "Provide thorough, comprehensive, deeply explained responses with full context and examples."
    )
}

/**
 * Controls the character / personality of the AI assistant.
 */
enum class AiCharacterPersona(
    val displayName: String,
    val systemTraitInstruction: String
) {
    EMPATHETIC_COMPANION(
        displayName = "Warm & Empathetic",
        systemTraitInstruction = "You are warm, encouraging, and conversational, like a thoughtful close friend."
    ),
    SHARP_TECHNICAL(
        displayName = "Sharp & Analytical",
        systemTraitInstruction = "You are precise, technical, logical, and no-nonsense, focusing on accuracy and efficiency."
    ),
    WITTY_CREATIVE(
        displayName = "Witty & Creative",
        systemTraitInstruction = "You are imaginative, clever, playful, and expressive with a touch of dry humor."
    ),
    CALM_MINDFUL(
        displayName = "Calm & Mindful",
        systemTraitInstruction = "You speak with serene, grounded, peaceful clarity."
    )
}

/**
 * The 7 languages supported by Fossistant's Fast TTS & Voice Pipeline.
 */
/**
 * A speech language: every language Whisper understands (100) plus [AUTO], which detects the spoken language
 * on each utterance. Speaking uses whichever voice Android has installed for [locale].
 */
data class SupportedLanguage(
    /** Whisper's language code, or "auto". */
    val whisperCode: String,
    val englishName: String
) {
    val isAuto: Boolean get() = whisperCode == AUTO_CODE

    val locale: Locale
        get() = if (isAuto) Locale.getDefault() else Locale.forLanguageTag(ISO_OVERRIDES[whisperCode] ?: whisperCode)

    /** BCP-47 tag (e.g. "pl"); for [AUTO] the device language. */
    val code: String get() = locale.toLanguageTag()

    val displayName: String
        get() {
            if (isAuto) return "Automatic (detect language)"
            val native = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
            return if (native.isBlank() || native.equals(englishName, ignoreCase = true) || native == locale.language) {
                englishName
            } else {
                "$native ($englishName)"
            }
        }

    companion object {
        const val AUTO_CODE = "auto"

        // Whisper codes that differ from the ISO / BCP-47 tag Android's TTS expects.
        private val ISO_OVERRIDES = mapOf("no" to "nb")

        /**
         * The verified common denominator of languages supported robustly across all three pillars:
         * 1. Whisper Multilingual STT (low WER on Tiny/Small, no hallucinations)
         * 2. Android Text-To-Speech (verified native TTS voice availability)
         * 3. Google Gemma 4 E2B (high-accuracy instruction-following & tool calling)
         *
         * All 74 low-resource or unsupported languages (with >30-40% Whisper WER, missing TTS voices,
         * or unrepresented in virtual assistant training data) are dropped completely.
         */
        private val COMMON_DENOMINATOR_LANGUAGES = listOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "ja" to "Japanese",
            "pl" to "Polish",
            "pt" to "Portuguese",
            "nl" to "Dutch",
            "ru" to "Russian",
            "uk" to "Ukrainian",
            "zh" to "Chinese",
            "ko" to "Korean",
            "tr" to "Turkish",
            "sv" to "Swedish",
            "vi" to "Vietnamese",
            "id" to "Indonesian",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "el" to "Greek",
            "cs" to "Czech",
            "ro" to "Romanian",
            "da" to "Danish",
            "hu" to "Hungarian",
            "fi" to "Finnish",
            "no" to "Norwegian"
        )

        val AUTO = SupportedLanguage(AUTO_CODE, "Automatic")
        val ENGLISH = SupportedLanguage("en", "English")
        val SPANISH = SupportedLanguage("es", "Spanish")
        val FRENCH = SupportedLanguage("fr", "French")
        val GERMAN = SupportedLanguage("de", "German")
        val ITALIAN = SupportedLanguage("it", "Italian")
        val JAPANESE = SupportedLanguage("ja", "Japanese")
        val POLISH = SupportedLanguage("pl", "Polish")
        val PORTUGUESE = SupportedLanguage("pt", "Portuguese")
        val DUTCH = SupportedLanguage("nl", "Dutch")
        val RUSSIAN = SupportedLanguage("ru", "Russian")
        val UKRAINIAN = SupportedLanguage("uk", "Ukrainian")
        val CHINESE = SupportedLanguage("zh", "Chinese")
        val KOREAN = SupportedLanguage("ko", "Korean")
        val TURKISH = SupportedLanguage("tr", "Turkish")
        val SWEDISH = SupportedLanguage("sv", "Swedish")
        val VIETNAMESE = SupportedLanguage("vi", "Vietnamese")
        val INDONESIAN = SupportedLanguage("id", "Indonesian")
        val ARABIC = SupportedLanguage("ar", "Arabic")
        val HINDI = SupportedLanguage("hi", "Hindi")
        val GREEK = SupportedLanguage("el", "Greek")
        val CZECH = SupportedLanguage("cs", "Czech")
        val ROMANIAN = SupportedLanguage("ro", "Romanian")
        val DANISH = SupportedLanguage("da", "Danish")
        val HUNGARIAN = SupportedLanguage("hu", "Hungarian")
        val FINNISH = SupportedLanguage("fi", "Finnish")
        val NORWEGIAN = SupportedLanguage("no", "Norwegian")

        /** Automatic first, then the 26 verified common denominator languages sorted by English name. */
        val entries: List<SupportedLanguage> = listOf(AUTO) +
            COMMON_DENOMINATOR_LANGUAGES.map { (code, name) -> SupportedLanguage(code, name) }.sortedBy { it.englishName }

        /** Finds a language by Whisper code or BCP-47 tag ("pl", "pl-PL", "auto"); [AUTO] when unknown. */
        fun fromCode(code: String?): SupportedLanguage {
            val key = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
            if (key.isEmpty()) return AUTO
            val whisperKey = ISO_OVERRIDES.entries.firstOrNull { it.value == key }?.key ?: key
            return entries.firstOrNull { it.whisperCode == whisperKey } ?: AUTO
        }
    }
}

/**
 * A TTS voice style (speed, pitch) for a language; the actual voice is the one Android has installed.
 */
data class FastTtsVoiceProfile(
    val id: String,
    val name: String,
    val language: SupportedLanguage,
    val speechRate: Float,
    val pitch: Float,
    val styleDescription: String
)

/**
 * Persistent fact stored by the On-Device AI Memory System.
 */
@Serializable
data class MemoryFact(
    val id: String,
    val fact: String,
    val category: String = "USER_FACT",
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Hardware inference acceleration backend for Whisper STT and Gemma.
 */
@Serializable
enum class HardwareInferenceBackend(
    val displayName: String,
    val subtitle: String,
    val shortBadge: String
) {
    AUTO(
        displayName = "Auto (Best Available)",
        subtitle = "Prioritizes GPU / NPU with automatic CPU fallback",
        shortBadge = "AUTO"
    ),
    GPU(
        displayName = "GPU Acceleration",
        subtitle = "Vulkan / OpenCL compute shader acceleration",
        shortBadge = "GPU"
    ),
    NPU(
        displayName = "NPU Acceleration (Optional)",
        subtitle = "Android Neural Networks API (NNAPI) / Google Tensor TPU / Hexagon DSP",
        shortBadge = "NPU"
    ),
    CPU(
        displayName = "CPU Multi-Thread",
        subtitle = "ARM64-v8a NEON FP16 dot-product SIMD",
        shortBadge = "CPU 4-T"
    )
}

