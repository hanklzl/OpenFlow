package com.hank.flow.open.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hank.flow.open.asr.WhisperEngine
import com.hank.flow.open.audio.AudioRecorder
import com.hank.flow.open.llm.PolishEngine
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import com.hank.flow.open.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ModelStore(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val recorder = remember { AudioRecorder() }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasMicPermission = it }

    var recording by remember { mutableStateOf(false) }
    var rms by remember { mutableStateOf(0f) }
    var sampleCount by remember { mutableStateOf(0) }
    var transcribeRunning by remember { mutableStateOf(false) }
    var polishRunning by remember { mutableStateOf(false) }
    var rawText by remember { mutableStateOf("") }
    var polishedText by remember { mutableStateOf("") }
    var transcribeLatencyMs by remember { mutableStateOf(0L) }
    var polishLatencyMs by remember { mutableStateOf(0L) }
    var polishThisRun by remember { mutableStateOf(true) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var rmsJob by remember { mutableStateOf<Job?>(null) }
    val settingsState by settings.flow.collectAsState(initial = null)

    fun log(s: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())
        logLines = (logLines + "[$ts] $s").takeLast(50)
    }

    val whisperModel = ModelCatalog.byId(
        settingsState?.whisperModelId ?: ModelCatalog.whisperDefault.id,
    ) ?: ModelCatalog.whisperDefault
    val llmModel = ModelCatalog.byId(
        settingsState?.llmModelId ?: ModelCatalog.llmDefault.id,
    ) ?: ModelCatalog.llmDefault
    val whisperReady = store.isReady(whisperModel)
    val llmReady = store.isReady(llmModel)

    DisposableEffect(Unit) {
        onDispose {
            rmsJob?.cancel()
            if (recording) recorder.stop()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("调试 / Debug", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "不挂悬浮球，直接验证录音 / ASR / 润色全链路",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(16.dp))

        // ----- Status -----
        StatusCard("麦克风权限", hasMicPermission) {
            Button(
                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                enabled = !hasMicPermission,
            ) { Text(if (hasMicPermission) "已授权" else "去授权") }
        }
        StatusCard("Whisper 模型 (${whisperModel.displayName})", whisperReady) {
            Text(
                text = if (whisperReady) "已安装" else "去模型 tab 下载",
                fontSize = 12.sp,
            )
        }
        StatusCard("Qwen 模型 (${llmModel.displayName})", llmReady) {
            Text(
                text = if (llmReady) "已安装" else "去模型 tab 下载（不开润色时可跳过）",
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ----- Recording controls -----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("录音", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        "${sampleCount / 16000}.${(sampleCount % 16000) / 1600}s · ${sampleCount} samples",
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { rms.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = {
                            if (!hasMicPermission) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@Button
                            }
                            sampleCount = 0
                            rawText = ""
                            polishedText = ""
                            transcribeLatencyMs = 0
                            polishLatencyMs = 0
                            recording = true
                            log("recording start")
                            recorder.start(scope)
                            rmsJob?.cancel()
                            rmsJob = scope.launch {
                                recorder.frames.collectLatest { frame ->
                                    rms = frame.rms
                                    sampleCount += frame.pcm.size
                                }
                            }
                        },
                        enabled = !recording && !transcribeRunning,
                    ) { Text("开始录音") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = {
                            recording = false
                            rmsJob?.cancel()
                            val pcm = recorder.stop()
                            log("recorded ${pcm.size} samples")
                            scope.launch {
                                if (!whisperReady) {
                                    log("whisper model not ready; skip")
                                    return@launch
                                }
                                transcribeRunning = true
                                val t0 = System.currentTimeMillis()
                                val whisperEngine = WhisperEngine(store.pathFor(whisperModel).absolutePath)
                                rawText = whisperEngine.transcribe(pcm, language = "auto")
                                transcribeLatencyMs = System.currentTimeMillis() - t0
                                transcribeRunning = false
                                log("transcribe ${transcribeLatencyMs}ms -> '${rawText.take(60)}'")

                                if (polishThisRun && llmReady && rawText.isNotBlank()) {
                                    polishRunning = true
                                    val p0 = System.currentTimeMillis()
                                    val polishEngine = PolishEngine(store.pathFor(llmModel).absolutePath)
                                    polishedText = polishEngine.polish(rawText)
                                    polishLatencyMs = System.currentTimeMillis() - p0
                                    polishRunning = false
                                    log("polish ${polishLatencyMs}ms -> '${polishedText.take(60)}'")
                                    polishEngine.release()
                                } else if (polishThisRun && !llmReady) {
                                    log("polish skipped: LLM model not ready")
                                }
                                whisperEngine.release()
                            }
                        },
                        enabled = recording,
                    ) { Text("停止 + 识别") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(
                        onClick = {
                            recording = false
                            rmsJob?.cancel()
                            recorder.stop()
                            sampleCount = 0
                            log("cancel")
                        },
                        enabled = recording,
                    ) { Text("取消") }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = polishThisRun, onCheckedChange = { polishThisRun = it })
                    Spacer(Modifier.size(8.dp))
                    Text("本次启用润色", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ----- Results -----
        ResultCard(
            label = "原始转写 (Whisper)",
            text = rawText,
            placeholder = if (transcribeRunning) "识别中…" else "（尚未录音）",
            latencyMs = transcribeLatencyMs,
        )
        Spacer(Modifier.height(12.dp))
        ResultCard(
            label = "润色后 (Qwen)",
            text = polishedText,
            placeholder = when {
                polishRunning -> "润色中…"
                !polishThisRun -> "（本次未启用润色）"
                rawText.isBlank() -> "（尚未识别）"
                else -> "（无）"
            },
            latencyMs = polishLatencyMs,
        )

        Spacer(Modifier.height(16.dp))

        // ----- Log -----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { logLines = emptyList() }) { Text("清空") }
                }
                Spacer(Modifier.height(8.dp))
                logLines.takeLast(20).forEach { line ->
                    Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (logLines.isEmpty()) {
                    Text("（日志为空）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (ok) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun ResultCard(label: String, text: String, placeholder: String, latencyMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (latencyMs > 0) {
                    Text("${latencyMs}ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = text.ifBlank { placeholder },
                fontSize = 14.sp,
                color = if (text.isBlank())
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
