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
     */
    fun handleCallStarted(callTaskId: String) {
        if (callInProgress) return   // guard: ignore nested / duplicate events

        val callTask = vm.activeTasks.value
            ?.firstOrNull { it.id == callTaskId && !it.isCompleted }
            ?: run {
                vm._toastMessage.value = "Call task not found — check Auto Switch settings"
                return
            }

        // Snapshot state before touching anything
        callInProgress            = true
        savedTaskBeforeCall       = vm._currentTask.value
        wasTimerRunningBeforeCall = vm._timerRunning.value == true

        vm.pauseTimer()
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
