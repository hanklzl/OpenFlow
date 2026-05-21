package com.hank.flow.open.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hank.flow.open.log.OpenFlowLog

class FlowAccessibilityService : AccessibilityService() {

    @Volatile private var lastEditableNode: AccessibilityNodeInfo? = null
    private lateinit var overlay: OverlayController

    private val overlayCallbacks = object : FloatingBallView.Listener {
        override fun onRecordStart() {
            Log.d(TAG, "RecordStart editable=${lastEditableNode != null}")
            OpenFlowLog.d(
                OpenFlowLog.Tag.A11Y,
                "record_start_fired",
                mapOf("lastEditableNonNull" to (lastEditableNode != null)),
            )
            startFgsWithAction(RecordingForegroundService.ACTION_START)
        }
        override fun onRecordCancel() {
            Log.d(TAG, "RecordCancel")
            OpenFlowLog.d(OpenFlowLog.Tag.A11Y, "record_cancel_fired")
            startFgsWithAction(RecordingForegroundService.ACTION_CANCEL)
        }
        override fun onRecordCommit() {
            Log.d(TAG, "RecordCommit")
            OpenFlowLog.d(
                OpenFlowLog.Tag.A11Y,
                "record_commit_fired",
                mapOf("lastEditableNonNull" to (lastEditableNode != null)),
            )
            startFgsWithAction(RecordingForegroundService.ACTION_COMMIT)
        }
        override fun onDragMove(dx: Float, dy: Float) {
            overlay.moveBy(dx, dy)
        }
        override fun onDragEnd() {
            overlay.snapToEdge()
        }
    }

    private fun startFgsWithAction(action: String) {
        val intent = Intent(this, RecordingForegroundService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 80L
        }
        Log.d(TAG, "Connected.")
        OpenFlowLog.d(OpenFlowLog.Tag.A11Y, "service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        OpenFlowLog.d(
            OpenFlowLog.Tag.A11Y,
            "a11y_event",
            mapOf(
                "type" to eventTypeName(event.eventType),
                "pkg" to event.packageName,
                "cls" to event.className,
            ),
        )
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: run {
                    OpenFlowLog.d(OpenFlowLog.Tag.A11Y, "overlay_show_skipped_no_source")
                    return
                }
                OpenFlowLog.d(
                    OpenFlowLog.Tag.A11Y,
                    "overlay_show_decided",
                    mapOf(
                        "isEditable" to source.isEditable,
                        "windowId" to source.windowId,
                        "viewId" to source.viewIdResourceName,
                        "cls" to source.className,
                    ),
                )
                if (source.isEditable) {
                    lastEditableNode = source
                    overlay.show(overlayCallbacks)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val matchesSelf = pkg == packageName
                OpenFlowLog.d(
                    OpenFlowLog.Tag.A11Y,
                    "overlay_hide_decided",
                    mapOf("pkg" to pkg, "matchesSelf" to matchesSelf),
                )
                if (matchesSelf) {
                    overlay.hide()
                    lastEditableNode = null
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
        OpenFlowLog.d(OpenFlowLog.Tag.A11Y, "service_interrupted")
    }

    override fun onDestroy() {
        OpenFlowLog.d(OpenFlowLog.Tag.A11Y, "service_destroyed")
        overlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentEditableNode(): AccessibilityNodeInfo? = lastEditableNode

    fun forceHideOverlay() { overlay.hide() }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        else -> "TYPE_$type"
    }

    companion object {
        private const val TAG = "FlowA11y"

        @Volatile var instance: FlowAccessibilityService? = null
            private set
    }
}
