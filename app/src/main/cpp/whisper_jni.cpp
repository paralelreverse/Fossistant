#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <thread>
#include "whisper.h"

// NOTE: app/src/main/jniLibs/arm64-v8a/libwhisper_jni.so is prebuilt from this file; after editing it,
// rebuild with scripts/build-whisper-jni.sh (whisper.cpp + Android NDK).

static std::mutex g_whisper_mutex;

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossistant_assistant_engine_WhisperMultilingualSttEngine_nativeInitWhisper(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelPath
) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (!modelPath) return 0L;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0L;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // built CPU-only (no Vulkan/OpenCL backend in libwhisper_jni.so)
    cparams.flash_attn = true; // fused attention kernels: faster encoder/decoder on CPU
    struct whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_WhisperMultilingualSttEngine_nativeTranscribePcm16(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray pcmSamples,
    jint sampleCount,
    jstring langCode
) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);
    if (!ctx || !pcmSamples || sampleCount <= 0) {
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(pcmSamples);
    int count = (sampleCount < len) ? sampleCount : static_cast<int>(len);
    std::vector<float> pcmf32(count);

    jshort* rawShorts = env->GetShortArrayElements(pcmSamples, nullptr);
    if (!rawShorts) {
        return env->NewStringUTF("");
    }
    for (int i = 0; i < count; ++i) {
        pcmf32[i] = static_cast<float>(rawShorts[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcmSamples, rawShorts, JNI_ABORT);

    const char* langStr = langCode ? env->GetStringUTFChars(langCode, nullptr) : "en";

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned hw = std::thread::hardware_concurrency();
    wparams.n_threads = static_cast<int>(hw >= 4 ? 4 : (hw > 0 ? hw : 2));
    wparams.no_context = true;
    wparams.single_segment = true;
    wparams.no_timestamps = true;   // voice commands never need timestamps; skips timestamp token decoding
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;    // suppress non-speech tokens ([Music], (sighs), ...) at decode time
    wparams.temperature_inc = 0.0f; // no temperature-fallback re-decodes: predictable latency for live voice
    wparams.language = (langStr && strlen(langStr) > 0) ? langStr : "en";

    // Size the encoder window to the utterance instead of always encoding a padded 30s window
    // (1500 frames = 30s, i.e. 320 samples per frame). Short commands still decode 2-3x faster. Smaller
    // windows (256-512) made whisper-tiny repeat short clips ("And so my fellow Americans!" x2-3), hence
    // the 768 floor, measured on whisper.cpp v1.9.5 with the bundled tiny and small-q5_1 models.
    const int needed_ctx = count / 320 + 192;
    wparams.audio_ctx = needed_ctx < 768 ? 768 : (needed_ctx > 1500 ? 0 : needed_ctx);
    // Cap output length to what the clip can plausibly contain so a repetition loop cannot run away.
    wparams.max_tokens = 24 + (count / 16000 + 1) * 10;

    int rc = whisper_full(ctx, wparams, pcmf32.data(), count);
    if (langCode && langStr) {
        env->ReleaseStringUTFChars(langCode, langStr);
    }

    if (rc != 0) {
        return env->NewStringUTF("");
    }

    // Result format: "<language code>\t<transcript>". With langCode "auto" whisper detects the spoken
    // language (99 supported); otherwise the requested language is echoed back.
    const int langId = whisper_full_lang_id(ctx);
    const char* detected = langId >= 0 ? whisper_lang_str(langId) : nullptr;
    std::string result = detected ? detected : "";
    result += '\t';
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            result += text;
        }
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_WhisperMultilingualSttEngine_nativeFreeWhisper(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);
    if (ctx) {
        whisper_free(ctx);
    }
}
