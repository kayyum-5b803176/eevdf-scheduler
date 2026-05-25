package com.eevdf.scheduler.model.runlog

/**
 * Sealed class representing one completed wall-clock run session.
 *
 * WHY THIS EXISTS — the two bugs it fixes:
 *
 *  Bug 1 — "full slice credited instead of actual elapsed":
 *    Task with 10s slice: user runs 7s → pauses (7s credited ✓), runs 3s → expires.
 *    OLD: onTimerFinished called updateVruntimeAfterRun(task, task.timeSliceSeconds = 10).
 *         Total credited: 7 + 10 = 17s. WRONG.
 *    NEW: RunSession.Expired(startEpochMs, endEpochMs).wallClockSeconds = 3s.
 *         Total credited: 7 + 3 = 10s. CORRECT.
 *
 *  Bug 2 — "RunLog start epoch is an approximation":
 *    OLD: startEpoch = System.currentTimeMillis() - secondsRan * 1_000L
 *         If updateVruntimeAfterRun is called 2s after expiry, startEpoch is off by 2s.
 *    NEW: session.startEpochMs = the real wall-clock ms when Start was tapped.
 *         RunLog always gets the exact epoch.
 *
 * RULES:
 *  1. wallClockSeconds is the ONLY permitted source for the value passed to
 *     EEVDFScheduler.updateVruntime and Task.totalRunTime. Never use timeSliceSeconds.
 *
 *  2. startEpochMs is the ONLY permitted source for RunLog.recordRun(startEpoch).
 *
 *  3. Creating a RunSession requires real wall-clock timestamps — never compute
 *     them from timeSliceSeconds or other config values.
 *
 *  4. For Notice tasks, the session covers the full cycle (delay + execute + wait).
 *     wallClockSeconds is overridden by totalPhaseSecs — the accumulated counter
 *     that the ViewModel maintains per phase — because the notice state machine
 *     has multiple phase timers, not a single engine epoch.
 */
sealed class RunSession {

    /** The task this session belongs to. */
    abstract val taskId: String

    /**
     * Wall-clock epoch ms when this run session started.
     * For execute-phase sessions: the epoch when startActualTimer() called TimerState.resume().
     * For notice sessions: the epoch when the full notice cycle started (before delay).
     * For recovery sessions: TimerState.Running.startTimeEpoch read from DB.
     */
    abstract val startEpochMs: Long

    /**
     * Wall-clock epoch ms when this run session ended.
     * For execute-phase sessions: the epoch captured at pause or expiry.
     * For recovery sessions: computed from stored epoch + remaining slice.
     */
    abstract val endEpochMs: Long

    /**
     * Actual wall-clock seconds consumed — THE value credited to vruntime and totalRunTime.
     *
     * Derived from timestamps, never from timeSliceSeconds.
     * Clamped to ≥ 0 so callers never need to guard against negative values.
     */
    open val wallClockSeconds: Long
        get() = ((endEpochMs - startEpochMs) / 1000L).coerceAtLeast(0L)

    // ── Concrete session types ────────────────────────────────────────────────

    /**
     * User tapped Pause during the execute phase.
     * wallClockSeconds = (pauseEpoch - lastStartEpoch) / 1000 — only this session's delta.
     * Previous sessions (before this pause) were already credited when they paused.
     */
    data class Paused(
        override val taskId:       String,
        override val startEpochMs: Long,
        override val endEpochMs:   Long
    ) : RunSession()

    /**
     * Execute-phase timer reached zero naturally.
     * startEpochMs comes from TimerEngine.ExpiredEvent — the real Running.startTimeEpoch
     * captured before the engine transitions to Expired state.
     * wallClockSeconds = actual remaining slice, NOT timeSliceSeconds.
     */
    data class Expired(
        override val taskId:       String,
        override val startEpochMs: Long,
        override val endEpochMs:   Long
    ) : RunSession()

    /**
     * App-kill recovery path: timer expired while the process was dead.
     * startEpochMs and endEpochMs are computed from the DB-stored Running epoch columns.
     * wallClockSeconds = (expiryEpoch - storedStartEpoch) / 1000 — correctly reflects
     * only the final session, not the full slice.
     */
    data class Recovered(
        override val taskId:       String,
        override val startEpochMs: Long,
        override val endEpochMs:   Long
    ) : RunSession()

    /**
     * Notice task: entire notice cycle (delay + one or more execute+wait rounds).
     *
     * wallClockSeconds is overridden by [totalPhaseSecs] — the ViewModel's accumulated
     * counter — because the notice state machine spans multiple CountDownTimer instances
     * with no single continuous epoch.
     *
     * startEpochMs is the real epoch when the notice cycle started (for RunLog accuracy).
     */
    data class NoticeSession(
        override val taskId:       String,
        override val startEpochMs: Long,
        override val endEpochMs:   Long,
        /** Total seconds accumulated across all notice phases (delay + execute + wait). */
        val totalPhaseSecs: Long
    ) : RunSession() {
        override val wallClockSeconds: Long get() = totalPhaseSecs.coerceAtLeast(0L)
    }
}
