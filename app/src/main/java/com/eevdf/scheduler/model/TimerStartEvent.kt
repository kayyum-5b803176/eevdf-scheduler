package com.eevdf.scheduler.model

/**
 * Sealed class representing a Start button press on the timer card.
 *
 * WHY THIS EXISTS — the bug it fixes:
 *
 *  Task slice: 10s. User taps Start, immediately taps Pause (50ms wall-clock).
 *
 *  OLD path in startActualTimer:
 *    remaining = _timerSeconds.value                    // = 9  (integer truncation of 9.95s)
 *    accumulated = (timeSliceSeconds - remaining)*1000  // = (10-9)*1000 = 1000ms  ← WRONG
 *    RunningState(accumulated=1000ms, start=T1)
 *    Next pause at T1+50ms → Paused(1050ms) → remainingSeconds = 8  (lost another second)
 *
 *  Each Start→Pause cycle under 1s falsely charges 1000ms, so the display loses
 *  1 second per tap regardless of real time passing.  Spam 10 taps in < 1s → 0:00.
 *
 *  NEW path:
 *    event = TimerStartEvent.from(task.timerState)      // reads Paused(50ms) directly
 *    accumulated = event.accumulatedMs                  // = 50ms  ← CORRECT
 *    RunningState(accumulated=50ms, start=T1)
 *    Next pause at T1+50ms → Paused(100ms) → remainingSeconds = 9 (correct)
 *
 * RULE:
 *  [TimerStartEvent.from] is the ONLY legal way to build a start event.
 *  It reads accumulatedMs from [TimerState.elapsedMs] — which operates on
 *  the exact millisecond values stored in the sealed state — never from
 *  (timeSliceSeconds - remainingSeconds) * 1000 which truncates to 1s granularity.
 */
sealed class TimerStartEvent {

    /** Milliseconds already consumed before this Start press — exact, never truncated. */
    abstract val accumulatedMs: Long

    /** Wall-clock epoch ms of this Start press. */
    abstract val startEpochMs: Long

    /** The [TimerState.Running] this event produces — the only correct way to start a timer. */
    val toRunning: TimerState.Running
        get() = TimerState.Running(
            accumulatedMs  = accumulatedMs,
            startTimeEpoch = startEpochMs
        )

    // ── Concrete types ────────────────────────────────────────────────────────

    /**
     * First press: task is Idle, accumulated = 0.
     * Produced when [TimerState.elapsedMs] == 0 for the current state.
     */
    data class Fresh(override val startEpochMs: Long) : TimerStartEvent() {
        override val accumulatedMs: Long = 0L
    }

    /**
     * Resume after a Pause: carries the EXACT sub-second accumulatedMs from the
     * engine's last Paused state.  This is the field that was previously lost by
     * the (timeSliceSeconds - remainingSeconds) * 1000 recomputation.
     *
     * Invariant: accumulatedMs > 0 (Idle produces Fresh, not Resume).
     */
    data class Resume(
        override val accumulatedMs: Long,
        override val startEpochMs: Long
    ) : TimerStartEvent() {
        init {
            require(accumulatedMs > 0) {
                "Resume requires accumulatedMs > 0; use Fresh for an idle/reset task. Got $accumulatedMs"
            }
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /**
         * Build the correct event type from the task's current [TimerState].
         *
         * This is the ONLY permitted entry point.  Call this inside startActualTimer
         * before constructing [TimerState.Running] — never pass remainingSeconds here.
         *
         * @param state   task.timerState — the exact sealed state last written by the engine.
         * @param nowMs   wall-clock epoch of the Start press (default = System.currentTimeMillis()).
         */
        fun from(
            state: TimerState,
            nowMs: Long = System.currentTimeMillis()
        ): TimerStartEvent {
            // elapsedMs reads accumulatedMs directly from the Paused/Running state —
            // no seconds conversion, no truncation.
            val accumulated = TimerState.elapsedMs(state, nowMs)
            return if (accumulated == 0L) Fresh(nowMs) else Resume(accumulated, nowMs)
        }
    }
}
