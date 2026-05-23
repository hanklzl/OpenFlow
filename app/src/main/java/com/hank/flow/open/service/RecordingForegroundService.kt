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
import com.hank.flow.open.insertion.TextInserter
import com.hank.flow.open.insertion.decideOutcome
import com.hank.flow.open.llm.PolishEngine
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import com.hank.flow.open.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var capturing = false

    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val modelStore by lazy { ModelStore(applicationContext) }
    private val historyStore: HistoryStore get() = OpenFlowApp.instance.historyStore

    private var whisperEngine: WhisperEngine? = null
    private var polishEngine: PolishEngine? = null

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
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "recording_started")
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
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "commit_entered", mapOf("capturing" to capturing))
        if (!capturing) {
            stopSelfSafely()
            return
        }
        captureJob = scope.launch {
            try {
                val pcm = recorder.stop()
                capturing = false
                rmsJob?.cancel()
                OpenFlowLog.d(
                    OpenFlowLog.Tag.FGS,
                    "pcm_collected",
                    mapOf("samples" to pcm.size, "durMs" to (pcm.size * 1000L / 16000L)),
                )
                pushState(BallState.Transcribing)

                val settings = settingsStore.current()
                val asrStart = System.currentTimeMillis()
                OpenFlowLog.d(OpenFlowLog.Tag.ASR, "asr_start")
                val raw = transcribe(pcm)
                val asrDurMs = System.currentTimeMillis() - asrStart
                OpenFlowLog.d(
                    OpenFlowLog.Tag.ASR,
                    "asr_done",
                    mapOf(
                        "textLen" to raw.length,
                        "text" to raw.take(40),
                        "durMs" to asrDurMs,
                    ),
                )

                val polishStart = System.currentTimeMillis()
                val polishAttempted = settings.polishEnabled && raw.isNotBlank()
                val finalText = if (polishAttempted) {
                    pushState(BallState.Polishing)
                    polish(raw, settings.llmModelId)
                } else raw
                val polishDurMs = if (polishAttempted) System.currentTimeMillis() - polishStart else null
                OpenFlowLog.d(
                    OpenFlowLog.Tag.LLM,
                    "polish_done",
                    mapOf(
                        "polishEnabled" to settings.polishEnabled,
                        "textLen" to finalText.length,
                        "text" to finalText.take(40),
                        "durMs" to (polishDurMs ?: 0L),
                    ),
                )

                val result = runInsertion(finalText)
                applyResult(result)
                recordHistory(
                    pcm = pcm,
                    raw = raw,
                    finalText = finalText,
                    polishAttempted = polishAttempted,
                    asrModelId = settings.whisperModelId,
                    llmModelId = settings.llmModelId,
                    asrDurMs = asrDurMs,
                    polishDurMs = polishDurMs,
                )
            } catch (t: Throwable) {
                OpenFlowLog.e(OpenFlowLog.Tag.FGS, "commit_failed", t)
                applyResult(PipelineResult.Failed("处理失败:${t.javaClass.simpleName}"))
            } finally {
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
        rmsJob?.cancel()
        if (capturing) {
            recorder.stop()
            capturing = false
        }
        stopSelfSafely()
    }

    private suspend fun transcribe(pcm: ShortArray): String {
        if (pcm.isEmpty()) {
            OpenFlowLog.d(OpenFlowLog.Tag.ASR, "asr_empty_pcm")
            return ""
        }
        val settings = settingsStore.current()
        val model = ModelCatalog.byId(settings.whisperModelId) ?: ModelCatalog.whisperDefault
        val ready = modelStore.isReady(model)
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "asr_model_check",
            mapOf("modelId" to model.id, "ready" to ready),
        )
        if (!ready) {
            Log.w(TAG, "Whisper model not ready: ${model.id}")
            return ""
        }
        val engine = whisperEngine ?: WhisperEngine(modelStore.pathFor(model).absolutePath)
            .also { whisperEngine = it }
        return engine.transcribe(pcm, language = "auto")
    }

    private suspend fun polish(text: String, llmModelId: String): String {
        if (text.isBlank()) return text
        val model = ModelCatalog.byId(llmModelId) ?: ModelCatalog.llmDefault
        val ready = modelStore.isReady(model)
        OpenFlowLog.d(
            OpenFlowLog.Tag.LLM,
            "llm_model_check",
            mapOf("modelId" to model.id, "ready" to ready),
        )
        if (!ready) {
            Log.w(TAG, "LLM model not ready: ${model.id}")
            return text
        }
        val engine = polishEngine ?: PolishEngine(
            modelPath = modelStore.pathFor(model).absolutePath,
            isQwen3 = model.id.startsWith("qwen3-"),
        ).also { polishEngine = it }
        return engine.polish(text)
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
    }
}
