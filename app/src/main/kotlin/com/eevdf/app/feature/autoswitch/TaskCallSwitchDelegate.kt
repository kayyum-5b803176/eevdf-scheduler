package com.eevdf.app.feature.autoswitch

import com.eevdf.data.task.Task
import com.eevdf.app.feature.task.timer.timerState
import com.eevdf.app.feature.autoswitch.AutoSwitchPrefs
import com.eevdf.app.feature.autoswitch.BubbleOverlayService
import com.eevdf.app.feature.task.TaskViewModel

/**
 * Handles the Auto Switch feature: automatically switches to a designated task
 * when a phone call starts, and restores the previous state when it ends.
 *
 * ── Dual-path architecture ────────────────────────────────────────────────────
 *
 * There are now two paths that can execute the call switch:
 *
 *   Path A — Background (preferred): [CallSwitchService] fires from the manifest
 *     BroadcastReceiver, writes DB directly, credits vruntime + RunLog, manages
 *     notifications and bubble.  Works even when the Activity is dead.
 *
 *   Path B — Foreground (UI sync): This delegate fires when [CallEvents] LiveData
 *     is observed by MainActivity.  Its job is to synchronize ViewModel in-memory
 *     state (savedTaskBeforeCall, wasTimerRunning, LiveData) with what Path A
 *     already wrote to DB.  It must NOT double-write DB timer state or double-credit
 *     vruntime, because Path A already did that.
 *
 * Detection: if [CallSwitchService] already ran, the DB will show the call task
 * as Running (isRunning = true) when handleCallStarted() is called here.  In
 * that case we skip all DB writes and only update LiveData for UI display.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
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
     * Called when [com.eevdf.app.feature.autoswitch.CallEvents] posts CALL_STARTED.
     *
     * If [CallSwitchService] already ran (detected by the call task being Running in DB),
     * we skip pauseTimer()/startTimer() to avoid double-crediting vruntime and
     * resetting the call task's startTimeEpoch.  Instead we only sync LiveData so
     * the UI displays the correct state.
     *
     * If [CallSwitchService] did NOT run (Activity was in foreground and handled it
     * before the service had a chance, or service was disabled), we execute the full
     * pause → switch → start sequence as before.
     *
     * ── ORDERING IS LOAD-BEARING — DO NOT REFACTOR ────────────────────────────
     *
     * pauseTimer() MUST run BEFORE we capture [savedTaskBeforeCall].
     * See the original kdoc for the full reproducer and explanation.
     */
    fun handleCallStarted(callTaskId: String) {
        if (callInProgress) return   // guard: ignore nested / duplicate events

        val callTask = vm.activeTasks.value
            ?.firstOrNull { it.id == callTaskId && !it.isCompleted }
            ?: run {
                vm._toastMessage.value = "Call task not found — check Auto Switch settings"
                return
            }

        callInProgress = true

        // ── Detect whether CallSwitchService already wrote the DB ─────────────
        // If the call task is already Running in the live list, the service ran.
        // In that case the DB has: previous task = Paused, call task = Running,
        // vruntime already credited.  We must not touch DB again.
        val callTaskAlreadyRunning = callTask.isRunning

        if (callTaskAlreadyRunning) {
            // Path A already ran — only sync ViewModel in-memory state.
            wasTimerRunningBeforeCall = vm._timerRunning.value == true

            // Capture whatever was on-screen before as the thing to return to.
            // If the current card IS already the call task, there was no prior task.
            val onScreen = vm._currentTask.value
            savedTaskBeforeCall = if (onScreen?.id == callTaskId) null else onScreen

            // Sync LiveData to show call task card with correct remaining time.
            vm._currentTask.value  = callTask
            vm._timerSeconds.value = callTask.remainingSeconds
            vm._timerRunning.value = true

            vm._toastMessage.value = "Call started → \"${callTask.name}\""
        } else {
            // Path B — service did not run; execute the full switch ourselves.
            wasTimerRunningBeforeCall = vm._timerRunning.value == true

            // Pause FIRST.  See class kdoc above for why this ordering matters.
            vm.pauseTimer()

            savedTaskBeforeCall = vm._currentTask.value

            vm._currentTask.value  = callTask
            vm._timerSeconds.value = callTask.remainingSeconds
            vm.startTimer()
            vm._toastMessage.value = "Call started → \"${callTask.name}\""

            // Bubble: start the overlay service (Path A would have done this too).
            val ctx = vm.getApplication<android.app.Application>()
            if (AutoSwitchPrefs.isBubbleEnabled(ctx)) {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx,
                    android.content.Intent(ctx, BubbleOverlayService::class.java)
                        .apply { action = BubbleOverlayService.ACTION_CALL_STARTED }
                )
            }
        }
    }

    /**
     * Called when [com.eevdf.app.feature.autoswitch.CallEvents] posts CALL_ENDED.
     *
     * Same dual-path logic: if [CallSwitchService] already wrote the DB (restored
     * task is Running), skip timer DB writes and only update LiveData.
     *
     * Pauses the call task, then restores the previous card and timer state:
     *  - No card was open before → close the timer card (null).
     *  - Card was paused before  → restore card, leave timer paused.
     *  - Card was running before → restore card, resume the timer.
     */
    fun handleCallEnded() {
        if (!callInProgress) return
        callInProgress = false

        val returnTo   = savedTaskBeforeCall
        val wasRunning = wasTimerRunningBeforeCall

        savedTaskBeforeCall       = null
        wasTimerRunningBeforeCall = false

        // ── Detect whether CallSwitchService already restored the DB ──────────
        // If the returned-to task is already Running (or no task was open before),
        // the service handled it. Check current DB-backed list for the saved task.
        val restoredByService = returnTo != null &&
            vm.activeTasks.value?.firstOrNull { it.id == returnTo.id }?.isRunning == true

        if (restoredByService) {
            // Path A already ran — only sync LiveData.
            val freshTask = vm.activeTasks.value?.firstOrNull { it.id == returnTo!!.id }
                ?: returnTo
            vm._currentTask.value  = freshTask
            vm._timerSeconds.value = freshTask?.remainingSeconds ?: 0L
            vm._timerRunning.value = wasRunning
            vm._toastMessage.value = if (wasRunning)
                "Call ended → resumed \"${freshTask?.name}\""
            else
                "Call ended → \"${freshTask?.name}\" (paused)"
        } else {
            // Path B — service did not run; execute full restore ourselves.
            // pauseTimer() here credits vruntime for the call task session.
            vm.pauseTimer()

            if (returnTo == null) {
                vm._currentTask.value  = null
                vm._toastMessage.value = "Call ended"

                val ctx = vm.getApplication<android.app.Application>()
                ctx.startService(
                    android.content.Intent(ctx, BubbleOverlayService::class.java)
                        .apply { action = BubbleOverlayService.ACTION_CALL_ENDED }
                )
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

            val ctx = vm.getApplication<android.app.Application>()
            ctx.startService(
                android.content.Intent(ctx, BubbleOverlayService::class.java)
                    .apply { action = BubbleOverlayService.ACTION_CALL_ENDED }
            )
        }
    }
}
