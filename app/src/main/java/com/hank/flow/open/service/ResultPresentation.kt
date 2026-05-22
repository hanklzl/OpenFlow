package com.hank.flow.open.service

import com.hank.flow.open.insertion.PipelineResult

/**
 * Color accent used for the result pill's leading dot. Maps to color resources
 * inside [OverlayPillView] rather than carrying raw hex around.
 */
enum class PillAccent { BLUE, RED }

/** What the result pill should display. `null` from [pillFor] means no pill. */
data class PillSpec(val text: String, val accent: PillAccent)

/**
 * Maps the pipeline outcome to a visible ball state. Empty/degraded outcomes
 * collapse to [BallState.Idle] so the ball silently returns to rest — per
 * `pipeline/rules.md` MUST 3.
 */
fun ballStateFor(result: PipelineResult): BallState = when (result) {
    PipelineResult.Inserted -> BallState.Done
    PipelineResult.EmptyOutput -> BallState.Idle
    PipelineResult.CopiedToClipboard -> BallState.Copied
    is PipelineResult.Failed -> BallState.Failed(result.reason)
}

/**
 * Returns the pill spec for an outcome, or null when no pill should appear.
 * Only [PipelineResult.CopiedToClipboard] and [PipelineResult.Failed] surface
 * pills; success and silent-degrade outcomes do not interrupt the user.
 */
fun pillFor(result: PipelineResult): PillSpec? = when (result) {
    PipelineResult.Inserted, PipelineResult.EmptyOutput -> null
    PipelineResult.CopiedToClipboard -> PillSpec("已复制 · 可手动粘贴", PillAccent.BLUE)
    is PipelineResult.Failed -> PillSpec(result.reason, PillAccent.RED)
}
