package com.eevdf.data.scheduler

import com.eevdf.data.task.Task
import kotlin.math.exp

/**
 * Per-task load average — a direct adaptation of the Linux kernel system load
 * average to this app's task model.
 *
 * ── How Linux does it ───────────────────────────────────────────────────────
 * Linux samples the run-queue every ~5 s and feeds an exponentially-weighted
 * moving average (EWMA):
 *
 *     load = load · e^(−Δt/T) + n · (1 − e^(−Δt/T))
 *
 * where `n` is the instantaneous number of runnable tasks and `T` is the time
 * constant (60 s / 300 s / 900 s for the 1 / 5 / 15-minute figures).
 *
 * ── How we adapt it ─────────────────────────────────────────────────────────
 * • The "instantaneous load" a task contributes while it is RUNNING is its
 *   [Task.loadFactor] (default 1.00 ⇒ behaves like one ordinary runnable task).
 *   While the task is idle its instantaneous load is 0.
 * • We use a single 24-hour smoothing window ([LOAD_WINDOW_SECONDS]) — the
 *   internal value requested for this feature — instead of 1/5/15-minute ones.
 * • Δt is REAL elapsed wall-time since the task's load was last advanced, so the
 *   value can be computed lazily on demand (no background sampler): each call to
 *   [advanced] integrates the whole gap in one step using the closed-form EWMA.
 *
 * The math is continuous and identical whether we advance once per minute or
 * once per hour, so foreground-only 1-minute ticking produces the same curve a
 * 5-second sampler would, just with coarser visible steps.
 */
object LoadAverage {

    /** Smoothing window (time constant T), in seconds. 24 h, per the feature spec. */
    const val LOAD_WINDOW_SECONDS: Double = 86_400.0

    /**
     * Returns a copy of [task] with its [Task.loadAverage] advanced to [nowEpoch].
     *
     * @param isRunning whether the task is currently running RIGHT NOW. While
     *        running the target value is [Task.loadFactor]; while idle it is 0.
     *
     * Decays the existing average toward the target over the real elapsed time:
     *     decay  = e^(−Δt/T)
     *     load'  = load·decay + target·(1 − decay)
     *
     * First-ever call (loadLastUpdateEpoch == 0) seeds the timestamp without
     * applying a giant initial jump.
     */
    fun advanced(task: Task, nowEpoch: Long, isRunning: Boolean): Task {
        val target = if (isRunning) task.loadFactor else 0.0

        if (task.loadLastUpdateEpoch == 0L) {
            // Seed: start from the target if running, else current (0-ish) value.
            return task.copy(
                loadAverage = if (isRunning) task.loadFactor else task.loadAverage,
                loadLastUpdateEpoch = nowEpoch
            )
        }

        val dtSeconds = (nowEpoch - task.loadLastUpdateEpoch) / 1_000.0
        if (dtSeconds <= 0.0) return task   // clock skew / no time passed

        val decay   = exp(-dtSeconds / LOAD_WINDOW_SECONDS)
        val updated = task.loadAverage * decay + target * (1.0 - decay)

        return task.copy(
            loadAverage = updated,
            loadLastUpdateEpoch = nowEpoch
        )
    }

    /**
     * Read-only current load average for display WITHOUT mutating/persisting —
     * integrates the gap since the last persisted update so the stats bar can
     * show a live value between ticks.
     */
    fun currentValue(task: Task, nowEpoch: Long, isRunning: Boolean): Double {
        if (task.loadLastUpdateEpoch == 0L) {
            return if (isRunning) task.loadFactor else task.loadAverage
        }
        val dtSeconds = (nowEpoch - task.loadLastUpdateEpoch) / 1_000.0
        if (dtSeconds <= 0.0) return task.loadAverage
        val target = if (isRunning) task.loadFactor else 0.0
        val decay  = exp(-dtSeconds / LOAD_WINDOW_SECONDS)
        return task.loadAverage * decay + target * (1.0 - decay)
    }

    /** System load = sum of every task's current load average. */
    fun systemLoad(tasks: List<Task>, nowEpoch: Long, runningId: String?): Double =
        tasks.sumOf { currentValue(it, nowEpoch, isRunning = it.id == runningId) }
}
