package com.hank.flow.open.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 悬浮球自定义 View。承载手势：按下 → ARMING（300ms 延时进入 RECORDING） → RECORDING → ACTION_UP（送出 confirm 或 cancel）。
 * 不直接持有业务逻辑；通过 [Listener] 回调到 [OverlayController]。
 */
class FloatingBallView(context: Context) : View(context) {

    enum class State { IDLE, ARMING, RECORDING, CANCELING }

    interface Listener {
        fun onRecordStart()
        fun onRecordCancel()
        fun onRecordCommit()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null

    private var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F46E5")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val cancelSwipeThreshold = touchSlop * 6
    private val armDelayMs = 250L

    private val armRunnable = Runnable {
        if (state == State.ARMING) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            state = State.RECORDING
            listener?.onRecordStart()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 4f
        bgPaint.color = when (state) {
            State.IDLE -> Color.parseColor("#4F46E5")
            State.ARMING -> Color.parseColor("#6366F1")
            State.RECORDING -> Color.parseColor("#10B981")
            State.CANCELING -> Color.parseColor("#EF4444")
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)
        if (state == State.RECORDING) {
            rect.set(cx - radius + 8, cy - radius + 8, cx + radius - 8, cy + radius - 8)
            canvas.drawArc(rect, 0f, 360f, false, ringPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
                state = State.ARMING
                postDelayed(armRunnable, armDelayMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                val totalDx = event.rawX - downX
                val totalDy = event.rawY - downY
                val distance = kotlin.math.hypot(totalDx, totalDy)
                if (state == State.ARMING && distance > touchSlop) {
                    // 短按拖动 = 普通拖拽，取消进入录音
                    removeCallbacks(armRunnable)
                    state = State.IDLE
                    dragging = true
                }
                if (dragging) {
                    listener?.onDragMove(dx, dy)
                } else if (state == State.RECORDING || state == State.CANCELING) {
                    state = if (-totalDy > cancelSwipeThreshold) State.CANCELING else State.RECORDING
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(armRunnable)
                when (state) {
                    State.RECORDING -> listener?.onRecordCommit()
                    State.CANCELING -> listener?.onRecordCancel()
                    else -> Unit
                }
                if (dragging) listener?.onDragEnd()
                state = State.IDLE
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
