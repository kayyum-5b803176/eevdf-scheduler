package com.eevdf.scheduler.ui.autoswitch

/**
 * Lightweight in-process bus that bridges [BubbleOverlayService] and
 * [com.eevdf.scheduler.ui.task.MainActivity] without Android IPC overhead.
 *
 * Both components run in the same process so volatile reads/writes are
 * sufficient for correct visibility across threads.
 *
 * Ownership rules:
 *  • [timerRunning]       — written by MainActivity's timerCardAction observer;
 *                           read by the service's dot-color update.
 *  • [callTaskRunning]    — true when the call-assigned task is the active timer.
 *                           Drives bubble color differentiation:
 *                             true  → green  (call task is running — tap switches to interrupt)
 *                             false → blue   (interrupt/other task running — tap switches to call)
 *  • [onBubbleTap]        — set by MainActivity.onCreate, cleared in onDestroy;
 *                           invoked by the service when the bubble is tapped.
 *  • [anyTimerRunning]    — true when ANY task timer is active (not just call task).
 *                           Used to decide whether to show bubble on incoming call
 *                           even before handleCallStarted() has run.
 */
object BubbleEventBus {

    /**
     * True while the call task timer is running.
     * Kept for backward compat; prefer [callTaskRunning] for colour logic.
     */
    @Volatile var timerRunning: Boolean = false

    /**
     * True when the currently active timer belongs to the call-assigned task.
     *   true  → bubble tint = green  (#4CAF50)  — tap will pause/resume call task
     *   false → bubble tint = blue   (#1565C0)  — tap will switch TO call task
     */
    @Volatile var callTaskRunning: Boolean = false

    /**
     * True whenever any task timer is active (regardless of which task).
     * Written by MainActivity alongside [timerRunning].
     * Read by [BubbleOverlayService] to implement the "show bubble on any
     * incoming call" mechanism.
     */
    @Volatile var anyTimerRunning: Boolean = false

    /**
     * Callback invoked on the main thread when the user taps the floating
     * bubble.  MainActivity sets this to the appropriate switch/toggle action.
     * Null-safe: the service always uses `?.invoke()`.
     */
    @Volatile var onBubbleTap: (() -> Unit)? = null
}
