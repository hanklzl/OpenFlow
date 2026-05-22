package com.hank.flow.open.insertion

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hank.flow.open.log.OpenFlowLog

object TextInserter {

    fun insertAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val state = TextInsertionState(
            currentText = node.text?.toString().orEmpty(),
            isShowingHint = node.isShowingHintText,
            rawSelectionStart = node.textSelectionStart,
            rawSelectionEnd = node.textSelectionEnd,
        )
        val plan = state.planInsertion(text)
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "inserter_call",
            mapOf(
                "textLen" to text.length,
                "currentLen" to state.currentText.length,
                "isShowingHint" to state.isShowingHint,
                "effectiveLen" to state.effectiveCurrent.length,
                "selStart" to state.rawSelectionStart,
                "selEnd" to state.rawSelectionEnd,
                "coercedStart" to plan.rangeStart,
                "coercedEnd" to plan.rangeEnd,
            ),
        )
        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    plan.newText,
                )
            },
        )
        OpenFlowLog.d(OpenFlowLog.Tag.INSERT, "inserter_set_text", mapOf("ok" to setOk))
        if (setOk) {
            val selOk = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        plan.cursor,
                    )
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        plan.cursor,
                    )
                },
            )
            OpenFlowLog.d(
                OpenFlowLog.Tag.INSERT,
                "inserter_set_selection",
                mapOf("ok" to selOk, "cursor" to plan.cursor),
            )
        }
        return setOk
    }
}
