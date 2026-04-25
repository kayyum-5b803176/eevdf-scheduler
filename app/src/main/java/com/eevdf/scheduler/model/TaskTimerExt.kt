package com.eevdf.scheduler.model

// ── Read: reconstruct sealed state from DB columns ───────────────────────────

/**
 * Reconstructs the canonical [TimerState] from the two stored epoch columns.
 *
 * THE ONLY PLACE in the app that reads accumulatedMs / startTimeEpoch directly
 * from a Task. All other code must go through TimerState functions.
 */
val Task.timerState: TimerState
    get() = when {
        isCompleted         -> TimerState.Expired(timeSliceSeconds * 1000L)
        startTimeEpoch > 0L -> TimerState.Running(accumulatedMs, startTimeEpoch)
        accumulatedMs  > 0L -> TimerState.Paused(accumulatedMs)
        else                -> TimerState.Idle
    }

// ── Write: the ONLY legal path to change timer columns on a Task ─────────────

/**
 * Returns a new Task with ALL timer-related columns updated atomically for [newState].
 *
 * MANDATORY CONTRACT: every call site that changes timer state MUST use this
 * function. Direct task.copy(accumulatedMs = …) calls are FORBIDDEN — copy()
 * only sets the fields you name, so a caller can silently forget startTimeEpoch
 * or isRunning and leave the task inconsistent.
 *
 * Columns owned exclusively by this function:
 *   accumulatedMs    — ms consumed before the current session
 *   startTimeEpoch   — epoch ms when current session started (0 = not running)
 *   isRunning        — derived from whether newState is Running
 *   remainingSeconds — cached snapshot for adapters / EEVDF
 */
fun Task.withTimerState(newState: TimerState): Task {
    val nowMs     = System.currentTimeMillis()
    val sliceMs   = timeSliceSeconds * 1000L
    val elapsedMs = TimerState.elapsedMs(newState, nowMs).coerceAtMost(sliceMs)
    val remainSec = ((sliceMs - elapsedMs) / 1000L).coerceAtLeast(0L)

    return copy(
        accumulatedMs    = when (newState) {
            is TimerState.Idle    -> 0L
            is TimerState.Paused  -> newState.accumulatedMs
            is TimerState.Running -> newState.accumulatedMs
            is TimerState.Expired -> sliceMs
        },
        startTimeEpoch   = if (newState is TimerState.Running) newState.startTimeEpoch else 0L,
        isRunning        = newState is TimerState.Running,
        remainingSeconds = remainSec
    )
}
