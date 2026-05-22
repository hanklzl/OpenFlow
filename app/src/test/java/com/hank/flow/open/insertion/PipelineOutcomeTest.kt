package com.hank.flow.open.insertion

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineOutcomeTest {

    @Test
    fun empty_text_returns_empty_output() {
        assertEquals(
            PipelineResult.EmptyOutput,
            decideOutcome(text = "", nodeAvailable = true, setTextOk = true, clipboardOk = true),
        )
    }

    @Test
    fun blank_text_returns_empty_output() {
        // ASR sometimes returns whitespace-only — treat the same as empty.
        assertEquals(
            PipelineResult.EmptyOutput,
            decideOutcome(text = "   \n", nodeAvailable = true, setTextOk = true, clipboardOk = true),
        )
    }

    @Test
    fun text_inserts_when_node_and_set_text_ok() {
        assertEquals(
            PipelineResult.Inserted,
            decideOutcome(text = "hello", nodeAvailable = true, setTextOk = true, clipboardOk = true),
        )
    }

    @Test
    fun no_focus_node_falls_back_to_clipboard() {
        assertEquals(
            PipelineResult.CopiedToClipboard,
            decideOutcome(text = "hello", nodeAvailable = false, setTextOk = false, clipboardOk = true),
        )
    }

    @Test
    fun set_text_failure_falls_back_to_clipboard() {
        // Rare path: ACTION_SET_TEXT can reject (web textfields, custom views).
        assertEquals(
            PipelineResult.CopiedToClipboard,
            decideOutcome(text = "hello", nodeAvailable = true, setTextOk = false, clipboardOk = true),
        )
    }

    @Test
    fun clipboard_failure_when_fallback_needed_is_failed() {
        assertEquals(
            PipelineResult.Failed("剪贴板写入失败"),
            decideOutcome(text = "hello", nodeAvailable = false, setTextOk = false, clipboardOk = false),
        )
    }

    @Test
    fun clipboard_state_irrelevant_when_inserted_directly() {
        // Direct insert path must not be affected by a stubbed clipboard signal.
        assertEquals(
            PipelineResult.Inserted,
            decideOutcome(text = "hello", nodeAvailable = true, setTextOk = true, clipboardOk = false),
        )
    }
}
