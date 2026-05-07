package com.eevdf.scheduler.viewmodel

import com.eevdf.scheduler.model.Task

/**
 * Handles the Auto Switch feature: automatically switches to a designated task
 * when a phone call starts, and restores the previous state when it ends.
 *
 * All call-detection state is isolated here.  Adding new auto-switch triggers
 * (e.g. calendar events, headphone connect) only requires new methods in this
 * class — the timer core, CRUD, and scheduler are untouched.
 *
 * State machine:
 *   callInProgress = false  (idle)
 *       ↓ handleCallStarted()
 *   callInProgress = true   (in call — timer running on call task)
 *       ↓ handleCallEnded()
 *   callInProgress = false  → restored to savedTaskBeforeCall
 */
internal class TaskCallSwitchDelegate(private val vm: TaskViewModel) {

    /**
     * True while a call is in progress.  Separate from [savedTaskBeforeCall]
     * because savedTaskBeforeCall can legitimately be null (no card was open).
     */
    private var callInProgress:            Boolean = false

    /** The task card that was showing before the call started. Null = no card open. */
    private var savedTaskBeforeCall:       Task?   = null

    /**
     * Whether the timer was actively running when the call arrived.
     * Restores the exact paused/running state after the call ends.
     */
    private var wasTimerRunningBeforeCall: Boolean = false

    /**
     * Called when [CallEvents] posts CALL_STARTED.
     * Pauses the current task (if any) and switches to [callTaskId].
     *
     * ── ORDERING IS LOAD-BEARING — DO NOT REFACTOR ────────────────────────────
     *
     * pauseTimer() MUST run BEFORE we capture [savedTaskBeforeCall].
     *
     * The legacy order was:
     *
     *      savedTaskBeforeCall = vm._currentTask.value      // (1) snapshot
     *      vm.pauseTimer()                                  // (2) pause
     *
     * Reproducer: task A timer running, slice=10:00, 5:00 elapsed, call arrives,
     *             call lasts 60s, user returns → A shows 4:00 remaining instead
     *             of 5:00.  The 60-second call ate one minute of A's slice.
     *
     * Why it happens: at step (1), Task A's in-memory snapshot is in
     * TimerState.Running(accumulatedMs = 0, startTimeEpoch = T0).  The tick
     * observer only refreshes .remainingSeconds on each tick — it never
     * updates the epoch fields, so the snapshot still describes "A started
     * at T0 and is still running".
     *
     * Step (2) writes Paused(elapsed_at_call_start) to DB and replaces
     * vm._currentTask.value with a properly Paused Task — but
     * [savedTaskBeforeCall] already holds the stale Running snapshot from (1).
     *
     * On call end, [handleCallEnded] feeds that stale Running snapshot back
     * into startTimer → startActualTimer → TimerStartEvent.from(state, nowMs).
     * For state = Running(0, T0):
     *
     *      elapsedMs = 0 + (nowMs - T0)
     *                = (call_start - T0) + (nowMs - call_start)
     *                = task_A_real_elapsed + call_duration   ← BUG
     *
     * The call duration is silently charged to A's accumulated time, so A
     * resumes with that much LESS slice remaining.  From the user's
     * perspective: "A's timer kept running during the call."
     *
     * The fix: pause first, then capture.  After pauseTimer(),
     * vm._currentTask.value is in TimerState.Paused(elapsed_at_call_start).
     * elapsedMs(Paused(X), nowMs) = X — call duration is excluded by
     * construction, no math required at the resume site.
     */
    fun handleCallStarted(callTaskId: String) {
        if (callInProgress) return   // guard: ignore nested / duplicate events

        val callTask = vm.activeTasks.value
            ?.firstOrNull { it.id == callTaskId && !it.isCompleted }
            ?: run {
                vm._toastMessage.value = "Call task not found — check Auto Switch settings"
                return
            }

        // Capture the wasRunning bit BEFORE pause flips _timerRunning to false.
        callInProgress            = true
        wasTimerRunningBeforeCall = vm._timerRunning.value == true

        // Pause FIRST.  See class kdoc above for why this ordering matters.
        // After this call:
        //   • Task A's DB row is Paused(elapsed_at_call_start)
        //   • vm._currentTask.value is the Paused snapshot of A
        //   • AlarmManager entry for A is cancelled
        //   • Foreground service has stopped A's countdown notification
        vm.pauseTimer()

        // Now capture the post-pause snapshot — this is what we restore on call end.
        // null is a valid value here (means no task card was open before the call).
        savedTaskBeforeCall = vm._currentTask.value

        vm._currentTask.value  = callTask
        vm._timerSeconds.value = callTask.remainingSeconds
        vm.startTimer()
        vm._toastMessage.value = "Call started → \"${callTask.name}\""
    }

    /**
     * Called when [CallEvents] posts CALL_ENDED.
     * Pauses the call task, then restores the previous card and timer state:
     *  - No card was open before → close the timer card (null).
     *  - Card was paused before  → restore card, leave timer paused.
     *  - Card was running before → restore card, resume the timer.
     */
    fun handleCallEnded() {
        if (!callInProgress) return
        callInProgress = false

        val returnTo   = savedTaskBeforeCall     // may be null — that is valid
        val wasRunning = wasTimerRunningBeforeCall

        savedTaskBeforeCall       = null
        wasTimerRunningBeforeCall = false

        vm.pauseTimer()

        if (returnTo == null) {
            vm._currentTask.value  = null
            vm._toastMessage.value = "Call ended"
            return
        }

        vm._currentTask.value  = returnTo
        vm._timerSeconds.value = returnTo.remainingSeconds

        if (wasRunning) {
            vm.startTimer()
            vm._toastMessage.value = "Call ended → resumed \"${returnTo.name}\""
        } else {
            vm._toastMessage.value = "Call ended → \"${returnTo.name}\" (paused)"
        }
    }
}