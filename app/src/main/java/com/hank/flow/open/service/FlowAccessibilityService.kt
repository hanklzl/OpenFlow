package com.hank.flow.open.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FlowAccessibilityService : AccessibilityService() {

    @Volatile private var lastEditableNode: AccessibilityNodeInfo? = null
    private lateinit var overlay: OverlayController

    private val overlayCallbacks = object : FloatingBallView.Listener {
        override fun onRecordStart() {
            Log.d(TAG, "RecordStart editable=${lastEditableNode != null}")
            startFgsWithAction(RecordingForegroundService.ACTION_START)
        }
        override fun onRecordCancel() {
            Log.d(TAG, "RecordCancel")
            startFgsWithAction(RecordingForegroundService.ACTION_CANCEL)
        }
        override fun onRecordCommit() {
            Log.d(TAG, "RecordCommit")
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    lastEditableNode = source
                    overlay.show(overlayCallbacks)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg == packageName) {
                    overlay.hide()
                    lastEditableNode = null
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        overlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentEditableNode(): AccessibilityNodeInfo? = lastEditableNode

    fun forceHideOverlay() { overlay.hide() }

    companion object {
        private const val TAG = "FlowA11y"

        @Volatile var instance: FlowAccessibilityService? = null
            private set
    }
}
