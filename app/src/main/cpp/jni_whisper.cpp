#include <jni.h>
#include <android/log.h>
#include <whisper.h>
#include <string>
#include <vector>
#include <cstring>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hank_flow_open_asr_WhisperJni_nativeInit(JNIEnv *env, jobject /*thiz*/,
                                                  jstring jModelPath) {
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    LOGI("Whisper context initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_hank_flow_open_asr_WhisperJni_nativeTranscribe(JNIEnv *env, jobject /*thiz*/,
                                                        jlong handle,
                                                        jshortArray jPcm,
                                                        jstring jLanguage) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jPcm);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<float> samples(static_cast<size_t>(n));
    {
        jshort *src = env->GetShortArrayElements(jPcm, nullptr);
        constexpr float kInvShortMax = 1.0f / 32768.0f;
        for (jsize i = 0; i < n; ++i) {
            samples[i] = static_cast<float>(src[i]) * kInvShortMax;
        }
        env->ReleaseShortArrayElements(jPcm, src, JNI_ABORT);
    }

    const char *language = env->GetStringUTFChars(jLanguage, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = (std::strcmp(language, "auto") == 0) ? nullptr : language;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    // Diagnostic: n_threads forced to 1 to test whether NDK r29 + whisper.cpp/ggml
    // multi-thread synchronization is responsible for the post-record ASR hang
    // observed on Xiaomi 13 (Android 16, SDK 36). Revert to 4 once root cause is
    // confirmed and properly fixed.
    params.n_threads = 1;

    const int rc = whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size()));
    env->ReleaseStringUTFChars(jLanguage, language);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int nSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) out.append(text);
    }
    // Trim leading whitespace inserted by whisper.
    size_t start = out.find_first_not_of(" \t\n\r");
    if (start != std::string::npos) out.erase(0, start);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_hank_flow_open_asr_WhisperJni_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/,
                                                  jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

} // extern "C"
