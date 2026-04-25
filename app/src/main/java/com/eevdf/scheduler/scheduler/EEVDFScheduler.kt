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
     * Compute the real-time CPU share percentage for every active task.
     *
     * Rules:
     *  1. Tasks with a non-null [Task.pinnedShare] always receive exactly that %.
     *  2. The remaining % (100 − sum-of-pinned) is distributed among un-pinned tasks
     *     proportionally by EEVDF weight (priority).
     *  3. If pinned tasks already consume ≥ 100 %, un-pinned tasks get 0 %.
     *
     * When [groupsEnabled] is false (default), all active tasks are treated as a flat
     * pool — the original behaviour for a plain list with no groups.
     *
     * When [groupsEnabled] is true the calculation is **scoped per hierarchy level**:
     *  • Root-level tasks/groups (parentId == null) are compared against each other → their
     *    shares sum to 100 % at the root.
     *  • Tasks that are direct children of a group are compared only against their group
     *    siblings → their shares sum to 100 % within that group.
     *
     * This means a task that is the sole member of a group correctly shows 100 % (its
     * full share of the group's CPU budget), while the group itself may represent only
     * part of the root budget.
     *
     * Returns a map of task.id → share (0.0–100.0).
     */
    fun computeShares(tasks: List<Task>, groupsEnabled: Boolean = false): Map<String, Double> {
        return if (groupsEnabled) {
            // Hierarchical mode: compute shares at every level independently.
            val result = mutableMapOf<String, Double>()
            fun computeLevel(parentId: String?) {
                val levelTasks = tasks.filter { !it.isCompleted && it.parentId == parentId }
                if (levelTasks.isEmpty()) return
                result.putAll(computeSharesAtLevel(levelTasks))
                // Recurse into each group so its children get their own 0–100 % slice.
                levelTasks.filter { it.isGroup }.forEach { group ->
                    computeLevel(group.id)
                }
            }
            computeLevel(null)
            result
        } else {
            // Flat mode: treat every active task as a peer (original behaviour).
            computeSharesAtLevel(tasks.filter { !it.isCompleted })
        }
    }

    /**
     * Core share calculation for a set of sibling tasks (already filtered to the
     * relevant level and completion state).
     *
     * Pinned tasks receive exactly their [Task.pinnedShare] %.
     * Un-pinned tasks share the remainder proportionally by EEVDF weight (priority).
     */
    private fun computeSharesAtLevel(levelTasks: List<Task>): Map<String, Double> {
        val pinnedTotal = levelTasks
            .filter  { it.pinnedShare != null }
            .sumOf   { it.pinnedShare!!.toDouble() }
        val floatPool   = (100.0 - pinnedTotal).coerceAtLeast(0.0)
        val floatTasks  = levelTasks.filter { it.pinnedShare == null }
        val floatWeight = floatTasks.sumOf { it.weight }.coerceAtLeast(1.0)

        return levelTasks.associate { task ->
            task.id to if (task.pinnedShare != null) {
                task.pinnedShare.toDouble()
            } else {
                (task.weight / floatWeight) * floatPool
            }
        }
    }

    /**
     * Validate that all pinned shares across [tasks] (excluding [excludeId]) plus
     * [newValue] do not exceed 100.
     * Returns the sum of all OTHER pinned shares so the caller can compare.
     */
    fun otherPinnedTotal(tasks: List<Task>, excludeId: String?): Int =
        tasks.filter { !it.isCompleted && it.pinnedShare != null && it.id != excludeId }
             .sumOf  { it.pinnedShare!! }

    /**
     * Back-calculates the EEVDF internal weight that would produce [targetShare]% CPU
     * allocation for a task when it participates in the float pool.
     *
     * The calculation is scoped to the task's sibling level ([parentId]) so grouped
     * tasks compare only against their group-mates.
     *
     * @param targetShare   Desired CPU share in percent (e.g. 25.0 for 25 %).
     * @param parentId      The task's parent group id, or null for root level.
     * @param excludeId     The task's own id — excluded from the sibling list so
     *                      the task doesn't count itself.
     * @param allTasks      Full active task list (completed tasks are ignored).
     * @param fallbackWeight Returned when no float siblings exist (any weight would
     *                      give the same result since the task is the sole floater).
     *                      Callers should pass the task's current priority as the default.
     */
    fun calcPinnedWeight(
        targetShare: Double,
        parentId: String?,
        excludeId: String?,
        allTasks: List<Task>,
        fallbackWeight: Double = 4.0
    ): Double {
        val siblings = allTasks.filter {
            !it.isCompleted && it.id != excludeId && it.parentId == parentId
        }
        val siblingPinned    = siblings.filter { it.pinnedShare != null }.sumOf { it.pinnedShare!!.toDouble() }
        val floatPool        = (100.0 - siblingPinned).coerceAtLeast(0.0)
        val otherFloatWeight = siblings.filter { it.pinnedShare == null }.sumOf { it.weight }

        val denominator = floatPool - targetShare
        return when {
            denominator <= 0.0      -> MAX_INTERNAL_WEIGHT   // float pool already exhausted
            otherFloatWeight == 0.0 -> fallbackWeight        // sole float sibling — any weight works
            else                    -> (targetShare * otherFloatWeight) / denominator
        }
    }

    /**
     * Recalculates [Task.internalWeight] for every active pinned task based on the
     * current sibling context.  Returns only the tasks whose weight actually changed
     * so the caller can batch-persist the minimal diff.
     *
     * Call this after any mutation that could shift the share distribution:
     * add, update, delete, or complete a task.
     */
    fun syncPinnedWeights(allTasks: List<Task>): List<Task> {
        val active  = allTasks.filter { !it.isCompleted }
        val changed = mutableListOf<Task>()

        active.filter { it.pinnedShare != null }.forEach { task ->
            val newWeight = calcPinnedWeight(
                targetShare    = task.pinnedShare!!.toDouble(),
                parentId       = task.parentId,
                excludeId      = task.id,
                allTasks       = active,
                fallbackWeight = task.priority.toDouble()
            )
            // Only record a change when the value materially differs (avoid pointless DB writes).
            if (task.internalWeight == null || Math.abs((task.internalWeight) - newWeight) > 1e-9) {
                changed.add(task.copy(internalWeight = newWeight))
            }
        }
        return changed
    }

    private const val MAX_INTERNAL_WEIGHT = 9_999.0
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
