// JNI bridge for llama.cpp (GGUF models such as PrismML Bonsai 2) used by
// org.fossistant.assistant.engine.LlamaCppEngine.
//
// app/src/main/jniLibs/arm64-v8a/libllama_jni.so is prebuilt from this file; rebuild it with
// scripts/build-llama-jni.sh after editing (llama.cpp + Vulkan headers + Android NDK).
//
// One session = one model + one context. The tokens currently in the KV cache are tracked so a new prompt
// that extends the previous conversation only prefills the new suffix.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdio>
#include <deque>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::vector<llama_token> cached;  // tokens currently in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    std::mutex mutex;                 // one generation at a time
    std::string backend;
    int n_ctx = 0;
    int n_batch = 512;
    // Prompt tokens per llama_decode call. Mali (Vulkan) prefills fastest in 8-token calls (~3x faster than 32 on a
    // Mali-G715), and short GPU jobs leave room for the phone's UI to draw.
    int prefill_chunk = 512;
    // State right after the system prompt (tools included), restored when the conversation changes further on.
    // Hybrid models (Bonsai 2 / Qwen3.5: attention + recurrent layers) can't roll their recurrent state back, so
    // without this every edit of the history would re-read the whole ~2.3K-token system prompt.
    std::vector<llama_token> snap_tokens;
    std::vector<uint8_t> snap_state;
    // The same state saved to disk, so the system prompt is only ever processed once per model (CPU prefill of
    // ~1-2K tokens takes a minute on a phone).
    std::string cache_dir;
    std::string model_key;
};

/** FNV-1a over token ids, for naming the on-disk prompt state. */
uint64_t hash_tokens(const llama_token* tokens, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; ++i) {
        h ^= static_cast<uint32_t>(tokens[i]);
        h *= 1099511628211ULL;
    }
    return h;
}

std::string state_file_path(const Session* session, const llama_token* tokens, size_t n) {
    char name[64];
    std::snprintf(name, sizeof(name), "_%016llx.state", static_cast<unsigned long long>(hash_tokens(tokens, n)));
    return session->cache_dir + "/" + session->model_key + name;
}

std::once_flag g_backend_once;

// Recent warnings and backend (Vulkan) messages, read by Kotlin into the app log: logcat isn't in user reports.
std::mutex g_log_mutex;
std::deque<std::string> g_recent_log;

bool mentions_backend(const char* text) {
    std::string lower(text);
    for (auto& c : lower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return lower.find("vulkan") != std::string::npos || lower.find("ggml_vk") != std::string::npos ||
           lower.find("offload") != std::string::npos;
}

void log_callback(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
    if (level >= GGML_LOG_LEVEL_WARN || mentions_backend(text)) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_recent_log.emplace_back(text);
        while (g_recent_log.size() > 60) g_recent_log.pop_front();
    }
}

/** Index of the first token after the system turn (the second <|im_start|>), or 0 if there is none. */
size_t system_prefix_end(const llama_vocab* vocab, const std::vector<llama_token>& tokens) {
    llama_token im_start = -1;
    llama_token tmp[4];
    if (llama_tokenize(vocab, "<|im_start|>", 12, tmp, 4, false, true) == 1) im_start = tmp[0];
    if (im_start < 0) return 0;
    int seen = 0;
    for (size_t i = 0; i < tokens.size(); ++i) {
        if (tokens[i] == im_start && ++seen == 2) return i;
    }
    return 0;
}

std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Java's modified UTF-8 can't hold 4-byte characters, so text is passed as bytes and decoded in Kotlin.
// Pieces may end mid-character; this returns how many leading bytes form complete UTF-8 characters.
size_t complete_utf8_prefix(const std::string& bytes) {
    size_t i = bytes.size();
    // walk back over at most 3 continuation bytes to the lead byte of the last character
    size_t back = 0;
    while (i > 0 && back < 4 && (static_cast<unsigned char>(bytes[i - 1]) & 0xC0) == 0x80) {
        --i;
        ++back;
    }
    if (i == 0) return bytes.size();
    const unsigned char lead = static_cast<unsigned char>(bytes[i - 1]);
    size_t need = 1;
    if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    const size_t have = back + 1;
    return have >= need ? bytes.size() : i - 1;
}

std::string describe_gpu_devices() {
    std::string names;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        // Phone GPUs (Mali, Adreno) register as integrated GPUs.
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            if (!names.empty()) names += ", ";
            names += ggml_backend_dev_description(dev);
        }
    }
    return names;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jstring jpath, jint n_ctx, jint n_gpu_layers, jint n_threads) {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();  // backends (CPU, Vulkan) are linked statically and register themselves
    });
    const std::string path = jstring_to_std(env, jpath);

    auto* session = new Session();
    for (int attempt = 0; attempt < 2 && !session->model; ++attempt) {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = attempt == 0 ? n_gpu_layers : 0;  // retry on CPU if the GPU load fails
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        // CPU mode: no offload devices at all, so nothing (KV cache, big batches) quietly lands on the GPU, whose
        // compute work on Mali starves the phone's UI.
        static ggml_backend_dev_t no_devices[] = { nullptr };
        if (mparams.n_gpu_layers == 0) mparams.devices = no_devices;
        session->model = llama_model_load_from_file(path.c_str(), mparams);
        if (session->model) {
            const std::string gpus = describe_gpu_devices();
            session->backend = (mparams.n_gpu_layers != 0 && !gpus.empty()) ? "Vulkan GPU (" + gpus + ")" : "CPU";
        } else if (attempt == 0 && n_gpu_layers != 0) {
            LOGW("GPU model load failed, retrying on CPU");
        }
    }
    if (!session->model) {
        delete session;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = session->n_batch;
    cparams.n_ubatch = session->n_batch;
    const unsigned hw = std::thread::hardware_concurrency();
    const int threads = n_threads > 0 ? n_threads : static_cast<int>(hw >= 4 ? 4 : (hw > 0 ? hw : 2));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    if (session->backend == "CPU") {
        cparams.offload_kqv = false;
        cparams.op_offload = false;
    } else {
        // Vulkan: 8-token micro-batches everywhere (also inside llama_decode) and a compute buffer sized for them
        // instead of 512 tokens (~500 MB less GPU memory on a 2B model).
        session->prefill_chunk = 8;
        cparams.n_ubatch = 8;
    }
    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        llama_model_free(session->model);
        delete session;
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);
    session->model_key = std::to_string(std::hash<std::string>{}(path));
    session->n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    LOGI("Loaded %s on %s (ctx %d, threads %d)", path.c_str(), session->backend.c_str(), session->n_ctx, threads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeBackend(JNIEnv* env, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return env->NewStringUTF(session ? session->backend.c_str() : "");
}

extern "C" JNIEXPORT jint JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeTokenCount(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return session ? static_cast<jint>(session->cached.size()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeContextSize(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return session ? session->n_ctx : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session) session->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->cached.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return;
    session->cancel = true;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        llama_free(session->ctx);
        llama_model_free(session->model);
    }
    delete session;
}

// Generates a reply for the full prompt text (chat template already applied by Kotlin). Streams UTF-8 bytes to
// callback.onBytes(byte[]): Boolean (false = stop). Returns "stop", "length", "cancelled", or "error: ...".
extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jobject callback) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return env->NewStringUTF("error: no model loaded");
    std::lock_guard<std::mutex> lock(session->mutex);
    session->cancel = false;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([B)Z");
    if (!onBytes) return env->NewStringUTF("error: callback has no onBytes([B)Z");

    // Tokenize the whole prompt
    const std::string prompt = jstring_to_std(env, jprompt);
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n <= 0) return env->NewStringUTF("error: could not tokenize the prompt");
    tokens.resize(n);
    if (n + max_tokens > session->n_ctx) {
        return env->NewStringUTF("error: context full");
    }

    // Reuse the KV cache for the longest common prefix; always re-decode at least one token for fresh logits.
    llama_memory_t mem = llama_get_memory(session->ctx);
    size_t prefix = 0;
    while (prefix < session->cached.size() && prefix < tokens.size() && session->cached[prefix] == tokens[prefix]) ++prefix;
    if (prefix == tokens.size()) --prefix;
    const size_t sys_end = system_prefix_end(session->vocab, tokens);
    const bool snapshot_matches = sys_end > 0 && session->snap_tokens.size() == sys_end && !session->snap_state.empty() &&
        std::equal(session->snap_tokens.begin(), session->snap_tokens.end(), tokens.begin());
    // Loads the system-prompt state saved on disk by an earlier session (after an app restart or model reload).
    auto restore_from_disk = [&]() -> bool {
        if (sys_end == 0 || sys_end >= tokens.size() || session->cache_dir.empty()) return false;
        const std::string file = state_file_path(session, tokens.data(), sys_end);
        if (!std::filesystem::exists(file)) return false;
        std::vector<llama_token> loaded(sys_end);
        size_t n_loaded = 0;
        llama_memory_clear(mem, true);
        if (llama_state_seq_load_file(session->ctx, file.c_str(), 0, loaded.data(), loaded.size(), &n_loaded) == 0 ||
            n_loaded != sys_end || !std::equal(loaded.begin(), loaded.end(), tokens.begin())) {
            llama_memory_clear(mem, true);
            return false;
        }
        session->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(sys_end));
        const size_t size = llama_state_seq_get_size(session->ctx, 0);
        session->snap_state.resize(size);
        if (llama_state_seq_get_data(session->ctx, session->snap_state.data(), size, 0) == size) {
            session->snap_tokens = session->cached;
        } else {
            session->snap_state.clear();
        }
        LOGI("Loaded the saved system prompt state (%zu tokens)", sys_end);
        return true;
    };
    if (session->cached.empty() && prefix == 0 && !snapshot_matches && restore_from_disk()) {
        prefix = sys_end;
    }

    if (prefix < session->cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(prefix), -1)) {
            // Recurrent layers can't be truncated: start over, from the saved system-prompt state if possible.
            llama_memory_clear(mem, true);
            prefix = 0;
            if (snapshot_matches && sys_end < tokens.size() &&
                llama_state_seq_set_data(session->ctx, session->snap_state.data(), session->snap_state.size(), 0) ==
                    session->snap_state.size()) {
                prefix = sys_end;
                session->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(sys_end));
                LOGI("Restored the system prompt state (%zu tokens)", sys_end);
            } else if (restore_from_disk()) {
                prefix = sys_end;
            }
        }
        session->cached.resize(prefix);
    }

    // Prefill the new part in batches; one batch ends exactly at the end of the system prompt so its state can be
    // saved for later turns.
    for (size_t i = prefix; i < tokens.size();) {
        if (session->cancel) return env->NewStringUTF("cancelled");
        size_t count = std::min<size_t>(session->prefill_chunk, tokens.size() - i);
        if (sys_end > i && sys_end < i + count) count = sys_end - i;
        llama_batch batch = llama_batch_get_one(tokens.data() + i, static_cast<int>(count));
        if (llama_decode(session->ctx, batch) != 0) {
            llama_memory_clear(mem, true);
            session->cached.clear();
            return env->NewStringUTF("error: prefill failed");
        }
        session->cached.insert(session->cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(i + count));
        i += count;
        if (i == sys_end && !snapshot_matches) {
            const size_t size = llama_state_seq_get_size(session->ctx, 0);
            session->snap_state.resize(size);
            if (llama_state_seq_get_data(session->ctx, session->snap_state.data(), size, 0) == size) {
                session->snap_tokens.assign(tokens.begin(), tokens.begin() + static_cast<long>(sys_end));
                LOGI("Saved the system prompt state (%zu tokens, %zu KB)", sys_end, size / 1024);
                if (!session->cache_dir.empty()) {
                    // Keep this model's two most recent other prompt versions (e.g. voice and text mode), drop older ones.
                    std::error_code ec;
                    std::vector<std::filesystem::directory_entry> mine;
                    for (const auto& entry : std::filesystem::directory_iterator(session->cache_dir, ec)) {
                        if (entry.path().filename().string().rfind(session->model_key + "_", 0) == 0) mine.push_back(entry);
                    }
                    std::sort(mine.begin(), mine.end(), [](const auto& a, const auto& b) {
                        return a.last_write_time() > b.last_write_time();
                    });
                    for (size_t k = 2; k < mine.size(); ++k) std::filesystem::remove(mine[k].path(), ec);
                    const std::string file = state_file_path(session, tokens.data(), sys_end);
                    llama_state_seq_save_file(session->ctx, file.c_str(), 0, tokens.data(), sys_end);
                }
            } else {
                session->snap_state.clear();
                session->snap_tokens.clear();
            }
        }
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(session->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending;
    const char* status = "length";
    char piece[256];
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (session->cancel) { status = "cancelled"; break; }
        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) { status = "stop"; break; }
        const int len = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, true);
        if (len > 0) pending.append(piece, len);

        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(ready));
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(ready), reinterpret_cast<const jbyte*>(pending.data()));
            const jboolean keepGoing = env->CallBooleanMethod(callback, onBytes, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !keepGoing) { status = "cancelled"; break; }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(session->ctx, batch) != 0) { status = "error: decode failed"; break; }
        session->cached.push_back(token);
        if (static_cast<int>(session->cached.size()) >= session->n_ctx - 1) { status = "length"; break; }
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(status);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeCountTokens(JNIEnv* env, jobject, jlong handle, jstring jtext) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return -1;
    const std::string text = jstring_to_std(env, jtext);
    const int n = -llama_tokenize(session->vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeRecentLog(JNIEnv* env, jobject) {
    std::string joined;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (const auto& line : g_recent_log) joined += line;
        g_recent_log.clear();
    }
    return env->NewStringUTF(joined.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeSetCacheDir(JNIEnv* env, jobject, jlong handle, jstring jdir) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return;
    session->cache_dir = jstring_to_std(env, jdir);
    std::error_code ec;
    std::filesystem::create_directories(session->cache_dir, ec);
}

// Answers a fixed one-word question greedily and returns the text, so the app can check that this backend computes
// correctly (phone GPU drivers can silently give wrong results). The prompt is longer than one micro-batch, so it
// exercises the batched prompt path. The KV cache is cleared afterwards.
extern "C" JNIEXPORT jstring JNICALL
Java_org_fossistant_assistant_engine_LlamaCppEngine_nativeProbe(JNIEnv* env, jobject, jlong handle, jstring jprompt) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(session->mutex);
    const std::string prompt = jstring_to_std(env, jprompt);
    std::vector<llama_token> tokens(prompt.size() + 16);
    const int n = llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                 tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n <= 0) return env->NewStringUTF("");
    tokens.resize(n);
    llama_memory_t mem = llama_get_memory(session->ctx);
    llama_memory_clear(mem, true);
    session->cached.clear();
    std::string out;
    bool ok = true;
    for (size_t i = 0; i < tokens.size() && ok;) {
        const size_t count = std::min<size_t>(session->prefill_chunk, tokens.size() - i);
        ok = llama_decode(session->ctx, llama_batch_get_one(tokens.data() + i, static_cast<int>(count))) == 0;
        i += count;
    }
    llama_sampler* greedy = llama_sampler_init_greedy();
    char piece[256];
    for (int k = 0; k < 6 && ok; ++k) {
        llama_token token = llama_sampler_sample(greedy, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        const int len = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        ok = llama_decode(session->ctx, llama_batch_get_one(&token, 1)) == 0;
    }
    llama_sampler_free(greedy);
    llama_memory_clear(mem, true);
    session->cached.clear();
    // Keep only ASCII so a garbage result can't break Java's modified UTF-8.
    std::string ascii;
    for (char c : out) if (static_cast<unsigned char>(c) < 0x80) ascii += c;
    return env->NewStringUTF(ok ? ascii.c_str() : "error: decode failed");
}
