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
import com.hank.flow.open.asr.WhisperEngine
import com.hank.flow.open.audio.AudioRecorder
import com.hank.flow.open.insertion.TextInserter
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
import kotlinx.coroutines.launch

/**
 * Foreground service (type=microphone) that owns the audio + ASR + polish + insert pipeline.
 *
 * Lifecycle: a single FGS instance starts on ACTION_START (long-press), keeps recording
 * until ACTION_COMMIT or ACTION_CANCEL, then runs the pipeline and stops itself.
 */
class RecordingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = AudioRecorder()
    private var captureJob: Job? = null
    private var capturing = false

    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val modelStore by lazy { ModelStore(applicationContext) }

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
        capturing = true
        recorder.start(scope)
        Log.d(TAG, "recording started")
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "recording_started")
    }

    private fun handleCommit() {
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "commit_entered", mapOf("capturing" to capturing))
        if (!capturing) {
            stopSelfSafely()
            return
        }
        captureJob = scope.launch {
            val pcm = recorder.stop()
            capturing = false
            OpenFlowLog.d(
                OpenFlowLog.Tag.FGS,
                "pcm_collected",
                mapOf("samples" to pcm.size, "durMs" to (pcm.size * 1000L / 16000L)),
            )
            val settings = settingsStore.current()
            val asrStart = System.currentTimeMillis()
            OpenFlowLog.d(OpenFlowLog.Tag.ASR, "asr_start")
            val raw = transcribe(pcm)
            OpenFlowLog.d(
                OpenFlowLog.Tag.ASR,
                "asr_done",
                mapOf(
                    "textLen" to raw.length,
                    "text" to raw.take(40),
                    "durMs" to (System.currentTimeMillis() - asrStart),
                ),
            )
            OpenFlowLog.d(
                OpenFlowLog.Tag.LLM,
                "polish_start",
                mapOf("polishEnabled" to settings.polishEnabled),
            )
            val polishStart = System.currentTimeMillis()
            val finalText = if (settings.polishEnabled) polish(raw, settings.llmModelId) else raw
            OpenFlowLog.d(
                OpenFlowLog.Tag.LLM,
                "polish_done",
                mapOf(
                    "textLen" to finalText.length,
                    "text" to finalText.take(40),
                    "durMs" to (System.currentTimeMillis() - polishStart),
                ),
            )
            if (finalText.isNotBlank()) insertIntoFocusedEditable(finalText)
            OpenFlowLog.flush()
            stopSelfSafely()
        }
    }

    private fun handleCancel() {
        OpenFlowLog.d(OpenFlowLog.Tag.FGS, "cancel_entered", mapOf("capturing" to capturing))
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
        val engine = polishEngine ?: PolishEngine(modelStore.pathFor(model).absolutePath)
            .also { polishEngine = it }
        return engine.polish(text)
    }

    private fun insertIntoFocusedEditable(text: String) {
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "insert_call",
            mapOf(
                "thread" to Thread.currentThread().name,
                "finalTextLen" to text.length,
            ),
        )
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
        if (node == null) {
            Log.w(TAG, "No editable node; skip insert")
            return
        }
        val ok = TextInserter.insertAtCursor(node, text)
        Log.d(TAG, "insert=$ok len=${text.length}")
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "insert_done",
            mapOf("ok" to ok, "textLen" to text.length),
        )
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
        captureJob?.cancel()
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
