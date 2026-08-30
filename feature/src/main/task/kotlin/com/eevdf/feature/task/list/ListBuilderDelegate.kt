package com.eevdf.feature.task.list

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.RtScheduler

/**
 * Builds and maintains the two flat [TaskDisplayItem] lists observed by the UI:
 *  - [flatActiveTasks]   — Queue tab (static number sort)
 *  - [flatScheduleOrder] — Schedule tab (live EEVDF / VDL sort)
 *
 * Each list is a [MediatorLiveData] that rebuilds automatically when any of its
 * source inputs change (task list, groups-enabled flag, or expand trigger).
 * Both lists use the expand state from [GroupExpandDelegate] independently.
 *
 * Adding a new display list (e.g. a Completed tab with different grouping):
 *  1. Add a new MediatorLiveData + private buildXxxList() method here.
 *  2. Wire up its sources in [setup].
 *  No other class needs to change.
 */
internal class ListBuilderDelegate(private val vm: TaskViewModel) {

    lateinit var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>>
    lateinit var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>>

    // ── DL period-expiry auto-resort ──────────────────────────────────────────
    //
    // Problem: isDlBudgetActive is a pure computed property (reads wall-clock).
    // When a DL period expires the task silently becomes active again, but no DB
    // row changes — so flatScheduleOrder's Room/settings/expand sources never
    // fire and the task stays wherever EEVDF left it instead of hoisting to #1.
    //
    // Fix: after every buildScheduleList() we look at all DL-configured active
    // tasks and schedule a one-shot Handler callback for the exact millisecond
    // the soonest period expires.  The callback bumps _dlResortTick, which is
    // wired as a fourth source on flatScheduleOrder.  That triggers a rebuild
    // which re-evaluates isDlBudgetActive with the current time — the task now
    // sorts to rank #1.  The handler re-arms after each rebuild as long as DL
    // tasks remain.  This is the same pattern MainActivity already uses for the
    // quota bar tick, just one-shot instead of periodic.

    private val _dlResortTick = MutableLiveData<Unit>()

    private val dlResortHandler  = Handler(Looper.getMainLooper())
    private val dlResortRunnable = Runnable {
        _dlResortTick.value = Unit   // nudges flatScheduleOrder to rebuild
    }

    /**
     * Cancels any pending resort callback and schedules a new one to fire at
     * the soonest DL period-expiry among [tasks].
     *
     * Tasks with dlPeriodRemainingSeconds == 0 are already active (period just
     * elapsed or never started) — they don't need a future callback.  We only
     * arm the handler when at least one task has a future expiry (> 0 s).
     *
     * +100 ms padding ensures the wall-clock has clearly crossed the boundary
     * before we re-evaluate isDlBudgetActive.
     */
    private fun rescheduleDlResort(tasks: List<Task>) {
        dlResortHandler.removeCallbacks(dlResortRunnable)
        val soonestMs = tasks
            .filter { it.isDlConfigured && !it.isCompleted }
            .mapNotNull { task ->
                val remaining = task.dlPeriodRemainingSeconds
                if (remaining > 0L) remaining * 1_000L else null
            }
            .minOrNull() ?: return   // no future expiry — nothing to schedule
        dlResortHandler.postDelayed(dlResortRunnable, soonestMs + 100L)
    }

    // ── RT window auto-resort ─────────────────────────────────────────────────
    //
    // Same one-shot Handler pattern as DL resort.  RtScheduler.nextResortMs()
    // returns the ms until the next activation or deactivation across all RT
    // tasks.  When the callback fires, _rtResortTick bumps and flatScheduleOrder
    // rebuilds, re-evaluating isRtWindowActive for each task.

    private val _rtResortTick = MutableLiveData<Unit>()

    private val rtResortHandler  = Handler(Looper.getMainLooper())
    private val rtResortRunnable = Runnable {
        _rtResortTick.value = Unit
    }

    private fun rescheduleRtResort(tasks: List<Task>) {
        rtResortHandler.removeCallbacks(rtResortRunnable)
        val nextMs = RtScheduler.nextResortMs(tasks)
        if (nextMs < Long.MAX_VALUE) {
            rtResortHandler.postDelayed(rtResortRunnable, nextMs + 100L)
        }
    }

    /** Called from [TaskViewModel.onCleared] to prevent callbacks after VM death. */
    fun stop() {
        dlResortHandler.removeCallbacks(dlResortRunnable)
        rtResortHandler.removeCallbacks(rtResortRunnable)
    }

    /**
     * Called once from [TaskViewModel.init] after the repository LiveData and
     * delegate instances are ready.  Initialising here (rather than eagerly) avoids
     * accessing uninitialized delegates during property initialisation order.
     */
    fun setup() {
        flatActiveTasks = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = vm.activeTasks.value   ?: emptyList()
                val enabled = vm.settings.groupsEnabled.value ?: false
                value = buildQueueList(tasks, enabled)
            }
            addSource(vm.activeTasks)                        { rebuild() }
            addSource(vm.settings.groupsEnabled)             { rebuild() }
            addSource(vm.groupExpand.queueExpandTrigger)     { rebuild() }
        }

        flatScheduleOrder = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = vm.activeTasks.value   ?: emptyList()
                val enabled = vm.settings.groupsEnabled.value ?: false
                value = buildScheduleList(tasks, enabled)
                // Re-arm the one-shot handler for the next period expiry so the
                // list auto-resorts when the next DL budget replenishes.
                rescheduleDlResort(tasks)
                // Re-arm the one-shot handler for the next RT window change.
                rescheduleRtResort(tasks)
            }
            addSource(vm.activeTasks)                        { rebuild() }
            addSource(vm.settings.groupsEnabled)             { rebuild() }
            addSource(vm.groupExpand.scheduleExpandTrigger)  { rebuild() }
            // Fourth source: fires when a DL period expires (wall-clock trigger).
            addSource(_dlResortTick)                         { rebuild() }
            // Fifth source: fires when an RT window opens or closes (wall-clock trigger).
            addSource(_rtResortTick)                         { rebuild() }
        }
    }

    // ── List builders ─────────────────────────────────────────────────────────

    /**
     * Queue tab: tasks sorted by the first number in their name (static order).
     * Groups are shown when [groupsEnabled] is true; only leaf tasks otherwise.
     */
    private fun buildQueueList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedWith(SortHelper.taskNameComparator)
                .mapIndexed { index, it ->
                    val (descGroups, descTasks) = countDescendants(it.id, tasks)
                    TaskDisplayItem(it, 0,
                        childGroupCount        = descGroups,
                        childTaskCount         = descTasks,
                        cpuShare               = shares[it.id] ?: 0.0,
                        effectiveQuotaExceeded = it.isQuotaExceeded,
                        effectiveQuotaWarning  = it.isQuotaWarning,
                        queueNumber            = "${index + 1}")
                }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int, parentNumber: String,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedWith(SortHelper.taskNameComparator)
            children.forEachIndexed { index, task ->
                val dc             = tasks.filter { it.parentId == task.id }
                val quotaExceeded  = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning   = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                val number = if (parentNumber.isEmpty()) "${index + 1}" else "$parentNumber.${index + 1}"
                val (descGroups, descTasks) = countDescendants(task.id, tasks)
                result.add(TaskDisplayItem(task, depth,
                    childGroupCount        = descGroups,
                    childTaskCount         = descTasks,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number,
                    isExpanded             = if (task.isGroup) (vm.groupExpand.queueExpandState[task.id] ?: true) else true))
                if (task.isGroup && (vm.groupExpand.queueExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, "", false, false)
        return result
    }

    /**
     * Schedule tab: tasks sorted within each level by scheduler class then urgency.
     *
     * Two group-promotion scenarios are both supported simultaneously:
     *
     *   Scenario A — group has its OWN DL/RT class:
     *     group-b (DL)            ← promoted at root by its own class
     *       1.1 b1 (CFS)          ← children sort by EEVDF among themselves
     *       1.2 b2 (CFS)
     *
     *   Scenario B — group is fair class but CONTAINS a DL/RT descendant:
     *     group-b (CFS, has DL child)  ← promoted at root because of DL descendant
     *       1.1 b-dl-task (DL)         ← DL child hoisted within the group
     *       1.2 b-rt-task (RT)         ← RT child second within the group
     *       1.3 b1 (CFS)               ← fair children by EEVDF
     *
     * Ordering rules at every level (root, group, nested group):
     *   1. DL-bucket: entity itself is DL-active, OR (if group) any descendant is.
     *      Sorted by EDF urgency — most urgent first.
     *   2. RT-bucket: entity itself is RT-active, OR (if group) any descendant is.
     *      Sorted by descending RT priority.
     *   3. Fair-bucket: fair-class leaves + groups not in buckets 1 or 2.
     *      Sorted by EEVDF virtual deadline.
     *   4. Dormant entities (DL budget expired, RT window closed, no active
     *      descendants) are excluded entirely — not runnable, not shown. A dormant
     *      group excludes its entire subtree.
     *
     * A group's class does not cascade into its children. Children inside a DL
     * group still sort among themselves by their own classes via recursion.
     * No entity ever leaves its group for display.
     */
    private fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        // Captured once so all partitions and sorts use the same instant.
        val nowMs = System.currentTimeMillis()

        if (!groupsEnabled) {
            val leaves   = tasks.filter { !it.isGroup }
            val dlActive = leaves.filter { it.isDlBudgetActive }
                .sortedBy { it.dlPeriodRemainingSeconds }
            val dlIds    = dlActive.mapTo(HashSet()) { it.id }
            val rtActive = leaves.filter { it.id !in dlIds && RtScheduler.isRtWindowActive(it, nowMs) }
                .sortedByDescending { it.rtPriority }
            val rtIds    = rtActive.mapTo(HashSet()) { it.id }
            val fairActive = leaves.filter {
                it.id !in dlIds && it.id !in rtIds && it.schedulerClass == "fair_sched_class"
            }.sortedBy { it.virtualDeadline }
            val ordered  = dlActive + rtActive + fairActive
            return ordered.mapIndexed { index, it ->
                val (descGroups, descTasks) = countDescendants(it.id, tasks)
                TaskDisplayItem(it, 0,
                    childGroupCount        = descGroups,
                    childTaskCount         = descTasks,
                    cpuShare               = shares[it.id] ?: 0.0,
                    effectiveQuotaExceeded = it.isQuotaExceeded,
                    effectiveQuotaWarning  = it.isQuotaWarning,
                    queueNumber            = "${index + 1}",
                    isDlActive             = it.isDlBudgetActive,
                    isRtActive             = RtScheduler.isRtWindowActive(it, nowMs))
            }
        }

        // ── Groups-enabled: descendant-aware per-level class partitioning ─────
        val result = mutableListOf<TaskDisplayItem>()

        // DL urgency for sorting within the DL bucket: for a promoted group,
        // urgency is the minimum remaining budget across all DL descendants.
        fun dlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else if (task.isDlBudgetActive) task.dlPeriodRemainingSeconds
            else tasks.filter { it.parentId == task.id && !it.isCompleted }
                      .minOfOrNull { dlUrgency(it) } ?: Long.MAX_VALUE

        fun addLevel(
            parentId: String?,
            depth: Int,
            parentNumber: String,
            parentQuotaExceeded: Boolean,
            parentQuotaWarning: Boolean,
            counter: IntArray,
        ) {
            val children = tasks.filter { it.parentId == parentId }

            // Bucket 1 — DL: entity is DL-active itself, or (if group) has an
            // active DL descendant. Scenario A + Scenario B both handled here.
            val dlActive = children.filter { child ->
                if (child.isGroup)
                    child.isDlBudgetActive || EEVDFScheduler.hasActiveDlDescendant(child, tasks)
                else
                    child.isDlBudgetActive
            }.sortedBy { dlUrgency(it) }
            val dlIds = dlActive.mapTo(HashSet()) { it.id }

            // Bucket 2 — RT: entity is RT-active itself, or (if group) has an
            // active RT descendant.
            val rtActive = children.filter { child ->
                child.id !in dlIds && (
                    if (child.isGroup)
                        RtScheduler.isRtWindowActive(child, nowMs) ||
                            RtScheduler.hasActiveRtDescendant(child, tasks, nowMs)
                    else
                        RtScheduler.isRtWindowActive(child, nowMs)
                )
            }.sortedByDescending { it.rtPriority }
            val rtIds = rtActive.mapTo(HashSet()) { it.id }

            // Bucket 3 — Fair: fair-class leaves + any group not in buckets 1 or 2.
            // Groups fall here when they are fair-class with no active DL/RT content.
            // Dormant non-fair leaves (expired DL, closed RT window, not a group)
            // are in none of the three buckets and are silently excluded.
            val fairActive = children.filter { child ->
                child.id !in dlIds && child.id !in rtIds &&
                    (child.isGroup || child.schedulerClass == "fair_sched_class")
            }.sortedBy { it.virtualDeadline }

            val ordered = dlActive + rtActive + fairActive

            ordered.forEach { task ->
                val dc              = tasks.filter { it.parentId == task.id }
                val quotaExceeded   = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning    = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                val isTaskDlActive  = task.isDlBudgetActive
                val isTaskRtActive  = RtScheduler.isRtWindowActive(task, nowMs)
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                val (descGroups, descTasks) = countDescendants(task.id, tasks)
                result.add(TaskDisplayItem(task, depth,
                    childGroupCount  = descGroups,
                    childTaskCount   = descTasks,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number,
                    isDlActive             = isTaskDlActive,
                    // isDlGroupHoisted: group promoted by own DL OR a DL descendant.
                    isDlGroupHoisted       = task.isGroup &&
                        (isTaskDlActive || EEVDFScheduler.hasActiveDlDescendant(task, tasks)),
                    isRtActive             = isTaskRtActive,
                    // isRtGroupHoisted: group promoted by own RT OR an RT descendant.
                    isRtGroupHoisted       = task.isGroup &&
                        (isTaskRtActive || RtScheduler.hasActiveRtDescendant(task, tasks, nowMs)),
                    isExpanded             = if (task.isGroup) (vm.groupExpand.scheduleExpandState[task.id] ?: true) else true))
                // Recurse into children with the same per-level rules applied
                // independently — the parent group's class does not cascade down.
                if (task.isGroup && (vm.groupExpand.scheduleExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning, IntArray(1))
            }
        }
        addLevel(null, 0, "", false, false, IntArray(1))
        return result
    }

    /**
     * Recursively counts all descendant groups and leaf tasks under [groupId]
     * at any depth within [allTasks] (active-only; completed/deleted are absent).
     * Returns Pair(descendantGroupCount, descendantTaskCount).
     */
    private fun countDescendants(taskId: String, allTasks: List<Task>): Pair<Int, Int> {
        val children = allTasks.filter { it.parentId == taskId }
        var groups = 0
        var leaves = 0
        for (child in children) {
            if (child.isGroup) {
                groups++
                // Recurse into real groups — their subtrees belong to this count.
                val (g, l) = countDescendants(child.id, allTasks)
                groups += g
                leaves += l
            } else {
                leaves++
                // Stop here. A formerly-group task (isGroup=false with children)
                // counts as one leaf from the parent's perspective. Its own G/T
                // is shown on its own card via countDescendants called at its level.
            }
        }
        return Pair(groups, leaves)
    }
}
