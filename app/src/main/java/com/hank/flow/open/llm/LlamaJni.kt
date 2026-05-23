package com.hank.flow.open.llm

import com.hank.flow.open.asr.WhisperJni

/**
 * Thin JNI binding to llama.cpp. Native lib is the same `openflow_jni` shared
 * library as Whisper (single CMake target).
 */
object LlamaJni {

    val loaded: Boolean get() = WhisperJni.loaded

    external fun nativeInit(modelPath: String, ctxSize: Int, nGpuLayers: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int, temperature: Float, topP: Float): String
    external fun nativeGenerateStreaming(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: TokenSink,
    ): String
    external fun nativeFree(handle: Long)

    fun interface TokenSink {
        /** Returning false aborts generation; the partial output is still flushed. */
        fun onToken(piece: String): Boolean
    }
}
