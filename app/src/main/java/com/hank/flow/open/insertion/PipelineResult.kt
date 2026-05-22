package com.hank.flow.open.insertion

/**
 * Outcome of the `ACTION_COMMIT` pipeline: recording → ASR → polish → write-out.
 *
 * Maps to the visual states driven on [com.hank.flow.open.service.BallState]:
 * - [Inserted] → Done
 * - [CopiedToClipboard] → Copied + pill
 * - [EmptyOutput] → silent return to Idle (ASR/polish soft-degradation per
 *   `pipeline/rules.md` MUST 3)
 * - [Failed] → Failed + pill (only for real exceptions: AudioRecord crash,
 *   permission revoked at record-start, etc.)
 */
sealed class PipelineResult {
    object Inserted : PipelineResult()
    object CopiedToClipboard : PipelineResult()
    object EmptyOutput : PipelineResult()
    data class Failed(val reason: String) : PipelineResult()
}

/**
 * Decides which [PipelineResult] to surface from the four signals the FGS
 * collects after `ACTION_COMMIT`. Pure function so the branching can be unit-
 * tested without instantiating Android system objects.
 *
 * @param text final text after ASR (+ optional polish); blank → silent degrade.
 * @param nodeAvailable whether the AccessibilityService reports a focused editable.
 * @param setTextOk result of `ACTION_SET_TEXT` (false when target rejected the edit).
 * @param clipboardOk result of writing to ClipboardManager; only consulted when
 *   falling back. Pass `true` if no fallback attempted.
 */
fun decideOutcome(
    text: String,
    nodeAvailable: Boolean,
    setTextOk: Boolean,
    clipboardOk: Boolean,
): PipelineResult {
    if (text.isBlank()) return PipelineResult.EmptyOutput
    if (nodeAvailable && setTextOk) return PipelineResult.Inserted
    return if (clipboardOk) PipelineResult.CopiedToClipboard
    else PipelineResult.Failed("剪贴板写入失败")
}
