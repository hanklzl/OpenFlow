package com.hank.flow.open.service

/**
 * Visual state of the floating ball. Single source of truth driving
 * [FloatingBallView] rendering + animation; pushed from [OverlayController].
 *
 * Gesture-derived states (Idle / Arming / Recording / Canceling) are still
 * authored inside [FloatingBallView.onTouchEvent] per the pipeline rule
 * "手势状态机由 FloatingBallView 单点判定". The view re-publishes them through
 * its own setter so external observers (FGS) see a consistent stream.
 *
 * Pipeline-derived states (Transcribing / Polishing / Done / Copied / Failed)
 * are pushed by [com.hank.flow.open.service.RecordingForegroundService]
 * after `ACTION_COMMIT`.
 */
sealed class BallState {
    object Idle : BallState()
    object Arming : BallState()

    /** rms is expected to live in [0, 1]; producer (AudioRecorder) already coerces. */
    data class Recording(val rms: Float) : BallState()

    object Canceling : BallState()
    object Transcribing : BallState()
    object Polishing : BallState()
    object Done : BallState()
    object Copied : BallState()

    /** reason populates the pill text; callers must pass a non-blank, user-facing string. */
    data class Failed(val reason: String) : BallState()
}
