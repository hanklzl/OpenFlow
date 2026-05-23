package com.hank.flow.open.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hank.flow.open.R
import com.hank.flow.open.log.LogExporter
import com.hank.flow.open.settings.CustomDictionary
import com.hank.flow.open.settings.FlowSettings
import com.hank.flow.open.settings.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val initial = remember {
        FlowSettings(
            polishEnabled = true,
            swipeUpCancelEnabled = true,
            waveformEnabled = true,
            editBeforeInsertEnabled = false,
            polishWarmupEnabled = true,
            polishStreamingEnabled = true,
            customDictionaryEntries = emptyList(),
            whisperModelId = "",
            llmModelId = "",
            mirrorBase = SettingsStore.DEFAULT_MIRROR,
        )
    }
    val settings by store.flow.collectAsState(initial = initial)
    var pendingDictionaryEntry by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        item {
            Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            Toggle(
                label = "启用润色",
                description = "识别后用本地 LLM 把口语转为通顺书面表达",
                checked = settings.polishEnabled,
                onChange = { scope.launch { store.setPolishEnabled(it) } },
            )
            Toggle(
                label = "上滑取消",
                description = "录音中手指上滑可取消本次识别",
                checked = settings.swipeUpCancelEnabled,
                onChange = { scope.launch { store.setSwipeUpCancel(it) } },
            )
            Toggle(
                label = "录音波形",
                description = "录音时悬浮球展开为带音量波形的胶囊",
                checked = settings.waveformEnabled,
                onChange = { scope.launch { store.setWaveform(it) } },
            )
            Toggle(
                label = "识别后手动确认",
                description = "识别完成后弹出可编辑卡片，确认再写入",
                checked = settings.editBeforeInsertEnabled,
                onChange = { scope.launch { store.setEditBeforeInsert(it) } },
            )
            Toggle(
                label = "录音时预热润色模型",
                description = "录音开始就提前加载 LLM，commit 后润色冷启动接近 0；关闭可降低低端机内存峰值",
                checked = settings.polishWarmupEnabled,
                onChange = { scope.launch { store.setPolishWarmupEnabled(it) } },
            )
            Toggle(
                label = "流式润色写入",
                description = "LLM 出第一个字就开始写入输入框，无需等整段润色完成；个别 IME 可能闪烁可关闭",
                checked = settings.polishStreamingEnabled,
                onChange = { scope.launch { store.setPolishStreamingEnabled(it) } },
            )

            Spacer(Modifier.height(20.dp))
            DictionaryHeader(
                pendingEntry = pendingDictionaryEntry,
                onPendingEntryChange = { pendingDictionaryEntry = it },
                onAddEntry = {
                    val next = CustomDictionary.add(settings.customDictionaryEntries, pendingDictionaryEntry)
                    pendingDictionaryEntry = ""
                    if (next != settings.customDictionaryEntries) {
                        scope.launch { store.setCustomDictionaryEntries(next) }
                    }
                },
                showEmpty = settings.customDictionaryEntries.isEmpty(),
            )
        }
        items(
            items = settings.customDictionaryEntries,
            key = { it.lowercase() },
        ) { entry ->
            DictionaryEntryRow(
                entry = entry,
                onRemoveEntry = {
                    val next = CustomDictionary.remove(settings.customDictionaryEntries, entry)
                    scope.launch { store.setCustomDictionaryEntries(next) }
                },
            )
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text("下载源", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Toggle(
                label = "使用 HuggingFace 镜像 (hf-mirror.com)",
                description = "在国内访问 HuggingFace 缓慢时可启用",
                checked = settings.mirrorBase == SettingsStore.ALT_MIRROR,
                onChange = { useMirror ->
                    scope.launch {
                        store.setMirrorBase(
                            if (useMirror) SettingsStore.ALT_MIRROR else SettingsStore.DEFAULT_MIRROR,
                        )
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
            DiagnosticsSection()
        }
    }
}

@Composable
private fun DictionaryHeader(
    pendingEntry: String,
    onPendingEntryChange: (String) -> Unit,
    onAddEntry: () -> Unit,
    showEmpty: Boolean,
) {
    Text(stringResource(R.string.settings_dictionary_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.settings_dictionary_description),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pendingEntry,
            onValueChange = onPendingEntryChange,
            label = { Text(stringResource(R.string.settings_dictionary_add_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onAddEntry,
            enabled = pendingEntry.isNotBlank(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.settings_dictionary_add_action),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    if (showEmpty) {
        Text(
            stringResource(R.string.settings_dictionary_empty),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun DictionaryEntryRow(entry: String, onRemoveEntry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemoveEntry) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_dictionary_remove_action),
            )
        }
    }
}

@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    Text("诊断", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "导出本机加密日志（AES-CBC）并通过分享面板发出。解密需要 tools/logan/decode-logan.sh。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = !exporting,
        onClick = {
            exporting = true
            scope.launch {
                val result = runCatching { LogExporter.exportToZip(context) }
                exporting = false
                result.onSuccess { zip ->
                    runCatching { LogExporter.share(context, zip) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                "分享失败：${it.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }.onFailure {
                    Toast.makeText(
                        context,
                        "导出失败：${it.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
    ) {
        Text(if (exporting) "正在打包…" else "导出日志")
    }
}

@Composable
private fun Toggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
