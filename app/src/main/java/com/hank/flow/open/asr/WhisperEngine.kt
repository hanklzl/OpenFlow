package com.hank.flow.open.asr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * High-level wrapper around [WhisperJni]. Single-threaded model access via
 * [mutex] — whisper.cpp internal state is not thread-safe.
 */
class WhisperEngine(private val modelPath: String) {

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) return true
        if (!WhisperJni.loaded) return false
        runCatching { WhisperJni.nativeInit(modelPath) }
            .onSuccess { handle = it }
            .onFailure { Log.e(TAG, "nativeInit failed", it) }
        handle != 0L
    }

    suspend fun transcribe(pcm: ShortArray, language: String = "auto"): String {
        if (!ensureLoaded()) return ""
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching { WhisperJni.nativeTranscribe(handle, pcm, language) }
                    .getOrElse {
                        Log.e(TAG, "transcribe failed", it)
                        ""
                    }
            }
        }
    }

    suspend fun release() = mutex.withLock {
        if (handle != 0L) {
            runCatching { WhisperJni.nativeFree(handle) }
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "WhisperEngine"
    }
}
