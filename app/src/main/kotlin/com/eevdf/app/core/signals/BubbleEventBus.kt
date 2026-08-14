package com.eevdf.app.core.signals

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between [BubbleOverlayService], [CallSwitchService] and the
 * task UI.
 *
 * PHASE 1 REFACTOR - what changed and why
 * ---------------------------------------
 * Previously this was a bag of bare `@Volatile var`s. Two problems:
 *
 *  1. Consumers could only POLL. BubbleOverlayService had no way to be told the
 *     timer had started, so state changes were observed late or not at all,
 *     depending on when the service happened to look.
 *  2. Any component could write any field, so ownership was documentation only.
 *     A regression in one feature showed up as wrong behaviour in another with
 *     no compiler or test able to see the connection.
 *
 * The fields are now backed by [MutableStateFlow] and additionally exposed as
 * observable [StateFlow]s. **The existing `var` API is unchanged**, so all
 * current call sites keep compiling as-is - this is deliberately a drop-in
 * replacement, not a migration.
 *
 * HOW TO USE THE NEW API
 * ----------------------
 * Instead of reading `BubbleEventBus.anyTimerRunning` on a handler tick:
 *
 *     lifecycleScope.launch {
 *         BubbleEventBus.anyTimerRunningFlow.collect { running -> updateDot(running) }
 *     }
 *
 * PHASE 2 will convert this object into an `@Singleton class AppSignals` that is
 * injected, so a feature can no longer reach global state at all. The flows
 * below are the seam that makes that change mechanical.
 */
object BubbleEventBus {

    // ── timerRunning ─────────────────────────────────────────────────────────

    private val _timerRunning = MutableStateFlow(false)

    /** Observable form. Emits the current value immediately on collection. */
    val timerRunningFlow: StateFlow<Boolean> = _timerRunning.asStateFlow()

    /**
     * True while the call task timer is running.
     * Kept for backward compat; prefer [callTaskRunning] for colour logic.
     *
     * Owner: MainActivity's timerCardAction observer.
     */
    var timerRunning: Boolean
        get() = _timerRunning.value
        set(value) { _timerRunning.value = value }

    // ── callTaskRunning ──────────────────────────────────────────────────────

    private val _callTaskRunning = MutableStateFlow(false)

    val callTaskRunningFlow: StateFlow<Boolean> = _callTaskRunning.asStateFlow()

    /**
     * True when the currently active timer belongs to the call-assigned task.
     *   true  -> bubble tint = green (#4CAF50) - tap will pause/resume call task
     *   false -> bubble tint = blue  (#1565C0) - tap will switch TO call task
     *
     * Owner: MainActivity / TaskViewModel.
     */
    var callTaskRunning: Boolean
        get() = _callTaskRunning.value
        set(value) { _callTaskRunning.value = value }

    // ── anyTimerRunning ──────────────────────────────────────────────────────

    private val _anyTimerRunning = MutableStateFlow(false)

    val anyTimerRunningFlow: StateFlow<Boolean> = _anyTimerRunning.asStateFlow()

    /**
     * True whenever any task timer is active, regardless of which task.
     * Read by [BubbleOverlayService] to decide whether to show the bubble on an
     * incoming call before handleCallStarted() has run.
     *
     * Owner: MainActivity, written alongside [timerRunning].
     */
    var anyTimerRunning: Boolean
        get() = _anyTimerRunning.value
        set(value) { _anyTimerRunning.value = value }

    // ── onBubbleTap ──────────────────────────────────────────────────────────

    /**
     * Invoked on the main thread when the user taps the floating bubble.
     *
     * LEAK WARNING: this holds a reference to whatever set it. MainActivity sets
     * it in onCreate and MUST clear it in onDestroy, or the Activity is retained
     * for the lifetime of the process. Prefer [clearBubbleTap] over assigning
     * null so the intent is obvious at the call site.
     */
    @Volatile
    var onBubbleTap: (() -> Unit)? = null

    /** Explicit teardown for the tap handler. Safe to call more than once. */
    fun clearBubbleTap() { onBubbleTap = null }

    /**
     * Resets every signal. Intended for tests and for a full sign-out/reset;
     * production code should own its individual fields instead.
     */
    fun reset() {
        _timerRunning.value = false
        _callTaskRunning.value = false
        _anyTimerRunning.value = false
        onBubbleTap = null
    }
}
