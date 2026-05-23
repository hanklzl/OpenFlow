#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <string>
#include <vector>
#include <cstring>
#include <chrono>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaHolder {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeInit(JNIEnv *env, jobject /*thiz*/,
                                                jstring jModelPath, jint ctxSize,
                                                jint nGpuLayers) {
    if (!gBackendInitialized) {
        llama_backend_init();
        gBackendInitialized = true;
    }
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;
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
