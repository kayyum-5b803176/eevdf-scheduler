package com.eevdf.scheduler.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TaskDisplayItem
import com.eevdf.scheduler.scheduler.EEVDFScheduler

/**
 * Builds and maintains the two flat [TaskDisplayItem] lists observed by the UI:
 *  - [flatActiveTasks]   — Queue tab (static number sort)
 *  - [flatScheduleOrder] — Schedule tab (live EEVDF / VDL sort)
 *
 * Each list is a [MediatorLiveData] that rebuilds automatically when any of its
 * source inputs change (task list, groups-enabled flag, or expand trigger).
 * Both lists use the expand state from [TaskGroupExpandDelegate] independently.
 *
 * Adding a new display list (e.g. a Completed tab with different grouping):
 *  1. Add a new MediatorLiveData + private buildXxxList() method here.
 *  2. Wire up its sources in [setup].
 *  No other class needs to change.
 */
internal class TaskListBuilderDelegate(private val vm: TaskViewModel) {

    lateinit var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>>
    lateinit var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>>

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
            }
            addSource(vm.activeTasks)                        { rebuild() }
            addSource(vm.settings.groupsEnabled)             { rebuild() }
            addSource(vm.groupExpand.scheduleExpandTrigger)  { rebuild() }
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
                .sortedWith(TaskSortHelper.taskNameComparator)
                .mapIndexed { index, it ->
                    TaskDisplayItem(it, 0,
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
                .sortedWith(TaskSortHelper.taskNameComparator)
            children.forEachIndexed { index, task ->
                val dc             = tasks.filter { it.parentId == task.id }
                val quotaExceeded  = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning   = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                val number = if (parentNumber.isEmpty()) "${index + 1}" else "$parentNumber.${index + 1}"
                result.add(TaskDisplayItem(task, depth,
                    childCount             = dc.size,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number))
                if (task.isGroup && (vm.groupExpand.queueExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, "", false, false)
        return result
    }

    /**
     * Schedule tab: tasks sorted by EEVDF virtual deadline, with one exception —
     * any task whose [Task.isDlBudgetActive] is true (has remaining SCHED_DEADLINE
     * runtime budget in the current period) is hoisted to the front of the list,
     * ranked among themselves by EDF urgency (shortest period remaining first).
     *
     * Once a DL task exhausts its period budget it falls back into the normal
     * EEVDF ordering alongside all other tasks.
     */
    private fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        if (!groupsEnabled) {
            val leaves = tasks.filter { !it.isGroup }

            // Partition: DL-budget-active tasks go first, sorted by EDF urgency
            // (shortest period remaining = most urgent deadline = goes first)
            val (dlActive, eevdfRest) = leaves.partition { it.isDlBudgetActive }
            val dlSorted   = dlActive.sortedBy { it.dlPeriodRemainingSeconds }
            val eevdfSorted = eevdfRest.sortedBy { it.virtualDeadline }
            val ordered    = dlSorted + eevdfSorted

            return ordered.mapIndexed { index, it ->
                TaskDisplayItem(it, 0,
                    cpuShare               = shares[it.id] ?: 0.0,
                    effectiveQuotaExceeded = it.isQuotaExceeded,
                    effectiveQuotaWarning  = it.isQuotaWarning,
                    queueNumber            = "${index + 1}",
                    isDlActive             = it.isDlBudgetActive)
            }
        }
        val result = mutableListOf<TaskDisplayItem>()
        // When groups are enabled we build the tree level-by-level; within each
        // level DL-active leaves are still hoisted before EEVDF siblings.
        fun addLevel(parentId: String?, depth: Int, parentNumber: String,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean,
                     counter: IntArray) {
            val children = tasks.filter { it.parentId == parentId }
            // DL-active leaves first (EDF urgency), then EEVDF order
            val (dlChildren, restChildren) = children.partition { it.isDlBudgetActive }
            val sorted = dlChildren.sortedBy { it.dlPeriodRemainingSeconds } +
                         restChildren.sortedBy { it.virtualDeadline }
            sorted.forEach { task ->
                val dc            = tasks.filter { it.parentId == task.id }
                val quotaExceeded = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning  = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                result.add(TaskDisplayItem(task, depth,
                    childCount             = dc.size,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number,
                    isDlActive             = task.isDlBudgetActive))
                if (task.isGroup && (vm.groupExpand.scheduleExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning, IntArray(1))
            }
        }
        addLevel(null, 0, "", false, false, IntArray(1))
        return result
    }
}
