package com.eevdf.scheduler.scheduler

import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.model.RunSession
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
 *  • Emit [expiredTask] + [expiredSession] exactly once when the slice hits zero.
 *    [expiredSession] carries the real wall-clock start epoch so the ViewModel
 *    can credit the ACTUAL elapsed time (remaining slice), never timeSliceSeconds.
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
    private val _tickSeconds   = MutableLiveData<Long>()
    val tickSeconds: LiveData<Long> = _tickSeconds

    /**
     * Fires once with the updated Task (state = Expired, remainingSeconds = 0)
     * when the slice reaches zero.
     */
    private val _expiredTask   = MutableLiveData<Task>()
    val expiredTask: LiveData<Task> = _expiredTask

    /**
     * Fires alongside [expiredTask] with a [RunSession] whose startEpochMs is
     * the real wall-clock epoch when Start was last tapped — NOT derived from
     * timeSliceSeconds.  The ViewModel MUST use this to credit vruntime so that
     * only the remaining slice (e.g. 3s of a 10s task paused at 7s) is charged,
     * never the full slice.
     *
     * Also emitted by [restoreFromDb] as a [RunSession.Recovered] when the timer
     * expired while the app was dead.
     */
    private val _expiredSession = MutableLiveData<RunSession>()
    val expiredSession: LiveData<RunSession> = _expiredSession

    // ── Internal state ────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var activeTask:     Task?           = null
    private var inMemoryState:  TimerState      = TimerState.Idle

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
     * Pause and return a [Pair] of (updated Task, [RunSession.Paused]) so the
     * ViewModel can credit exactly the seconds consumed this session — derived
     * from real wall-clock timestamps, never from timeSliceSeconds.
     *
     * Returns null if no timer is active.
     */
    fun pause(nowMs: Long = System.currentTimeMillis()): Pair<Task, RunSession.Paused>? {
        val task = activeTask ?: return null

        // Capture start epoch BEFORE transitioning state — inMemoryState is still Running.
        val sessionStartMs = (inMemoryState as? TimerState.Running)?.startTimeEpoch ?: nowMs

        stopTicker()
        val paused    = TimerState.pause(inMemoryState, nowMs)
        inMemoryState = paused
        val updated   = task.withTimerState(paused)
        activeTask    = updated

        val session = RunSession.Paused(
            taskId       = task.id,
            startEpochMs = sessionStartMs,
            endEpochMs   = nowMs
        )
        return Pair(updated, session)
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
     * Re-attaches ticker if time remains; posts to [expiredTask] + [expiredSession]
     * if already elapsed.  Does NOT reschedule AlarmManager — the foreground service
     * already did that.
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
            // Timer expired while the app was dead.
            // expiryEpoch = when the slice actually ran out (not necessarily now).
            val expiryEpochMs = state.startTimeEpoch + sliceMs - state.accumulatedMs
            val expired       = task.withTimerState(TimerState.expire(sliceMs))
            inMemoryState     = TimerState.expire(sliceMs)

            // Recovered session: credits only the final session's real elapsed time.
            // wallClockSeconds = (expiryEpoch - startEpoch) / 1000 = remaining slice,
            // which may be much less than timeSliceSeconds if the task was paused before.
            val session = RunSession.Recovered(
                taskId       = task.id,
                startEpochMs = state.startTimeEpoch,
                endEpochMs   = expiryEpochMs
            )
            _expiredSession.postValue(session)
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
                val endMs      = System.currentTimeMillis()
                val sliceMs    = sliceSecs * 1000L

                // Capture start epoch BEFORE overwriting inMemoryState.
                // inMemoryState is still Running here — this is the last moment
                // startTimeEpoch is available.  After expire() it is gone.
                val sessionStartMs = (inMemoryState as? TimerState.Running)
                    ?.startTimeEpoch ?: endMs

                val expired = TimerState.expire(sliceMs)
                inMemoryState = expired

                // session.wallClockSeconds = (endMs - sessionStartMs) / 1000
                // = actual remaining slice, NOT timeSliceSeconds.
                val session = RunSession.Expired(
                    taskId       = task.id,
                    startEpochMs = sessionStartMs,
                    endEpochMs   = endMs
                )
                _expiredSession.postValue(session)
                _expiredTask.postValue(task.withTimerState(expired))
            }
        }.start()
    }

    private fun stopTicker() {
        countDownTimer?.cancel()
        countDownTimer = null
    }
}
