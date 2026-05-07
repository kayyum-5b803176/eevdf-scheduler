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
                .sortedWith(compareBy({ TaskSortHelper.extractNumber(it.name) }, { it.name }))
                .map {
                    TaskDisplayItem(it, 0,
                        cpuShare               = shares[it.id] ?: 0.0,
                        effectiveQuotaExceeded = it.isQuotaExceeded,
                        effectiveQuotaWarning  = it.isQuotaWarning)
                }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedWith(compareBy({ TaskSortHelper.extractNumber(it.name) }, { it.name }))
            for (task in children) {
                val dc             = tasks.filter { it.parentId == task.id }
                val quotaExceeded  = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning   = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                result.add(TaskDisplayItem(task, depth,
                    childCount             = dc.size,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning))
                if (task.isGroup && (vm.groupExpand.queueExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, false, false)
        return result
    }

    /**
     * Schedule tab: tasks sorted by virtualDeadline (live EEVDF order).
     * Groups are shown when [groupsEnabled] is true; only leaf tasks otherwise.
     */
    private fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedBy { it.virtualDeadline }
                .map {
                    TaskDisplayItem(it, 0,
                        cpuShare               = shares[it.id] ?: 0.0,
                        effectiveQuotaExceeded = it.isQuotaExceeded,
                        effectiveQuotaWarning  = it.isQuotaWarning)
                }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedBy { it.virtualDeadline }
            for (task in children) {
                val dc            = tasks.filter { it.parentId == task.id }
                val quotaExceeded = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning  = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                result.add(TaskDisplayItem(task, depth,
                    childCount             = dc.size,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning))
                if (task.isGroup && (vm.groupExpand.scheduleExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, false, false)
        return result
    }
}
