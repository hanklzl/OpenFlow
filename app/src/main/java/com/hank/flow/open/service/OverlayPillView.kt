package com.hank.flow.open.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import com.hank.flow.open.R

/**
 * Result pill rendered as a separate, non-touchable WindowManager view below the
 * floating ball. Owned and laid out by [OverlayController]; auto-dismissed
 * after [OverlayController.showPill]'s configured duration.
 */
class OverlayPillView(context: Context) : View(context) {

    private var spec: PillSpec = PillSpec("", PillAccent.BLUE)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pill_background)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pill_text)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = dp(12f)
    }

    private val rect = RectF()

    fun setSpec(next: PillSpec) {
        spec = next
        dotPaint.color = accentColor(next.accent)
        invalidate()
    }

    /** Width/height the parent should request when laying out this view. */
    fun measureSize(): IntArray {
        val padH = dp(14f)
        val padV = dp(8f)
        val dotR = dp(4f)
        val gap = dp(8f)
        val textW = textPaint.measureText(spec.text)
        val metrics = textPaint.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val w = (padH + dotR * 2 + gap + textW + padH).toInt()
        val h = (padV + textH + padV).toInt()
        return intArrayOf(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val dotR = dp(4f)
        val dotCx = dp(14f) + dotR
        val cy = h / 2f
        canvas.drawCircle(dotCx, cy, dotR, dotPaint)

        val metrics = textPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        val textX = dotCx + dotR + dp(8f)
        canvas.drawText(spec.text, textX, baseline, textPaint)
    }

    private fun accentColor(accent: PillAccent): Int = when (accent) {
        PillAccent.BLUE -> ContextCompat.getColor(context, R.color.ball_state_copied)
        PillAccent.RED -> ContextCompat.getColor(context, R.color.ball_state_failed)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
