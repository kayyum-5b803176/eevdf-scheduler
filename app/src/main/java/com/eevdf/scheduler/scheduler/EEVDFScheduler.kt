package com.eevdf.scheduler.scheduler

import com.eevdf.scheduler.model.task.Task
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

        averageVruntime(tasks)

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
     * Linux EEVDF `place_entity()` equivalent for brand-new tasks.
     *
     * Problem without this fix:
     *   A task created with the default vruntime = 0 has a massive positive lag
     *   ( lag = (avgVr − 0) × weight ) the moment it joins the run-queue.
     *   The scheduler repeatedly picks it first until its vruntime "catches up"
     *   to the average, starving every existing task in the interim.
     *
     * Linux solution — `place_entity()` with ENQUEUE_INITIAL:
     *   For a task that has never run (exec_start == 0), the kernel explicitly
     *   clamps lag to 0 before computing the initial placement:
     *
     *       lag  = 0                       // brand-new task, no history
     *       se->vruntime = avg_vruntime     // start right on the mean
     *       se->deadline = avg_vruntime + slice / weight
     *
     *   lag = 0  →  the task is immediately eligible (lag ≥ 0) but holds
     *   no priority advantage over any existing task.
     *
     * Reference: kernel/sched/fair.c  place_entity(), ENQUEUE_INITIAL branch.
     *
     * @param task          The newly created task (vruntime must still be 0).
     * @param existingTasks Active tasks already in the run-queue (excluding [task]).
     *                      When the list is empty the first-ever task keeps vruntime = 0,
     *                      which is correct — there is no average to place against.
     */
    fun placeNewTask(task: Task, existingTasks: List<Task>) {
        // ── Group-aware sibling scoping (cgroup hierarchy) ────────────────────
        // Tasks only compete against peers at the SAME hierarchy level (same parentId),
        // exactly as Linux cgroup scheduling scopes its run-queue per group.
        //
        // Without this filter, a new task added inside a group would be placed
        // relative to the global average vruntime of ALL tasks — including tasks
        // in completely different groups.  That average is meaningless for the
        // new task: its scheduler (the group's run-queue) only sees its siblings.
        //
        // Example:
        //   Group A  →  taskA1 vrt=100, taskA2 vrt=120   → sibling avg = 110
        //   Group B  →  taskB1 vrt=5,   taskB2 vrt=8     → sibling avg =   6.5
        //   Global avg = (100+120+5+8)/4 ≈ 58
        //
        //   New task added to Group B:
        //     Before fix  → placed at vrt ≈ 58  (way above B's avg 6.5 → immediately starved)
        //     After fix   → placed at vrt ≈ 6.5 (fair start within Group B)
        val siblings = existingTasks.filter { !it.isCompleted && it.parentId == task.parentId }
        if (siblings.isEmpty()) return   // first task at this cgroup level — vruntime = 0 is correct

        // lag = 0 → eligible immediately, no scheduling advantage over siblings.
        // Mirrors Linux place_entity(ENQUEUE_INITIAL): clamp lag to 0 → vruntime = avg_vruntime.
        task.vruntime = averageVruntime(siblings)
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
        averageVruntime(tasks)

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
     * Sum of pinned shares of all OTHER tasks that are siblings of [taskId]
     * (same [parentId]) — excluding [excludeId] itself.
     *
     * Scoped to siblings only so that a child group's pinned shares are validated
     * independently of root-level tasks. Root-level tasks at 100% do NOT block a
     * child task inside a group from setting its own pinned share.
     *
     * Returns Double so callers can compare against 100.0 with 2dp precision.
     */
    fun otherPinnedTotal(tasks: List<Task>, excludeId: String?, parentId: String?): Double =
        tasks.filter {
            !it.isCompleted &&
            it.pinnedShare != null &&
            it.id != excludeId &&
            it.parentId == parentId
        }.sumOf { it.pinnedShare!! }

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

    /**
     * Returns true when [task] is a group that contains at least one descendant
     * (at any depth) with an active SCHED_DEADLINE budget.
     *
     * This is the cgroup-aware DL promotion check: Linux's deadline scheduler
     * propagates the urgency of a deadline entity upward through the group
     * hierarchy so the root-level group entity wins the run-queue competition.
     * We replicate that by inspecting the task tree here.
     *
     * @param task      The candidate group node.
     * @param allTasks  Full flat task list (active + completed; filtering is
     *                  done internally — completed tasks cannot be DL-active).
     */
    fun hasActiveDlDescendant(task: Task, allTasks: List<Task>): Boolean {
        if (!task.isGroup) return task.isDlBudgetActive
        val children = allTasks.filter { it.parentId == task.id && !it.isCompleted }
        return children.any { child ->
            if (child.isGroup) hasActiveDlDescendant(child, allTasks)
            else child.isDlBudgetActive
        }
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
