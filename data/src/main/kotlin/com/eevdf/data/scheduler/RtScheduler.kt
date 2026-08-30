package com.eevdf.data.scheduler

import android.content.SharedPreferences
import com.eevdf.core.scheduler.model.RtConfig
import com.eevdf.core.scheduler.rt.RtPolicy
import com.eevdf.data.task.Task
import java.util.Calendar

/**
 * RT Scheduler — SCHED_FIFO / SCHED_RR realtime window logic.
 *
 * ── Phase 8: thin adapter over :core (this file's window/activation math used
 * to be a duplicate, Calendar-based reimplementation of the same rules now
 * proven in [RtConfig]/[RtPolicy] — ported there earlier, characterization-
 * tested in RtWindowCharacterizationTest, but never wired to any live caller
 * until this pass. What's below now converts [Task] -> [SchedTask] and the
 * current instant -> (dayIndex, secondOfDay, prevDayIndex), then delegates. ──
 *
 * NOT migrated, deliberately: [pickRrTask], [advanceRrIndex] are dead code —
 * called by nothing except the equally-unused RtSchedulerService wrapper, no
 * live caller exercises FIFO/RR cohort selection today. Migrating dead code
 * for symmetry would add risk for zero behavioral benefit; left as-is.
 * [clearRrState] is untouched too — it's pure SharedPreferences removal with
 * no policy content, already a thin adapter in its current form.
 *
 * ── Data model ────────────────────────────────────────────────────────────────
 *
 *   rtPriority          Int  1–99  mirrors POSIX sched_priority; higher = more urgent
 *   rtPolicy            String  "FIFO" | "RR"
 *   rtActiveDays        Int  bitmask  bit 0 = Sun, 1 = Mon, … 6 = Sat
 *   rtActivationHour    Int  0–23  wall-clock hour
 *   rtActivationMinute  Int  0–59
 *   rtActivationSecond  Int  0–59
 *   rtSliceTimeoutSecs  Long  1–604800  window duration (max 7 days)
 *
 * ── RR state ─────────────────────────────────────────────────────────────────
 *
 * The index of the currently-serving RR task within its same-activation-time
 * cohort is stored in SharedPreferences (key = RT_RR_INDEX_KEY).  It is
 * transient scheduling state — no need to survive a DB migration.  Cleared
 * automatically whenever the cohort composition changes.
 */
object RtScheduler {

    // ── SharedPrefs key for RR round-robin position ───────────────────────────

    private const val RT_RR_INDEX_KEY    = "rt_rr_index"
    private const val RT_RR_COHORT_KEY   = "rt_rr_cohort"   // serialised task-id list

    // ── Day bitmask constants — single source of truth is now RtConfig ───────
    // Kept here, delegating, so every existing caller (RtScheduler.DAY_SUN
    // etc.) needed zero changes.

    const val DAY_SUN = RtConfig.DAY_SUN
    const val DAY_MON = RtConfig.DAY_MON
    const val DAY_TUE = RtConfig.DAY_TUE
    const val DAY_WED = RtConfig.DAY_WED
    const val DAY_THU = RtConfig.DAY_THU
    const val DAY_FRI = RtConfig.DAY_FRI
    const val DAY_SAT = RtConfig.DAY_SAT
    const val DAY_ALL = RtConfig.DAY_ALL

    // ── Core window query ─────────────────────────────────────────────────────

    /**
     * Returns true when [task] is inside its RT activation window right now.
     * Delegates to [RtPolicy.isWindowActive] — see that function and
     * [RtConfig.isWindowActive] for the actual midnight-crossing logic.
     */
    fun isRtWindowActive(task: Task, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!task.isRtConfigured) return false
        val (day, sec, prevDay) = wallClockParts(nowMs)
        return RtPolicy.isWindowActive(task.toSched(), day, sec, prevDay)
    }

    /**
     * Milliseconds until the task's next activation window opens.
     * Returns 0 when the window is currently active.
     * Returns Long.MAX_VALUE when rtActiveDays is 0 (no days selected).
     */
    fun nextActivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long {
        if (!task.isRtConfigured) return Long.MAX_VALUE
        if (task.rtActiveDays == 0) return Long.MAX_VALUE
        val (day, sec, prevDay) = wallClockParts(nowMs)
        val secs = RtPolicy.secondsUntilNextActivation(task.toSched(), day, sec, prevDay)
        return if (secs == Long.MAX_VALUE) Long.MAX_VALUE else secs * 1_000L
    }

    /**
     * Milliseconds until the task's current activation window closes.
     * Returns 0 when the window is not active.
     */
    fun nextDeactivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long {
        if (!isRtWindowActive(task, nowMs)) return 0L
        val (_, sec, _) = wallClockParts(nowMs)
        val remaining = task.toSched().rt?.secondsUntilClose(sec) ?: 0L
        return (remaining * 1_000L).coerceAtLeast(0L)
    }

    /**
     * For the Schedule tab auto-resort handler: returns the milliseconds until
     * the next state change (activation or deactivation) across all RT-configured
     * tasks.  Returns Long.MAX_VALUE when no change is pending.
     */
    fun nextResortMs(tasks: List<Task>, nowMs: Long = System.currentTimeMillis()): Long {
        val rtTasks = tasks.filter { it.isRtConfigured && !it.isCompleted }
        if (rtTasks.isEmpty()) return Long.MAX_VALUE
        return rtTasks.minOf { task ->
            if (isRtWindowActive(task, nowMs)) {
                nextDeactivationMs(task, nowMs)
            } else {
                nextActivationMs(task, nowMs)
            }
        }
    }

    // ── SCHED_RR round-robin selection ────────────────────────────────────────
    //
    // NOT migrated to :core (see class doc). Unchanged Calendar/SharedPreferences
    // implementation below — dead code today, kept as-is rather than risked.

    /**
     * Selects which RT-active task should be at rank #1 for the current moment.
     *
     * FIFO: returns the highest-priority RT-active task.  If multiple tasks share
     *       the same rtPriority, the one with the lexicographically smallest id
     *       wins (stable, deterministic).
     *
     * RR:   tasks with the same activation time form a "cohort" and are served
     *       round-robin in descending rtPriority order (ties broken by id).
     *       The current cohort index is stored in [prefs] so it survives ViewModel
     *       recreation but resets when the cohort membership changes.
     *
     * @param activeTasks  All currently RT-window-active leaf tasks (non-group,
     *                     non-completed, [isRtWindowActive] == true).
     * @param prefs        SharedPreferences for RR index persistence.
     * @param nowMs        Current epoch ms (injected for testability).
     */
    fun pickRrTask(
        activeTasks: List<Task>,
        prefs: SharedPreferences,
        _nowMs: Long = System.currentTimeMillis()
    ): Task? {
        if (activeTasks.isEmpty()) return null

        // Partition into cohorts by activation time (same H:M:S = same cohort)
        // Tasks with the same activation time compete as a group.
        val cohortKey = activeTasks
            .groupBy { it.rtActivationSecondOfDay }
            .maxByOrNull { (_, members) -> members.size }
            ?.key ?: activeTasks.first().rtActivationSecondOfDay

        val cohort = activeTasks
            .filter { it.rtActivationSecondOfDay == cohortKey }
            .sortedWith(compareByDescending<Task> { it.rtPriority }.thenBy { it.id })

        if (cohort.isEmpty()) return activeTasks.maxByOrNull { it.rtPriority }

        // FIFO: always pick head of cohort (highest priority)
        if (cohort.first().rtPolicy == "FIFO") return cohort.first()

        // RR: persist and advance the index within the cohort
        val cohortIds    = cohort.joinToString(",") { it.id }
        val savedCohort  = prefs.getString(RT_RR_COHORT_KEY, "") ?: ""
        val savedIndex   = prefs.getInt(RT_RR_INDEX_KEY, 0)

        // Reset index when cohort membership changes
        val currentIndex = if (savedCohort == cohortIds) savedIndex % cohort.size else 0

        // Persist updated cohort snapshot (membership may have changed)
        prefs.edit()
            .putString(RT_RR_COHORT_KEY, cohortIds)
            .putInt(RT_RR_INDEX_KEY, currentIndex)
            .apply()

        return cohort[currentIndex]
    }

    /**
     * Advances the RR index to the next task in the cohort.
     * Call after the current RR task finishes its window slice or is manually
     * switched away from.
     */
    fun advanceRrIndex(prefs: SharedPreferences, cohortSize: Int) {
        if (cohortSize <= 1) return
        val current = prefs.getInt(RT_RR_INDEX_KEY, 0)
        prefs.edit().putInt(RT_RR_INDEX_KEY, (current + 1) % cohortSize).apply()
    }

    /**
     * Clears the RR state — called when a task is deleted or its RT config changes.
     */
    fun clearRrState(prefs: SharedPreferences) {
        prefs.edit()
            .remove(RT_RR_INDEX_KEY)
            .remove(RT_RR_COHORT_KEY)
            .apply()
    }

    // ── Hoisting helpers (used by ListBuilderDelegate) ────────────────────

    /**
     * True when [task] is a group containing at least one descendant that is
     * currently inside its RT activation window. Delegates to
     * [RtPolicy.hasActiveRtDescendant].
     */
    fun hasActiveRtDescendant(task: Task, allTasks: List<Task>,
                               _nowMs: Long = System.currentTimeMillis()): Boolean {
        val (day, sec, prevDay) = wallClockParts(_nowMs)
        return RtPolicy.hasActiveRtDescendant(
            task.toSched(), allTasks.map { it.toSched() }, day, sec, prevDay,
        )
    }

    /**
     * Minimum seconds-of-day among active RT descendants — used for RT-urgency
     * sort within hoisted groups (earlier activation = more urgent).
     */
    fun minRtUrgency(task: Task, allTasks: List<Task>): Long =
        if (!task.isGroup) task.rtActivationSecondOfDay
        else allTasks
            .filter { it.parentId == task.id && !it.isCompleted }
            .minOfOrNull { minRtUrgency(it, allTasks) } ?: Long.MAX_VALUE

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Converts an epoch-ms instant into (dayIndex, secondOfDay, prevDayIndex)
     * — the wall-clock triple every [RtPolicy]/[RtConfig] query needs.
     * dayIndex: 0 = Sunday … 6 = Saturday, matching [RtConfig.activeDaysMask]'s
     * bit convention. Same Calendar computation the original inline math used,
     * now computed once and fed to the pure :core functions instead of a
     * second, duplicate reimplementation of the same rules.
     */
    private fun wallClockParts(nowMs: Long): Triple<Int, Long, Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val day = cal.get(Calendar.DAY_OF_WEEK) - 1   // 0 = Sun, 1 = Mon, …
        val sec = cal.get(Calendar.HOUR_OF_DAY) * 3600L +
                  cal.get(Calendar.MINUTE)      *   60L +
                  cal.get(Calendar.SECOND)
        val prevDay = (day + 6) % 7
        return Triple(day, sec, prevDay)
    }
}
