#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include "stable-diffusion.h"

// NOTE: app/src/main/jniLibs/arm64-v8a/libsd_jni.so is prebuilt from this file; after editing it,
// rebuild with scripts/build-sd-jni.sh (stable-diffusion.cpp + Android NDK).
//
// ggml is linked statically with hidden symbols, so it never clashes with llama.cpp's libggml*.so.

#define TAG "sd_jni"

namespace {

struct SdHandle {
    sd_ctx_t* ctx = nullptr;
    bool tiny_vae = false;
};

std::mutex g_generate_mutex;
std::mutex g_log_mutex;
std::deque<std::string> g_log_lines;
JavaVM* g_vm = nullptr;

struct ProgressTarget {
    jobject listener = nullptr;
    jmethodID on_step = nullptr;
};
ProgressTarget g_progress;
std::atomic<sd_ctx_t*> g_active_ctx{nullptr};

void on_log(sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    int priority = level >= SD_LOG_ERROR ? ANDROID_LOG_ERROR : level >= SD_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    if (level >= SD_LOG_INFO) __android_log_print(priority, TAG, "%s", text);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_lines.emplace_back(text);
    while (g_log_lines.size() > 40) g_log_lines.pop_front();
}

void on_progress(int step, int steps, float, void*) {
    if (!g_vm || !g_progress.listener || !g_progress.on_step) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(g_progress.listener, g_progress.on_step, step, steps);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

std::string to_string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    sd_set_log_callback(on_log, nullptr);
    sd_set_progress_callback(on_progress, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossistant_assistant_engine_ImageGenEngine_nativeLoad(
    JNIEnv* env, jobject, jstring modelPath, jstring taesdPath, jint nThreads) {
    std::string model = to_string(env, modelPath);
    std::string taesd = to_string(env, taesdPath);
    if (model.empty()) return 0L;

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = model.c_str();
    params.taesd_path = taesd.empty() ? nullptr : taesd.c_str();
    params.n_threads = nThreads > 0 ? nThreads : sd_get_num_physical_cores();
    // Weights stay in the page cache instead of a second copy in the heap.
    params.enable_mmap = true;
    // Smaller attention buffers in the UNet: lower peak RAM on 8 GB phones.
    params.diffusion_flash_attn = true;

    sd_ctx_t* ctx = new_sd_ctx(&params);
    if (!ctx) return 0L;
    auto* handle = new SdHandle();
    handle->ctx = ctx;
    handle->tiny_vae = !taesd.empty();
    return reinterpret_cast<jlong>(handle);
}

// Returns width * height * 3 RGB bytes, or null on failure or cancel. initRgb/maskGray are optional
// (img2img and inpainting) and must already be width x height.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_fossistant_assistant_engine_ImageGenEngine_nativeGenerate(
    JNIEnv* env, jobject, jlong handlePtr,
    jstring prompt, jstring negativePrompt, jint width, jint height,
    jint steps, jfloat cfg, jlong seed, jstring sampler, jstring scheduler,
    jstring loraPath, jfloat loraStrength,
    jbyteArray initRgb, jfloat strength, jbyteArray maskGray,
    jobject listener) {
    auto* handle = reinterpret_cast<SdHandle*>(handlePtr);
    if (!handle || !handle->ctx) return nullptr;
    std::lock_guard<std::mutex> lock(g_generate_mutex);

    std::string prompt_s = to_string(env, prompt);
    std::string negative_s = to_string(env, negativePrompt);
    std::string sampler_s = to_string(env, sampler);
    std::string scheduler_s = to_string(env, scheduler);
    std::string lora_s = to_string(env, loraPath);

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = prompt_s.c_str();
    params.negative_prompt = negative_s.c_str();
    params.width = width;
    params.height = height;
    params.seed = seed;
    params.batch_count = 1;
    params.strength = strength;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfg;
    if (!sampler_s.empty()) params.sample_params.sample_method = str_to_sample_method(sampler_s.c_str());
    if (!scheduler_s.empty()) params.sample_params.scheduler = str_to_scheduler(scheduler_s.c_str());
    // The full VAE decodes 512x512 in tiles to keep peak RAM down; TAESD is small enough not to need it.
    params.vae_tiling_params.enabled = !handle->tiny_vae;

    sd_lora_t lora{};
    if (!lora_s.empty()) {
        lora.is_high_noise = false;
        lora.multiplier = loraStrength;
        lora.path = lora_s.c_str();
        params.loras = &lora;
        params.lora_count = 1;
    }

    std::vector<uint8_t> init_pixels;
    std::vector<uint8_t> mask_pixels;
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (initRgb && static_cast<size_t>(env->GetArrayLength(initRgb)) == pixels * 3) {
        init_pixels.resize(pixels * 3);
        env->GetByteArrayRegion(initRgb, 0, static_cast<jsize>(pixels * 3), reinterpret_cast<jbyte*>(init_pixels.data()));
        params.init_image = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 3, init_pixels.data()};
        if (maskGray && static_cast<size_t>(env->GetArrayLength(maskGray)) == pixels) {
            mask_pixels.resize(pixels);
            env->GetByteArrayRegion(maskGray, 0, static_cast<jsize>(pixels), reinterpret_cast<jbyte*>(mask_pixels.data()));
            params.mask_image = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1, mask_pixels.data()};
        }
    }

    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        g_progress.on_step = env->GetMethodID(cls, "onStep", "(II)V");
        g_progress.listener = env->NewGlobalRef(listener);
        env->DeleteLocalRef(cls);
    }
    sd_cancel_generation(handle->ctx, SD_CANCEL_RESET);
    g_active_ctx = handle->ctx;

    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(handle->ctx, &params, &result);

    g_active_ctx = nullptr;
    if (g_progress.listener) env->DeleteGlobalRef(g_progress.listener);
    g_progress = {};

    jbyteArray out = nullptr;
    if (ok && result && result->count > 0) {
        const sd_image_t& image = result->items[0].image;
        if (image.data && image.channel == 3) {
            const jsize size = static_cast<jsize>(image.width * image.height * 3);
            out = env->NewByteArray(size);
            if (out) env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte*>(image.data));
        }
    }
    if (result) free_sd_image_generation_result(result);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_ImageGenEngine_nativeCancel(JNIEnv*, jobject) {
    sd_ctx_t* ctx = g_active_ctx.load();
    if (ctx) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_ImageGenEngine_nativeFree(JNIEnv*, jobject, jlong handlePtr) {
    auto* handle = reinterpret_cast<SdHandle*>(handlePtr);
    if (!handle) return;
    std::lock_guard<std::mutex> lock(g_generate_mutex);
    if (handle->ctx) free_sd_ctx(handle->ctx);
    delete handle;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_ImageGenEngine_nativeRecentLog(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string joined;
    for (const auto& line : g_log_lines) {
        joined += line;
        if (joined.empty() || joined.back() != '\n') joined += '\n';
    }
    return env->NewStringUTF(joined.c_str());
}
