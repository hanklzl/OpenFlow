package com.hank.flow.open.insertion

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object TextInserter {

    fun insertAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = node.text?.toString().orEmpty()
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart < 0) current.length else rawStart.coerceIn(0, current.length)
        val end = if (rawEnd < 0) start else rawEnd.coerceIn(start, current.length)
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
        if (setOk) {
            val cursor = start + text.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
        return setOk
    }
}
