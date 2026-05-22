package com.hank.flow.open.service

import com.hank.flow.open.insertion.PipelineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultPresentationTest {

    // ----- ballStateFor -----

    @Test
    fun inserted_maps_to_done() {
        assertEquals(BallState.Done, ballStateFor(PipelineResult.Inserted))
    }

    @Test
    fun empty_output_maps_to_idle_for_silent_degrade() {
        // Per pipeline/rules.md MUST 3, model-not-ready / empty ASR must not
        // surface UI feedback. We map to Idle so the ball just fades back.
        assertEquals(BallState.Idle, ballStateFor(PipelineResult.EmptyOutput))
    }

    @Test
    fun copied_maps_to_copied_ball_state() {
        assertEquals(BallState.Copied, ballStateFor(PipelineResult.CopiedToClipboard))
    }

    @Test
    fun failed_carries_reason_into_ball_state() {
        assertEquals(
            BallState.Failed("麦克风被占用"),
            ballStateFor(PipelineResult.Failed("麦克风被占用")),
        )
    }

    // ----- pillFor -----

    @Test
    fun inserted_shows_no_pill() {
        assertNull(pillFor(PipelineResult.Inserted))
    }

    @Test
    fun empty_output_shows_no_pill_silent_degrade() {
        assertNull(pillFor(PipelineResult.EmptyOutput))
    }

    @Test
    fun copied_pill_uses_blue_accent_and_hint_text() {
        assertEquals(
            PillSpec("已复制 · 可手动粘贴", PillAccent.BLUE),
            pillFor(PipelineResult.CopiedToClipboard),
        )
    }

    @Test
    fun failed_pill_uses_red_accent_and_carries_reason() {
        assertEquals(
            PillSpec("麦克风被占用", PillAccent.RED),
            pillFor(PipelineResult.Failed("麦克风被占用")),
        )
    }
}
