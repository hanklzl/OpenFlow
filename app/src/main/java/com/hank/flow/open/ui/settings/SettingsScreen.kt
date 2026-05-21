package com.hank.flow.open.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            whisperModelId = "",
            llmModelId = "",
            mirrorBase = SettingsStore.DEFAULT_MIRROR,
        )
    }
    val settings by store.flow.collectAsState(initial = initial)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
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
