package com.hank.flow.open.insertion

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
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

    /**
     * Fallback writer used by [com.hank.flow.open.service.RecordingForegroundService]
     * when no focused editable is available or `ACTION_SET_TEXT` rejects the edit.
     *
     * Not a primary insertion path: per `service/rules.md` MUST-NOT 1 we never
     * trigger `ACTION_PASTE` ourselves. The user is told via the result pill so
     * they can manually paste later.
     *
     * Marks the clip with `EXTRA_IS_SENSITIVE = true` on Android 13+ so the
     * clipboard preview doesn't surface arbitrary speech transcripts.
     */
    fun copyToClipboard(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenFlow", text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        cm.setPrimaryClip(clip)
        OpenFlowLog.d(
            OpenFlowLog.Tag.INSERT,
            "clipboard_fallback_ok",
            mapOf("textLen" to text.length),
        )
        true
    } catch (t: Throwable) {
        OpenFlowLog.e(OpenFlowLog.Tag.INSERT, "clipboard_fallback_failed", t)
        false
    }
}
