package com.eevdf.scheduler.scheduler

import android.content.SharedPreferences
import com.eevdf.scheduler.model.task.Task
import java.util.Calendar

/**
 * RT Scheduler — SCHED_FIFO / SCHED_RR realtime window logic.
 *
 * ── What this class owns ──────────────────────────────────────────────────────
 *
 * Linux rt_sched_class operates at fixed priorities (1–99) and preempts every
 * fair/EEVDF task unconditionally.  This app-level simulation matches that
 * contract with wall-clock activation windows:
 *
 *   • A task is "RT-active" when the current wall-clock time falls inside
 *     [activationTime, activationTime + rtSliceTimeoutSeconds) on a day that
 *     matches the task's rtActiveDays bitmask.
 *
 *   • RT-active tasks are hoisted above all EEVDF tasks on the Schedule tab
 *     but remain below SCHED_DEADLINE tasks (dl_sched_class), matching Linux
 *     scheduler class priority order: stop > deadline > rt > fair > idle.
 *
 *   • When multiple tasks share the same activation time, SCHED_RR rotates
 *     among them round-robin (ordered by descending rtPriority, ties broken
 *     by task id for stability).  SCHED_FIFO never rotates — the highest-
 *     priority task holds until its slice expires.
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

    // ── Day bitmask constants (same as Calendar.DAY_OF_WEEK − 1) ─────────────

    const val DAY_SUN = 1 shl 0
    const val DAY_MON = 1 shl 1
    const val DAY_TUE = 1 shl 2
    const val DAY_WED = 1 shl 3
    const val DAY_THU = 1 shl 4
    const val DAY_FRI = 1 shl 5
    const val DAY_SAT = 1 shl 6
    const val DAY_ALL = 0b1111111

    // ── Core window query ─────────────────────────────────────────────────────

    /**
     * Returns true when [task] is inside its RT activation window right now.
     *
     * A window spans [activationSecondOfDay, activationSecondOfDay + rtSliceTimeoutSecs).
     * Windows that cross midnight are handled: if activationTime = 23:50 and timeout = 20m,
     * the window wraps around; the task is active from 23:50 to 00:10 on the *next* day,
     * and the day check is relaxed to allow the activation day OR the day before.
     */
    fun isRtWindowActive(task: Task, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!task.isRtConfigured) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowSecOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                          cal.get(Calendar.MINUTE)      *   60 +
                          cal.get(Calendar.SECOND)

        val activationSec = task.rtActivationSecondOfDay
        val timeoutSec    = task.rtSliceTimeoutSeconds
        val windowEnd     = activationSec + timeoutSec   // may exceed 86400

        return if (windowEnd <= 86_400L) {
            // Window fits within a single day — check current day and time range
            isDayActive(task.rtActiveDays, cal) &&
            nowSecOfDay >= activationSec &&
            nowSecOfDay <  windowEnd
        } else {
            // Window crosses midnight
            val overflow = windowEnd - 86_400L
            val calYest  = Calendar.getInstance().apply {
                timeInMillis = nowMs - 86_400_000L   // yesterday
            }
            val activationDayMatch  = isDayActive(task.rtActiveDays, cal)
            val previousDayMatch    = isDayActive(task.rtActiveDays, calYest)

            (activationDayMatch && nowSecOfDay >= activationSec) ||
            (previousDayMatch   && nowSecOfDay <  overflow)
        }
    }

    /**
     * Milliseconds until the task's next activation window opens.
     * Returns 0 when the window is currently active.
     * Returns Long.MAX_VALUE when rtActiveDays is 0 (no days selected).
     */
    fun nextActivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long {
        if (!task.isRtConfigured) return Long.MAX_VALUE
        if (task.rtActiveDays == 0) return Long.MAX_VALUE
        if (isRtWindowActive(task, nowMs)) return 0L

        val cal           = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowSecOfDay   = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                            cal.get(Calendar.MINUTE)      *   60 +
                            cal.get(Calendar.SECOND)
        val activationSec = task.rtActivationSecondOfDay

        // Scan up to 8 days ahead to find the next active day + activation time
        for (daysAhead in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, daysAhead)
            }
            if (!isDayActive(task.rtActiveDays, candidate)) continue

            val secUntilActivation = if (daysAhead == 0) {
                // Same day: only valid if activation is still in the future
                if (activationSec > nowSecOfDay) activationSec - nowSecOfDay else continue
            } else {
                // Future day: always valid
                (daysAhead * 86_400L) - nowSecOfDay + activationSec
            }
            return secUntilActivation * 1_000L
        }
        return Long.MAX_VALUE
    }

    /**
     * Milliseconds until the task's current activation window closes.
     * Returns 0 when the window is not active.
     */
    fun nextDeactivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long {
        if (!isRtWindowActive(task, nowMs)) return 0L
        val cal           = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowSecOfDay   = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                            cal.get(Calendar.MINUTE)      *   60 +
                            cal.get(Calendar.SECOND)
        val activationSec = task.rtActivationSecondOfDay
        val windowEnd     = activationSec + task.rtSliceTimeoutSeconds

        val remaining = if (windowEnd <= 86_400L) {
            windowEnd - nowSecOfDay
        } else {
            // Window crossed midnight — compute remaining from wrapped position
            val overflow = windowEnd - 86_400L
            if (nowSecOfDay >= activationSec) {
                windowEnd - nowSecOfDay          // before midnight
            } else {
                overflow - nowSecOfDay           // after midnight
            }
        }
        return (remaining * 1_000L).coerceAtLeast(0L)
    }

    /**
     * For the Schedule tab auto-resort handler: returns the milliseconds until
     * the next state change (activation or deactivation) across all RT-configured
     * tasks.  Returns Long.MAX_VALUE when no change is pending.
     */
    fun nextResortMs(tasks: List<Task>, nowMs: Long = System.currentTimeMillis()): Long {
        val rtTasks = tasks.filter { it.isRtConfigured && !it.isCompleted && !it.isGroup }
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

    // ── Hoisting helpers (used by TaskListBuilderDelegate) ────────────────────

    /**
     * True when [task] is a group containing at least one descendant that is
     * currently inside its RT activation window.  Mirrors [EEVDFScheduler.hasActiveDlDescendant]
     * for the RT class.
     */
    fun hasActiveRtDescendant(task: Task, allTasks: List<Task>,
                               _nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!task.isGroup) return isRtWindowActive(task, _nowMs)
        val children = allTasks.filter { it.parentId == task.id && !it.isCompleted }
        return children.any { child ->
            if (child.isGroup) hasActiveRtDescendant(child, allTasks, _nowMs)
            else isRtWindowActive(child, _nowMs)
        }
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
     * Returns true when the day-of-week in [cal] is set in [daysBitmask].
     * Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday; bitmask bit 0 = Sun.
     */
    private fun isDayActive(daysBitmask: Int, cal: Calendar): Boolean {
        val bit = cal.get(Calendar.DAY_OF_WEEK) - 1   // 0 = Sun, 1 = Mon, …
        return (daysBitmask and (1 shl bit)) != 0
    }
}
