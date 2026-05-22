package com.hank.flow.open.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayHideDecisionTest {

    private val ownPkg = "com.hank.flow.open"

    @Test
    fun hides_when_own_main_activity_resumes() {
        assertTrue(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = ownPkg,
                eventClass = "com.hank.flow.open.MainActivity",
                ownPackage = ownPkg,
            ),
        )
    }

    @Test
    fun does_not_hide_when_own_overlay_window_state_changes() {
        // This is the bug case: adding the floating-ball overlay fires
        // TYPE_WINDOW_STATE_CHANGED with pkg=ownPkg and cls="android.view.View".
        // It must NOT be treated as "user returned to our Activity".
        assertFalse(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = ownPkg,
                eventClass = "android.view.View",
                ownPackage = ownPkg,
            ),
        )
    }

    @Test
    fun does_not_hide_when_other_app_window_state_changes() {
        assertFalse(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = "com.android.settings",
                eventClass = "com.android.settings.Settings",
                ownPackage = ownPkg,
            ),
        )
    }

    @Test
    fun does_not_hide_when_event_package_is_null() {
        assertFalse(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = null,
                eventClass = "com.hank.flow.open.MainActivity",
                ownPackage = ownPkg,
            ),
        )
    }

    @Test
    fun does_not_hide_when_event_class_is_null() {
        // Defensive: missing cls falls back to "do not hide" — better to leave
        // overlay visible than to false-positive-hide it on a malformed event.
        assertFalse(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = ownPkg,
                eventClass = null,
                ownPackage = ownPkg,
            ),
        )
    }

    @Test
    fun hides_when_any_own_activity_subclass_resumes() {
        // Future-proofing: if a second Activity is added, the check should match by
        // package-prefix rather than hardcoded MainActivity.
        assertTrue(
            shouldHideOverlayForOwnWindowStateChange(
                eventPackage = ownPkg,
                eventClass = "com.hank.flow.open.ui.debug.SomeOtherActivity",
                ownPackage = ownPkg,
            ),
        )
    }
}
