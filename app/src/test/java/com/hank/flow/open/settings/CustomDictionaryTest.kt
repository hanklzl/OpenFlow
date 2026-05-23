package com.hank.flow.open.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomDictionaryTest {

    @Test
    fun parseSplitsLinesAndCommasThenNormalizesEntries() {
        val entries = CustomDictionary.parse(" OpenFlow \nwhisper.cpp,  Qwen3  \n\nKubernetes，OAuth ")

        assertEquals(listOf("OpenFlow", "whisper.cpp", "Qwen3", "Kubernetes", "OAuth"), entries)
    }

    @Test
    fun normalizeDeduplicatesCaseInsensitivelyAndKeepsFirstCasing() {
        val entries = CustomDictionary.normalize(
            listOf("OpenFlow", " openflow ", "Qwen3", "qwen3", "whisper.cpp"),
        )

        assertEquals(listOf("OpenFlow", "Qwen3", "whisper.cpp"), entries)
    }

    @Test
    fun serializeRoundTripsAsOneEntryPerLine() {
        val raw = "OpenFlow\nOpenFlow\n whisper.cpp "

        assertEquals("OpenFlow\nwhisper.cpp", CustomDictionary.serialize(CustomDictionary.parse(raw)))
    }

    @Test
    fun addAcceptsPastedMultipleEntries() {
        val entries = CustomDictionary.add(listOf("OpenFlow"), "Qwen3, Kubernetes")

        assertEquals(listOf("OpenFlow", "Qwen3", "Kubernetes"), entries)
    }

    @Test
    fun promptJoinsEntriesAsWhisperContextHints() {
        val prompt = CustomDictionary.toWhisperPrompt(listOf("OpenFlow", "whisper.cpp", "Qwen3"))

        assertEquals("OpenFlow, whisper.cpp, Qwen3", prompt)
    }

    @Test
    fun promptReturnsNullForEmptyDictionary() {
        assertNull(CustomDictionary.toWhisperPrompt(emptyList()))
    }
}
