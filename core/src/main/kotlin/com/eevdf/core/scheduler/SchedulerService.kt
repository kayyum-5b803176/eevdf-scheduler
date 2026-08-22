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

    /**
     * Pick the next task to run, honouring class precedence and group hierarchy.
     *
     * DL and RT operate on the flat non-group pool (group containers are not a
     * meaningful use-case for those classes).  The fair class uses hierarchical
     * EEVDF: group entities compete at their own level against peers using their
     * own vruntime/weight/timeSlice, and a winning group triggers recursion into
     * its children's runqueue — mirroring Linux's `pick_next_task_fair` descent
     * via `se->my_q`.
     *
     * Concretely, for task-a (root leaf, 10 s slice) and task-b (root group,
     * 10 s slice, children b1..b4 with 1 s slices each):
     *   Root EEVDF picks between a and b.  If b wins, we recurse to pick among
     *   b1..b4.  b1's 1 s expiry triggers a full hierarchical re-pick — which
     *   gives b a turn again as long as b's own vruntime (charged via
     *   [EevdfScheduler.advanceVruntimeHierarchical]) is still behind a's.  Only
     *   once b has used its fair share at root level does a win.
     */
    fun selectNext(tasks: List<SchedTask>, now: Now): SchedTask? {
        // DL and RT: flat non-group pool (hierarchy not supported for these classes).
        val flatNonGroup = tasks.filter { !it.isCompleted && !it.isGroup }

        // 1. SCHED_DEADLINE: any task with a live budget wins outright.
        flatNonGroup.filter { it.isDlConfigured && it.dl!!.isBudgetActiveAt(now.epochSeconds) }
            .minByOrNull { it.dl!!.deadlineSeconds }
            ?.let { return it }

        // 2. SCHED_RT: window-active tasks, FIFO/RR within the cohort.
        val rtActive = flatNonGroup.filter {
            RtPolicy.isWindowActive(it, now.dayOfWeekIndex, now.secondOfDay, now.prevDayOfWeekIndex)
        }
        RtPolicy.pickRr(rtActive, rrState)?.let { return it }

        // 3. Fair class: hierarchical EEVDF.
        //    Group entities are NOT filtered out here.  They carry their own
        //    vruntime/weight/timeSlice and compete at their parentId level.
        //    pickNextHierarchical recurses into a winning group's children until
        //    a leaf task is found — the leaf is what gets scheduled.
        return EevdfScheduler.pickNextHierarchical(tasks)
    }

    /**
     * Full ordered schedule for display: DL first, then RT, then fair leaves in
     * depth-first EEVDF order. Mirrors Linux's runqueue view:
     *
     *   - Every position in the returned list is a **runnable leaf** — group
     *     containers never appear. They participate in the EEVDF ordering at
     *     their own level (so their children inherit the correct position
     *     relative to peer leaves/groups), but the group entity itself is
     *     replaced by its recursively-expanded children.
     *
     *   - DL and RT leaves are collected from **all depths** first, then
     *     excluded from the fair walk entirely — so a DL child of a fair group
     *     appears exactly once in the DL section, never again inside the fair
     *     expansion. This matches Linux where DL/RT classes sit above CFS
     *     globally and do not participate in the cgroup fair hierarchy.
     *
     *   - The fair walk recurses to arbitrary depth, not just one level —
     *     nested groups (group inside a group) are expanded correctly.
     *
     *   - `scheduleOrder()[0]` is always the same task `selectNext()` returns,
     *     preserving the queue contract.
     */
    fun scheduleOrder(tasks: List<SchedTask>, now: Now): List<SchedTask> {
        // 1. Collect ALL runnable leaves (any depth) for DL and RT.
        //    Group containers are never DL/RT, so !isGroup is correct here.
        val allLeaves = tasks.filter { !it.isCompleted && !it.isGroup }

        val dl = allLeaves.filter { it.isDlConfigured && it.dl!!.isBudgetActiveAt(now.epochSeconds) }
            .sortedBy { it.dl!!.deadlineSeconds }
        val dlIds = dl.mapTo(HashSet()) { it.id }

        val rt = allLeaves.filter {
            it.id !in dlIds &&
                RtPolicy.isWindowActive(it, now.dayOfWeekIndex, now.secondOfDay, now.prevDayOfWeekIndex)
        }.sortedWith(compareByDescending<SchedTask> { it.rt!!.priority }.thenBy { it.rt!!.activationSecondOfDay })
        val rtIds = rt.mapTo(HashSet()) { it.id }

        // 2. Fair: depth-first EEVDF walk from root, yielding only leaves.
        //    DL/RT tasks excluded so they appear exactly once (above).
        val excludedIds = dlIds + rtIds
        val fair = fairLeavesDepthFirst(tasks, parentId = null, excludedIds = excludedIds)

        return dl + rt + fair
    }

    /**
     * Depth-first EEVDF walk mirroring Linux's per-cgroup `cfs_rq` rb-tree
     * traversal. At each level:
     *   1. EEVDF-order the siblings (leaves + group entities together).
     *   2. Walk the ordered list: leaf → emit; group → recurse, emitting
     *      its children in its place.
     *
     * Group entities determine *where* their children sit relative to peer
     * leaves (a group with low vruntime puts its children ahead of a peer
     * leaf with high vruntime), but the group itself is never emitted.
     *
     * Result: only runnable leaf tasks, in the execution order Linux would
     * produce by exhaustively walking the cgroup tree.
     */
    private fun fairLeavesDepthFirst(
        allTasks: List<SchedTask>,
        parentId: String?,
        excludedIds: Set<String>,
    ): List<SchedTask> {
        // isFair: excludes dormant RT and expired-budget DL tasks that are not
        // in excludedIds (because they were inactive when DL/RT sets were built).
        // Those classes do not belong in the CFS runqueue under any condition.
        val levelEntities = allTasks.filter {
            !it.isCompleted && it.isFair && it.parentId == parentId && it.id !in excludedIds
        }
        if (levelEntities.isEmpty()) return emptyList()

        val ordered = EevdfScheduler.scheduleOrder(levelEntities)

        return buildList {
            for (entity in ordered) {
                if (entity.isGroup) {
                    // Descend: the group's children replace the group in the queue.
                    addAll(fairLeavesDepthFirst(allTasks, parentId = entity.id, excludedIds = excludedIds))
                } else {
                    add(entity)
                }
            }
        }
    }
}
