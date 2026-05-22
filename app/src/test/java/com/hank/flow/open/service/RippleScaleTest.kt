package com.hank.flow.open.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RippleScaleTest {

    @Test
    fun silent_rms_uses_quiet_max() {
        assertEquals(1.2f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = 0f), 1e-4f)
    }

    @Test
    fun full_rms_uses_loud_max() {
        assertEquals(1.7f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = 1f), 1e-4f)
    }

    @Test
    fun midpoint_rms_interpolates_linearly() {
        assertEquals(1.45f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = 0.5f), 1e-4f)
    }

    @Test
    fun negative_rms_clamps_to_quiet_max() {
        assertEquals(1.2f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = -0.3f), 1e-4f)
    }

    @Test
    fun rms_above_one_clamps_to_loud_max() {
        assertEquals(1.7f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = 1.5f), 1e-4f)
    }

    @Test
    fun NaN_rms_falls_back_to_quiet_max() {
        // RMS can be NaN if AudioRecord delivers an empty frame; never propagate
        // into the animator — would blow up the canvas transform.
        assertEquals(1.2f, rippleMaxScale(quietMax = 1.2f, loudMax = 1.7f, rms = Float.NaN), 1e-4f)
    }
}
