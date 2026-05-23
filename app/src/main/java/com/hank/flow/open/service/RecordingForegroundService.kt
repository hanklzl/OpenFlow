package com.hank.flow.open.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hank.flow.open.OpenFlowApp
import com.hank.flow.open.asr.WhisperEngine
import com.hank.flow.open.audio.AudioRecorder
import com.hank.flow.open.history.HistoryRecord
import com.hank.flow.open.history.HistoryStore
import com.hank.flow.open.insertion.PipelineResult
import com.hank.flow.open.insertion.StreamingHandle
import com.hank.flow.open.insertion.TextInserter
import com.hank.flow.open.insertion.decideOutcome
import com.hank.flow.open.asr.WhisperResult
import com.hank.flow.open.llm.InferenceBackend
import com.hank.flow.open.llm.InferenceBackendRuntime
import com.hank.flow.open.llm.PolishEngine
import com.hank.flow.open.llm.PolishResult
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelEntry
import com.hank.flow.open.model.ModelStore
import com.hank.flow.open.settings.CustomDictionary
import com.hank.flow.open.settings.FlowSettings
import com.hank.flow.open.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground service (type=microphone) that owns the audio + ASR + polish + insert pipeline.
 *
 * Lifecycle: a single FGS instance starts on ACTION_START (long-press), keeps recording
 * until ACTION_COMMIT or ACTION_CANCEL, then runs the pipeline and stops itself.
 *
 * UI feedback: pushes [BallState] + [PillSpec] through [FlowAccessibilityService.overlayController]
 * so the floating ball reflects every pipeline stage. Failure UI is narrow — model-not-ready /
 * empty ASR stay silent per `pipeline/rules.md` MUST 3; only real exceptions surface a pill.
 */
class RecordingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = AudioRecorder()
    private var captureJob: Job? = null
    private var rmsJob: Job? = null
    private var warmupJob: Job? = null
    private var capturing = false
    @Volatile private var cancelStreamingFlag = false

    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val modelStore by lazy { ModelStore(applicationContext) }
    private val historyStore: HistoryStore get() = OpenFlowApp.instance.historyStore

    private var whisperEngine: WhisperEngine? = null
    private var polishEngine: PolishEngine? = null
    private var polishEngineKey: PolishEngineKey? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OpenFlowLog.d(
            OpenFlowLog.Tag.FGS,
            "fgs_start_command",
            mapOf("action" to intent?.action, "capturing" to capturing),
        )
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_COMMIT -> handleCommit()
            ACTION_CANCEL -> handleCancel()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (capturing) return
        cancelStreamingFlag = false
        startForegroundCompat()
        recorder.start(scope)
        capturing = recorder.isCapturing
        if (!capturing) {
            OpenFlowLog.e(OpenFlowLog.Tag.FGS, "recording_start_failed")
            scope.launch { applyResult(PipelineResult.Failed("麦克风启动失败")) }
            stopSelfSafely()
            return
        }
        startRmsForwarding()
        startEngineWarmup()
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "recording_started")
    }

    /**
     * Phase 3: while the user is still holding the mic button, race ASR and
     * polish model loads (nativeInit can take 0.5–2 s each) so they're hot
     * when handleCommit fires. Cancelled in handleCancel; on commit we just
     * call ensureLoaded again — it's a no-op once the handle is set.
     */
    private fun startEngineWarmup() {
        warmupJob?.cancel()
        warmupJob = scope.launch {
            runCatching {
                val tWarmup = System.currentTimeMillis()
                val s = settingsStore.current()
                val tasks = mutableListOf<Job>()
                tasks += launch {
                    ensureWhisperEngine(s)?.ensureLoaded()
                }
                if (s.polishEnabled && s.polishWarmupEnabled) {
                    tasks += launch {
                        ensurePolishEngine(s)?.ensureLoaded()
                    }
                }
                tasks.forEach { it.join() }
                OpenFlowLog.d(
                    OpenFlowLog.Tag.FGS,
                    "warmup_done",
                    mapOf(
                        "warmupMs" to (System.currentTimeMillis() - tWarmup),
                        "polishWarmup" to (s.polishEnabled && s.polishWarmupEnabled),
                    ),
                )
            }.onFailure {
                OpenFlowLog.e(OpenFlowLog.Tag.FGS, "warmup_failed", it)
            }
        }
    }

    private fun startRmsForwarding() {
        rmsJob?.cancel()
        rmsJob = scope.launch {
            recorder.frames.collect { frame ->
                withContext(Dispatchers.Main) {
                    FlowAccessibilityService.instance?.overlayController?.pushRms(frame.rms)
                }
            }
        }
    }

    private fun handleCommit() {
        val tCommit = System.currentTimeMillis()
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "commit_entered", mapOf("capturing" to capturing))
        if (!capturing) {
            stopSelfSafely()
            return
        }
        captureJob = scope.launch {
            var asrResult = WhisperResult("")
            var polishResult = PolishResult("")
            var insertMs = -1L
            var pcmCollectMs = -1L
            try {
                val pcmStart = System.currentTimeMillis()
                val pcm = recorder.stop()
                pcmCollectMs = System.currentTimeMillis() - pcmStart
                capturing = false
                rmsJob?.cancel()
                OpenFlowLog.d(
                    OpenFlowLog.Tag.FGS,
                    "pcm_collected",
                    mapOf(
                        "samples" to pcm.size,
                        "audioMs" to (pcm.size * 1000L / 16000L),
                        "collectMs" to pcmCollectMs,
                    ),
                )
                pushState(BallState.Transcribing)

                val settings = settingsStore.current()
                OpenFlowLog.d(OpenFlowLog.Tag.ASR, "asr_start")
                asrResult = transcribe(pcm, settings)
                OpenFlowLog.d(
                    OpenFlowLog.Tag.ASR,
                    "asr_done",
                    mapOf(
                        "textLen" to asrResult.text.length,
                        "text" to asrResult.text.take(40),
                        "loadMs" to asrResult.loadMs,
                        "nativeMs" to asrResult.nativeMs,
                    ),
                )

                val polishWanted = settings.polishEnabled && asrResult.text.isNotBlank()
                var streamingTookOver = false
                val polishStartWall = System.currentTimeMillis()
                if (polishWanted) pushState(BallState.Polishing)

                polishResult = if (polishWanted) {
                    val streamed = if (settings.polishStreamingEnabled) {
                        tryStreamingPolishAndInsert(asrResult.text, settings.llmModelId)
                    } else null
                    if (streamed != null) {
                        streamingTookOver = true
                        streamed
                    } else {
                        polish(asrResult.text, settings.llmModelId)
                    }
                } else {
                    PolishResult(asrResult.text)
                }
                val polishDurMsWall: Long? =
                    if (polishWanted) System.currentTimeMillis() - polishStartWall else null
                OpenFlowLog.d(
                    OpenFlowLog.Tag.LLM,
                    "polish_done",
                    mapOf(
                        "polishEnabled" to settings.polishEnabled,
                        "streaming" to streamingTookOver,
                        "textLen" to polishResult.text.length,
                        "text" to polishResult.text.take(40),
                        "loadMs" to polishResult.loadMs,
                        "prefillMs" to polishResult.prefillMs,
                        "decodeMs" to polishResult.decodeMs,
                        "firstTokenMs" to polishResult.firstTokenMs,
                        "wallMs" to (polishDurMsWall ?: 0L),
                    ),
                )

                val result: PipelineResult
                if (streamingTookOver) {
                    // Text already inserted during streaming; record 0 ms here so
                    // pipeline_summary doesn't double-count the streaming time.
                    insertMs = 0L
                    result = decideOutcome(
                        text = polishResult.text,
                        nodeAvailable = true,
                        setTextOk = true,
                        clipboardOk = true,
                    )
                } else {
                    val insertStart = System.currentTimeMillis()
                    result = runInsertion(polishResult.text)
                    insertMs = System.currentTimeMillis() - insertStart
                }
                applyResult(result)
                recordHistory(
                    pcm = pcm,
                    raw = asrResult.text,
                    finalText = polishResult.text,
                    polishAttempted = polishWanted,
                    asrModelId = settings.whisperModelId,
                    llmModelId = settings.llmModelId,
                    asrDurMs = asrResult.nativeMs.coerceAtLeast(0L),
                    polishDurMs = polishDurMsWall,
                )
            } catch (t: Throwable) {
                OpenFlowLog.e(OpenFlowLog.Tag.FGS, "commit_failed", t)
                applyResult(PipelineResult.Failed("处理失败:${t.javaClass.simpleName}"))
            } finally {
                OpenFlowLog.d(
                    OpenFlowLog.Tag.FGS,
                    "pipeline_summary",
                    mapOf(
                        "e2e_ms" to (System.currentTimeMillis() - tCommit),
                        "pcm_collect_ms" to pcmCollectMs,
                        "asr_load_ms" to asrResult.loadMs,
                        "asr_native_ms" to asrResult.nativeMs,
                        "polish_load_ms" to polishResult.loadMs,
                        "polish_prefill_ms" to polishResult.prefillMs,
                        "polish_decode_ms" to polishResult.decodeMs,
                        "polish_first_token_ms" to polishResult.firstTokenMs,
                        "insert_ms" to insertMs,
                    ),
                )
                OpenFlowLog.flush()
                stopSelfSafely()
            }
        }
    }

    private suspend fun recordHistory(
        pcm: ShortArray,
        raw: String,
        finalText: String,
        polishAttempted: Boolean,
        asrModelId: String,
        llmModelId: String,
        asrDurMs: Long,
        polishDurMs: Long?,
    ) {
        if (pcm.isEmpty() || raw.isBlank()) return
        val polished = if (polishAttempted && finalText != raw) finalText else null
        val effectiveLlmId = if (polishAttempted) llmModelId else null
        val record = HistoryRecord(
            id = HistoryStore.newId(),
            createdAtMs = System.currentTimeMillis(),
            sampleRate = 16_000,
            sampleCount = pcm.size,
            rawText = raw,
            polishedText = polished,
            asrModelId = asrModelId,
            llmModelId = effectiveLlmId,
            asrDurationMs = asrDurMs,
            polishDurationMs = if (polishAttempted) polishDurMs else null,
        )
        try {
            historyStore.append(record, pcm)
            OpenFlowLog.d(
                OpenFlowLog.Tag.FGS,
                "history_appended",
                mapOf("id" to record.id, "samples" to pcm.size),
            )
        } catch (t: Throwable) {
            OpenFlowLog.e(OpenFlowLog.Tag.FGS, "history_append_failed", t)
        }
    }

    private fun handleCancel() {
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "cancel_entered", mapOf("capturing" to capturing))
        // Signal any in-flight streaming polish to abort. The native sample
        // loop checks this on the next token callback and breaks; whatever
        // has already been written to the EditText is kept per design.
        cancelStreamingFlag = true
        rmsJob?.cancel()
        warmupJob?.cancel()
        if (capturing) {
            recorder.stop()
            capturing = false
        }
        stopSelfSafely()
    }

    private suspend fun transcribe(pcm: ShortArray, settings: FlowSettings): WhisperResult {
        if (pcm.isEmpty()) {
            OpenFlowLog.d(OpenFlowLog.Tag.ASR, "asr_empty_pcm")
            return WhisperResult("")
        }
        val engine = ensureWhisperEngine(settings)
            ?: return WhisperResult("")
        val dictionaryPrompt = CustomDictionary.toWhisperPrompt(settings.customDictionaryEntries)
        return engine.transcribe(pcm, language = "auto", initialPrompt = dictionaryPrompt)
    }

    private suspend fun polish(text: String, llmModelId: String): PolishResult {
        if (text.isBlank()) return PolishResult(text)
        val engine = ensurePolishEngine(settingsStore.current(), llmModelId)
            ?: return PolishResult(text)
        return engine.polish(text)
    }

    private fun resolveWhisperModel(s: FlowSettings): ModelEntry =
        ModelCatalog.byId(s.whisperModelId) ?: ModelCatalog.whisperDefault

    private fun resolveLlmModel(s: FlowSettings, llmModelId: String? = null): ModelEntry =
        ModelCatalog.byId(llmModelId ?: s.llmModelId) ?: ModelCatalog.llmDefault

    private fun ensureWhisperEngine(s: FlowSettings): WhisperEngine? {
        val model = resolveWhisperModel(s)
        val ready = modelStore.isReady(model)
        val dictionaryPrompt = CustomDictionary.toWhisperPrompt(s.customDictionaryEntries)
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "asr_model_check",
            mapOf(
                "modelId" to model.id,
                "ready" to ready,
                "dictionaryEntries" to s.customDictionaryEntries.size,
                "dictionaryPromptLen" to (dictionaryPrompt?.length ?: 0),
            ),
        )
        if (!ready) {
            Log.w(TAG, "Whisper model not ready: ${model.id}")
            return null
        }
        return whisperEngine ?: WhisperEngine(modelStore.pathFor(model).absolutePath)
            .also { whisperEngine = it }
    }

    /**
     * Phase 4 streaming insert. Snapshots the focused editable, starts a
     * `polishStreaming` JNI call, and pipes each cleaned delta into the field
     * via a Main-dispatcher writer coroutine that debounces by 80 ms / 4 chars
     * to avoid IME flicker.
     *
     * Returns the [PolishResult] when streaming succeeded (text is already
     * in the field). Returns null if the caller should fall back to the batch
     * polish+insert path — happens when there's no focused editable, the LLM
     * model isn't ready, the streaming call failed mid-flight, or no a11y
     * write ever succeeded (so the field can't be assumed to hold the text).
     *
     * Cancel semantics: if [cancelStreamingFlag] is set mid-stream, the next
     * token callback returns false → native sample loop breaks → whatever was
     * already written to the field is kept (see rules.md Phase 4 decision).
     */
    private suspend fun tryStreamingPolishAndInsert(
        rawText: String,
        llmModelId: String,
    ): PolishResult? {
        val s = settingsStore.current()
        val engine = ensurePolishEngine(s, llmModelId) ?: return null
        val node = FlowAccessibilityService.instance?.currentEditableNode() ?: run {
            OpenFlowLog.d(OpenFlowLog.Tag.LLM, "stream_no_editable_node_fallback")
            return null
        }
        runCatching { node.refresh() }
        val handle = TextInserter.startStreaming(node)

        val accum = StringBuilder()
        val accumLock = Any()
        val tick = Channel<Unit>(Channel.CONFLATED)
        var anyWriteOk = false

        val writerJob = scope.launch(Dispatchers.Default) {
            var lastFlushedLen = 0
            var lastFlushMs = 0L

            fun maybeFlush(force: Boolean) {
                val snap = synchronized(accumLock) { accum.toString() }
                val now = System.currentTimeMillis()
                val grew = snap.length - lastFlushedLen
                if (grew <= 0) return
                if (!force && grew < DEBOUNCE_CHARS && now - lastFlushMs < DEBOUNCE_MS) return
                val ok = runCatching { TextInserter.updateStreaming(handle, snap) }
                    .getOrElse { false }
                if (ok) {
                    anyWriteOk = true
                    lastFlushedLen = snap.length
                    lastFlushMs = now
                }
            }

            try {
                for (signal in tick) maybeFlush(force = false)
                maybeFlush(force = true)
            } catch (_: Throwable) {
                // Channel closed or write threw — swallow; finalize block below
                // handles the corrective write.
            }
        }

        val polishResult = engine.polishStreaming(rawText) { delta ->
            if (cancelStreamingFlag) return@polishStreaming false
            synchronized(accumLock) { accum.append(delta) }
            tick.trySend(Unit)
            true
        }

        tick.close()
        writerJob.join()

        // Final corrective write if surrounding-quote stripping / final trim
        // changed the canonical text vs what we streamed.
        if (anyWriteOk && polishResult.text != accum.toString()) {
            val ok = runCatching { TextInserter.updateStreaming(handle, polishResult.text) }
                .getOrElse { false }
            OpenFlowLog.d(
                OpenFlowLog.Tag.LLM,
                "stream_final_correction",
                mapOf("ok" to ok, "finalLen" to polishResult.text.length),
            )
        }

        return if (anyWriteOk) polishResult else null
    }

    private fun ensurePolishEngine(s: FlowSettings, llmModelId: String? = null): PolishEngine? {
        val model = resolveLlmModel(s, llmModelId)
        val ready = modelStore.isReady(model)
        OpenFlowLog.d(
            OpenFlowLog.Tag.LLM,
            "llm_model_check",
            mapOf("modelId" to model.id, "ready" to ready),
        )
        if (!ready) {
            Log.w(TAG, "LLM model not ready: ${model.id}")
            return null
        }
        val backendAvailability = InferenceBackendRuntime.resolve(applicationContext, s)
        OpenFlowLog.d(
            OpenFlowLog.Tag.LLM,
            "llm_backend_resolved",
            mapOf(
                "accelEnabled" to s.llmAccelerationEnabled,
                "requested" to backendAvailability.requested.id,
                "resolved" to backendAvailability.resolved.name,
                "supported" to backendAvailability.supported,
                "reason" to (backendAvailability.unavailableReason?.name ?: ""),
                "nGpuLayers" to backendAvailability.nGpuLayers,
            ),
        )
        val modelPath = modelStore.pathFor(model).absolutePath
        val key = PolishEngineKey(
            modelPath = modelPath,
            isQwen3 = model.id.startsWith("qwen3-"),
            backend = backendAvailability.resolved,
            nGpuLayers = backendAvailability.nGpuLayers,
        )
        polishEngine?.takeIf { polishEngineKey == key }?.let { return it }
        polishEngine?.let { old -> scope.launch { old.release() } }
        return PolishEngine(
            modelPath = modelPath,
            isQwen3 = key.isQwen3,
            backend = key.backend,
            nGpuLayers = key.nGpuLayers,
        ).also {
            polishEngine = it
            polishEngineKey = key
        }
    }

    private fun runInsertion(text: String): PipelineResult {
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "insert_call",
            mapOf(
                "thread" to Thread.currentThread().name,
                "finalTextLen" to text.length,
            ),
        )
        if (text.isBlank()) {
            return decideOutcome(text = text, nodeAvailable = false, setTextOk = false, clipboardOk = true)
        }
        val a11y = FlowAccessibilityService.instance
        val node = a11y?.currentEditableNode()
        val refreshOk = node?.let { runCatching { it.refresh() }.getOrDefault(false) }
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "insert_node_state",
            mapOf(
                "a11yNull" to (a11y == null),
                "nodeNull" to (node == null),
                "isEditable" to node?.isEditable,
                "isFocused" to node?.isFocused,
                "refresh" to refreshOk,
                "cls" to node?.className,
                "windowId" to node?.windowId,
                "pkg" to node?.packageName,
            ),
        )
        val setTextOk = node?.let { TextInserter.insertAtCursor(it, text) } ?: false
        OpenFlowLog.d(OpenFlowLog.Tag.INSERT, "insert_set_text", mapOf("ok" to setTextOk))
        val needsClipboard = node == null || !setTextOk
        val clipboardOk = if (needsClipboard) TextInserter.copyToClipboard(applicationContext, text) else true
        val outcome = decideOutcome(
            text = text,
            nodeAvailable = node != null,
            setTextOk = setTextOk,
            clipboardOk = clipboardOk,
        )
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "insert_outcome",
            mapOf("outcome" to outcome::class.simpleName),
        )
        return outcome
    }

    private suspend fun applyResult(result: PipelineResult) {
        val state = ballStateFor(result)
        val pill = pillFor(result)
        withContext(Dispatchers.Main) {
            val controller = FlowAccessibilityService.instance?.overlayController
                ?: return@withContext
            controller.setBallState(state)
            if (pill != null) controller.showPill(pill)
        }
    }

    private suspend fun pushState(state: BallState) {
        withContext(Dispatchers.Main) {
            FlowAccessibilityService.instance?.overlayController?.setBallState(state)
        }
    }

    private fun stopSelfSafely() {
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "stop_self")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        OpenFlowLog.d(
            OpenFlowLog.Tag.FGS,
            "fgs_destroyed",
            mapOf("captureJobActive" to (captureJob?.isActive == true)),
        )
        OpenFlowLog.flush()
        rmsJob?.cancel()
        captureJob?.cancel()
        runBlocking {
            whisperEngine?.release()
            polishEngine?.release()
        }
        whisperEngine = null
        polishEngine = null
        polishEngineKey = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OpenFlow")
            .setContentText("正在录音")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "OpenFlow Recording",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) },
                )
            }
        }
    }

    companion object {
        const val ACTION_START = "com.hank.flow.open.action.START"
        const val ACTION_COMMIT = "com.hank.flow.open.action.COMMIT"
        const val ACTION_CANCEL = "com.hank.flow.open.action.CANCEL"

        private const val TAG = "FlowFGS"
        private const val CHANNEL_ID = "openflow_recording"
        private const val NOTIF_ID = 9601

        // Streaming insert debounce — small enough that the user perceives
        // continuous reveal, large enough that we don't hammer a11y with
        // ACTION_SET_TEXT for every single token (which causes IME flicker
        // on some keyboards and a noticeable cursor jitter).
        private const val DEBOUNCE_CHARS = 4
        private const val DEBOUNCE_MS = 80L
    }

    private data class PolishEngineKey(
        val modelPath: String,
        val isQwen3: Boolean,
        val backend: InferenceBackend,
        val nGpuLayers: Int,
    )
}
