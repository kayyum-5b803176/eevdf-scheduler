package com.eevdf.scheduler.model

/**
 * The complete, self-consistent timer state for one task slice.
 *
 * RULES FOR THE WHOLE CODEBASE:
 *  1. Never read accumulatedMs / startTimeEpoch from Task directly.
 *     Use Task.timerState (TaskTimerExt.kt) to get the sealed value,
 *     then call the pure functions here.
 *
 *  2. Never write accumulatedMs / startTimeEpoch / isRunning / remainingSeconds
 *     via task.copy(). Use task.withTimerState(newState) (TaskTimerExt.kt) which
 *     updates all four columns atomically.
 *
 *  3. The only legal state transitions are the companion functions below.
 *     Idle    → Running   : TimerState.resume(Idle)
 *     Paused  → Running   : TimerState.resume(Paused)
 *     Running → Paused    : TimerState.pause(Running)
 *     Running → Expired   : TimerState.expire(sliceMs)
 *     any     → Idle      : TimerState.reset()
 *
 * Why sealed instead of flags on Task:
 *  task.copy() only updates fields you name. A caller can silently forget
 *  startTimeEpoch while setting isRunning=false, leaving the DB inconsistent.
 *  A sealed class makes that impossible state unrepresentable — Running requires
 *  a non-zero startTimeEpoch by construction.
 */
sealed class TimerState {

    // ── States ────────────────────────────────────────────────────────────────

    /** Never started or fully reset. Both epoch columns are 0. */
    object Idle : TimerState()

    /**
     * Paused mid-run.
     * [accumulatedMs] = total ms consumed across all sessions so far.
     */
    data class Paused(val accumulatedMs: Long) : TimerState() {
        init { require(accumulatedMs >= 0) { "accumulatedMs must be ≥ 0, got $accumulatedMs" } }
    }

    /**
     * Actively counting down.
     * liveElapsedMs = accumulatedMs + (nowMs − startTimeEpoch)
     *
     * [accumulatedMs]  = ms consumed before this session started.
     * [startTimeEpoch] = System.currentTimeMillis() when Start was last pressed.
     */
    data class Running(
        val accumulatedMs: Long,
        val startTimeEpoch: Long
    ) : TimerState() {
        init {
            require(accumulatedMs  >= 0) { "accumulatedMs must be ≥ 0, got $accumulatedMs" }
            require(startTimeEpoch  > 0) { "startTimeEpoch must be a real epoch ms, got $startTimeEpoch" }
        }
    }

    /**
     * Slice fully consumed. Stored in DB as Paused(sliceMs) with isRunning=false.
     * [sliceMs] = timeSliceSeconds * 1000.
     */
    data class Expired(val sliceMs: Long) : TimerState()

    // ── Pure math — no side effects, safe to call from anywhere ──────────────

    companion object {

        /**
         * Total milliseconds consumed as of [nowMs].
         * THE ONLY function in the app that computes elapsed time.
         */
        fun elapsedMs(state: TimerState, nowMs: Long = System.currentTimeMillis()): Long =
            when (state) {
                is Idle    -> 0L
                is Paused  -> state.accumulatedMs
                is Running -> state.accumulatedMs + (nowMs - state.startTimeEpoch)
                is Expired -> state.sliceMs
            }

        /** Remaining milliseconds — never negative. */
        fun remainingMs(
            state: TimerState,
            sliceMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Long = (sliceMs - elapsedMs(state, nowMs)).coerceAtLeast(0L)

        /** Remaining whole seconds for UI display. */
        fun remainingSecs(
            state: TimerState,
            sliceSecs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Long = remainingMs(state, sliceSecs * 1000L, nowMs) / 1000L

        /** Progress 0–100 for the card progress bar. */
        fun progress(
            state: TimerState,
            sliceMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Int {
            if (sliceMs == 0L) return 0
            return (elapsedMs(state, nowMs) * 100L / sliceMs).toInt().coerceIn(0, 100)
        }

        fun isExpired(
            state: TimerState,
            sliceMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean = remainingMs(state, sliceMs, nowMs) == 0L

        // ── Transitions ───────────────────────────────────────────────────────

        /**
         * Idle / Paused → Running.
         * Carries forward any already-accumulated ms so partial slices resume correctly.
         */
        fun resume(
            state: TimerState,
            nowMs: Long = System.currentTimeMillis()
        ): Running = Running(
            accumulatedMs  = elapsedMs(state, nowMs),
            startTimeEpoch = nowMs
        )

        /**
         * Running → Paused.
         * Snapshots elapsed ms at [nowMs]. Safe on already-Paused / Idle state.
         */
        fun pause(
            state: TimerState,
            nowMs: Long = System.currentTimeMillis()
        ): Paused = Paused(elapsedMs(state, nowMs))

        /** any → Idle (full reset — clears all accumulated ms). */
        fun reset(): Idle = Idle

        /** Running → Expired (slice fully consumed). */
        fun expire(sliceMs: Long): Expired = Expired(sliceMs)
    }
}
