package com.hank.flow.open.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Persists voice input history (raw PCM + ASR/polish text + model + timing).
 *
 * Storage layout under [historyDir]:
 *  - `index.json` — JSON object {v:1, items:[{...}, ...]} sorted newest-first
 *  - `<id>.pcm`   — raw little-endian PCM-16 mono samples for each record
 *
 * All public methods are mutex-serialized. UI observes [records].
 */
class HistoryStore(private val historyDir: File) {

    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()
    private var loaded = false

    private val indexFile: File get() = File(historyDir, INDEX_FILE)

    suspend fun ensureLoaded() = mutex.withLock { ensureLoadedLocked() }

    suspend fun append(record: HistoryRecord, pcm: ShortArray) = mutex.withLock {
        ensureLoadedLocked()
        if (!historyDir.exists()) historyDir.mkdirs()
        writePcmFile(audioFileFor(record.id), pcm)
        val combined = (listOf(record) + _records.value)
            .sortedByDescending { it.createdAtMs }
        val (kept, evicted) = if (combined.size > MAX_RECORDS) {
            combined.take(MAX_RECORDS) to combined.drop(MAX_RECORDS)
        } else combined to emptyList()
        evicted.forEach { audioFileFor(it.id).delete() }
        _records.value = kept
        writeIndex(kept)
    }

    suspend fun delete(id: String) = mutex.withLock {
        ensureLoadedLocked()
        val remaining = _records.value.filterNot { it.id == id }
        if (remaining.size == _records.value.size) return@withLock
        audioFileFor(id).delete()
        _records.value = remaining
        writeIndex(remaining)
    }

    suspend fun clearAll() = mutex.withLock {
        ensureLoadedLocked()
        _records.value.forEach { audioFileFor(it.id).delete() }
        _records.value = emptyList()
        if (indexFile.exists()) indexFile.delete()
    }

    suspend fun loadPcm(record: HistoryRecord): ShortArray? = mutex.withLock {
        val file = audioFileFor(record.id)
        if (!file.exists()) return@withLock null
        readPcmFile(file)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!indexFile.exists()) return
        runCatching {
            val text = indexFile.readText()
            val json = JSONObject(text)
            val version = json.optInt("v", 1)
            if (version != 1) return@runCatching
            val items = json.optJSONArray("items") ?: JSONArray()
            val parsed = ArrayList<HistoryRecord>(items.length())
            for (i in 0 until items.length()) {
                parsed.add(items.getJSONObject(i).toRecord())
            }
            _records.value = parsed.sortedByDescending { it.createdAtMs }
        }
    }

    private fun writeIndex(list: List<HistoryRecord>) {
        if (!historyDir.exists()) historyDir.mkdirs()
        val items = JSONArray()
        list.forEach { items.put(it.toJson()) }
        val root = JSONObject().apply {
            put("v", 1)
            put("items", items)
        }
        val tmp = File(historyDir, INDEX_FILE + ".tmp")
        tmp.writeText(root.toString())
        if (indexFile.exists()) indexFile.delete()
        tmp.renameTo(indexFile)
    }

    private fun audioFileFor(id: String) = File(historyDir, "$id.pcm")

    companion object {
        const val MAX_RECORDS = 100
        private const val INDEX_FILE = "index.json"

        fun newId(): String = UUID.randomUUID().toString()

        internal fun writePcmFile(file: File, pcm: ShortArray) {
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buf.putShort(it) }
            file.writeBytes(buf.array())
        }

        internal fun readPcmFile(file: File): ShortArray {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = ShortArray(bytes.size / 2)
            for (i in out.indices) out[i] = buf.short
            return out
        }

        private fun HistoryRecord.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("createdAtMs", createdAtMs)
            put("sampleRate", sampleRate)
            put("sampleCount", sampleCount)
            put("rawText", rawText)
            putOrNull("polishedText", polishedText)
            put("asrModelId", asrModelId)
            putOrNull("llmModelId", llmModelId)
            put("asrDurationMs", asrDurationMs)
            putOrNull("polishDurationMs", polishDurationMs)
        }

        private fun JSONObject.toRecord(): HistoryRecord = HistoryRecord(
            id = getString("id"),
            createdAtMs = getLong("createdAtMs"),
            sampleRate = getInt("sampleRate"),
            sampleCount = getInt("sampleCount"),
            rawText = optString("rawText", ""),
            polishedText = optNullableString("polishedText"),
            asrModelId = getString("asrModelId"),
            llmModelId = optNullableString("llmModelId"),
            asrDurationMs = getLong("asrDurationMs"),
            polishDurationMs = optNullableLong("polishDurationMs"),
        )

        private fun JSONObject.optNullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        private fun JSONObject.optNullableLong(key: String): Long? =
            if (!has(key) || isNull(key)) null else getLong(key)

        private fun JSONObject.putOrNull(key: String, value: Any?) {
            if (value == null) put(key, JSONObject.NULL) else put(key, value)
        }
    }
}
