package com.eevdf.core.scheduler.eevdf

import com.eevdf.core.scheduler.model.SchedTask

/**
 * EEVDF (Earliest Eligible Virtual Deadline First) core.
 *
 * This is a faithful re-implementation of the reference `EEVDFScheduler`, with
 * the following deliberate changes — every one of them addresses a concrete
 * issue found in the original, in line with the "don't inherit known issues"
 * directive:
 *
 *   FIXED 1 — No in-place mutation.
 *     The reference `recalculate(tasks)` wrote `task.lag`, `task.eligibleTime`,
 *     `task.virtualDeadline` directly onto the (Room-entity) inputs. Here the
 *     pass is a pure function: inputs are read-only, results come back as new
 *     `SchedTask` copies. Callers decide what to persist.
 *
 *   FIXED 2 — Dead code removed.
 *     The original computed `avgVr + task.vruntime - avgVr` (== task.vruntime)
 *     and then immediately reassigned it; it also called `averageVruntime(...)`
 *     in `selectNext`/`getScheduleOrder` purely for a discarded side effect.
 *     Both are gone.
 *
 *   FIXED 3 — Single average computation per pass (performance).
 *     The reference recomputed the weighted average vruntime O(n) multiple
 *     times within one logical operation. Because performance is the top
 *     priority here, the average is computed exactly once per pass and reused.
 *
 * Algorithm (unchanged semantics):
 *   lag_i           = (avgVruntime − vruntime_i) · weight_i
 *   eligible        ⇔ lag_i ≥ 0  (vruntime_i ≤ avgVruntime)
 *   virtualDeadline = eligibleTime + sliceSeconds / weight
 *   selectNext      = eligible task with smallest virtualDeadline,
 *                     else the most-behind task (smallest vruntime).
 */
object EevdfScheduler {

    fun totalWeight(tasks: List<SchedTask>): Double =
        tasks.filter { !it.isCompleted }.sumOf { it.weight }

    /** Weighted mean vruntime across active tasks. Returns 0.0 when empty. */
    fun averageVruntime(tasks: List<SchedTask>): Double {
        val active = tasks.filter { !it.isCompleted }
        if (active.isEmpty()) return 0.0
        val tw = active.sumOf { it.weight }
        if (tw == 0.0) return 0.0
        return active.sumOf { it.vruntime * it.weight } / tw
    }

    /**
     * Pure recalculation pass. Returns copies with refreshed lag / eligibleTime
     * / virtualDeadline. Completed tasks are returned unchanged. The weighted
     * average is computed once and reused for every task (FIXED 3).
     */
    fun recalculate(tasks: List<SchedTask>): List<SchedTask> {
        val avgVr = averageVruntime(tasks)
        return tasks.map { task ->
            if (task.isCompleted) return@map task
            val lag = (avgVr - task.vruntime) * task.weight
            // Eligible (lag ≥ 0) tasks key off their own vruntime; ineligible
            // tasks become eligible exactly when their vruntime reaches it — which
            // is also their own vruntime. So eligibleTime == vruntime either way.
            val eligibleTime = task.vruntime
            val virtualDeadline = eligibleTime + task.timeSliceSeconds.toDouble() / task.weight
            task.copy(lag = lag, eligibleTime = eligibleTime, virtualDeadline = virtualDeadline)
        }
    }

    /**
     * Select the next task to run under EEVDF policy. Pure: callers should pass
     * an already-[recalculate]d list (or this will recalc-free-read the lag that
     * is already on the tasks). Excludes the currently running task.
     */
    fun selectNext(tasks: List<SchedTask>): SchedTask? {
        val candidates = tasks.filter { !it.isCompleted && !it.isRunning }
        if (candidates.isEmpty()) return null
        val eligible = candidates.filter { it.lag >= 0 }
        return if (eligible.isNotEmpty()) {
            eligible.minByOrNull { it.virtualDeadline }
        } else {
            // No eligible task: run the one most behind (smallest vruntime).
            candidates.minByOrNull { it.vruntime }
        }
    }

    /**
     * Linux `place_entity(ENQUEUE_INITIAL)` equivalent: place a brand-new task
     * at its siblings' average vruntime so it starts fair (lag = 0) instead of
     * starving the queue. Sibling scoping (same parentId) mirrors cgroup
     * run-queues. Returns the vruntime the new task should adopt.
     *
     * Pure: returns the value instead of mutating the task (reference mutated
     * `task.vruntime` directly).
     */
    fun initialVruntime(newTask: SchedTask, existing: List<SchedTask>): Double {
        val siblings = existing.filter { !it.isCompleted && it.parentId == newTask.parentId }
        return if (siblings.isEmpty()) 0.0 else averageVruntime(siblings)
    }

    /**
     * vruntime increment after running [secondsRan]: Δ = secondsRan / weight.
     * Returns the new vruntime; caller persists.
     */
    fun advanceVruntime(task: SchedTask, secondsRan: Long): Double =
        if (task.weight > 0) task.vruntime + secondsRan.toDouble() / task.weight else task.vruntime

    /**
     * Full ordered schedule for a flat sibling level: eligible tasks first by
     * virtualDeadline, then ineligible by vruntime. Recalculates internally so
     * the ordering is consistent, then orders — one average computation total.
     */
    fun scheduleOrder(tasks: List<SchedTask>): List<SchedTask> {
        val recalced = recalculate(tasks).filter { !it.isCompleted }
        val eligible = recalced.filter { it.lag >= 0 }.sortedBy { it.virtualDeadline }
        val ineligible = recalced.filter { it.lag < 0 }.sortedBy { it.vruntime }
        return eligible + ineligible
    }

    // ── Hierarchical (cgroup) scheduling ─────────────────────────────────────

    /**
     * Hierarchical EEVDF pick. Runs EEVDF over the entities at
     * `parentId = null` (root level); if the winner is a group entity, recurses
     * into its children's runqueue — and so on until a leaf task is reached.
     *
     * This mirrors Linux CFS `pick_next_task_fair`:
     * ```
     * while (cfs_rq) { se = pick_eevdf(cfs_rq); cfs_rq = group_cfs_rq(se); }
     * ```
     *
     * Group entities participate at their own level using their own
     * [SchedTask.vruntime], [SchedTask.weight], and [SchedTask.timeSliceSeconds]
     * — completely decoupled from their children, whose EEVDF state is scoped to
     * the children's runqueue one level below.
     *
     * The correct flow for two top-level tasks `a` and `b` (group, with children
     * `b1..b4`):
     *   - Root: EEVDF picks between `a` and `b` using their own vruntimes.
     *   - If `b` wins: recurse into `b`'s children; EEVDF picks among `b1..b4`.
     *   - Leaf result (`b2`, say) is returned.
     *   - `a`'s vruntime and deadline are never touched by child-level moves.
     */
    fun pickNextHierarchical(allTasks: List<SchedTask>): SchedTask? =
        pickAtLevel(allTasks, parentId = null)

    private fun pickAtLevel(allTasks: List<SchedTask>, parentId: String?): SchedTask? {
        // Candidates at this level: not completed, not already running, fair-class.
        val levelFair = allTasks.filter { task ->
            !task.isCompleted && !task.isRunning && task.isFair && task.parentId == parentId
        }
        if (levelFair.isEmpty()) return null
        val recalced = recalculate(levelFair)
        val winner = selectNext(recalced) ?: return null
        // Group entity won: descend into its children's runqueue.
        return if (winner.isGroup) pickAtLevel(allTasks, parentId = winner.id) ?: winner
        else winner
    }

    /**
     * After [secondsRan] of actual CPU time on [task], return updated copies of
     * [task] **and every ancestor group** present in [allTasks] with their
     * vruntimes charged proportionally.  The caller should persist all returned
     * copies in a single `saveAll`.
     *
     * This mirrors Linux `update_curr` propagating through group scheduling
     * entities: every ancestor's vruntime is charged so the root-level EEVDF
     * contest (e.g. task-a vs task-b as a group) stays fair independently of
     * individual child ticks.
     *
     * Example: `b1` runs 1 s (weight 4).
     *   - `b1.vruntime` += 1 / 4 = 0.25  (charged in b's children runqueue)
     *   - `b.vruntime`  += 1 / weight_b   (charged in root runqueue)
     * Task-a's vruntime is untouched; the root-level EEVDF will switch back
     * to `a` only once `b`'s own vruntime catches up — not on every child tick.
     */
    fun advanceVruntimeHierarchical(
        task: SchedTask,
        secondsRan: Long,
        allTasks: List<SchedTask>,
    ): List<SchedTask> {
        val updates = mutableListOf<SchedTask>()
        updates += task.copy(vruntime = advanceVruntime(task, secondsRan))
        var parentId = task.parentId
        while (parentId != null) {
            val parent = allTasks.firstOrNull { it.id == parentId } ?: break
            updates += parent.copy(vruntime = advanceVruntime(parent, secondsRan))
            parentId = parent.parentId
        }
        return updates
    }
}
