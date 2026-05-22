package com.hank.flow.open.service

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.hank.flow.open.R

/**
 * Floating ball renderer + gesture detector. Gesture states (Idle / Arming /
 * Recording / Canceling) are authored here per `pipeline/rules.md` MUST 2.
 * Pipeline-derived states (Transcribing / Polishing / Done / Copied / Failed)
 * are pushed externally via [setBallState] from
 * [com.hank.flow.open.service.RecordingForegroundService] through
 * [OverlayController].
 *
 * Live RMS amplitudes during recording are forwarded via [pushRms] to drive
 * ripple intensity without re-triggering state transitions on every frame.
 */
class FloatingBallView(context: Context) : View(context) {

    interface Listener {
        fun onRecordStart()
        fun onRecordCancel()
        fun onRecordCommit()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null

    private var ballState: BallState = BallState.Idle
    private var recordingRms: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = colorFromRes(R.color.ball_ripple)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringRect = RectF()

    private var displayedColor: Int = colorFromRes(R.color.ball_state_idle)
    private var displayedIcon: Drawable? = drawable(R.drawable.ic_ball_mic)
    private var ballScale: Float = 1f
    private var iconAlpha: Float = 1f
    private var sparkleAngle: Float = 0f
    private var ringRotation: Float = 0f
    private var haloAlpha: Float = 0f
    private var rippleCycleT: Float = 0f       // 0..1 master phase

    /** [quietMax, loudMax] per ripple layer; outer layer travels further. */
    private val rippleQuietMax = floatArrayOf(1.2f, 1.3f, 1.4f)
    private val rippleLoudMax = floatArrayOf(1.7f, 2.2f, 2.8f)

    private val activeAnimators = mutableListOf<Animator>()

    // Gesture / drag scratch
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val cancelSwipeThreshold = touchSlop * 6
    private val armDelayMs = 250L

    private val armRunnable = Runnable {
        if (ballState is BallState.Arming) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            applyState(BallState.Recording(0f))
            listener?.onRecordStart()
        }
    }

    private val returnToIdle = Runnable { applyState(BallState.Idle) }

    init {
        applyState(BallState.Idle)
    }

    // ---- public API ----

    fun setBallState(state: BallState) {
        applyState(state)
    }

    fun pushRms(rms: Float) {
        recordingRms = if (rms.isNaN()) 0f else rms.coerceIn(0f, 1f)
        // Animator's update listener reads recordingRms each frame; no invalidate needed here.
    }

    // ---- state machine ----

    private fun applyState(next: BallState) {
        if (ballState == next) return
        removeCallbacks(returnToIdle)
        cancelAllAnimators()
        ballState = next
        when (next) {
            BallState.Idle -> enterIdle()
            BallState.Arming -> enterArming()
            is BallState.Recording -> {
                recordingRms = next.rms
                enterRecording()
            }
            BallState.Canceling -> enterCanceling()
            BallState.Transcribing -> enterProcessing(R.drawable.ic_ball_mic, sparkleSpin = false)
            BallState.Polishing -> enterProcessing(R.drawable.ic_ball_sparkle, sparkleSpin = true)
            BallState.Done -> enterResult(
                bg = R.color.ball_state_done,
                icon = R.drawable.ic_ball_check,
                holdMs = DONE_HOLD_MS,
            )
            BallState.Copied -> enterResult(
                bg = R.color.ball_state_copied,
                icon = R.drawable.ic_ball_clipboard,
                holdMs = RESULT_HOLD_MS,
            )
            is BallState.Failed -> enterResult(
                bg = R.color.ball_state_failed,
                icon = R.drawable.ic_ball_close,
                holdMs = RESULT_HOLD_MS,
            )
        }
    }

    private fun enterIdle() {
        haloAlpha = 0f
        animateTo(
            color = R.color.ball_state_idle,
            icon = R.drawable.ic_ball_mic,
            scale = 1f,
            iconAlphaTarget = 1f,
            durationMs = 200L,
        )
    }

    private fun enterArming() {
        haloAlpha = 0f
        animateTo(
            color = R.color.ball_state_arming,
            icon = R.drawable.ic_ball_mic,
            scale = 0.94f,
            iconAlphaTarget = 1f,
            durationMs = 150L,
        )
    }

    private fun enterRecording() {
        haloAlpha = 0f
        animateTo(
            color = R.color.ball_state_recording,
            icon = R.drawable.ic_ball_mic,
            scale = 1f,
            iconAlphaTarget = 1f,
            durationMs = 200L,
        )
        startRipples()
    }

    private fun enterCanceling() {
        animateTo(
            color = R.color.ball_state_canceling,
            icon = R.drawable.ic_ball_close,
            scale = 1f,
            iconAlphaTarget = 1f,
            durationMs = 180L,
        )
        startHaloPulse()
    }

    private fun enterProcessing(iconRes: Int, sparkleSpin: Boolean) {
        haloAlpha = 0f
        animateTo(
            color = R.color.ball_state_processing,
            icon = iconRes,
            scale = 1f,
            iconAlphaTarget = if (sparkleSpin) 1f else 0.7f,
            durationMs = 220L,
        )
        startRingRotation()
        if (sparkleSpin) startSparkleRotation()
    }

    private fun enterResult(bg: Int, icon: Int, holdMs: Long) {
        haloAlpha = 0f
        animateTo(
            color = bg,
            icon = icon,
            scale = 1f,
            iconAlphaTarget = 1f,
            durationMs = 220L,
        )
        startResultFlash()
        postDelayed(returnToIdle, holdMs)
    }

    // ---- animators ----

    private fun animateTo(
        color: Int,
        icon: Int,
        scale: Float,
        iconAlphaTarget: Float,
        durationMs: Long,
    ) {
        val startColor = displayedColor
        val endColor = colorFromRes(color)
        val startScale = ballScale
        val startIconAlpha = iconAlpha
        val nextIcon = drawable(icon)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                displayedColor = ArgbEvaluator().evaluate(t, startColor, endColor) as Int
                ballScale = startScale + (scale - startScale) * t
                iconAlpha = startIconAlpha + (iconAlphaTarget - startIconAlpha) * t
                if (t >= 0.5f && displayedIcon !== nextIcon) {
                    displayedIcon = nextIcon
                }
                invalidate()
            }
            addListener(onEnd = { displayedIcon = nextIcon; invalidate() })
        }
        animator.start()
        activeAnimators += animator
    }

    private fun startRipples() {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                rippleCycleT = va.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
        activeAnimators += anim
    }

    private fun startRingRotation() {
        val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                ringRotation = va.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
        activeAnimators += anim
    }

    private fun startSparkleRotation() {
        val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1600L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                sparkleAngle = va.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
        activeAnimators += anim
    }

    private fun startHaloPulse() {
        val anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                haloAlpha = va.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
        activeAnimators += anim
    }

    private fun startResultFlash() {
        val set = AnimatorSet()
        val flash = ValueAnimator.ofFloat(0.94f, 1.12f, 1f).apply {
            duration = 450L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { va ->
                ballScale = va.animatedValue as Float
                invalidate()
            }
        }
        set.play(flash)
        set.start()
        activeAnimators += set
    }

    private fun cancelAllAnimators() {
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    // ---- rendering ----

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) - dp(4f)

        if (haloAlpha > 0f) {
            haloPaint.color = displayedColor
            haloPaint.alpha = (haloAlpha * 80).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, baseRadius * (1f + 0.18f * haloAlpha), haloPaint)
        }

        if (ballState is BallState.Recording) {
            drawRipples(canvas, cx, cy, baseRadius)
        }

        val r = baseRadius * ballScale
        bgPaint.color = displayedColor
        canvas.drawCircle(cx, cy, r, bgPaint)

        if (ballState is BallState.Transcribing || ballState is BallState.Polishing) {
            val ringR = baseRadius * 0.96f
            ringRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            ringPaint.color = colorFromRes(R.color.ball_state_processing_ring)
            canvas.drawArc(ringRect, ringRotation - 60f, 120f, false, ringPaint)
        }

        val icon = displayedIcon ?: return
        val iconSize = dp(22f).toInt()
        val ix = (cx - iconSize / 2f).toInt()
        val iy = (cy - iconSize / 2f).toInt()
        icon.setBounds(ix, iy, ix + iconSize, iy + iconSize)
        icon.alpha = (iconAlpha * 255).toInt().coerceIn(0, 255)
        if (ballState is BallState.Polishing) {
            canvas.save()
            canvas.rotate(sparkleAngle, cx, cy)
            icon.draw(canvas)
            canvas.restore()
        } else {
            icon.draw(canvas)
        }
    }

    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        for (layer in 0..2) {
            val phase = (rippleCycleT + layer * 0.33f) % 1f
            val maxScale = rippleMaxScale(
                quietMax = rippleQuietMax[layer],
                loudMax = rippleLoudMax[layer],
                rms = recordingRms,
            )
            val innerR = baseRadius * 0.4f
            val outerR = baseRadius * maxScale
            val r = innerR + (outerR - innerR) * phase
            val alpha = ((1f - phase) * 153f).toInt().coerceIn(0, 255)
            ripplePaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, ripplePaint)
        }
    }

    // ---- gesture ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isGestureAllowed(ballState)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
                applyState(BallState.Arming)
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
                if (ballState is BallState.Arming && distance > touchSlop) {
                    removeCallbacks(armRunnable)
                    applyState(BallState.Idle)
                    dragging = true
                }
                if (dragging) {
                    listener?.onDragMove(dx, dy)
                } else if (ballState is BallState.Recording || ballState is BallState.Canceling) {
                    val want = if (-totalDy > cancelSwipeThreshold)
                        BallState.Canceling else BallState.Recording(recordingRms)
                    if (ballState != want) applyState(want)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(armRunnable)
                when (ballState) {
                    is BallState.Recording -> {
                        listener?.onRecordCommit()
                        // Optimistic transition: FGS pushes the canonical state
                        // (still Transcribing) within ms, so this just removes
                        // the brief flash of Recording-with-finger-off.
                        applyState(BallState.Transcribing)
                    }
                    BallState.Canceling -> {
                        listener?.onRecordCancel()
                        postDelayed({ applyState(BallState.Idle) }, 200L)
                    }
                    else -> {
                        if (dragging) listener?.onDragEnd()
                        applyState(BallState.Idle)
                    }
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(armRunnable)
        removeCallbacks(returnToIdle)
        cancelAllAnimators()
    }

    // ---- helpers ----

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun colorFromRes(resId: Int): Int =
        ContextCompat.getColor(context, resId)

    private fun drawable(resId: Int): Drawable? =
        ContextCompat.getDrawable(context, resId)?.mutate()

    companion object {
        private const val DONE_HOLD_MS = 1050L
        private const val RESULT_HOLD_MS = 2500L

        fun isGestureAllowed(state: BallState): Boolean = when (state) {
            BallState.Idle, BallState.Arming, BallState.Canceling -> true
            is BallState.Recording -> true
            BallState.Transcribing, BallState.Polishing,
            BallState.Done, BallState.Copied -> false
            is BallState.Failed -> false
        }
    }
}

private inline fun Animator.addListener(crossinline onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) { onEnd() }
    })
}
