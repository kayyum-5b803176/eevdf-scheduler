package com.eevdf.feature.task.list

import android.os.CountDownTimer
import androidx.lifecycle.viewModelScope
import com.eevdf.data.task.Task
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.data.task.timer.withTimerState
import kotlinx.coroutines.launch

/**
 * The alarm overrun counter (ticks once per second while an expiry alarm
 * rings) and the "restart after expire" hardware-key path.
 *
 * Extracted from TaskViewModel (Phase 10) — second cluster after
 * [TaskCrudDelegate]. Chosen next because, like CRUD, it calls into the
 * not-yet-extracted timer lifecycle only through TaskViewModel's public
 * surface (`setCurrentTask`, `startTimer`), never by reaching into timer
 * internals directly. No behavior changed — every line is the original,
 * moved as-is.
 */
internal class AlarmOverrunDelegate(private val vm: TaskViewModel) {

    fun startInAppOverrunCounter(_taskName: String, initialElapsedSeconds: Long = 0L) {
        vm.overrunTimer?.cancel()
        vm.overrunTimer = object : CountDownTimer(3600_000L, 1000L) {
            var elapsed = initialElapsedSeconds
            override fun onTick(millisUntilFinished: Long) {
                elapsed++
                vm._alarmElapsedSeconds.postValue(elapsed)
            }
            override fun onFinish() { stopAlarmSound() }
        }.start()
    }

    private fun stopOverrunCounter() {
        vm.overrunTimer?.cancel()
        vm.overrunTimer = null
    }

    fun stopAlarmSound() {
        stopOverrunCounter()
        vm._alarmTaskName.postValue(null)
        vm._alarmElapsedSeconds.postValue(0L)
        vm.alarms.stopAlarm()
        vm.taskToRestoreAfterExpire?.let { resetTask ->
            // The just-expired task is being re-seated on the card. For a
            // NOTIFICATION task, triggerAlarmExpire() left _noticePhase == Expired
            // and never cleared it; without resetting here the timerCardAction
            // derivation would see (task != null, phase == Expired) and emit
            // Unavailable ("—") — a dead button — until the user manually
            // re-selected the task. Reset the notice state to Idle so the button
            // correctly shows Start. resetState() is idempotent, and this branch
            // only runs on the alarm-restore path (not on pause/cancel), so it
            // cannot interfere with an in-flight delay/wait phase.
            vm.notice.resetState()
            vm._currentTask.postValue(resetTask)
            vm._timerSeconds.postValue(resetTask.timeSliceSeconds)
            vm.taskToRestoreAfterExpire = null
        }
    }

    /**
     * True while a timer-expiry alarm is ringing.  Used by MainActivity to decide
     * whether a hardware-key press should be consumed for Stop / Restart
     * (requirement #4: keys act only during the expire event).
     */
    fun isAlarmActive(): Boolean = vm._alarmTaskName.value != null

    /**
     * "Stop and Start (Restart)" action for hardware keys.
     *
     * Restarts the just-expired task on a fresh full slice.  Prefers the
     * in-memory [TaskViewModel.taskToRestoreAfterExpire]; if that is gone (e.g.
     * the stop broadcast already cleared it, or the process was killed and
     * recreated), falls back to resolving the task by [fallbackName] from the DB.
     */
    fun restartAfterExpire(fallbackName: String? = null) {
        val inMemory = vm.taskToRestoreAfterExpire
        // Null BEFORE stopAlarmSound() so its restore branch is skipped — otherwise
        // its queued postValue() would overwrite _currentTask / _timerSeconds with
        // the idle reset task moments after we start the timer.
        vm.taskToRestoreAfterExpire = null
        stopAlarmSound()

        if (inMemory != null) {
            startFreshSlice(inMemory)
            return
        }
        // Fallback: resolve from DB by name (survives process death / broadcast race).
        if (!fallbackName.isNullOrBlank()) {
            vm.viewModelScope.launch {
                val task = vm.repository.getActiveTaskByName(fallbackName) ?: return@launch
                startFreshSlice(task)
            }
        }
    }

    /** Seats [task] on the timer card with a full reset slice and starts it. */
    private fun startFreshSlice(task: Task) {
        val fresh = task
            .withTimerState(TaskTimerState.reset())
            .copy(remainingSeconds = task.timeSliceSeconds)
        vm.setCurrentTask(fresh)
        vm._timerSeconds.value = fresh.timeSliceSeconds
        vm.startTimer()
    }
}
