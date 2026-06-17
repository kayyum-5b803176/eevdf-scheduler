package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.RrStatePort
import com.eevdf.core.scheduler.rt.RtPolicy

/**
 * The single entry point for "what runs next" and "what order is the queue in".
 *
 * In the reference app this decision was smeared across `TaskRepository`,
 * `TaskListBuilderDelegate`, and `TaskSchedulerDelegate`, each re-deriving class
 * precedence and re-querying the clock. Here it is one cohesive, pure use-case:
 * sample the world once ([Now]), pass it in, get a deterministic answer.
 *
 * Class precedence mirrors Linux: deadline > rt > fair (stop/idle omitted).
 * Within the fair class, EEVDF decides; RT uses window + FIFO/RR; DL wins
 * outright while its budget is live.
 */
class SchedulerService(private val rrState: RrStatePort) {

    /** A single consistent sample of the world, taken by the caller (data/platform). */
    data class Now(
        val epochSeconds: Long,
        val dayOfWeekIndex: Int,
        val secondOfDay: Long,
        val prevDayOfWeekIndex: Int,
    )

    /** Pick the next task to run, honoring class precedence. Excludes running/completed. */
    fun selectNext(tasks: List<SchedTask>, now: Now): SchedTask? {
        val active = tasks.filter { !it.isCompleted && !it.isGroup }

        // 1. SCHED_DEADLINE: any task with a live budget wins outright.
        active.filter { it.isDlConfigured && it.dl!!.isBudgetActiveAt(now.epochSeconds) }
            .minByOrNull { it.dl!!.deadlineSeconds }
            ?.let { return it }

        // 2. SCHED_RT: window-active tasks, FIFO/RR within the cohort.
        val rtActive = active.filter {
            RtPolicy.isWindowActive(it, now.dayOfWeekIndex, now.secondOfDay, now.prevDayOfWeekIndex)
        }
        RtPolicy.pickRr(rtActive, rrState)?.let { return it }

        // 3. Fair class: EEVDF over the recalculated fair tasks.
        val fair = EevdfScheduler.recalculate(active.filter { it.isFair && !it.isRunning })
        return EevdfScheduler.selectNext(fair)
    }

    /** Full ordered schedule for display: DL first, then RT, then EEVDF order. */
    fun scheduleOrder(tasks: List<SchedTask>, now: Now): List<SchedTask> {
        val active = tasks.filter { !it.isCompleted && !it.isGroup }
        val dl = active.filter { it.isDlConfigured && it.dl!!.isBudgetActiveAt(now.epochSeconds) }
            .sortedBy { it.dl!!.deadlineSeconds }
        val dlIds = dl.mapTo(HashSet()) { it.id }

        val rt = active.filter {
            it.id !in dlIds &&
                RtPolicy.isWindowActive(it, now.dayOfWeekIndex, now.secondOfDay, now.prevDayOfWeekIndex)
        }.sortedWith(compareByDescending<SchedTask> { it.rt!!.priority }.thenBy { it.rt!!.activationSecondOfDay })
        val rtIds = rt.mapTo(HashSet()) { it.id }

        val fair = EevdfScheduler.scheduleOrder(active.filter { it.id !in dlIds && it.id !in rtIds })
        return dl + rt + fair
    }
}
