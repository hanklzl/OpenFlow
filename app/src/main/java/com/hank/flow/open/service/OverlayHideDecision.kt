package com.hank.flow.open.service

/**
 * Pure decision: should we hide the floating-ball overlay (and drop the
 * cached editable node) in response to a [android.view.accessibility.AccessibilityEvent]
 * of type `TYPE_WINDOW_STATE_CHANGED`?
 *
 * The intent is "user navigated back to our own Activity (Settings / Models / Debug
 * tab), so the ball should disappear there". The trap: our own overlay window also
 * fires `WINDOW_STATE_CHANGED` with `pkg=ownPkg` and `cls="android.view.View"`. A
 * naive `pkg == ownPkg` check immediately hides the overlay right after we add it.
 * See `docs/dev-harness/incidents/INC-SERVICE-0001.md`.
 */
internal fun shouldHideOverlayForOwnWindowStateChange(
    eventPackage: String?,
    eventClass: String?,
    ownPackage: String,
): Boolean {
    if (eventPackage != ownPackage) return false
    val cls = eventClass ?: return false
    // Activity classes live under our own package namespace, e.g.
    // `com.hank.flow.open.MainActivity`. The overlay window's cls is the framework
    // class `android.view.View`, which does NOT start with our package prefix.
    return cls.startsWith("$ownPackage.")
}
