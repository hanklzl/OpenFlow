package com.hank.flow.open.debug

import com.hank.flow.open.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAssetPipelineRequestTest {

    @Test
    fun fromValuesUsesRunbookDefaults() {
        val request = DebugAssetPipelineRequest.fromValues(
            wavAsset = null,
            whisperId = null,
            lang = null,
            polish = false,
            llmId = null,
            rawText = null,
            maxTokens = null,
        )

        assertEquals("test/jfk.wav", request.wavAsset)
        assertEquals(ModelCatalog.whisperDefault.id, request.whisperId)
        assertEquals("auto", request.lang)
        assertFalse(request.polish)
        assertEquals(ModelCatalog.llmDefault.id, request.llmId)
        assertEquals(null, request.rawText)
        assertEquals(null, request.maxTokens)
    }

    @Test
    fun fromValuesKeepsRawTextForPolishOnlyDebugRuns() {
        val request = DebugAssetPipelineRequest.fromValues(
            wavAsset = "ignored.wav",
            whisperId = "ggml-tiny-q5_1",
            lang = "en",
            polish = true,
            llmId = "qwen3-0.6b-q4_k_m",
            rawText = "嗯这个测试句子需要润色",
            maxTokens = 16,
        )

        assertTrue(request.polish)
        assertEquals("qwen3-0.6b-q4_k_m", request.llmId)
        assertEquals("嗯这个测试句子需要润色", request.rawText)
        assertEquals(16, request.maxTokens)
    }
}
