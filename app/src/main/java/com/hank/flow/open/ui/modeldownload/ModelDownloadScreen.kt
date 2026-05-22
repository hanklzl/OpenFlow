package com.hank.flow.open.ui.modeldownload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hank.flow.open.model.DownloadState
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelDownloader
import com.hank.flow.open.model.ModelEntry
import com.hank.flow.open.model.ModelKind
import com.hank.flow.open.model.ModelStore
import com.hank.flow.open.settings.SettingsStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Composable
fun ModelDownloadScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ModelStore(context) }
    val settings = remember { SettingsStore(context) }
    val downloader = remember {
        ModelDownloader(store) { runBlocking { settings.current().mirrorBase } }
    }
    val scope = rememberCoroutineScope()
    val perEntryState = remember { mutableStateMapOf<String, EntryUi>() }
    val settingsState by settings.flow.collectAsState(initial = null)
    var pendingConfirm by remember { mutableStateOf<ModelEntry?>(null) }

    LaunchedEffect(Unit) {
        ModelCatalog.all.forEach { entry ->
            perEntryState[entry.id] = EntryUi(installed = store.isReady(entry))
        }
    }

    val activeWhisperId = settingsState?.whisperModelId
    val activeLlmId = settingsState?.llmModelId

    fun startDownload(entry: ModelEntry) {
        perEntryState[entry.id] = (perEntryState[entry.id] ?: EntryUi()).copy(downloading = true)
        scope.launch {
            downloader.download(entry).collect { state ->
                handleState(perEntryState, entry, state)
            }
        }
    }

    suspend fun persistActive(entry: ModelEntry) {
        when (entry.kind) {
            ModelKind.Whisper -> settings.setWhisperModelId(entry.id)
            ModelKind.Llm -> settings.setLlmModelId(entry.id)
        }
    }

    fun handleSelect(entry: ModelEntry) {
        val current = when (entry.kind) {
            ModelKind.Whisper -> activeWhisperId
            ModelKind.Llm -> activeLlmId
        } ?: return
        val installed = perEntryState[entry.id]?.installed == true
        when (decideSelection(entry.id, current, installed)) {
            SelectionAction.NoOp -> Unit
            SelectionAction.PersistOnly -> scope.launch { persistActive(entry) }
            SelectionAction.ConfirmDownload -> pendingConfirm = entry
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("模型下载", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "下载多个模型后，可在卡片上点选「当前使用」。下次录音将以所选模型生效。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))

        Section("语音识别（Whisper）") {
            ModelCatalog.all.filter { it.kind == ModelKind.Whisper }.forEach { entry ->
                ModelCard(
                    entry = entry,
                    ui = perEntryState[entry.id],
                    isActive = entry.id == activeWhisperId,
                    onSelect = { handleSelect(entry) },
                    onDownload = { startDownload(entry) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Section("文本润色（LLM）") {
            ModelCatalog.all.filter { it.kind == ModelKind.Llm }.forEach { entry ->
                ModelCard(
                    entry = entry,
                    ui = perEntryState[entry.id],
                    isActive = entry.id == activeLlmId,
                    onSelect = { handleSelect(entry) },
                    onDownload = { startDownload(entry) },
                )
            }
        }
    }

    pendingConfirm?.let { entry ->
        ConfirmDownloadDialog(
            entry = entry,
            onConfirm = {
                pendingConfirm = null
                scope.launch { persistActive(entry) }
                if (perEntryState[entry.id]?.downloading != true) startDownload(entry)
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

private data class EntryUi(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
)

private fun handleState(
    states: androidx.compose.runtime.snapshots.SnapshotStateMap<String, EntryUi>,
    entry: ModelEntry,
    state: DownloadState,
) {
    val cur = states[entry.id] ?: EntryUi()
    states[entry.id] = when (state) {
        is DownloadState.Progress -> cur.copy(
            downloading = true,
            progress = if (state.total > 0) state.bytesDownloaded.toFloat() / state.total else 0f,
            message = "${state.bytesDownloaded / (1024 * 1024)} / ${state.total / (1024 * 1024)} MB",
        )
        is DownloadState.Done -> cur.copy(downloading = false, installed = true, progress = 1f, message = "已安装")
        is DownloadState.Failed -> cur.copy(downloading = false, message = "失败：${state.message}")
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    ui: EntryUi?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val state = ui ?: EntryUi()
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onSelect)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (isActive) {
                            Spacer(Modifier.width(6.dp))
                            CurrentChip()
                        }
                    }
                    Text(
                        "${entry.sizeBytes / (1024 * 1024)} MB · ${entry.kind.label()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                when {
                    state.installed -> OutlinedButton(onClick = {}, enabled = false) { Text("已安装") }
                    state.downloading -> OutlinedButton(onClick = {}, enabled = false) { Text("下载中") }
                    else -> Button(onClick = onDownload) { Text("下载") }
                }
            }
            if (state.downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(state.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            } else if (state.message.isNotEmpty() && !state.installed) {
                Spacer(Modifier.height(4.dp))
                Text(state.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            if (isActive && !state.installed && !state.downloading) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前已选中，但文件未就绪 — 请下载",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CurrentChip() {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "当前",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ConfirmDownloadDialog(
    entry: ModelEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeMb = entry.sizeBytes / (1024 * 1024)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载并使用该模型？") },
        text = {
            Text(
                "${entry.displayName} 尚未下载（约 ${sizeMb} MB）。" +
                    "确认后将设为当前，并开始下载；下载完成后下次录音生效。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("下载并设为当前") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun ModelKind.label(): String = when (this) {
    ModelKind.Whisper -> "语音识别"
    ModelKind.Llm -> "润色"
}
