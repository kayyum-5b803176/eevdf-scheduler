package com.eevdf.core.scheduler.rt

import com.eevdf.core.scheduler.model.RtConfig
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.RrStatePort

/**
 * Pure SCHED_FIFO / SCHED_RR policy. Ported from the reference `RtScheduler`
 * onto [SchedTask].
 *
 * Kept: real window evaluation (incl. midnight crossing — lived in RtConfig),
 * FIFO/RR cohort selection, group hoisting, next-activation scan, Linux class
 * precedence. Dropped: `SharedPreferences` and `Calendar` from core — RR cursor
 * goes through [RrStatePort]; the current day/second are passed in by the
 * caller (computed via WallClock in platform/).
 */
object RtPolicy {

    /** Linux scheduler-class precedence: deadline > rt > fair. Lower = higher priority. */
    fun classRank(schedulerClass: String): Int = when (schedulerClass) {
        SchedTask.DL -> 0
        SchedTask.RT -> 1
        else -> 2
    }

    fun isWindowActive(task: SchedTask, dayIndex: Int, secondOfDay: Long, prevDayIndex: Int): Boolean =
        task.isRtConfigured && task.rt!!.isWindowActive(dayIndex, secondOfDay, prevDayIndex)

    /** True when [task] is a group with any descendant currently inside its RT window. */
    fun hasActiveRtDescendant(
        task: SchedTask, all: List<SchedTask>, dayIndex: Int, secondOfDay: Long, prevDayIndex: Int,
    ): Boolean {
        if (!task.isGroup) return isWindowActive(task, dayIndex, secondOfDay, prevDayIndex)
        return all.filter { it.parentId == task.id && !it.isCompleted }.any { child ->
            if (child.isGroup) hasActiveRtDescendant(child, all, dayIndex, secondOfDay, prevDayIndex)
            else isWindowActive(child, dayIndex, secondOfDay, prevDayIndex)
        }
    }

    /**
     * Select the rank-#1 RT task among currently window-active leaves.
     * FIFO: highest rtPriority (ties → smallest id). RR: round-robin within the
     * largest same-activation-time cohort, cursor persisted via [rrState].
     */
    fun pickRr(activeRtTasks: List<SchedTask>, rrState: RrStatePort): SchedTask? {
        if (activeRtTasks.isEmpty()) return null
        val cohortSecond = activeRtTasks
            .groupBy { it.rt!!.activationSecondOfDay }
            .maxByOrNull { (_, m) -> m.size }!!.key

        val cohort = activeRtTasks
            .filter { it.rt!!.activationSecondOfDay == cohortSecond }
            .sortedWith(compareByDescending<SchedTask> { it.rt!!.priority }.thenBy { it.id })

        if (cohort.first().rt!!.policy == RtConfig.Policy.FIFO) return cohort.first()

        val cohortKey = cohort.joinToString(",") { it.id }
        val index = rrState.currentIndex(cohortKey) % cohort.size
        rrState.setIndex(cohortKey, index)
        return cohort[index]
    }

    fun advanceRr(cohortKey: String, cohortSize: Int, rrState: RrStatePort) {
        if (cohortSize <= 1) return
        rrState.setIndex(cohortKey, (rrState.currentIndex(cohortKey) + 1) % cohortSize)
    }

    /**
     * Seconds until [task]'s next activation, scanning up to 7 days ahead.
     * Long.MAX_VALUE when no days are enabled. 0 when already active.
     */
    fun secondsUntilNextActivation(task: SchedTask, dayIndex: Int, secondOfDay: Long, prevDayIndex: Int): Long {
        val cfg = task.rt ?: return Long.MAX_VALUE
        if (!cfg.isConfigured) return Long.MAX_VALUE
        if (isWindowActive(task, dayIndex, secondOfDay, prevDayIndex)) return 0L
        val activation = cfg.activationSecondOfDay
        for (daysAhead in 0..7) {
            val day = (dayIndex + daysAhead) % 7
            if ((cfg.activeDaysMask shr day) and 1 == 0) continue
            val secs = if (daysAhead == 0) {
                if (activation > secondOfDay) activation - secondOfDay else continue
            } else {
                daysAhead * 86_400L - secondOfDay + activation
            }
            return secs
        }
        return Long.MAX_VALUE
    }
}
