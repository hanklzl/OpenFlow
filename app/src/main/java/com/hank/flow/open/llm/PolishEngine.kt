package com.hank.flow.open.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PolishEngine(
    private val modelPath: String,
    private val isQwen3: Boolean = false,
) {

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L
    @Volatile private var lastLoadMs: Long = -1L

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) {
            lastLoadMs = 0L
            return@withLock true
        }
        if (!LlamaJni.loaded) return@withLock false
        val t0 = System.currentTimeMillis()
        runCatching { LlamaJni.nativeInit(modelPath, CTX_SIZE, 0) }
            .onSuccess { handle = it }
            .onFailure { Log.e(TAG, "nativeInit failed", it) }
        lastLoadMs = System.currentTimeMillis() - t0
        handle != 0L
    }

    suspend fun polish(rawText: String, maxNewTokens: Int = MAX_NEW_TOKENS): PolishResult {
        if (rawText.isBlank()) return PolishResult(rawText)
        if (!ensureLoaded()) return PolishResult(rawText, loadMs = lastLoadMs)
        val loadMs = lastLoadMs
        val prompt = PolishPrompt.build(rawText, appendNoThink = isQwen3)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    val raw = LlamaJni.nativeGenerate(handle, prompt, maxNewTokens, TEMPERATURE, TOP_P)
                    val (body, metric) = raw.parsePolishMetric()
                    PolishResult(
                        text = body.cleanPolishOutput(),
                        loadMs = loadMs,
                        prefillMs = metric.prefillMs,
                        decodeMs = metric.decodeMs,
                        firstTokenMs = metric.firstTokenMs,
                    )
                }.getOrElse {
                    Log.e(TAG, "polish failed", it)
                    PolishResult(rawText, loadMs = loadMs)
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
        private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

        private const val METRIC_DELIM = ''
        private val METRIC_REGEX =
            Regex("""prefill_ms=(-?\d+),decode_ms=(-?\d+),first_token_ms=(-?\d+)""")

        internal fun String.cleanPolishOutput(): String =
            replace(THINK_BLOCK, "")
                .trim()
                .removeSurrounding("\"")
                .trim()

        internal fun String.parsePolishMetric(): Pair<String, PolishMetric> {
            if (length < 2 || this[0] != METRIC_DELIM) return this to PolishMetric.EMPTY
            val end = indexOf(METRIC_DELIM, startIndex = 1)
            if (end < 0) return this to PolishMetric.EMPTY
            val payload = substring(1, end)
            val rest = substring(end + 1)
            val match = METRIC_REGEX.find(payload) ?: return rest to PolishMetric.EMPTY
            val (prefill, decode, firstToken) = match.destructured
            return rest to PolishMetric(prefill.toLong(), decode.toLong(), firstToken.toLong())
        }
    }
}

data class PolishMetric(
    val prefillMs: Long,
    val decodeMs: Long,
    val firstTokenMs: Long,
) {
    companion object {
        val EMPTY = PolishMetric(-1L, -1L, -1L)
    }
}

data class PolishResult(
    val text: String,
    val loadMs: Long = -1L,
    val prefillMs: Long = -1L,
    val decodeMs: Long = -1L,
    val firstTokenMs: Long = -1L,
)
