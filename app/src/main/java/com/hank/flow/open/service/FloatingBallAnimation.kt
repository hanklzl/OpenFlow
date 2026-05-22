package com.hank.flow.open.service

import kotlin.math.max
import kotlin.math.min

/**
 * Maps an RMS amplitude (0..1) to the ripple layer's peak scale for the current
 * cycle. Each of the 3 ripple layers in [FloatingBallView] uses its own
 * `(quietMax, loudMax)` pair so the outer layers always travel further than
 * the inner ones, but the entire fan widens with louder input.
 *
 * Defensive: clamps RMS into [0, 1] and treats NaN as silent, since the
 * `AudioRecorder` frame loop can briefly emit empty/degenerate buffers.
 */
fun rippleMaxScale(quietMax: Float, loudMax: Float, rms: Float): Float {
    val safeRms = if (rms.isNaN()) 0f else max(0f, min(1f, rms))
    return quietMax + (loudMax - quietMax) * safeRms
}
