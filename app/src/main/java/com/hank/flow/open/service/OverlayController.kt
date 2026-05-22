package com.hank.flow.open.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.hank.flow.open.log.OpenFlowLog

/**
 * Owns the floating-ball [FloatingBallView] and the result [OverlayPillView]
 * lifecycle on the [WindowManager]. Single-instance per AccessibilityService.
 *
 * State pushes (post-release pipeline + RMS during recording) flow through
 * [setBallState], [pushRms], and [showPill]; the [FlowAccessibilityService]
 * exposes this controller so [RecordingForegroundService] can drive it.
 */
class OverlayController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var pillView: OverlayPillView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private val dismissPill = Runnable { hidePill() }

    fun show(callbacks: FloatingBallView.Listener) {
        OpenFlowLog.d(
            OpenFlowLog.Tag.OVERLAY,
            "overlay_show_call",
            mapOf("alreadyShown" to (ballView != null)),
        )
        if (ballView != null) return
        val view = FloatingBallView(context).also { it.listener = callbacks }
        val sizePx = (BALL_DP * context.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - sizePx - margin()
            y = context.resources.displayMetrics.heightPixels / 2
        }
        try {
            windowManager.addView(view, lp)
            ballView = view
            ballParams = lp
            OpenFlowLog.d(OpenFlowLog.Tag.OVERLAY, "overlay_show_applied")
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
            OpenFlowLog.e(OpenFlowLog.Tag.OVERLAY, "overlay_show_failed", t)
        }
    }

    fun hide() {
        val hadView = ballView != null
        OpenFlowLog.d(OpenFlowLog.Tag.OVERLAY, "overlay_hide_call", mapOf("hadView" to hadView))
        hidePill()
        ballView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        ballView = null
        ballParams = null
    }

    fun moveBy(dx: Float, dy: Float) {
        val view = ballView ?: return
        val lp = ballParams ?: return
        lp.x = (lp.x + dx.toInt()).coerceAtLeast(0)
            .coerceAtMost(context.resources.displayMetrics.widthPixels - view.width)
        lp.y = (lp.y + dy.toInt()).coerceAtLeast(0)
            .coerceAtMost(context.resources.displayMetrics.heightPixels - view.height)
        windowManager.updateViewLayout(view, lp)
        repositionPillBelowBall()
    }

    fun snapToEdge() {
        val view = ballView ?: return
        val lp = ballParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        lp.x = if (lp.x + view.width / 2 < screenW / 2) margin() else screenW - view.width - margin()
        windowManager.updateViewLayout(view, lp)
        repositionPillBelowBall()
    }

    fun setBallState(state: BallState) {
        ballView?.setBallState(state)
    }

    fun pushRms(rms: Float) {
        ballView?.pushRms(rms)
    }

    fun showPill(spec: PillSpec, durationMs: Long = DEFAULT_PILL_DURATION_MS) {
        val ball = ballView ?: return
        mainHandler.removeCallbacks(dismissPill)
        hidePill()
        val pill = OverlayPillView(context).also { it.setSpec(spec) }
        val size = pill.measureSize()
        val wPx = size[0]
        val hPx = size[1]
        val ballLp = ballParams ?: return
        val pillX = ballLp.x + ball.width / 2 - wPx / 2
        val pillY = ballLp.y + ball.height + dp(8f).toInt()
        val lp = WindowManager.LayoutParams(
            wPx,
            hPx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pillX.coerceAtLeast(margin())
                .coerceAtMost(context.resources.displayMetrics.widthPixels - wPx - margin())
            y = pillY.coerceAtMost(context.resources.displayMetrics.heightPixels - hPx - margin())
        }
        try {
            windowManager.addView(pill, lp)
            pillView = pill
            pillParams = lp
            mainHandler.postDelayed(dismissPill, durationMs)
        } catch (t: Throwable) {
            OpenFlowLog.e(OpenFlowLog.Tag.OVERLAY, "pill_show_failed", t)
        }
    }

    private fun hidePill() {
        mainHandler.removeCallbacks(dismissPill)
        pillView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        pillView = null
        pillParams = null
    }

    private fun repositionPillBelowBall() {
        val ball = ballView ?: return
        val pill = pillView ?: return
        val ballLp = ballParams ?: return
        val pillLp = pillParams ?: return
        pillLp.x = (ballLp.x + ball.width / 2 - pill.width / 2)
            .coerceAtLeast(margin())
            .coerceAtMost(context.resources.displayMetrics.widthPixels - pill.width - margin())
        pillLp.y = ballLp.y + ball.height + dp(8f).toInt()
        try { windowManager.updateViewLayout(pill, pillLp) } catch (_: Throwable) {}
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun margin(): Int = dp(24f).toInt()

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    companion object {
        private const val TAG = "OverlayController"
        private const val BALL_DP = 56f
        private const val DEFAULT_PILL_DURATION_MS = 2500L
    }
}
