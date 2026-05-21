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
    }

    private fun handleCommit() {
        if (!capturing) {
            stopSelfSafely()
            return
        }
        captureJob = scope.launch {
            val pcm = recorder.stop()
            capturing = false
            val settings = settingsStore.current()
            val raw = transcribe(pcm)
            val finalText = if (settings.polishEnabled) polish(raw, settings.llmModelId) else raw
            if (finalText.isNotBlank()) insertIntoFocusedEditable(finalText)
            stopSelfSafely()
        }
    }

    private fun handleCancel() {
        if (capturing) {
            recorder.stop()
            capturing = false
        }
        stopSelfSafely()
    }

    private suspend fun transcribe(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val settings = settingsStore.current()
        val model = ModelCatalog.byId(settings.whisperModelId) ?: ModelCatalog.whisperDefault
        if (!modelStore.isReady(model)) {
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
        if (!modelStore.isReady(model)) {
            Log.w(TAG, "LLM model not ready: ${model.id}")
            return text
        }
        val engine = polishEngine ?: PolishEngine(modelStore.pathFor(model).absolutePath)
            .also { polishEngine = it }
        return engine.polish(text)
    }

    private fun insertIntoFocusedEditable(text: String) {
        val node = FlowAccessibilityService.instance?.currentEditableNode()
        if (node == null) {
            Log.w(TAG, "No editable node; skip insert")
            return
        }
        val ok = TextInserter.insertAtCursor(node, text)
        Log.d(TAG, "insert=$ok len=${text.length}")
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
