package com.hank.flow.open.insertion

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hank.flow.open.log.OpenFlowLog

object TextInserter {

    fun insertAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = node.text?.toString().orEmpty()
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart < 0) current.length else rawStart.coerceIn(0, current.length)
        val end = if (rawEnd < 0) start else rawEnd.coerceIn(start, current.length)
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "inserter_call",
            mapOf(
                "textLen" to text.length,
                "currentLen" to current.length,
                "selStart" to rawStart,
                "selEnd" to rawEnd,
                "coercedStart" to start,
                "coercedEnd" to end,
            ),
        )
        val newText = current.substring(0, start) + text + current.substring(end)
        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            },
        )
        OpenFlowLog.d(OpenFlowLog.Tag.INSERT, "inserter_set_text", mapOf("ok" to setOk))
        if (setOk) {
            val cursor = start + text.length
            val selOk = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            OpenFlowLog.d(
                OpenFlowLog.Tag.INSERT,
                "inserter_set_selection",
                mapOf("ok" to selOk, "cursor" to cursor),
            )
        }
        return setOk
    }
}
