package com.hank.flow.open.llm

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
}
