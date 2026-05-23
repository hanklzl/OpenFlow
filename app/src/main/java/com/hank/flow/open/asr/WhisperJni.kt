package com.hank.flow.open.asr

import android.util.Log

/**
 * Thin JNI binding to whisper.cpp.
 *
 * Native library `openflow_jni` is built by Step E. Until then, [loaded] is
 * false and [WhisperEngine] falls back to stub behavior.
 */
object WhisperJni {

    @Volatile var loaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("openflow_jni")
            loaded = true
        } catch (t: Throwable) {
            Log.w(TAG, "openflow_jni not loaded yet: ${t.message}")
        }
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeTranscribe(
        handle: Long,
        pcm16k: ShortArray,
        language: String,
        initialPrompt: String,
    ): String
    external fun nativeFree(handle: Long)

    private const val TAG = "WhisperJni"
}
