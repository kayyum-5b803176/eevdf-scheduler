package com.eevdf.app.core.control

/**
 * Contract for the floating call bubble overlay.
 *
 * Removes the `task -> autoswitch` edge: `TaskCallSwitchDelegate` used to build
 * an explicit `Intent` against `BubbleOverlayService` and set one of that
 * class's action constants. Both the class reference and the constants were
 * compile-time dependencies on another feature.
 *
 * The implementation lives in the autoswitch feature and owns the details —
 * whether the bubble is enabled in preferences, and whether the service needs
 * `startForegroundService` or a plain `startService`. Callers only say what
 * happened.
 */
interface OverlayController {

    /**
     * A call started. Shows the bubble if the user has it enabled.
     *
     * The enablement check lives in the implementation, so callers do not need
     * to read autoswitch preferences to decide whether to call this.
     */
    fun onCallStarted()

    /** A call ended. Hides the bubble and stops the overlay service. */
    fun onCallEnded()
}
