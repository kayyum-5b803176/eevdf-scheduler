package com.eevdf.scheduler.viewmodel.scheduler

import androidx.lifecycle.viewModelScope
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import kotlinx.coroutines.launch
import com.eevdf.scheduler.viewmodel.task.TaskViewModel
import com.eevdf.scheduler.viewmodel.task.TaskSortHelper

/**
 * Owns all task-navigation and scheduler-selection logic:
 *  - Sibling rotation (same parentId, Queue or Schedule sort order)
 *  - Global rotation (one representative per root-level entry)
 *  - EEVDF-based schedule-next
 *  - Auto-mode next-task selection
 *  - jumpToFirst / pauseAndDismiss helpers
 *  - refreshSchedule (stats + order update)
 *
 * Adding a new navigation strategy (e.g. priority-weighted random):
 *  1. Add a private strategy method here.
 *  2. Wire it through [nextSibling] or a new public entry point.
 *  No timer, CRUD, or notice-state code needs to change.
 */
internal class TaskSchedulerDelegate(private val vm: TaskViewModel) {

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * "Next" button tap.
     *  - No card open → [jumpToFirst].
     *  - Global Rotate ON  → [rotateGlobal].
     *  - Global Rotate OFF → [rotateSiblings].
     *
     * [onQueueTab] true → Queue (number-sorted); false → Schedule (VDL-sorted).
     */
    fun nextSibling(onQueueTab: Boolean = false) {
        vm.pauseTimer()
        if (vm._currentTask.value == null) {
            jumpToFirst(onQueueTab)
            return
        }
        if (vm.settings.globalRotateEnabled.value == true) {
            rotateGlobal(onQueueTab)
        } else {
            rotateSiblings(onQueueTab)
        }
    }

    /**
     * Jumps to the first visible leaf task at the top of the current tab list
     * (depth-first, list order — e.g. group-a → group-aa → task-aa1).
     * Skips groups, completed tasks, and the interrupt task.
     */
    fun jumpToFirst(onQueueTab: Boolean) {
        val list  = if (onQueueTab) vm.listBuilder.flatActiveTasks.value
                    else            vm.listBuilder.flatScheduleOrder.value
        val first = list
            ?.firstOrNull { !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt }
            ?.task
            ?: run { vm._toastMessage.value = "No tasks available"; return }
        vm.pauseTimer()
        vm._currentTask.value  = first
        vm._timerSeconds.value = first.remainingSeconds
        vm._toastMessage.value = "Jumped to \"${first.name}\""
    }

    /**
     * "Next" button hold with no timer card open.
     * Saves the current timer state (same as a manual pause) then dismisses
     * the timer card so the user sees the plain task list.
     */
    fun pauseAndDismiss() {
        vm.pauseTimer()
        vm._currentTask.value  = null
        vm._toastMessage.value = "Timer paused — task saved"
    }

    /**
     * Selects the highest-priority task via the EEVDF repository query and
     * opens it in the timer card.
     */
    fun scheduleNext() = vm.viewModelScope.launch {
        vm.pauseTimer()
        val next = vm.repository.selectNextTask()
        if (next != null) {
            vm._currentTask.postValue(next)
            vm._timerSeconds.postValue(next.remainingSeconds)
            vm._toastMessage.postValue("Now: \"${next.name}\" (Priority ${next.priority})")
        } else {
            vm._currentTask.postValue(null)
            vm._toastMessage.postValue("No active tasks to schedule")
        }
        refreshSchedule()
    }

    /**
     * Re-derives the schedule order and stats from the DB.
     *
     * Stats are computed group-aware when groups are enabled:
     *  - activeTasks  counts only leaf nodes (groups/containers excluded).
     *  - weight / avgVrt / fairness are aggregated bottom-up through the
     *    cgroup tree so sibling sets at each level are compared against each
     *    other before their result is promoted to the parent level.
     *
     * When groups are disabled the original flat computation is used, keeping
     * behaviour identical to the pre-group implementation.
     */
    fun refreshSchedule() = vm.viewModelScope.launch {
        val order = vm.repository.getScheduleOrder()
        vm._scheduleOrder.postValue(order)
        val allTasks      = order + (vm.completedTasks.value ?: emptyList())
        val groupsEnabled = vm.groupsEnabled.value ?: false
        vm._stats.postValue(EEVDFScheduler.getStats(allTasks, groupsEnabled))
    }

    /**
     * Selects the next task for Auto mode using the parent group's taskType.
     *
     * | Parent taskType | Strategy                                           |
     * |-----------------|----------------------------------------------------|\
     * | DEFAULT         | Next sibling by VDL, looping back to first         |
     * | NOTIFICATION    | Sibling with lowest virtual deadline               |
     * | ALERT / CUSTOM  | null → caller falls back to global selectNextTask  |
     * | no parent group | null → caller falls back to global selectNextTask  |
     */
    fun selectAutoNextTask(task: Task, allTasks: List<Task>): Task? {
        val parentId = task.parentId ?: return null
        val parent   = allTasks.find { it.id == parentId } ?: return null

        val siblings = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }
            .sortedBy { it.virtualDeadline }

        if (siblings.isEmpty()) return null

        return when (parent.taskType) {
            "DEFAULT" -> {
                val idx = siblings.indexOfFirst { it.id == task.id }
                siblings[(idx + 1) % siblings.size]
            }
            "NOTIFICATION" -> siblings.first()
            else            -> null
        }
    }

    // ── Private rotation strategies ───────────────────────────────────────────

    /**
     * Cycles through siblings that share the same parentId, in UI list order.
     * NOTIFICATION parent: always jumps to the lowest-VDL sibling (no rotation).
     */
    private fun rotateSiblings(onQueueTab: Boolean) {
        val current  = vm._currentTask.value
        val allTasks = (if (onQueueTab) vm.listBuilder.flatActiveTasks
                        else            vm.listBuilder.flatScheduleOrder)
            .value?.map { it.task } ?: return

        val parentId   = current?.parentId
        val parentType = allTasks.find { it.id == parentId }?.taskType

        val base = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }
        val siblings = if (onQueueTab)
            base.sortedWith(TaskSortHelper.taskNameComparator)
        else
            base.sortedBy { it.virtualDeadline }

        if (siblings.size <= 1) {
            vm._toastMessage.value = "No other siblings to rotate"
            return
        }

        val next = if (parentType == "NOTIFICATION") {
            base.sortedBy { it.virtualDeadline }.first()
        } else {
            val idx = siblings.indexOfFirst { it.id == current?.id }
            siblings[(idx + 1) % siblings.size]
        }

        vm._currentTask.value  = next
        vm._timerSeconds.value = next.remainingSeconds
        vm._toastMessage.value = "Next: \"${next.name}\""
        vm.viewModelScope.launch { refreshSchedule() }
    }

    /**
     * One representative leaf per root-level entry, cycling in UI list order.
     * For a group the representative is its first leaf (depth-first, VDL order).
     * For a root leaf task it represents itself.
     */
    private fun rotateGlobal(onQueueTab: Boolean) {
        val current  = vm._currentTask.value
        val allTasks = (if (onQueueTab) vm.listBuilder.flatActiveTasks
                        else            vm.listBuilder.flatScheduleOrder)
            .value?.map { it.task } ?: return

        val base = allTasks.filter { it.parentId == null && !it.isCompleted && !it.isInterrupt }
        val rootEntries = if (onQueueTab)
            base.sortedWith(TaskSortHelper.taskNameComparator)
        else
            base.sortedBy { it.virtualDeadline }

        val representatives = rootEntries.mapNotNull { root ->
            val leaf = if (!root.isGroup) root else firstLeafOf(allTasks, root.id)
            if (leaf == null || leaf.isInterrupt) null else Pair(root.id, leaf)
        }
        if (representatives.isEmpty()) return

        val currentRootId = current?.let { rootAncestorOf(allTasks, it)?.id }
        val currentIdx    = representatives.indexOfFirst { it.first == currentRootId }
        val nextIdx       = (currentIdx + 1) % representatives.size
        val next          = representatives[nextIdx].second

        vm._currentTask.value  = next
        vm._timerSeconds.value = next.remainingSeconds
        vm._toastMessage.value = "Next: \"${next.name}\" (${nextIdx + 1}/${representatives.size})"
        vm.viewModelScope.launch { refreshSchedule() }
    }

    // ── Tree traversal helpers ────────────────────────────────────────────────

    /** Returns the first non-group, non-completed leaf under [parentId] in VDL order. */
    private fun firstLeafOf(tasks: List<Task>, parentId: String?): Task? {
        val children = tasks
            .filter { it.parentId == parentId && !it.isCompleted && !it.isInterrupt }
            .sortedBy { it.virtualDeadline }
        for (child in children) {
            if (!child.isGroup) return child
            val leaf = firstLeafOf(tasks, child.id)
            if (leaf != null) return leaf
        }
        return null
    }

    /** Traces up the parentId chain to return the root-level ancestor (parentId == null). */
    private fun rootAncestorOf(tasks: List<Task>, task: Task): Task? {
        if (task.parentId == null) return task
        val parent = tasks.find { it.id == task.parentId } ?: return task
        return rootAncestorOf(tasks, parent)
    }
}
