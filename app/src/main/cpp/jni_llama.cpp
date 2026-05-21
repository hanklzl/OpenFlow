#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <string>
#include <vector>
#include <cstring>

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
    cparams.n_batch = 256;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto *holder = new LlamaHolder{model, ctx, llama_model_get_vocab(model)};
    LOGI("Llama context initialized (ctx=%d)", ctxSize);
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_llm_LlamaJni_nativeGenerate(JNIEnv *env, jobject /*thiz*/,
                                                    jlong handle, jstring jPrompt,
                                                    jint maxNewTokens, jfloat temperature,
                                                    jfloat topP) {
    auto *h = reinterpret_cast<LlamaHolder *>(handle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");

    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    auto tokens = tokenize(h->vocab, prompt, /*addSpecial=*/true, /*parseSpecial=*/true);
    if (tokens.empty()) {
        LOGE("tokenize returned empty");
        return env->NewStringUTF("");
    }

    // Fresh KV state for each call.
    llama_memory_t mem = llama_get_memory(h->ctx);
    llama_memory_clear(mem, /*data=*/true);

    // Feed prompt in chunks of n_batch.
    const int batchSize = 256;
    int pos = 0;
    while (pos < (int)tokens.size()) {
        const int chunk = std::min(batchSize, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(&tokens[pos], chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("decode failed at pos=%d", pos);
            return env->NewStringUTF("");
        }
        pos += chunk;
    }

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

    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token id = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) break;

        out += tokenToString(h->vocab, id);

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, one) != 0) {
            LOGE("decode failed during generation");
            break;
        }
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
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
