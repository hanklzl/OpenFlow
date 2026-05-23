package com.hank.flow.open.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PolishEngine(
    private val modelPath: String,
    private val isQwen3: Boolean = false,
    private val backend: InferenceBackend = InferenceBackend.Cpu,
    private val nGpuLayers: Int = 0,
    private val bridge: LlamaBridge = JniLlamaBridge,
) {

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L
    @Volatile private var lastLoadMs: Long = -1L
    @Volatile private var prefixHandle: Long = 0L
    @Volatile private var prefixSignature: String? = null

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) {
            lastLoadMs = 0L
            return@withLock true
        }
        if (!bridge.loaded) return@withLock false
        val t0 = System.currentTimeMillis()
        runCatching { initWithFallback() }
            .onSuccess { handle = it }
            .onFailure { logError("nativeInit failed", it) }
        if (handle != 0L) {
            // Phase 5: prewarm the system+ChatML prefix into a KV-state blob
            // so each polish only prefills the user transcript + suffix.
            // Failure here is non-fatal — polish falls back to the full prompt path.
            val prefixText = PolishPrompt.systemPrefix()
            val expectedSig = signatureFor(prefixText)
            runCatching { bridge.prewarmPrefix(handle, prefixText) }
                .onSuccess { ptr ->
                    if (ptr != 0L) {
                        prefixHandle = ptr
                        prefixSignature = expectedSig
                    } else {
                        logWarn("nativePrewarmPrefix returned 0; falling back to full prompt")
                    }
                }
                .onFailure { logError("nativePrewarmPrefix failed", it) }
        }
        lastLoadMs = System.currentTimeMillis() - t0
        handle != 0L
    }

    private fun initWithFallback(): Long {
        val requestedBackendName = backend.nativeName
        val requestedGpuLayers = if (backend == InferenceBackend.Cpu) 0 else nGpuLayers
        if (backend == InferenceBackend.Cpu) {
            return bridge.init(
                modelPath = modelPath,
                ctxSize = CTX_SIZE,
                nGpuLayers = 0,
                backendName = null,
            )
        }
        val acceleratedHandle = runCatching {
            bridge.init(
                modelPath = modelPath,
                ctxSize = CTX_SIZE,
                nGpuLayers = requestedGpuLayers,
                backendName = requestedBackendName,
            )
        }.getOrElse {
            logError("accelerated nativeInit failed; retrying CPU", it)
            0L
        }
        if (acceleratedHandle != 0L) return acceleratedHandle
        return bridge.init(
            modelPath = modelPath,
            ctxSize = CTX_SIZE,
            nGpuLayers = 0,
            backendName = null,
        )
    }

    private fun signatureFor(prefixText: String): String =
        "$modelPath::$isQwen3::$CTX_SIZE::${prefixText.hashCode()}"

    suspend fun polish(rawText: String, maxNewTokens: Int = MAX_NEW_TOKENS): PolishResult {
        if (rawText.isBlank()) return PolishResult(rawText)
        if (!ensureLoaded()) return PolishResult(rawText, loadMs = lastLoadMs)
        val loadMs = lastLoadMs
        val prompt = PolishPrompt.build(rawText, appendNoThink = isQwen3)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    val raw = bridge.generate(handle, prompt, maxNewTokens, TEMPERATURE, TOP_P)
                    buildResult(raw, loadMs)
                }.getOrElse {
                    logError("polish failed", it)
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
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    val filter = ThinkStreamFilter()
                    val sink = LlamaJni.TokenSink { piece ->
                        val delta = filter.consume(piece)
                        if (delta.isEmpty()) true else onCleanDelta(delta)
                    }
                    val expectedSig = signatureFor(PolishPrompt.systemPrefix())
                    val usePrefix = prefixHandle != 0L && prefixSignature == expectedSig
                    val raw = if (usePrefix) {
                        bridge.polishStreamingWithPrefix(
                            handle,
                            prefixHandle,
                            rawText,
                            PolishPrompt.userSuffix(appendNoThink = isQwen3),
                            maxNewTokens,
                            TEMPERATURE,
                            TOP_P,
                            sink,
                        )
                    } else {
                        val prompt = PolishPrompt.build(rawText, appendNoThink = isQwen3)
                        bridge.generateStreaming(
                            handle, prompt, maxNewTokens, TEMPERATURE, TOP_P, sink,
                        )
                    }
                    buildResult(raw, loadMs)
                }.getOrElse {
                    logError("polishStreaming failed", it)
                    PolishResult(rawText, loadMs = loadMs)
                }
            }
        }
    }

    suspend fun release() = mutex.withLock {
        if (prefixHandle != 0L) {
            runCatching { bridge.freePrefix(prefixHandle) }
            prefixHandle = 0L
            prefixSignature = null
        }
        if (handle != 0L) {
            runCatching { bridge.free(handle) }
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

    private fun logError(message: String, throwable: Throwable) {
        runCatching { Log.e(TAG, message, throwable) }
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
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
