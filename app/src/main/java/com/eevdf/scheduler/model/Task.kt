package com.eevdf.scheduler.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val priority: Int,
    val timeSliceSeconds: Long,
    val category: String = "General",
    val color: Int = 0,

    // cgroup hierarchy
    val parentId: String? = null,
    val isGroup: Boolean = false,
    var isGroupExpanded: Boolean = true,

    // EEVDF scheduler state
    var vruntime: Double = 0.0,
    var eligibleTime: Double = 0.0,
    var virtualDeadline: Double = 0.0,
    var lag: Double = 0.0,

    // Timer state
    var remainingSeconds: Long = timeSliceSeconds,
    var isRunning: Boolean = false,
    var isCompleted: Boolean = false,
    var totalRunTime: Long = 0L,
    var runCount: Int = 0,

    val isInterrupt: Boolean = false,

    // Task type — drives which sound/vibration profile fires on expiry
    // Values: "DEFAULT" | "NOTIFICATION" | "ALARM" | "CUSTOM"
    val taskType: String = "DEFAULT",

    // Notice type only: seconds to wait before the countdown starts (0–300)
    val notificationDelaySeconds: Long = 0L,

    // Notice type only: rest seconds after task timer (0–300), 0 = skip rest
    val notificationRestSeconds: Long = 0L,

    // Notice type only: how many extra cycles (timer→rest) after the first (0 = run once)
    val notificationRepeatCount: Int = 0,

    /**
     * Total elapsed ms accumulated across all sessions before the current one.
     * 0 when Idle. Updated on every Start/Pause so process death loses nothing.
     * READ via Task.timerState — WRITE via Task.withTimerState().
     */
    var accumulatedMs: Long = 0L,

    /**
     * Epoch ms (System.currentTimeMillis) when the timer was last resumed.
     * 0 = paused or never started.
     * liveElapsedMs = accumulatedMs + (now − startTimeEpoch)   // while Running
     * READ via Task.timerState — WRITE via Task.withTimerState().
     */
    var startTimeEpoch: Long = 0L,

    // CPU share pinning — null = auto-float (EEVDF weight-based), 0–100 = fixed %
    val pinnedShare: Int? = null,

    /**
     * Auto-calculated internal scheduling weight derived from [pinnedShare].
     * When non-null, overrides [priority] for all EEVDF weight calculations so
     * that — if the user later removes the pin — the task naturally receives the
     * same CPU share via the float pool.  Null = use [priority] as normal.
     */
    val internalWeight: Double? = null,

    val createdAt: Long = System.currentTimeMillis(),

    // ── Quota / cgroup CPU bandwidth control ─────────────────────────────────
    //
    // Mirrors Linux's cpu.cfs_quota_us / cpu.cfs_period_us per cgroup.
    //
    // quotaSeconds      — max allowed runtime per period; 0 = unlimited (no quota).
    // quotaPeriodSeconds— length of the rolling window; default 86400 s (1 day).
    // quotaPeriodStart  — epoch ms when the current accounting period started;
    //                     0 = quota tracking has never started for this task.
    // quotaUsedSeconds  — seconds consumed within the current period.
    //
    // A new period opens automatically the first time the task runs after the
    // previous period has fully elapsed (handled in TaskRepository).

    val quotaSeconds: Long = 0L,
    val quotaPeriodSeconds: Long = 86400L,
    var quotaPeriodStartEpoch: Long = 0L,
    var quotaUsedSeconds: Long = 0L
) {
    /** Effective EEVDF weight. Uses auto-calc value when available, else falls back to priority. */
    val weight: Double get() = internalWeight ?: priority.toDouble()

    // ── Quota helpers ─────────────────────────────────────────────────────────

    /** True when this task has an active quota configured (quotaSeconds > 0). */
    val isQuotaEnabled: Boolean get() = quotaSeconds > 0L

    /**
     * Current effective quota used, decayed continuously by wall-clock time.
     *
     * Budget replenishes at a constant rate of (quotaSeconds / quotaPeriodSeconds)
     * seconds per second — a linear drain that produces a smooth reverse-clock effect.
     *
     * On every run, TaskRepository snapshots this value and resets quotaPeriodStartEpoch
     * to the current time, so the decay always restarts cleanly from the new baseline.
     *
     * Example: quota=5min=300s, period=10min=600s, used=20min=1200s
     *   rate = 300/600 = 0.5 s/s
     *   t+0min  → 1200 − 0   = 1200s (−15m)
     *   t+5min  → 1200 − 150 = 1050s (−12m 30s)
     *   t+10min → 1200 − 300 =  900s (−10m)
     *   t+30min → 1200 − 900 =  300s (0  — at quota)
     *   t+40min → 1200 −1200 =    0s (+5m — full budget)
     */
    val currentQuotaUsed: Long
        get() {
            if (!isQuotaEnabled || quotaPeriodStartEpoch == 0L) return quotaUsedSeconds.coerceAtLeast(0L)
            val elapsedSeconds = (System.currentTimeMillis() - quotaPeriodStartEpoch) / 1_000L
            val replenished    = elapsedSeconds * quotaSeconds / quotaPeriodSeconds
            return (quotaUsedSeconds - replenished).coerceAtLeast(0L)
        }

    /** True when the task has consumed ≥ its quota for the current period. */
    val isQuotaExceeded: Boolean
        get() = isQuotaEnabled && currentQuotaUsed >= quotaSeconds

    /**
     * True when quota usage is in the warning zone (≥ 80 % but not yet exceeded).
     */
    val isQuotaWarning: Boolean
        get() = isQuotaEnabled &&
            currentQuotaUsed >= (quotaSeconds * 0.8).toLong() &&
            currentQuotaUsed < quotaSeconds

    /**
     * Remaining quota seconds relative to the current decayed used value.
     *  -1  → quota not enabled
     *   0  → exceeded or exactly at limit
     *  > 0 → time left
     */
    val quotaRemainingSeconds: Long
        get() = when {
            !isQuotaEnabled -> -1L
            else            -> (quotaSeconds - currentQuotaUsed).coerceAtLeast(0L)
        }

    /**
     * Overflow seconds beyond quota (positive when exceeded, else 0).
     */
    val quotaOverflowSeconds: Long
        get() = if (isQuotaEnabled) (currentQuotaUsed - quotaSeconds).coerceAtLeast(0L) else 0L

    /**
     * 0–100 bar progress.
     *
     * Normal  (not exceeded): usage fraction, 0→100 as budget fills.
     * Exceeded               : overflow fraction, drains from >0 back to 0 as debt
     *                          replenishes — gives a reverse-clock feel on the bar.
     */
    val quotaProgressPercent: Int
        get() {
            if (!isQuotaEnabled || quotaSeconds == 0L) return 0
            return if (isQuotaExceeded)
                (quotaOverflowSeconds * 100L / quotaSeconds).toInt().coerceIn(0, 100)
            else
                (currentQuotaUsed * 100L / quotaSeconds).toInt().coerceIn(0, 100)
        }

    val timeSliceDisplay: String get() {
        val h = timeSliceSeconds / 3600
        val m = (timeSliceSeconds % 3600) / 60
        val s = timeSliceSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    val remainingDisplay: String get() {
        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        return when {
            h > 0 -> String.format("%d:%02d:%02d", h, m, s)
            else  -> String.format("%02d:%02d", m, s)
        }
    }

    /**
     * Live elapsed ms — correct whether running or paused.
     * Used for progress bar; never depends on the remainingSeconds cache.
     */
    val liveElapsedMs: Long get() =
        if (startTimeEpoch > 0L) accumulatedMs + (System.currentTimeMillis() - startTimeEpoch)
        else accumulatedMs

    val progressPercent: Int get() {
        val totalMs = timeSliceSeconds * 1000L
        if (totalMs == 0L) return 0
        return (liveElapsedMs * 100L / totalMs).toInt().coerceIn(0, 100)
    }
}
