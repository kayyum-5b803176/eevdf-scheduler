package com.eevdf.scheduler.scheduler

import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TimerState
import com.eevdf.scheduler.model.timerState
import com.eevdf.scheduler.model.withTimerState

/**
 * Owns ALL running-timer mechanics for one active task.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *  • Manage the single CountDownTimer that wakes the UI every second.
 *  • Re-derive remaining from epoch on every tick — CountDownTimer's internal
 *    millisUntilFinished is NEVER used for display values.
 *  • Emit [tickSeconds] for the UI countdown label / progress bar.
 *  • Emit [expiredTask] exactly once when the slice hits zero.
 *
 * ── Non-responsibilities (ViewModel's job) ────────────────────────────────────
 *  • Database writes.
 *  • EEVDF vruntime / virtualDeadline updates.
 *  • Notifications, AlarmManager, sound, vibration.
 *  • Delay / wait phases (NOTIFICATION task type).
 *
 * ── Idempotency ───────────────────────────────────────────────────────────────
 *  start()  on the same running task id → no-op.
 *  pause()  when not running            → returns null.
 *  reset()  when already idle           → returns null.
 *  clear()  always safe.
 */
class TimerEngine {

    // ── Output LiveData ───────────────────────────────────────────────────────

    /**
     * Emits remaining seconds on every 1-second tick.
     * Always derived from [TimerState.remainingSecs] — never from
     * CountDownTimer.millisUntilFinished, so immune to CountDownTimer drift.
     */
    private val _tickSeconds  = MutableLiveData<Long>()
    val tickSeconds: LiveData<Long> = _tickSeconds

    /**
     * Fires once with the updated Task (state = Expired, remainingSeconds = 0)
     * when the slice reaches zero.
     */
    private val _expiredTask  = MutableLiveData<Task>()
    val expiredTask: LiveData<Task> = _expiredTask

    // ── Internal state ────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var activeTask: Task?               = null
    private var inMemoryState: TimerState       = TimerState.Idle

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start or resume the engine for [task].
     * [task] must already have correct epoch columns written by
     * task.withTimerState(TimerState.resume(…)) before this call.
     * Idempotent: same task id already running → no-op.
     */
    fun start(task: Task) {
        if (activeTask?.id == task.id && inMemoryState is TimerState.Running) return

        val state   = task.timerState
        val running = if (state is TimerState.Running) state
                      else TimerState.resume(state)

        inMemoryState = running
        activeTask    = task

        val remaining = TimerState.remainingMs(running, task.timeSliceSeconds * 1000L)
        attachTicker(task, remaining)
    }

    /**
     * Pause and return the updated Task (caller persists to DB).
     * Returns null if no timer is active.
     */
    fun pause(nowMs: Long = System.currentTimeMillis()): Task? {
        val task = activeTask ?: return null
        stopTicker()
        val paused    = TimerState.pause(inMemoryState, nowMs)
        inMemoryState = paused
        val updated   = task.withTimerState(paused)
        activeTask    = updated
        return updated
    }

    /**
     * Reset to Idle and return the updated Task (caller persists to DB).
     * Returns null if no task is loaded.
     */
    fun reset(): Task? {
        val task = activeTask ?: return null
        stopTicker()
        inMemoryState = TimerState.Idle
        val updated   = task.withTimerState(TimerState.Idle)
        activeTask    = updated
        return updated
    }

    /**
     * Restore engine after an app kill.
     * Call from ViewModel.init() when DB shows isRunning=1.
     * Re-attaches ticker if time remains; posts to [expiredTask] if already elapsed.
     * Does NOT reschedule AlarmManager — the foreground service already did that.
     */
    fun restoreFromDb(task: Task) {
        val state = task.timerState
        if (state !is TimerState.Running) return

        inMemoryState = state
        activeTask    = task

        val sliceMs   = task.timeSliceSeconds * 1000L
        val remaining = TimerState.remainingMs(state, sliceMs)

        if (remaining > 0L) {
            attachTicker(task, remaining)
        } else {
            val expired = task.withTimerState(TimerState.expire(sliceMs))
            inMemoryState = TimerState.expire(sliceMs)
            _expiredTask.postValue(expired)
        }
    }

    /** Snapshot of current in-memory state (ViewModel reads on pause for vruntime). */
    fun currentState(): TimerState = inMemoryState

    /** Hard-stop everything. Idempotent. */
    fun clear() {
        stopTicker()
        activeTask    = null
        inMemoryState = TimerState.Idle
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun attachTicker(task: Task, remainingMs: Long) {
        stopTicker()
        val sliceSecs = task.timeSliceSeconds

        countDownTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                // Re-derive from epoch every tick — intentionally ignoring
                // CountDownTimer's own millisUntilFinished to avoid drift.
                val secs = TimerState.remainingSecs(inMemoryState, sliceSecs)
                _tickSeconds.postValue(secs)
            }
            override fun onFinish() {
                stopTicker()
                val sliceMs = sliceSecs * 1000L
                val expired = TimerState.expire(sliceMs)
                inMemoryState = expired
                _expiredTask.postValue(task.withTimerState(expired))
            }
        }.start()
    }

    private fun stopTicker() {
        countDownTimer?.cancel()
        countDownTimer = null
    }
}
