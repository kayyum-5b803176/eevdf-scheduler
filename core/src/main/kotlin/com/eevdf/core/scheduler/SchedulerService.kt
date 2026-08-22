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
     * Full ordered schedule for display: DL first, then RT, then EEVDF order.
     *
     * For the fair class, root-level entities (including group containers) are
     * ordered by EEVDF, and each group's children are inserted immediately after
     * it so the caller gets a coherent hierarchical view in one flat list.
     */
    fun scheduleOrder(tasks: List<SchedTask>, now: Now): List<SchedTask> {
        // DL and RT: flat non-group pool (unchanged behaviour).
        val flatNonGroup = tasks.filter { !it.isCompleted && !it.isGroup }

        val dl = flatNonGroup.filter { it.isDlConfigured && it.dl!!.isBudgetActiveAt(now.epochSeconds) }
            .sortedBy { it.dl!!.deadlineSeconds }
        val dlIds = dl.mapTo(HashSet()) { it.id }

        val rt = flatNonGroup.filter {
            it.id !in dlIds &&
                RtPolicy.isWindowActive(it, now.dayOfWeekIndex, now.secondOfDay, now.prevDayOfWeekIndex)
        }.sortedWith(compareByDescending<SchedTask> { it.rt!!.priority }.thenBy { it.rt!!.activationSecondOfDay })
        val rtIds = rt.mapTo(HashSet()) { it.id }

        // Fair: root-level entities (leaves + group containers) in EEVDF order.
        val rootFair = tasks.filter {
            !it.isCompleted && it.parentId == null && it.isFair &&
                it.id !in dlIds && it.id !in rtIds
        }
        val fairRootOrdered = EevdfScheduler.scheduleOrder(rootFair)

        // Expand each group: append its children in EEVDF order immediately after
        // the group entity so the display shows the sub-queue inline.
        return buildList {
            addAll(dl)
            addAll(rt)
            for (entity in fairRootOrdered) {
                add(entity)
                if (entity.isGroup) {
                    addAll(
                        EevdfScheduler.scheduleOrder(
                            tasks.filter { it.parentId == entity.id && !it.isCompleted }
                        )
                    )
                }
            }
        }
    }
}
