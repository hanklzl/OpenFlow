package com.hank.flow.open.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * Owns the floating-ball [FloatingBallView] lifecycle on the [WindowManager].
 * Single-instance per AccessibilityService.
 */
class OverlayController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var ballView: FloatingBallView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(callbacks: FloatingBallView.Listener) {
        if (ballView != null) return
        val view = FloatingBallView(context).also { it.listener = callbacks }
        val sizePx = (56 * context.resources.displayMetrics.density).toInt()
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
            x = context.resources.displayMetrics.widthPixels - sizePx - 24
            y = context.resources.displayMetrics.heightPixels / 2
        }
        try {
            windowManager.addView(view, lp)
            ballView = view
            params = lp
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
        }
    }

    fun hide() {
        ballView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        ballView = null
        params = null
    }

    fun moveBy(dx: Float, dy: Float) {
        val view = ballView ?: return
        val lp = params ?: return
        lp.x = (lp.x + dx.toInt()).coerceAtLeast(0)
            .coerceAtMost(context.resources.displayMetrics.widthPixels - view.width)
        lp.y = (lp.y + dy.toInt()).coerceAtLeast(0)
            .coerceAtMost(context.resources.displayMetrics.heightPixels - view.height)
        windowManager.updateViewLayout(view, lp)
    }

    fun snapToEdge() {
        val view = ballView ?: return
        val lp = params ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        lp.x = if (lp.x + view.width / 2 < screenW / 2) 24 else screenW - view.width - 24
        windowManager.updateViewLayout(view, lp)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "OverlayController"
    }
}
