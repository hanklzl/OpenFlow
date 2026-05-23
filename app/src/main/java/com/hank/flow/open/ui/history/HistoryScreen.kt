package com.hank.flow.open.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hank.flow.open.OpenFlowApp
import com.hank.flow.open.R
import com.hank.flow.open.audio.PcmPlayer
import com.hank.flow.open.history.HistoryRecord
import com.hank.flow.open.history.HistoryStore
import com.hank.flow.open.model.ModelCatalog
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { OpenFlowApp.instance.historyStore }
    val records by store.records.collectAsState()
    val scope = rememberCoroutineScope()
    val player = remember { PcmPlayer() }
    var confirmClear by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        scope.launch { store.ensureLoaded() }
        onDispose { player.release() }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.history_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (records.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.history_clear_all))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryCard(
                        record = record,
                        onPlay = {
                            scope.launch {
                                val pcm = store.loadPcm(record) ?: return@launch
                                player.play(pcm, record.sampleRate)
                            }
                        },
                        onCopyPolished = {
                            val text = record.polishedText ?: record.rawText
                            copyAndToast(context, text)
                        },
                        onCopyRaw = { copyAndToast(context, record.rawText) },
                        onDelete = { scope.launch { store.delete(record.id) } },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { store.clearAll() }
                }) { Text(stringResource(R.string.history_clear_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.history_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryCard(
    record: HistoryRecord,
    onPlay: () -> Unit,
    onCopyPolished: () -> Unit,
    onCopyRaw: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativeTime(record.createdAtMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${record.durationMs} ms · ${record.sampleCount} samples",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.history_delete),
                    )
                }
            }

            val displayText = record.polishedText ?: record.rawText
            Text(
                text = displayText.ifBlank { "(空)" },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )

            if (record.polishedText == null) {
                Spacer(Modifier.height(6.dp))
                Chip(stringResource(R.string.history_not_polished))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stringResource(R.string.history_asr_label)}: ${displayModel(record.asrModelId)} · ${record.asrDurationMs} ms",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (record.llmModelId != null && record.polishDurationMs != null) {
                Text(
                    text = "${stringResource(R.string.history_polish_label)}: ${displayModel(record.llmModelId)} · ${record.polishDurationMs} ms",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.history_play),
                    )
                }
                IconButton(onClick = onCopyPolished) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.history_copy_polished),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (record.polishedText != null && record.rawText != record.polishedText) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }

            if (expanded && record.polishedText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.history_raw_text_header),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.rawText.ifBlank { "(空)" },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCopyRaw) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.history_copy_raw),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clickable(enabled = false) {},
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

private fun displayModel(id: String): String =
    ModelCatalog.byId(id)?.displayName ?: id

private fun relativeTime(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(
        ms,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private fun copyAndToast(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
    Toast.makeText(context, context.getString(R.string.history_copied), Toast.LENGTH_SHORT).show()
}
