#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <string>
#include <vector>
#include <cstring>
#include <chrono>
#include <algorithm>
#include <cctype>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaHolder {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

// Phase 5: a snapshot of a llama context's seq-0 KV state after decoding
// some fixed prefix (system prompt + ChatML user-tag opener). Reused across
// polish() calls so each invocation only prefills the user's transcript +
// suffix, not the 70–90 tokens of constant boilerplate.
struct PrefixCache {
    std::vector<uint8_t> blob;
    int32_t tokenCount = 0;
};

bool gBackendInitialized = false;

long long nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool addSpecial, bool parseSpecial) {
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               nullptr, 0, addSpecial, parseSpecial);
    if (n >= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(-n));
    int32_t got = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                 tokens.data(), static_cast<int32_t>(tokens.size()),
                                 addSpecial, parseSpecial);
    if (got < 0) tokens.clear();
    else tokens.resize(static_cast<size_t>(got));
    return tokens;
}

std::string tokenToString(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::vector<char> bigger(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, bigger.data(),
                                 static_cast<int32_t>(bigger.size()), 0, false);
        if (n <= 0) return "";
        return std::string(bigger.data(), static_cast<size_t>(n));
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::string trimLower(std::string value) {
    auto start = value.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    auto end = value.find_last_not_of(" \t\r\n");
    value = value.substr(start, end - start + 1);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool mapBackendNameToRegistry(const std::string &backend, const char *&outRegName) {
    if (backend.empty()) {
        return false;
    }
    if (backend == "vulkan") {
        outRegName = "Vulkan";
        return true;
    }
    if (backend == "opencl") {
        outRegName = "OpenCL";
        return true;
    }
    return false;
}

bool findDeviceForBackend(const char *regName, ggml_backend_dev_t &outDevice) {
    outDevice = nullptr;
    ggml_backend_reg_t reg = ggml_backend_reg_by_name(regName);
    if (reg == nullptr) {
        return false;
    }

    const size_t devCount = ggml_backend_reg_dev_count(reg);
    for (size_t i = 0; i < devCount; ++i) {
        ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, i);
        if (dev == nullptr) {
            continue;
        }
        const auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_CPU) {
            outDevice = dev;
            return true;
        }
    }
    return false;
}

std::string jStringToUtf8(JNIEnv *env, jstring jValue) {
    if (env == nullptr || jValue == nullptr) return "";
    const char *raw = env->GetStringUTFChars(jValue, nullptr);
    if (raw == nullptr) return "";
    std::string value(raw);
    env->ReleaseStringUTFChars(jValue, raw);
    return value;
}

std::string sanitizeDeviceField(const char *value) {
    std::string out = value == nullptr ? "" : value;
    for (char &ch : out) {
        if (ch == '\t' || ch == '\n' || ch == '\r') {
            ch = ' ';
        }
    }
    return out.empty() ? "unknown" : out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeInit(JNIEnv *env, jobject /*thiz*/,
                                                jstring jModelPath, jint ctxSize,
                                                jint nGpuLayers, jstring jBackendName) {
    if (!gBackendInitialized) {
        llama_backend_init();
        gBackendInitialized = true;
    }
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    ggml_backend_dev_t userBackendDev = nullptr;
    ggml_backend_dev_t selectedDevices[2] = {nullptr, nullptr};
    const std::string backend = trimLower(jStringToUtf8(env, jBackendName));

    if (!backend.empty() && backend != "cpu") {
        if (backend == "vulkan" || backend == "opencl") {
            const char *regName = nullptr;
            if (!mapBackendNameToRegistry(backend, regName)) {
                LOGE("backend=%s mapping failed", backend.c_str());
                env->ReleaseStringUTFChars(jModelPath, modelPath);
                return 0;
            }

            // force backend auto registration for explicit backend mode
            ggml_backend_load_all();
            if (!findDeviceForBackend(regName, userBackendDev)) {
                LOGE("backend %s requested but no suitable %s device found", backend.c_str(), regName);
                env->ReleaseStringUTFChars(jModelPath, modelPath);
                return 0;
            }

            selectedDevices[0] = userBackendDev;
            mparams.devices = selectedDevices;
        } else {
            LOGE("unknown backend=%s", backend.c_str());
            env->ReleaseStringUTFChars(jModelPath, modelPath);
            return 0;
        }
    } else {
        mparams.n_gpu_layers = 0;
    }

    llama_model *model = llama_model_load_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(ctxSize);
    // Phase 1: bigger batch/ubatch so the ~100-token system+user prefill
    // fits in a single decode call instead of being chunked.
    cparams.n_batch = 256;
    cparams.n_ubatch = 64;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto *holder = new LlamaHolder{model, ctx, llama_model_get_vocab(model)};
    LOGI("Llama context initialized (ctx=%d, batch=%u, ubatch=%u, threads=%d/%d)",
         ctxSize, cparams.n_batch, cparams.n_ubatch, cparams.n_threads, cparams.n_threads_batch);
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jboolean JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeSupportsGpuOffload(JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(llama_supports_gpu_offload());
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeListBackendDevices(JNIEnv *env, jobject /*thiz*/) {
    ggml_backend_load_all();
    const size_t total = ggml_backend_dev_count();
    std::string out;
    for (size_t i = 0; i < total; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) {
            continue;
        }
        const std::string name = sanitizeDeviceField(ggml_backend_dev_name(dev));
        const std::string desc = sanitizeDeviceField(ggml_backend_dev_description(dev));
        const auto type = ggml_backend_dev_type(dev);
        const char *typeName = type == GGML_BACKEND_DEVICE_TYPE_CPU
                                   ? "cpu"
                                   : (type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "igpu" : "gpu");
        if (!out.empty()) {
            out += "\n";
        }
        out += name;
        out += "\t";
        out += desc;
        out += "\t";
        out += typeName;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeGenerate(JNIEnv *env, jobject /*thiz*/,
                                                    jlong handle, jstring jPrompt,
                                                    jint maxNewTokens, jfloat temperature,
                                                    jfloat topP) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");

    const long long totalStartMs = nowMs();
    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);
    LOGI("nativeGenerate start promptBytes=%zu maxNewTokens=%d temp=%.2f topP=%.2f",
         prompt.size(), maxNewTokens, temperature, topP);

    const long long tokenizeStartMs = nowMs();
    auto tokens = tokenize(h->vocab, prompt, /*addSpecial=*/true, /*parseSpecial=*/true);
    if (tokens.empty()) {
        LOGE("tokenize returned empty");
        return env->NewStringUTF("");
    }
    LOGI("nativeGenerate tokenized tokens=%zu durMs=%lld",
         tokens.size(), nowMs() - tokenizeStartMs);

    // Fresh KV state for each call.
    llama_memory_t mem = llama_get_memory(h->ctx);
    llama_memory_clear(mem, /*data=*/true);

    // Feed prompt in chunks of n_batch. Per-chunk timing is aggregated and
    // logged once after the whole prefill completes.
    const int batchSize = 64;
    int pos = 0;
    int chunkCount = 0;
    const long long promptDecodeStartMs = nowMs();
    while (pos < (int)tokens.size()) {
        const int chunk = std::min(batchSize, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(&tokens[pos], chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("decode failed at pos=%d", pos);
            return env->NewStringUTF("");
        }
        pos += chunk;
        ++chunkCount;
    }
    LOGI("nativeGenerate prompt_decode_all_done tokens=%zu chunks=%d durMs=%lld",
         tokens.size(), chunkCount, nowMs() - promptDecodeStartMs);

    // Build sampler chain.
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    if (topP < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    std::string out;
    out.reserve(static_cast<size_t>(maxNewTokens) * 4);

    const long long prefillMs = nowMs() - promptDecodeStartMs;

    const long long generateStartMs = nowMs();
    long long firstTokenMs = -1;
    int generatedTokens = 0;
    bool eogReached = false;
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token id = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) {
            eogReached = true;
            break;
        }

        if (firstTokenMs < 0) firstTokenMs = nowMs() - generateStartMs;
        out += tokenToString(h->vocab, id);
        ++generatedTokens;

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, one) != 0) {
            LOGE("decode failed during generation step=%d", i);
            break;
        }
    }
    const long long decodeMs = nowMs() - generateStartMs;
    LOGI("nativeGenerate done outBytes=%zu tokens=%d eog=%d prefillMs=%lld firstTokenMs=%lld decodeMs=%lld totalDurMs=%lld",
         out.size(), generatedTokens, eogReached ? 1 : 0,
         prefillMs, firstTokenMs, decodeMs, nowMs() - totalStartMs);

    llama_sampler_free(sampler);

    // Piggyback structured metric on the returned string: a NUL-byte-delimited
    // header followed by the actual generated text. PolishEngine strips it
    // before returning to callers and surfaces the metric in pipeline_summary.
    char header[128];
    int headerLen = snprintf(header, sizeof(header),
                             "\x01prefill_ms=%lld,decode_ms=%lld,first_token_ms=%lld\x01",
                             prefillMs, decodeMs, firstTokenMs);
    std::string combined;
    combined.reserve(static_cast<size_t>(headerLen) + out.size());
    combined.append(header, static_cast<size_t>(headerLen));
    combined.append(out);
    return env->NewStringUTF(combined.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeGenerateStreaming(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jPrompt,
        jint maxNewTokens, jfloat temperature, jfloat topP,
        jobject sink) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");
    if (sink == nullptr) return env->NewStringUTF("");

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onTokenMid = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(sinkCls);
    if (onTokenMid == nullptr) {
        LOGE("TokenSink.onToken(Ljava/lang/String;)Z not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }

    const long long totalStartMs = nowMs();
    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);
    LOGI("nativeGenerateStreaming start promptBytes=%zu maxNewTokens=%d temp=%.2f topP=%.2f",
         prompt.size(), maxNewTokens, temperature, topP);

    auto tokens = tokenize(h->vocab, prompt, /*addSpecial=*/true, /*parseSpecial=*/true);
    if (tokens.empty()) {
        LOGE("tokenize returned empty");
        return env->NewStringUTF("");
    }

    llama_memory_t mem = llama_get_memory(h->ctx);
    llama_memory_clear(mem, /*data=*/true);

    const int batchSize = 64;
    int pos = 0;
    int chunkCount = 0;
    const long long promptDecodeStartMs = nowMs();
    while (pos < (int)tokens.size()) {
        const int chunk = std::min(batchSize, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(&tokens[pos], chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("decode failed at pos=%d", pos);
            return env->NewStringUTF("");
        }
        pos += chunk;
        ++chunkCount;
    }
    const long long prefillMs = nowMs() - promptDecodeStartMs;
    LOGI("nativeGenerateStreaming prefill_done tokens=%zu chunks=%d durMs=%lld",
         tokens.size(), chunkCount, prefillMs);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    if (topP < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    std::string out;
    out.reserve(static_cast<size_t>(maxNewTokens) * 4);

    const long long generateStartMs = nowMs();
    long long firstTokenMs = -1;
    int generatedTokens = 0;
    bool eogReached = false;
    bool cancelled = false;
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token id = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) {
            eogReached = true;
            break;
        }

        if (firstTokenMs < 0) firstTokenMs = nowMs() - generateStartMs;
        std::string piece = tokenToString(h->vocab, id);
        out += piece;
        ++generatedTokens;

        jstring jPiece = env->NewStringUTF(piece.c_str());
        jboolean keepGoing = env->CallBooleanMethod(sink, onTokenMid, jPiece);
        env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            LOGE("TokenSink.onToken threw");
            env->ExceptionDescribe();
            env->ExceptionClear();
            cancelled = true;
            break;
        }
        if (!keepGoing) {
            cancelled = true;
            break;
        }

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, one) != 0) {
            LOGE("decode failed during generation step=%d", i);
            break;
        }
    }
    const long long decodeMs = nowMs() - generateStartMs;
    LOGI("nativeGenerateStreaming done outBytes=%zu tokens=%d eog=%d cancelled=%d "
         "prefillMs=%lld firstTokenMs=%lld decodeMs=%lld totalDurMs=%lld",
         out.size(), generatedTokens, eogReached ? 1 : 0, cancelled ? 1 : 0,
         prefillMs, firstTokenMs, decodeMs, nowMs() - totalStartMs);

    llama_sampler_free(sampler);

    char header[160];
    int headerLen = snprintf(header, sizeof(header),
                             "\x01prefill_ms=%lld,decode_ms=%lld,first_token_ms=%lld\x01",
                             prefillMs, decodeMs, firstTokenMs);
    std::string combined;
    combined.reserve(static_cast<size_t>(headerLen) + out.size());
    combined.append(header, static_cast<size_t>(headerLen));
    combined.append(out);
    return env->NewStringUTF(combined.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativePrewarmPrefix(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jPrefix) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    if (h == nullptr || h->ctx == nullptr) return 0;

    const char *cPrefix = env->GetStringUTFChars(jPrefix, nullptr);
    std::string prefix(cPrefix);
    env->ReleaseStringUTFChars(jPrefix, cPrefix);

    auto tokens = tokenize(h->vocab, prefix, /*addSpecial=*/true, /*parseSpecial=*/true);
    if (tokens.empty()) {
        LOGE("nativePrewarmPrefix tokenize empty");
        return 0;
    }

    const long long t0 = nowMs();
    llama_memory_t mem = llama_get_memory(h->ctx);
    llama_memory_clear(mem, /*data=*/true);

    int pos = 0;
    const int batchSize = 64;
    while (pos < (int)tokens.size()) {
        const int chunk = std::min(batchSize, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(&tokens[pos], chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("nativePrewarmPrefix decode failed at pos=%d", pos);
            return 0;
        }
        pos += chunk;
    }

    const size_t size = llama_state_seq_get_size(h->ctx, /*seq_id=*/0);
    if (size == 0) {
        LOGE("nativePrewarmPrefix state_seq_get_size returned 0");
        return 0;
    }
    auto *cache = new PrefixCache();
    cache->blob.resize(size);
    cache->tokenCount = static_cast<int32_t>(tokens.size());
    const size_t actual = llama_state_seq_get_data(
        h->ctx, cache->blob.data(), cache->blob.size(), /*seq_id=*/0);
    if (actual == 0) {
        LOGE("nativePrewarmPrefix state_seq_get_data returned 0");
        delete cache;
        return 0;
    }
    cache->blob.resize(actual);
    LOGI("nativePrewarmPrefix tokens=%d blobBytes=%zu durMs=%lld",
         cache->tokenCount, actual, nowMs() - t0);
    return reinterpret_cast<jlong>(cache);
}

JNIEXPORT void JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeFreePrefix(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong prefixHandle) {
    auto *c = reinterpret_cast<PrefixCache *>(prefixHandle);
    if (c != nullptr) delete c;
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativePolishStreamingWithPrefix(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jlong prefixHandle,
        jstring jUserText, jstring jSuffix,
        jint maxNewTokens, jfloat temperature, jfloat topP,
        jobject sink) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    auto *cache = reinterpret_cast<PrefixCache *>(prefixHandle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");
    if (cache == nullptr || cache->blob.empty()) return env->NewStringUTF("");
    if (sink == nullptr) return env->NewStringUTF("");

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onTokenMid = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(sinkCls);
    if (onTokenMid == nullptr) {
        LOGE("TokenSink.onToken(Ljava/lang/String;)Z not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }

    const long long totalStartMs = nowMs();
    const char *cUser = env->GetStringUTFChars(jUserText, nullptr);
    const char *cSuffix = env->GetStringUTFChars(jSuffix, nullptr);
    std::string body;
    body.reserve(std::strlen(cUser) + std::strlen(cSuffix));
    body.append(cUser).append(cSuffix);
    env->ReleaseStringUTFChars(jUserText, cUser);
    env->ReleaseStringUTFChars(jSuffix, cSuffix);
    LOGI("nativePolishStreamingWithPrefix start bodyBytes=%zu prefixTokens=%d "
         "maxNewTokens=%d temp=%.2f topP=%.2f",
         body.size(), cache->tokenCount, maxNewTokens, temperature, topP);

    // Restore prefix KV state into seq 0. We clear memory first because
    // llama_state_seq_set_data expects an empty seq slot.
    llama_memory_t mem = llama_get_memory(h->ctx);
    llama_memory_clear(mem, /*data=*/true);
    const long long restoreStartMs = nowMs();
    const size_t loaded = llama_state_seq_set_data(
        h->ctx, cache->blob.data(), cache->blob.size(), /*dest_seq_id=*/0);
    if (loaded == 0) {
        LOGE("nativePolishStreamingWithPrefix state_seq_set_data failed");
        return env->NewStringUTF("");
    }
    const long long restoreMs = nowMs() - restoreStartMs;

    // Tokenize body with addSpecial=false — BOS is already in the cached prefix.
    auto tokens = tokenize(h->vocab, body, /*addSpecial=*/false, /*parseSpecial=*/true);
    if (tokens.empty()) {
        LOGE("nativePolishStreamingWithPrefix body tokenize empty");
        return env->NewStringUTF("");
    }

    const int batchSize = 64;
    int pos = 0;
    int chunkCount = 0;
    const long long promptDecodeStartMs = nowMs();
    while (pos < (int)tokens.size()) {
        const int chunk = std::min(batchSize, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(&tokens[pos], chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("nativePolishStreamingWithPrefix decode failed at pos=%d", pos);
            return env->NewStringUTF("");
        }
        pos += chunk;
        ++chunkCount;
    }
    const long long prefillMs = nowMs() - promptDecodeStartMs;
    LOGI("nativePolishStreamingWithPrefix body_decode_done bodyTokens=%zu chunks=%d "
         "restoreMs=%lld prefillMs=%lld",
         tokens.size(), chunkCount, restoreMs, prefillMs);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    if (topP < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    std::string out;
    out.reserve(static_cast<size_t>(maxNewTokens) * 4);

    const long long generateStartMs = nowMs();
    long long firstTokenMs = -1;
    int generatedTokens = 0;
    bool eogReached = false;
    bool cancelled = false;
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token id = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) {
            eogReached = true;
            break;
        }
        if (firstTokenMs < 0) firstTokenMs = nowMs() - generateStartMs;
        std::string piece = tokenToString(h->vocab, id);
        out += piece;
        ++generatedTokens;

        jstring jPiece = env->NewStringUTF(piece.c_str());
        jboolean keepGoing = env->CallBooleanMethod(sink, onTokenMid, jPiece);
        env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            LOGE("TokenSink.onToken threw");
            env->ExceptionDescribe();
            env->ExceptionClear();
            cancelled = true;
            break;
        }
        if (!keepGoing) {
            cancelled = true;
            break;
        }

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, one) != 0) {
            LOGE("nativePolishStreamingWithPrefix gen decode failed step=%d", i);
            break;
        }
    }
    const long long decodeMs = nowMs() - generateStartMs;
    LOGI("nativePolishStreamingWithPrefix done outBytes=%zu tokens=%d eog=%d cancelled=%d "
         "restoreMs=%lld prefillMs=%lld firstTokenMs=%lld decodeMs=%lld totalDurMs=%lld",
         out.size(), generatedTokens, eogReached ? 1 : 0, cancelled ? 1 : 0,
         restoreMs, prefillMs, firstTokenMs, decodeMs, nowMs() - totalStartMs);

    llama_sampler_free(sampler);

    char header[160];
    int headerLen = snprintf(header, sizeof(header),
                             "\x01prefill_ms=%lld,decode_ms=%lld,first_token_ms=%lld\x01",
                             prefillMs, decodeMs, firstTokenMs);
    std::string combined;
    combined.reserve(static_cast<size_t>(headerLen) + out.size());
    combined.append(header, static_cast<size_t>(headerLen));
    combined.append(out);
    return env->NewStringUTF(combined.c_str());
}

JNIEXPORT void JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/,
                                                jlong handle) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) llama_free(h->ctx);
    if (h->model != nullptr) llama_model_free(h->model);
    delete h;
    LOGI("Llama context freed");
}

} // extern "C"
