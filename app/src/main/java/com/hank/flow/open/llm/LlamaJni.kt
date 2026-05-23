package com.hank.flow.open.llm

import com.hank.flow.open.asr.WhisperJni

/**
 * Thin JNI binding to llama.cpp. Native lib is the same `openflow_jni` shared
 * library as Whisper (single CMake target).
 */
object LlamaJni {

    val loaded: Boolean get() = WhisperJni.loaded

    external fun nativeInit(modelPath: String, ctxSize: Int, nGpuLayers: Int, backendName: String?): Long
    external fun nativeSupportsGpuOffload(): Boolean
    external fun nativeListBackendDevices(): String
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

interface LlamaBridge {
    val loaded: Boolean
    fun init(modelPath: String, ctxSize: Int, nGpuLayers: Int, backendName: String?): Long
    fun supportsGpuOffload(): Boolean = false
    fun listBackendDevices(): String = ""
    fun generate(handle: Long, prompt: String, maxNewTokens: Int, temperature: Float, topP: Float): String
    fun generateStreaming(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: LlamaJni.TokenSink,
    ): String
    fun prewarmPrefix(handle: Long, prefixText: String): Long
    fun polishStreamingWithPrefix(
        handle: Long,
        prefixHandle: Long,
        userText: String,
        suffix: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: LlamaJni.TokenSink,
    ): String
    fun freePrefix(prefixHandle: Long)
    fun free(handle: Long)
}

internal object JniLlamaBridge : LlamaBridge {
    override val loaded: Boolean
        get() = LlamaJni.loaded

    override fun init(modelPath: String, ctxSize: Int, nGpuLayers: Int, backendName: String?): Long =
        LlamaJni.nativeInit(modelPath, ctxSize, nGpuLayers, backendName)

    override fun supportsGpuOffload(): Boolean =
        runCatching { LlamaJni.nativeSupportsGpuOffload() }.getOrDefault(false)

    override fun listBackendDevices(): String =
        runCatching { LlamaJni.nativeListBackendDevices() }.getOrDefault("")

    override fun generate(handle: Long, prompt: String, maxNewTokens: Int, temperature: Float, topP: Float): String =
        LlamaJni.nativeGenerate(handle, prompt, maxNewTokens, temperature, topP)

    override fun generateStreaming(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: LlamaJni.TokenSink,
    ): String = LlamaJni.nativeGenerateStreaming(handle, prompt, maxNewTokens, temperature, topP, sink)

    override fun prewarmPrefix(handle: Long, prefixText: String): Long =
        LlamaJni.nativePrewarmPrefix(handle, prefixText)

    override fun polishStreamingWithPrefix(
        handle: Long,
        prefixHandle: Long,
        userText: String,
        suffix: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        sink: LlamaJni.TokenSink,
    ): String = LlamaJni.nativePolishStreamingWithPrefix(
        handle,
        prefixHandle,
        userText,
        suffix,
        maxNewTokens,
        temperature,
        topP,
        sink,
    )

    override fun freePrefix(prefixHandle: Long) = LlamaJni.nativeFreePrefix(prefixHandle)

    override fun free(handle: Long) = LlamaJni.nativeFree(handle)
}
