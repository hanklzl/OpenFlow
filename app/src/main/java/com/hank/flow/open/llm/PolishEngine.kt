package com.hank.flow.open.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PolishEngine(private val modelPath: String) {

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) return true
        if (!LlamaJni.loaded) return false
        runCatching { LlamaJni.nativeInit(modelPath, CTX_SIZE, 0) }
            .onSuccess { handle = it }
            .onFailure { Log.e(TAG, "nativeInit failed", it) }
        handle != 0L
    }

    suspend fun polish(rawText: String): String {
        if (rawText.isBlank()) return rawText
        if (!ensureLoaded()) return rawText
        val prompt = PolishPrompt.build(rawText)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    LlamaJni.nativeGenerate(handle, prompt, MAX_NEW_TOKENS, TEMPERATURE, TOP_P)
                        .trim()
                        .removeSurrounding("\"")
                }.getOrElse {
                    Log.e(TAG, "polish failed", it)
                    rawText
                }
            }
        }
    }

    suspend fun release() = mutex.withLock {
        if (handle != 0L) {
            runCatching { LlamaJni.nativeFree(handle) }
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "PolishEngine"
        private const val CTX_SIZE = 1024
        private const val MAX_NEW_TOKENS = 256
        private const val TEMPERATURE = 0.3f
        private const val TOP_P = 0.9f
    }
}
