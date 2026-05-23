package com.hank.flow.open.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore() = HistoryStore(tempFolder.root)

    private fun sampleRecord(
        id: String = HistoryStore.newId(),
        createdAtMs: Long = System.currentTimeMillis(),
        rawText: String = "hello",
        polishedText: String? = "Hello.",
        sampleCount: Int = 8,
        llmModelId: String? = "qwen",
        polishDurationMs: Long? = 250L,
    ) = HistoryRecord(
        id = id,
        createdAtMs = createdAtMs,
        sampleRate = 16_000,
        sampleCount = sampleCount,
        rawText = rawText,
        polishedText = polishedText,
        asrModelId = "whisper-small",
        llmModelId = llmModelId,
        asrDurationMs = 120L,
        polishDurationMs = polishDurationMs,
    )

    private fun pcm(size: Int, fill: Short = 42): ShortArray = ShortArray(size) { fill }

    @Test
    fun appendWritesRecordAndPcmFile() = runBlocking {
        val store = newStore()
        val record = sampleRecord(sampleCount = 16)
        val data = pcm(16, 7)

        store.append(record, data)

        val list = store.records.value
        assertEquals(1, list.size)
        assertEquals(record.id, list[0].id)
        val audio = tempFolder.root.resolve("${record.id}.pcm")
        assertTrue(audio.exists())
        assertEquals(32L, audio.length())
    }

    @Test
    fun deleteRemovesRecordAndPcmFile() = runBlocking {
        val store = newStore()
        val record = sampleRecord()
        store.append(record, pcm(8))
        assertTrue(tempFolder.root.resolve("${record.id}.pcm").exists())

        store.delete(record.id)

        assertTrue(store.records.value.isEmpty())
        assertFalse(tempFolder.root.resolve("${record.id}.pcm").exists())
    }

    @Test
    fun fifoEvictsOldestWhenAboveCap() = runBlocking {
        val store = newStore()
        val ids = ArrayList<String>()
        repeat(HistoryStore.MAX_RECORDS + 5) { idx ->
            val record = sampleRecord(
                createdAtMs = 1_000_000L + idx,
                sampleCount = 4,
            )
            ids.add(record.id)
            store.append(record, pcm(4))
        }

        val list = store.records.value
        assertEquals(HistoryStore.MAX_RECORDS, list.size)
        val expectedDeleted = ids.subList(0, 5)
        expectedDeleted.forEach { id ->
            assertFalse(
                "evicted record's pcm should be deleted: $id",
                tempFolder.root.resolve("$id.pcm").exists(),
            )
        }
        val expectedKept = ids.subList(5, ids.size).toSet()
        assertEquals(expectedKept, list.map { it.id }.toSet())
    }

    @Test
    fun loadPcmReturnsOriginalSamples() = runBlocking {
        val store = newStore()
        val record = sampleRecord(sampleCount = 5)
        val data = shortArrayOf(-32768, -1, 0, 1, 32767)
        store.append(record, data)

        val loaded = store.loadPcm(record)
        assertNotNull(loaded)
        assertArrayEquals(data, loaded)
    }

    @Test
    fun loadPcmReturnsNullForMissingFile() = runBlocking {
        val store = newStore()
        val record = sampleRecord()
        val loaded = store.loadPcm(record)
        assertNull(loaded)
    }

    @Test
    fun secondStoreLoadsExistingIndex() = runBlocking {
        val first = newStore()
        val record = sampleRecord(rawText = "你好", polishedText = "你好。")
        first.append(record, pcm(8))

        val second = newStore()
        second.ensureLoaded()
        val list = second.records.value
        assertEquals(1, list.size)
        assertEquals("你好", list[0].rawText)
        assertEquals("你好。", list[0].polishedText)
        assertEquals("whisper-small", list[0].asrModelId)
    }

    @Test
    fun nullableFieldsRoundTrip() = runBlocking {
        val first = newStore()
        val record = sampleRecord(polishedText = null, llmModelId = null, polishDurationMs = null)
        first.append(record, pcm(2))

        val second = newStore()
        second.ensureLoaded()
        val loaded = second.records.value.single()
        assertNull(loaded.polishedText)
        assertNull(loaded.llmModelId)
        assertNull(loaded.polishDurationMs)
    }

    @Test
    fun clearAllWipesEverything() = runBlocking {
        val store = newStore()
        repeat(3) {
            val r = sampleRecord(createdAtMs = it.toLong() * 1000)
            store.append(r, pcm(4))
        }
        assertEquals(3, store.records.value.size)

        store.clearAll()

        assertTrue(store.records.value.isEmpty())
        val pcms = tempFolder.root.listFiles().orEmpty().filter { it.extension == "pcm" }
        assertTrue("no pcm files should remain", pcms.isEmpty())
    }
}
