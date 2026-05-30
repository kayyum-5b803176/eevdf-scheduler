package com.eevdf.scheduler.ui.autoswitch

/**
 * Lightweight in-process bus that bridges [BubbleOverlayService] and
 * [com.eevdf.scheduler.ui.task.MainActivity] without Android IPC overhead.
 *
 * Both components run in the same process so volatile reads/writes are
 * sufficient for correct visibility across threads.
 *
 * Ownership rules:
 *  • [timerRunning]  — written by MainActivity's timerCardAction observer;
 *                      read by the service's dot-color update.
 *  • [onBubbleTap]   — set by MainActivity.onCreate, cleared in onDestroy;
 *                      invoked by the service when the bubble is tapped.
 */
object BubbleEventBus {

    /**
     * True while the call task timer is running (TimerCardAction.Pause /
     * TimerCardAction.Cancel).  Drives the bubble dot colour:
     *   true  → green  (#4CAF50)
     *   false → amber  (#FF9800)
     */
    @Volatile var timerRunning: Boolean = false

    /**
     * Callback invoked on the main thread when the user taps the floating
     * bubble.  MainActivity sets this to `{ viewModel.toggleCallTaskTimer() }`.
     * Null-safe: the service always uses `?.invoke()`.
     */
    @Volatile var onBubbleTap: (() -> Unit)? = null
}
