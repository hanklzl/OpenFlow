package com.hank.flow.open.llm

import com.hank.flow.open.llm.PolishEngine.Companion.parsePolishMetric
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishMetricTest {

    private val soh = ""

    @Test
    fun parsesValidHeader() {
        val raw = "${soh}prefill_ms=120,decode_ms=480,first_token_ms=35${soh}这是润色后的文本。"
        val (text, metric) = raw.parsePolishMetric()
        assertEquals("这是润色后的文本。", text)
        assertEquals(120L, metric.prefillMs)
        assertEquals(480L, metric.decodeMs)
        assertEquals(35L, metric.firstTokenMs)
    }

    @Test
    fun handlesNegativeFirstTokenWhenNothingSampled() {
        val raw = "${soh}prefill_ms=120,decode_ms=480,first_token_ms=-1${soh}"
        val (text, metric) = raw.parsePolishMetric()
        assertEquals("", text)
        assertEquals(-1L, metric.firstTokenMs)
    }

    @Test
    fun returnsEmptyMetricWhenHeaderMissing() {
        val raw = "无元数据，直接出文本。"
        val (text, metric) = raw.parsePolishMetric()
        assertEquals("无元数据，直接出文本。", text)
        assertEquals(PolishMetric.EMPTY, metric)
    }

    @Test
    fun returnsEmptyMetricWhenHeaderUnclosed() {
        val raw = "${soh}prefill_ms=10,decode_ms=20,first_token_ms=5 没有结束符"
        val (text, metric) = raw.parsePolishMetric()
        assertEquals(raw, text)
        assertEquals(PolishMetric.EMPTY, metric)
    }

    @Test
    fun returnsEmptyMetricWhenHeaderMalformed() {
        val raw = "${soh}random_garbage${soh}hello"
        val (text, metric) = raw.parsePolishMetric()
        assertEquals("hello", text)
        assertEquals(PolishMetric.EMPTY, metric)
    }
}
