package com.hank.flow.open.asr

import android.util.Log
import com.hank.flow.open.log.OpenFlowLog
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
    @Volatile private var lastLoadMs: Long = -1L

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) {
            lastLoadMs = 0L
            return@withLock true
        }
        if (!WhisperJni.loaded) {
            OpenFlowLog.d(OpenFlowLog.Tag.ASR, "whisper_lib_not_loaded")
            return@withLock false
        }
        val t0 = System.currentTimeMillis()
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "whisper_load_call",
            mapOf("path" to modelPath),
        )
        runCatching { WhisperJni.nativeInit(modelPath) }
            .onSuccess { handle = it }
            .onFailure {
                Log.e(TAG, "nativeInit failed", it)
                OpenFlowLog.e(OpenFlowLog.Tag.ASR, "whisper_load_failed", it)
            }
        val ok = handle != 0L
        lastLoadMs = System.currentTimeMillis() - t0
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "whisper_load_done",
            mapOf("ok" to ok, "durMs" to lastLoadMs),
        )
        ok
    }

    suspend fun transcribe(pcm: ShortArray, language: String = "auto"): WhisperResult {
        if (!ensureLoaded()) return WhisperResult("", loadMs = lastLoadMs)
        val loadMs = lastLoadMs
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val t0 = System.currentTimeMillis()
                OpenFlowLog.d(
                    OpenFlowLog.Tag.ASR,
                    "whisper_transcribe_call",
                    mapOf("pcmShorts" to pcm.size, "lang" to language),
                )
                val out = runCatching { WhisperJni.nativeTranscribe(handle, pcm, language) }
                    .getOrElse {
                        Log.e(TAG, "transcribe failed", it)
                        OpenFlowLog.e(OpenFlowLog.Tag.ASR, "whisper_transcribe_failed", it)
                        ""
                    }
                val nativeMs = System.currentTimeMillis() - t0
                OpenFlowLog.d(
                    OpenFlowLog.Tag.ASR,
                    "whisper_transcribe_done",
                    mapOf("textLen" to out.length, "durMs" to nativeMs),
                )
                WhisperResult(text = out, loadMs = loadMs, nativeMs = nativeMs)
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

data class WhisperResult(
    val text: String,
    val loadMs: Long = -1L,
    val nativeMs: Long = -1L,
)
