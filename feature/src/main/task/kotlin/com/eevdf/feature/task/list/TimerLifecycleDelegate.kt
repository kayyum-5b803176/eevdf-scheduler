package com.eevdf.feature.task.list

import androidx.lifecycle.viewModelScope
import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.Task
import com.eevdf.feature.task.timer.TimerStartEvent
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.data.task.timer.timerState
import com.eevdf.data.task.timer.withTimerState
import kotlinx.coroutines.launch

/**
 * The core timer state machine: start / pause / reset / skip / select / the
 * countdown-reaches-zero handler, and the persisted-selection + manual-hide
 * helpers that go with task selection.
 *
 * Extracted from TaskViewModel (Phase 10) — the fifth and last cluster, and
 * the biggest and most central one. Nearly every other delegate in this app
 * calls into this cluster (SchedulerDelegate, CallSwitchDelegate,
 * InterruptDelegate, NoticeStateMachine, BubbleTapDelegate, TaskCrudDelegate,
 * AlarmOverrunDelegate, StartupRecoveryDelegate, plus MainActivity and its
 * own delegates) — confirmed by grep across the whole feature/ tree before
 * writing a line of this file. Every one of those call sites goes through
 * vm.startTimer()/vm.pauseTimer()/etc. on TaskViewModel itself, never
 * anything in this file directly, so keeping every function here mirrored as
 * a same-signature facade on TaskViewModel (the same pattern used for all
 * four previous extractions) means none of them need to change. No behavior
 * changed — every line is the original, moved as-is.
 */
internal class TimerLifecycleDelegate(private val vm: TaskViewModel) {

    fun startTimer() {
        if (vm._timerRunning.value == true ||
            vm.notice.isDelayRunning()      ||
            vm.notice.isWaitRunning()) return

        val task      = vm._currentTask.value ?: return
        val remaining = vm._timerSeconds.value ?: task.remainingSeconds

        if (remaining <= 0) {
            // Slice already expired — engine's onFinish() never fired (user paused
            // at 0:00 before CountDownTimer could call back).
            vm.timerEngine.clear()
            onTimerFinished(task, session = null)
            return
        }

        val delaySecs = if (task.taskType == "NOTIFICATION") task.notificationDelaySeconds else 0L

        vm.notice.initSession(task)

        // Resume-type INITIAL: always restart execute from the full slice (0 elapsed).
        //
        // Two things must be corrected, not just the countdown seconds:
        //   1. effectiveRemaining — pass timeSliceSeconds so the engine countdown
        //      ticks down from the full duration.
        //   2. effectiveTask — reset timerState to Idle (accumulatedMs = 0) so
        //      TimerStartEvent.from() does NOT carry the accumulated 15 s from the
        //      previous Paused state into the new Running state.  Without this,
        //      the engine starts with accumulatedMs=15000 and the progress bar /
        //      remaining display begins at (30s − 15s) = 15 s even when we passed
        //      secs=30.  Resetting to Idle gives accumulatedMs=0 → full 30 s.
        //
        // Only applies to true resumes (Paused state); fresh starts and
        // pending-wait paths are unaffected.
        val isInitialResume = task.resumeType == "INITIAL" &&
            task.timerState is TaskTimerState.Paused &&
            !vm.notice.hasPendingWait()

        val effectiveTask      = if (isInitialResume) task.withTimerState(TaskTimerState.reset()) else task
        val effectiveRemaining = if (isInitialResume) task.timeSliceSeconds else remaining

        if (delaySecs > 0) {
            vm.notice.startDelayPhase(effectiveTask, effectiveRemaining, delaySecs)
        } else if (task.taskType == "NOTIFICATION") {
            // Route through resolveAfterDelay so a pending wait-cancel is handled
            // (timestamp resume or skip to next execute) even when there is no delay.
            vm.notice.resolveAfterDelay(effectiveTask, effectiveRemaining)
        } else {
            startActualTimer(effectiveTask, effectiveRemaining)
        }
    }

    /**
     * Builds a Running state, persists it to DB, then hands off to the timer engine.
     * Single entry point for starting an execute countdown — called by the ViewModel
     * directly and by [com.eevdf.feature.task.notice.NoticeStateMachine.startExecutePhase].
     *
     * @param remaining  Execute-slice seconds remaining — drives the engine countdown and
     *                   the notification chronometer.
     * @param alarmSecs  Total seconds until the AlarmManager should fire.  For NOTIFICATION
     *                   tasks `NoticeStateMachine.startExecutePhase` passes the pre-computed
     *                   sum of all remaining (execute + wait) cycles so the alarm is set ONCE
     *                   and never cancelled mid-cycle.  Defaults to [remaining] for all other
     *                   task types (alarm fires when the single execute slice expires).
     */
    fun startActualTimer(task: Task, remaining: Long, alarmSecs: Long = remaining) {
        val nowMs   = System.currentTimeMillis()
        val event   = TimerStartEvent.from(task.timerState, nowMs)
        val running = event.toRunning
        val updated = task.withTimerState(running)

        vm._timerRunning.value = true
        // Update _currentTask with the Running state so tick observer copies carry
        // the correct startTimeEpoch (needed for live progressPercent calculation).
        vm._currentTask.value = updated
        // Record which task ran inside each ancestor group so the Queue tab's
        // global-rotate Next can return to the most recently used task per group.
        vm.lastRun.update(task, vm.activeTasks.value ?: emptyList())
        vm.viewModelScope.launch {
            vm.repository.update(updated)
            vm.triggerSyncExport()          // notify other users: timer started
        }
        vm.alarms.timerStart(task.name, remaining, task.taskType, alarmSecs)
        vm.timerEngine.start(updated)
    }

    fun pauseTimer() {
        // Notice-phase cancellations take priority
        if (vm.notice.isDelayRunning()) { vm.notice.cancelDelayPhase(); return }
        if (vm.notice.isWaitRunning())  { vm.notice.cancelWaitPhase();  return }

        vm.stopAlarmSound()

        val nowMs   = System.currentTimeMillis()
        val result  = vm.timerEngine.pause(nowMs)
        val session = result?.second   // RunSession.Paused; null if engine was idle
        vm._timerRunning.value = false

        val task = vm._currentTask.value
        if (result != null) {
            val paused = result.first
            vm._currentTask.value  = paused
            vm._timerSeconds.value = paused.remainingSeconds
            vm.viewModelScope.launch { vm.repository.update(paused) }
            // Clear the engine so stale activeTask can't overwrite _currentTask on
            // the next pauseTimer() call (fixes Next-stuck / random-jump bug).
            vm.timerEngine.clear()
        }

        if (task != null && task.taskType == "NOTIFICATION") {
            vm.notice.handlePause(task.id, session?.wallClockSeconds ?: 0L, nowMs)
        } else if (session != null && session.wallClockSeconds > 0) {
            applyVruntimeUpdate(session)
        }
        vm.alarms.timerPause()
        vm.triggerSyncExport()               // notify other users: timer paused
    }

    /**
     * Hold-to-close action (Start/Pause long-press on the timer card).
     *
     * Pauses the running task — crediting the partial session's run time and
     * persisting the Paused state, so progress is NOT lost — then DESELECTS it by
     * clearing `_currentTask`. The currentTask observer in MainActivity then closes
     * the timer card and clears the running highlight in the adapters.
     *
     * This is distinct from the manual hide (isCardManuallyHidden), which keeps the
     * task selected and only hides the card. Here the task is fully deselected; the
     * task remains Paused (not reset), so reselecting it later resumes where it left
     * off.
     */
    fun pauseAndDeselect() {
        pauseTimer()
        vm._currentTask.value = null
        vm.clearPersistedSelection()
    }

    fun resetTimer() {
        pauseTimer()
        vm.timerEngine.clear()
        vm.notice.resetState()
        val task  = vm._currentTask.value ?: return
        val reset = task.withTimerState(TaskTimerState.reset())
        vm._timerSeconds.value = reset.remainingSeconds
        vm.viewModelScope.launch {
            vm.repository.update(reset)
            vm._currentTask.postValue(reset)
        }
    }

    /** Resets the timer slice of any task back to its default timeSliceSeconds. */
    fun resetSlice(task: Task) {
        if (task.id == vm._currentTask.value?.id) { resetTimer(); return }
        vm.viewModelScope.launch { vm.repository.update(task.withTimerState(TaskTimerState.reset())) }
    }

    fun skipTask() {
        vm.stopAlarmSound()
        pauseTimer()
        val task = vm._currentTask.value ?: return
        vm._toastMessage.value = "Skipped \"${task.name}\""
        vm._currentTask.value  = null
        vm.clearPersistedSelection()
        vm.scheduler.scheduleNext()
    }

    fun setCurrentTask(task: Task) {
        pauseTimer()
        // Bug 1 fix — stale NoticePhase.Expired locking the button:
        //
        // After a NOTIFICATION task expires, triggerAlarmExpire() sets
        // _noticePhase = Expired (sync) then nulls _currentTask via postValue
        // (async).  By the time the user taps the task row, _currentTask is
        // already null, so pauseTimer()'s `task != null` guard skips handlePause()
        // and the Expired phase is never cleared.  On the first re-select the
        // derive() therefore sees:  task != null  +  phase == Expired
        // -> TimerCardAction.Unavailable ("-") instead of Start.
        //
        // Fix: always reset notice state here, after pauseTimer() has already
        // handled any truly-running delay/wait/execute phase.  resetState() is
        // idempotent: if pauseTimer() already transitioned the phase to Idle
        // (normal cancel/pause paths) this is a harmless no-op.
        vm.notice.resetState()

        // If an expiry alarm is up when the user selects a (possibly different)
        // task, clear it synchronously so timerCardAction does not derive Expired
        // for the freshly-selected task on the next frame.
        if (vm._alarmTaskName.value != null) {
            vm.taskToRestoreAfterExpire = null
            vm.stopAlarmSound()
        }

        vm._currentTask.value  = task
        vm._timerSeconds.value = task.remainingSeconds

        // Selecting a task is an explicit "open this card" gesture: clear any
        // prior manual-hide and persist the selection so it survives reboot.
        setCardManuallyHidden(false)
        vm.settings.saveSelectedTaskId(task.id)
    }

    /**
     * Persists the manual card-hidden flag so a hand-closed card stays closed
     * across app reopen / reboot. Called by MainActivity's key1-hold handler and
     * by [setCurrentTask] (which always reopens the card).
     */
    fun setCardManuallyHidden(hidden: Boolean) = vm.settings.saveCardManuallyHidden(hidden)

    /** Restored on startup by MainActivity to decide whether to show the card. */
    fun getCardManuallyHidden(): Boolean = vm.settings.getSavedCardManuallyHidden()

    /**
     * Re-seats the persisted last-selected task onto the card on startup, without
     * the side effects of [setCurrentTask] (no notice reset, no re-persist). Reads
     * the live row from the DB so paused/reset state is reflected. No-op if no id
     * is stored or the task no longer exists (e.g. it was deleted/completed).
     */
    fun restorePersistedSelection() {
        // Don't clobber a task already seated by the mid-run / alarm recovery paths.
        if (vm._currentTask.value != null || vm._alarmTaskName.value != null) return
        val savedId = vm.settings.getSavedSelectedTaskId() ?: return
        vm.viewModelScope.launch {
            val task = vm.repository.getTaskById(savedId)
            if (task == null || task.isCompleted) {
                vm.settings.saveSelectedTaskId(null)
                return@launch
            }
            vm._currentTask.postValue(task)
            vm._timerSeconds.postValue(task.remainingSeconds)
        }
    }

    fun cancelNotice() = vm.notice.cancelNotice()

    fun stopTimer(completed: Boolean) {
        vm.stopAlarmSound()
        vm.timerEngine.clear()
        vm._timerRunning.value = false
        if (completed) {
            val task = vm._currentTask.value ?: return
            vm.viewModelScope.launch {
                vm.repository.markCompleted(task)
                vm._currentTask.postValue(null)
                vm.clearPersistedSelection()
                vm._toastMessage.postValue("\"${task.name}\" completed!")
                vm.refreshSchedule()
            }
        }
    }

    /**
     * Called when the countdown reaches zero.
     *
     * [taskOverride] is supplied by the app-killed recovery path in `init{}`
     * (via [StartupRecoveryDelegate]) where `_currentTask` hasn't been set yet
     * (postValue is asynchronous).
     * [session] == null means vruntime was already applied by the caller.
     */
    fun onTimerFinished(
        taskOverride: Task?       = null,
        session:      RunSession? = null
    ) {
        val task = taskOverride ?: vm._currentTask.value ?: return

        val expiryEpochMs      = session?.endEpochMs ?: System.currentTimeMillis()
        val elapsedSinceExpiry = ((System.currentTimeMillis() - expiryEpochMs) / 1000L)
            .coerceAtLeast(0L)

        // Clear engine synchronously — before any suspend call — so that a user
        // interaction arriving before the coroutine runs sees Idle state and avoids
        // the Paused(sliceMs) → remainingSeconds=0 stuck-at-0:00 bug.
        vm.timerEngine.clear()

        vm.viewModelScope.launch {
            // NOTIFICATION tasks: do NOT cancel the alarm here.
            //
            // The alarm is now set for the FULL remaining cycle duration in
            // startExecutePhase (execute + all future wait/execute pairs), so it
            // cannot collide with CountDownTimer.onFinish() at execute boundaries —
            // the alarm fires at e.g. 30 s while execute ends at 10 s.
            //
            // The only remaining collision point is the FINAL wait phase where
            // CountDownTimer and AlarmManager both fire at the same epoch.
            // That race is handled in triggerAlarmExpire(), which cancels the
            // AlarmManager entry synchronously before starting the in-app expire
            // path, so onAlarmFired() finds AlarmState==Idle and returns false.
            if (session != null) {
                if (task.taskType != "NOTIFICATION") {
                    vm.repository.updateVruntimeAfterRun(task, session)
                } else {
                    vm.notice.accumulateSessionSeconds(session.wallClockSeconds)
                }
            }
            vm.repository.update(task.withTimerState(TaskTimerState.reset()))
            vm._toastMessage.postValue("Time slice done for \"${task.name}\"")
            vm.refreshSchedule()

            if (vm.settings.autoMode.value == true) {
                val allTasks = vm.activeTasks.value ?: emptyList()
                val next = vm.scheduler.selectAutoNextTask(task, allTasks)
                    ?: vm.repository.selectNextTask()
                if (next != null) {
                    vm.pendingAutoStart = true
                    vm._currentTask.postValue(next)
                    vm._timerSeconds.postValue(next.remainingSeconds)
                    vm.settings.saveSelectedTaskId(next.id)   // card follows the auto task
                    vm._toastMessage.postValue("Auto → \"${next.name}\"")
                } else {
                    vm._currentTask.postValue(null)
                    vm.clearPersistedSelection()
                    vm._toastMessage.postValue("Auto: no more active tasks")
                }
            } else if (task.taskType == "NOTIFICATION") {
                vm.notice.handleExpiredNotificationTask(task)
            } else {
                vm.alarms.timerExpire(task.name, task.taskType)
                vm._alarmTaskName.postValue(task.name)
                vm._alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                vm.startInAppOverrunCounter(task.name, elapsedSinceExpiry)
                vm.taskToRestoreAfterExpire = task.withTimerState(TaskTimerState.reset())
                // Requirement #3: do NOT clear the persisted selection on expiry.
                // The merged card stays seated on the just-expired task (showing the
                // Expired/alarm state); keep its id stored so a reboot mid-alarm
                // reopens the card on the same task.
                vm.settings.saveSelectedTaskId(task.id)
                vm._currentTask.postValue(null)
            }
        }
    }

    fun applyVruntimeUpdate(session: RunSession) {
        val task = vm._currentTask.value ?: return
        vm.viewModelScope.launch {
            vm.repository.updateVruntimeAfterRun(task, session)
            vm.refreshSchedule()
        }
    }
}
