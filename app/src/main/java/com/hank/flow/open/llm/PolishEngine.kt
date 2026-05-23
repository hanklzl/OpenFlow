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
                    buildResult(raw, loadMs)
                }.getOrElse {
                    Log.e(TAG, "polish failed", it)
                    PolishResult(rawText, loadMs = loadMs)
                }
            }
        }
    }

    /**
     * Streaming variant. [onCleanDelta] receives each cleaned delta chunk
     * (with complete `<think>...</think>` blocks stripped and partial open
     * tags held back). Returning false from the callback aborts generation;
     * any text already streamed is preserved (cancel semantics, see rules.md).
     *
     * The returned [PolishResult.text] is the fully cleaned final output —
     * may differ from the concatenation of all deltas if surrounding quotes
     * had to be removed at finalize. Callers should reconcile by issuing one
     * final corrective write with [PolishResult.text] when the two differ.
     */
    suspend fun polishStreaming(
        rawText: String,
        maxNewTokens: Int = MAX_NEW_TOKENS,
        onCleanDelta: (String) -> Boolean,
    ): PolishResult {
        if (rawText.isBlank()) return PolishResult(rawText)
        if (!ensureLoaded()) return PolishResult(rawText, loadMs = lastLoadMs)
        val loadMs = lastLoadMs
        val prompt = PolishPrompt.build(rawText, appendNoThink = isQwen3)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    val filter = ThinkStreamFilter()
                    val sink = LlamaJni.TokenSink { piece ->
                        val delta = filter.consume(piece)
                        if (delta.isEmpty()) true else onCleanDelta(delta)
                    }
                    val raw = LlamaJni.nativeGenerateStreaming(
                        handle, prompt, maxNewTokens, TEMPERATURE, TOP_P, sink,
                    )
                    buildResult(raw, loadMs)
                }.getOrElse {
                    Log.e(TAG, "polishStreaming failed", it)
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

    private fun buildResult(raw: String, loadMs: Long): PolishResult {
        val (body, metric) = raw.parsePolishMetric()
        return PolishResult(
            text = body.cleanPolishOutput(),
            loadMs = loadMs,
            prefillMs = metric.prefillMs,
            decodeMs = metric.decodeMs,
            firstTokenMs = metric.firstTokenMs,
        )
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

/**
 * Streaming filter for polish output. Accepts raw token pieces, emits cleaned
 * deltas that:
 *  - drop complete `<think>...</think>` blocks
 *  - hold back any unclosed `<think>...` (could still close in a later piece)
 *  - hold back a trailing `<X...` partial tag until we know if it's a `<think>`
 *    or some other glyph the model produced verbatim
 *
 * NOTE: leading-quote and surrounding-quote stripping is NOT done in streaming
 * mode — that's a final-pass concern handled by [PolishEngine.buildResult].
 * Callers reconcile via a final corrective write if needed.
 */
internal class ThinkStreamFilter {
    private val raw = StringBuilder()
    private var emittedCleanLen = 0

    fun consume(piece: String): String {
        if (piece.isEmpty()) return ""
        raw.append(piece)
        val clean = filtered(raw)
        if (clean.length <= emittedCleanLen) return ""
        val delta = clean.substring(emittedCleanLen)
        emittedCleanLen = clean.length
        return delta
    }

    private fun filtered(s: CharSequence): String {
        var t = THINK_BLOCK.replace(s, "")
        val openIdx = t.indexOf("<think>")
        if (openIdx >= 0) t = t.substring(0, openIdx)
        val ltIdx = t.lastIndexOf('<')
        if (ltIdx >= 0 && !t.substring(ltIdx).contains('>')) {
            t = t.substring(0, ltIdx)
        }
        return t
    }

    companion object {
        private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    }
}
