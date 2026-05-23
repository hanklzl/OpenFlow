package com.hank.flow.open.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishPromptTest {

    @Test
    fun build_default_does_not_append_no_think() {
        val prompt = PolishPrompt.build("今天 嗯 天气不错")
        assertFalse("default prompt must not contain /no_think", prompt.contains("/no_think"))
        assertTrue(prompt.contains("<|im_start|>user\n今天 嗯 天气不错<|im_end|>"))
    }

    @Test
    fun build_with_no_think_flag_appends_marker_inside_user_segment() {
        val prompt = PolishPrompt.build("今天 嗯 天气不错", appendNoThink = true)
        assertTrue(prompt.contains("<|im_start|>user\n今天 嗯 天气不错\n/no_think<|im_end|>"))
    }

    @Test
    fun build_instructs_model_to_keep_primary_language() {
        val prompt = PolishPrompt.build("今天 嗯 天气不错")
        assertTrue(prompt.contains("保持输入文本的主要语言"))
        assertTrue(prompt.contains("中文输入必须输出中文"))
    }

    @Test
    fun systemPrefixPlusUserSuffixMatchesBuild() {
        val transcript = "今天天气怎么样"
        val full = PolishPrompt.build(transcript, appendNoThink = false)
        val combined = PolishPrompt.systemPrefix() + transcript + PolishPrompt.userSuffix(false)
        assertEquals(full, combined)
    }

    @Test
    fun systemPrefixPlusUserSuffixWithNoThinkMatchesBuild() {
        val transcript = "测试 Qwen3 /no_think 路径"
        val full = PolishPrompt.build(transcript, appendNoThink = true)
        val combined = PolishPrompt.systemPrefix() + transcript + PolishPrompt.userSuffix(true)
        assertEquals(full, combined)
    }

    @Test
    fun systemPrefixIsConstantAcrossCalls() {
        // Same content twice → cached KV signature stays valid across polish calls.
        assertEquals(PolishPrompt.systemPrefix(), PolishPrompt.systemPrefix())
    }

    @Test
    fun systemPrefixEndsAtUserOpener() {
        // The split must end exactly where the user transcript starts so the
        // KV cache covers the system block + ChatML user-opener and nothing more.
        val prefix = PolishPrompt.systemPrefix()
        val tail = "<|im_start|>user\n"
        assertEquals(tail, prefix.takeLast(tail.length))
    }

    @Test
    fun userSuffixDiffersByNoThinkFlag() {
        // Qwen2.5 must not see /no_think (it doesn't recognize it). Qwen3 does.
        assertTrue(PolishPrompt.userSuffix(true).contains("/no_think"))
        assertFalse(PolishPrompt.userSuffix(false).contains("/no_think"))
    }
}
