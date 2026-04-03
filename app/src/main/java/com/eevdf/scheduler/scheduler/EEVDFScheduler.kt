package com.eevdf.scheduler.scheduler

import com.eevdf.scheduler.model.Task
import kotlin.math.max

/**
 * EEVDF (Earliest Eligible Virtual Deadline First) Scheduler
 *
 * Core concepts:
 * - Each task has a weight derived from its priority
 * - Virtual runtime (vruntime): weighted time consumed — lower-weight tasks advance faster
 * - Eligible time: the point in virtual time when a task can be scheduled
 * - Virtual deadline: eligibleTime + (requestedSlice / weight)
 * - The scheduler always picks the eligible task with the EARLIEST virtual deadline
 *
 * Lag: how far behind or ahead a task is relative to fair share
 *   lag_i = (avgVruntime - vruntime_i) * weight_i
 *   A positive lag means the task is owed CPU time (eligible to run now)
 */
object EEVDFScheduler {

    /**
     * Compute the total (sum) weight of all non-completed tasks.
     */
    fun totalWeight(tasks: List<Task>): Double =
        tasks.filter { !it.isCompleted }.sumOf { it.weight }

    /**
     * Weighted average virtual runtime across all active tasks.
     */
    fun averageVruntime(tasks: List<Task>): Double {
        val active = tasks.filter { !it.isCompleted }
        if (active.isEmpty()) return 0.0
        val tw = totalWeight(active)
        if (tw == 0.0) return 0.0
        return active.sumOf { it.vruntime * it.weight } / tw
    }

    /**
     * Recalculate eligibility and virtual deadlines for all tasks.
     * Called when a new task is added or after scheduling decisions.
     */
    fun recalculate(tasks: List<Task>) {
        val avgVr = averageVruntime(tasks)
        tasks.filter { !it.isCompleted }.forEach { task ->
            // Lag: positive means task is behind (eligible now)
            task.lag = (avgVr - task.vruntime) * task.weight

            // A task is eligible if its vruntime ≤ avgVruntime (lag ≥ 0)
            task.eligibleTime = if (task.lag >= 0) {
                task.vruntime  // already eligible
            } else {
                // Will become eligible when its vruntime catches up
                avgVr + task.vruntime - avgVr  // = task.vruntime
                task.vruntime
            }

            // Virtual deadline = eligible_time + slice / weight
            val sliceVirtual = task.timeSliceSeconds.toDouble() / task.weight
            task.virtualDeadline = task.eligibleTime + sliceVirtual
        }
    }

    /**
     * Select the next task to run using EEVDF policy:
     * Among all eligible tasks, pick the one with the smallest virtual deadline.
     * A task is eligible if lag >= 0 (it is owed CPU time or is on-time).
     */
    fun selectNext(tasks: List<Task>): Task? {
        val candidates = tasks.filter { !it.isCompleted && !it.isRunning }
        if (candidates.isEmpty()) return null

        val avgVr = averageVruntime(tasks)

        // Eligible tasks: vruntime ≤ avgVruntime (lag ≥ 0)
        val eligible = candidates.filter { it.lag >= 0 }

        return if (eligible.isNotEmpty()) {
            eligible.minByOrNull { it.virtualDeadline }
        } else {
            // No eligible task: pick the one with the smallest vruntime (most behind)
            candidates.minByOrNull { it.vruntime }
        }
    }

    /**
     * Update vruntime after a task has run for [secondsRan] seconds.
     * vruntime increases by secondsRan / weight  (higher weight → slower vruntime growth)
     */
    fun updateVruntime(task: Task, secondsRan: Long) {
        if (task.weight > 0) {
            task.vruntime += secondsRan.toDouble() / task.weight
        }
        task.totalRunTime += secondsRan
        task.runCount++
        recalculate(listOf(task))
    }

    /**
     * Full schedule pass: returns tasks ordered by EEVDF priority.
     */
    fun getScheduleOrder(tasks: List<Task>): List<Task> {
        recalculate(tasks)
        val active = tasks.filter { !it.isCompleted }.toMutableList()
        val result = mutableListOf<Task>()
        val avgVr = averageVruntime(tasks)

        // Sort: eligible first (by virtual deadline), then ineligible (by vruntime)
        val eligible = active.filter { it.lag >= 0 }.sortedBy { it.virtualDeadline }
        val ineligible = active.filter { it.lag < 0 }.sortedBy { it.vruntime }
        result.addAll(eligible)
        result.addAll(ineligible)
        return result
    }

    /**
     * Statistics summary for the scheduler dashboard.
     */
    fun getStats(tasks: List<Task>): SchedulerStats {
        val active = tasks.filter { !it.isCompleted }
        val completed = tasks.filter { it.isCompleted }
        val avgVr = averageVruntime(tasks)
        return SchedulerStats(
            totalTasks = tasks.size,
            activeTasks = active.size,
            completedTasks = completed.size,
            averageVruntime = avgVr,
            totalWeight = totalWeight(active),
            fairnessScore = computeFairness(active)
        )
    }

    /**
     * Jain's fairness index for vruntime distribution.
     * Returns 0.0–1.0 (1.0 = perfectly fair).
     */
    private fun computeFairness(tasks: List<Task>): Double {
        if (tasks.size < 2) return 1.0
        val vrs = tasks.map { it.vruntime }
        val n = vrs.size.toDouble()
        val sumSq = vrs.sumOf { it * it }
        val sum = vrs.sum()
        if (sumSq == 0.0) return 1.0
        return (sum * sum) / (n * sumSq)
    }
}

data class SchedulerStats(
    val totalTasks: Int,
    val activeTasks: Int,
    val completedTasks: Int,
    val averageVruntime: Double,
    val totalWeight: Double,
    val fairnessScore: Double
)
