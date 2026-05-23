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
    /**
     * Phase 5: prefills [prefixText] into the model context and saves the
     * resulting KV state as an opaque cache blob. Returns a Long handle that
     * must be paired with [nativeFree] and [nativeFreePrefix] on release.
     * Returns 0 on failure.
     */
    external fun nativePrewarmPrefix(handle: Long, prefixText: String): Long

    /**
     * Polishes a single user transcript by restoring the [prefixHandle] KV
     * state into seq 0, then prefilling only `userText + suffix` and sampling.
     * Returns the same metric-piggybacked string format as [nativeGenerateStreaming].
     */
    external fun nativePolishStreamingWithPrefix(
        handle: Long,
        prefixHandle: Long,
        userText: String,
        suffix: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: TokenSink,
    ): String

    external fun nativeFreePrefix(prefixHandle: Long)
    external fun nativeFree(handle: Long)

    fun interface TokenSink {
        /** Returning false aborts generation; the partial output is still flushed. */
        fun onToken(piece: String): Boolean
    }
}
