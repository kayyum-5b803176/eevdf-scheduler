package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.runhistory.RunSession
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskTimerState
import com.eevdf.capabilities.taskstorage.timerState
import com.eevdf.kernel.eventbus.AlarmTimerStartRequest
import com.eevdf.kernel.eventbus.SyncRemoteApplied
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Brings what this screen holds in memory back in line with the database
 * after changes made somewhere else were merged into it — another Android
 * user, another device, or a restored backup (`Topics.SYNC_REMOTE_APPLIED`).
 *
 * WHAT THIS REPLACES
 * ------------------
 * Until v6.38.0 a received sync swapped the database file and the app killed
 * and relaunched itself ("Sync received — reloading…") so that a brand-new
 * ViewModel would read the new file. Nothing is swapped any more — the rows
 * simply change underneath a running app — so this does, in place, the only
 * part of that restart that actually mattered: re-reading the timer.
 *
 * The task LISTS need nothing from here. They are Room LiveData and re-query
 * by themselves when the merge transaction commits.
 *
 * THE THREE CASES
 * ---------------
 *  1. A task is counting down HERE, and the database now says that run is
 *     over or a different run exists (another user pressed Stop, or started
 *     something else). Stop counting — silently. The other side already
 *     credited the run and wrote the result; crediting it again here would
 *     double-count it.
 *  2. Nothing is counting down here, and the database says a task IS
 *     running (another user pressed Start). Pick it up exactly as start-up
 *     recovery picks up a task that was running when the app was killed —
 *     and schedule this user's own alarm for it, since the alarm set by the
 *     user who started it lives in *their* Android user.
 *  3. Otherwise: just refresh the selected task's fields.
 *
 * An alarm that is ringing, or a notice task in its delay/wait phase, is
 * left exactly as it is: the user is in the middle of something and will
 * resolve it by hand; the next merge reconciles again.
 */
internal class SyncReconcileDelegate(private val vm: TaskViewModel) {

    suspend fun onRemoteApplied(event: SyncRemoteApplied) {
        if (TASKS_TABLE in event.tables) {
            // Sync does not carry lag / eligibleTime / virtualDeadline: every
            // user works them out for itself. Do it now, before anything sorts
            // by the stored copy (the lists, the schedule order).
            vm.repository.recalculateScheduleCache()
            // The ViewModel's LiveData setters and the timer engine's
            // CountDownTimer both belong to the main thread.
            withContext(Dispatchers.Main) { reconcile() }
        }
        vm.refreshSchedule()
    }

    private suspend fun reconcile() {
        // Let a tap that is being handled right now finish writing first: a
        // Start or Pause updates this screen immediately and the database a
        // few milliseconds later, and reading in between would look like a
        // disagreement that is not one.
        delay(SETTLE_MS)
        if (vm._alarmTaskName.value != null || vm.notice.isDelayRunning() || vm.notice.isWaitRunning()) return

        val dbRunning = vm.repository.getRunningTask()
        val current = vm.currentTask.value
        val countingHere = vm._timerRunning.value == true

        if (countingHere && current != null) {
            val sameRun = dbRunning != null &&
                dbRunning.id == current.id &&
                dbRunning.startTimeEpoch == current.startTimeEpoch &&
                dbRunning.accumulatedMs == current.accumulatedMs
            if (sameRun) return
            // Started here a moment ago and not saved yet — not a remote stop.
            if (vm.clock.nowEpochMillis() - current.startTimeEpoch < JUST_STARTED_MS) return
            stopCountingSilently()
            if (dbRunning == null) {
                reseat(vm.repository.getTaskById(current.id))
                return
            }
        }

        if (dbRunning != null) {
            adopt(dbRunning)
        } else if (current != null && !countingHere) {
            reseat(vm.repository.getTaskById(current.id))
        }
    }

    /** Case 1: stop the local countdown and this user's alarm. Writes nothing, credits nothing. */
    private suspend fun stopCountingSilently() {
        vm.timerEngine.clear()
        vm._timerRunning.value = false
        vm.bus.publish(Topics.ALARM_TIMER_PAUSE_REQUESTED, Unit, CAPABILITY_ID)
    }

    /** Shows [fresh] (the selected task as it now is in the database) on the card, or clears the card. */
    private suspend fun reseat(fresh: Task?) {
        // A task put to sleep elsewhere (or under a group that was) is hidden
        // here too. A local sleep deselects it first ("a hidden task must not
        // stay the current one", SleepDelegate); a sleep that arrives from
        // another phone must do the same. The awake list is exactly the tasks
        // the app shows and schedules.
        val hidden = fresh != null && vm.repository.getActiveTasksSync().none { it.id == fresh.id }
        if (fresh == null || fresh.isCompleted || hidden) {
            vm.currentTaskOwner.set(null)
            vm.clearPersistedSelection()
            return
        }
        // Same task, refreshed fields — keep whichever placement is selected.
        vm.currentTaskOwner.set(fresh, vm.currentInstanceRef.value)
        vm._timerSeconds.value = fresh.remainingSeconds
    }

    /** Case 2: take over a run that was started elsewhere. Mirrors StartupRecoveryDelegate step 2. */
    private suspend fun adopt(running: Task) {
        val state = running.timerState as? TaskTimerState.Running ?: return
        val nowMs = vm.clock.nowEpochMillis()
        val secondsLeft = TaskTimerState.remainingSecs(state, running.timeSliceSeconds, nowMs)

        if (secondsLeft <= 0L) {
            // It ran out while no screen was watching it. Finish it the way
            // start-up recovery does: credit up to the moment it expired.
            val expiryEpochMs = state.startTimeEpoch + running.timeSliceSeconds * 1000L - state.accumulatedMs
            vm.onTimerFinished(
                running,
                session = RunSession.Recovered(
                    taskId = running.id,
                    startEpochMs = state.startTimeEpoch,
                    endEpochMs = expiryEpochMs,
                ),
            )
            return
        }

        // Deliberately NOT written back to the database (start-up recovery
        // does write the corrected remainingSeconds): a write here would be
        // sent to every other user, each of which would reconcile and write
        // again. The remaining time is derived from the start time anyway.
        val corrected = running.copy(remainingSeconds = secondsLeft)
        val savedRef = vm.settings.getSavedSelectedInstanceRef()?.takeIf { it.taskId == corrected.id }
        vm.currentTaskOwner.set(corrected, savedRef)
        vm._timerSeconds.value = secondsLeft
        vm._timerRunning.value = true
        vm.timerEngine.restoreFromDb(corrected)
        vm.settings.saveSelectedTaskId(vm.currentInstanceRef.value)
        vm.bus.publish(
            Topics.ALARM_TIMER_START_REQUESTED,
            AlarmTimerStartRequest(corrected.name, secondsLeft, corrected.taskType, secondsLeft),
            CAPABILITY_ID,
        )
    }

    private companion object {
        const val CAPABILITY_ID = "task-list-screen"
        const val TASKS_TABLE = "tasks"
        const val SETTLE_MS = 400L
        const val JUST_STARTED_MS = 3_000L
    }
}
