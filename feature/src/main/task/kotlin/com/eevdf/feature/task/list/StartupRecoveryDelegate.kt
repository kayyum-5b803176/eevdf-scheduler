package com.eevdf.feature.task.list

import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.data.task.timer.timerState
import com.eevdf.data.task.timer.withTimerState

/**
 * Three-step startup / app-kill recovery, run once from `init{}` inside a
 * `viewModelScope.launch`.
 *
 *   1. An expiry alarm is already ringing (app was killed mid-alarm) — the
 *      in-app onTimerFinished() never ran, so finalize the run exactly once.
 *   2. A task was mid-run when the app was killed — either resume its
 *      countdown with the corrected remaining time, or finish it if the
 *      slice had already elapsed while the process was dead.
 *   3. Neither of the above — re-seat the last-selected task on the card in
 *      its idle state so it survives reboot / app re-open.
 *
 * Extracted from TaskViewModel (Phase 10) — the fourth cluster, and the
 * riskiest one moved so far: getting any of these three steps wrong means a
 * run is silently mis-credited or double-credited, or a ringing alarm from
 * before the kill is never resolved. `init{}` itself couldn't move — Kotlin
 * requires TaskViewModel's own `val` LiveData fields to be assigned inside
 * an init block or at declaration, not from an external delegate — but the
 * actual recovery decision logic could, and does, here. No behavior
 * changed — every line is the original, moved as-is; `init{}` now just
 * calls [recover] inside the same `viewModelScope.launch` it always used.
 */
internal class StartupRecoveryDelegate(private val vm: TaskViewModel) {

    suspend fun recover() {
        vm.interrupt.postInterruptTask(vm.repository.getInterruptTask())
        vm.interrupt.postInterruptTaskB(vm.repository.getInterruptTaskB())

        // Step 1: check if alarm is already ringing (app killed mid-alarm)
        val ringing = vm.alarms.ringingAlarm()
        if (ringing != null) {
            // The alarm fired via AlarmManager (e.g. in Doze / background), so the
            // in-app onTimerFinished() never ran: the run was never credited and the
            // task is still flagged running in the DB. Finalize it exactly once here.
            // Idempotent — getRunningTask() only matches isRunning=1 & startTimeEpoch>0,
            // so after the reset() below a later reopen will not double-credit.
            val orphan = vm.repository.getRunningTask()
            val runState = orphan?.timerState as? TaskTimerState.Running
            if (orphan != null && runState != null) {
                val sliceMs       = orphan.timeSliceSeconds * 1000L
                val expiryEpochMs = runState.startTimeEpoch + sliceMs - runState.accumulatedMs
                val session = RunSession.Recovered(orphan.id, runState.startTimeEpoch, expiryEpochMs)
                if (orphan.taskType != "NOTIFICATION") {
                    vm.repository.updateVruntimeAfterRun(orphan, session)
                }
                vm.repository.update(orphan.withTimerState(TaskTimerState.reset()))
                vm.refreshSchedule()
            }

            val elapsedSinceExpiry =
                ((System.currentTimeMillis() - ringing.firedEpoch) / 1000L)
                    .coerceAtLeast(0L)
            vm._alarmTaskName.postValue(ringing.taskName)
            vm._alarmElapsedSeconds.postValue(elapsedSinceExpiry)
            vm.startInAppOverrunCounter(ringing.taskName, elapsedSinceExpiry)
            return
        }

        // Step 2: check if a task was mid-run when app was killed
        val running = vm.repository.getRunningTask()
        if (running != null) {
            val nowMs       = System.currentTimeMillis()
            val secondsLeft = TaskTimerState.remainingSecs(
                running.timerState, running.timeSliceSeconds, nowMs
            )
            if (secondsLeft > 0L) {
                val corrected = running.copy(remainingSeconds = secondsLeft)
                vm.repository.update(corrected)
                vm._currentTask.postValue(corrected)
                vm._timerSeconds.postValue(secondsLeft)
                vm._timerRunning.postValue(true)
                vm.timerEngine.restoreFromDb(corrected)
                vm.settings.saveSelectedTaskId(corrected.id)
            } else {
                val state         = running.timerState as TaskTimerState.Running
                val expiryEpochMs = state.startTimeEpoch +
                    running.timeSliceSeconds * 1000L - state.accumulatedMs
                val session = RunSession.Recovered(
                    taskId       = running.id,
                    startEpochMs = state.startTimeEpoch,
                    endEpochMs   = expiryEpochMs
                )
                vm.onTimerFinished(running, session = session)
            }
            return
        }

        // Step 3: nothing was mid-run or ringing — re-seat the last-selected
        // task on the card so it survives reboot / app re-open in its idle
        // (Start) state. Whether the card is actually shown is decided by the
        // persisted manual-hide flag, applied in MainActivity. No-op if no id
        // is stored or the task was since deleted/completed.
        vm.restorePersistedSelection()
    }
}
