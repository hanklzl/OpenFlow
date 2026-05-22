package com.hank.flow.open.ui.modeldownload

import org.junit.Assert.assertSame
import org.junit.Test

class SelectionDecisionTest {

    @Test
    fun `reselecting the current model is a no-op even when the file is missing`() {
        val action = decideSelection(
            candidateId = "ggml-tiny-q5_1",
            currentActiveId = "ggml-tiny-q5_1",
            installed = false,
        )
        assertSame(SelectionAction.NoOp, action)
    }

    @Test
    fun `reselecting the current installed model is a no-op`() {
        val action = decideSelection(
            candidateId = "ggml-tiny-q5_1",
            currentActiveId = "ggml-tiny-q5_1",
            installed = true,
        )
        assertSame(SelectionAction.NoOp, action)
    }

    @Test
    fun `selecting a different installed model persists without confirmation`() {
        val action = decideSelection(
            candidateId = "ggml-tiny-q5_1",
            currentActiveId = "ggml-small-q5_1",
            installed = true,
        )
        assertSame(SelectionAction.PersistOnly, action)
    }

    @Test
    fun `selecting a different not-installed model asks for confirmation`() {
        val action = decideSelection(
            candidateId = "qwen3-1_7b-q4_k_m",
            currentActiveId = "qwen2_5-1_5b-instruct-q4_k_m",
            installed = false,
        )
        assertSame(SelectionAction.ConfirmDownload, action)
    }
}
