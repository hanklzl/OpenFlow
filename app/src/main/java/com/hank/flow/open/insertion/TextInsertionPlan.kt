package com.hank.flow.open.insertion

/**
 * Pure-Kotlin model of the inputs needed to decide what text to set on a
 * focused EditText and where to place the cursor afterwards.
 *
 * Lives separately from [TextInserter] so the splicing logic can be unit-tested
 * without instantiating an `AccessibilityNodeInfo`.
 */
data class TextInsertionState(
    val currentText: String,
    val isShowingHint: Boolean,
    val rawSelectionStart: Int,
    val rawSelectionEnd: Int,
) {
    /**
     * Many EditText subclasses synthesize `node.text == node.hintText` when the
     * field is empty. Treat that as "no existing content" — otherwise the
     * inserted text would be appended after the hint, producing strings like
     * `"Search settings[BLANK_AUDIO]"`. See `docs/dev-harness/incidents/INC-SERVICE-0002.md`.
     */
    val effectiveCurrent: String
        get() = if (isShowingHint) "" else currentText
}

data class TextInsertionPlan(
    val newText: String,
    val cursor: Int,
    val rangeStart: Int,
    val rangeEnd: Int,
)

fun TextInsertionState.planInsertion(insertion: String): TextInsertionPlan {
    val base = effectiveCurrent
    val len = base.length
    val start = if (rawSelectionStart < 0) len else rawSelectionStart.coerceIn(0, len)
    val end = if (rawSelectionEnd < 0) start else rawSelectionEnd.coerceIn(start, len)
    val newText = base.substring(0, start) + insertion + base.substring(end)
    return TextInsertionPlan(
        newText = newText,
        cursor = start + insertion.length,
        rangeStart = start,
        rangeEnd = end,
    )
}
