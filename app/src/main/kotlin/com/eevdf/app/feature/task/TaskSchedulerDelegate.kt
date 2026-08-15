package com.eevdf.app.feature.task

import androidx.lifecycle.viewModelScope
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.RtScheduler
import kotlinx.coroutines.launch
import com.eevdf.app.feature.task.TaskViewModel
import com.eevdf.app.feature.task.TaskSortHelper

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
        val runningId     = vm.currentTask.value?.id?.takeIf { vm.currentTask.value?.isRunning == true }
        vm._stats.postValue(EEVDFScheduler.getStats(allTasks, groupsEnabled, runningId))
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
     *
     * Queue tab:    siblings sorted by task name (static number order).
     * Schedule tab: siblings taken directly from [flatScheduleOrder] in display
     *               order, which already applies DL → RT → EEVDF hoisting.
     *               Re-sorting by virtualDeadline here was the bug: a DL/RT-class
     *               sibling sitting at position #1 in the UI would be skipped
     *               because its vdl happened to be larger than a plain EEVDF
     *               sibling's.
     *
     * NOTIFICATION parent: always jumps to the lowest-VDL sibling (no rotation).
     */
    private fun rotateSiblings(onQueueTab: Boolean) {
        val current   = vm._currentTask.value
        val flatItems = (if (onQueueTab) vm.listBuilder.flatActiveTasks
                         else            vm.listBuilder.flatScheduleOrder)
            .value ?: return

        val allTasks   = flatItems.map { it.task }
        val parentId   = current?.parentId
        val parentType = allTasks.find { it.id == parentId }?.taskType

        // Unordered sibling pool — used by the NOTIFICATION branch which always
        // wants the lowest-VDL sibling regardless of scheduler class.
        val base = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }

        // Queue tab:    sort by task name.
        // Schedule tab: preserve the order already in flatScheduleOrder.
        //               flatScheduleOrder applies DL-active → RT-active → EEVDF at
        //               every level; filtering it by parentId retains that ordering
        //               without any re-sort.
        val siblings = if (onQueueTab) {
            base.sortedWith(TaskSortHelper.taskNameComparator)
        } else {
            flatItems
                .map { it.task }
                .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }
            // No re-sort: flatScheduleOrder already reflects DL → RT → EEVDF.
        }

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
     * One representative leaf per entry at the effective rotation depth, cycling
     * in UI list order.  For a group the representative is its first leaf
     * (depth-first, schedule order); for a leaf task it represents itself.
     *
     * IMPORTANT: entries are taken directly from the flat list in the order they
     * appear there — NOT re-sorted by name or virtualDeadline.  The flat list
     * already reflects RT/DL hoisting so the rotation matches what the user sees.
     *
     * Auto-depth: [resolveEffectiveParentId] descends through single-entry
     * expanded groups until a level with 2+ candidates is found.  Collapsing a
     * root group automatically causes the next tap to dive into the remaining
     * expanded group's children; expanding it again pops back to root — no stored
     * depth state anywhere.
     *
     * On the Schedule tab, [firstLeafOf] uses the same DL → RT → EEVDF tier sort
     * as [TaskListBuilderDelegate.buildScheduleList] so the representative leaf
     * matches rank #1 within its group.
     */
    private fun rotateGlobal(onQueueTab: Boolean) {
        val current   = vm._currentTask.value
        val flatItems = (if (onQueueTab) vm.listBuilder.flatActiveTasks
                         else            vm.listBuilder.flatScheduleOrder)
            .value ?: return

        val allTasks = flatItems.map { it.task }

        // Find the shallowest depth with 2+ rotation candidates, descending
        // automatically through single expanded-group levels.
        val effectiveParentId = resolveEffectiveParentId(flatItems)

        // Entries at the effective level in flat-list (display) order.
        // mapNotNull drops collapsed groups — firstLeafOf returns null for them
        // because their children are absent from allTasks.
        val representatives = flatItems
            .filter { it.task.parentId == effectiveParentId &&
                      !it.task.isCompleted &&
                      !it.task.isInterrupt }
            .mapNotNull { item ->
                val candidate = item.task
                val leaf = if (!candidate.isGroup) candidate
                           else firstLeafOf(allTasks, candidate.id, scheduleSort = !onQueueTab)
                if (leaf == null || leaf.isInterrupt) null else Pair(candidate.id, leaf)
            }
        if (representatives.isEmpty()) return

        // ancestorUnder finds which representative slot the current task belongs
        // to at effectiveParentId — a strict generalisation of the former
        // rootAncestorOf (identical behaviour when effectiveParentId is null).
        val currentAnchorId = current?.let { ancestorUnder(allTasks, it, effectiveParentId)?.id }
        val currentIdx      = representatives.indexOfFirst { it.first == currentAnchorId }
        val nextIdx         = (currentIdx + 1) % representatives.size
        val next            = representatives[nextIdx].second

        vm._currentTask.value  = next
        vm._timerSeconds.value = next.remainingSeconds
        vm._toastMessage.value = "Next: \"${next.name}\" (${nextIdx + 1}/${representatives.size})"
        vm.viewModelScope.launch { refreshSchedule() }
    }

    // ── Tree traversal helpers ────────────────────────────────────────────────

    /**
     * Returns the first non-group, non-completed leaf under [parentId].
     *
     * [scheduleSort] = false (Queue tab / legacy):
     *   Children at each level are sorted by virtualDeadline — the original
     *   EEVDF-only ordering.
     *
     * [scheduleSort] = true (Schedule tab):
     *   Children at each level are sorted with the same DL → RT → EEVDF tier
     *   order that [TaskListBuilderDelegate.buildScheduleList] applies, so the
     *   leaf returned matches what the user sees at rank #1 in the UI — even
     *   for collapsed groups whose children are absent from the flat display list.
     *
     * [nowMs] is captured once at the [rotateGlobal] call site and threaded down
     * through every recursive level so all sibling sets see the same wall-clock
     * instant, preventing boundary flicker when an RT window sits exactly on
     * the activation edge.
     */
    private fun firstLeafOf(
        tasks: List<Task>,
        parentId: String?,
        scheduleSort: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Task? {
        val children = tasks
            .filter { it.parentId == parentId && !it.isCompleted && !it.isInterrupt }
        val sorted = if (scheduleSort) scheduleSortChildren(children, tasks, nowMs)
                     else              children.sortedBy { it.virtualDeadline }
        for (child in sorted) {
            if (!child.isGroup) return child
            val leaf = firstLeafOf(tasks, child.id, scheduleSort, nowMs)
            if (leaf != null) return leaf
        }
        return null
    }

    /**
     * Mirrors the per-level sort applied by [TaskListBuilderDelegate.buildScheduleList]
     * so that [firstLeafOf] and [rotateSiblings] produce the same ordering the user
     * sees on the Schedule tab:
     *
     *   Tier 1 — DL-active  ([Task.isDlBudgetActive], or group with an active DL
     *             descendant): sorted by [Task.dlPeriodRemainingSeconds] ascending
     *             (shortest deadline remaining = most urgent, matching EDF policy).
     *
     *   Tier 2 — RT-active  ([RtScheduler.isRtWindowActive], or group with an active
     *             RT descendant): sorted by [Task.rtPriority] descending (higher
     *             priority value = more urgent, matching Linux rt_sched_class).
     *
     *   Tier 3 — Everything else: sorted by [Task.virtualDeadline] ascending (EEVDF
     *             policy — earliest virtual deadline first).
     *
     * [nowMs] is passed in (not read from [System.currentTimeMillis]) so that all
     * levels within a single [firstLeafOf] traversal share one consistent instant.
     */
    private fun scheduleSortChildren(
        children: List<Task>,
        allTasks: List<Task>,
        nowMs: Long,
    ): List<Task> {
        val (dlChildren, nonDl) = children.partition { child ->
            if (child.isGroup) EEVDFScheduler.hasActiveDlDescendant(child, allTasks)
            else child.isDlBudgetActive
        }
        val (rtChildren, restChildren) = nonDl.partition { child ->
            if (child.isGroup) RtScheduler.hasActiveRtDescendant(child, allTasks, nowMs)
            else RtScheduler.isRtWindowActive(child, nowMs)
        }
        // dlUrgency mirrors the same local function used in buildScheduleList:
        // leaf → dlPeriodRemainingSeconds; group → minimum across its children.
        fun dlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else allTasks
                .filter { it.parentId == task.id && !it.isCompleted }
                .minOfOrNull { dlUrgency(it) } ?: Long.MAX_VALUE
        return dlChildren.sortedBy  { dlUrgency(it) } +
               rtChildren.sortedByDescending { if (it.isGroup) 0 else it.rtPriority } +
               restChildren.sortedBy { it.virtualDeadline }
    }

    /**
     * Entry point for auto-depth resolution.  Pre-computes [expandedGroupIds] —
     * the set of group IDs that have at least one visible child in [flatItems]
     * (i.e. are expanded) — then delegates to [resolveEffectiveParentIdAt].
     * Building the set once here keeps the per-level check O(1) throughout the
     * recursive descent instead of O(n) per candidate.
     */
    private fun resolveEffectiveParentId(flatItems: List<TaskDisplayItem>): String? {
        val expandedGroupIds = flatItems.mapNotNull { it.task.parentId }.toSet()
        return resolveEffectiveParentIdAt(flatItems, null, expandedGroupIds)
    }

    /**
     * Recursive depth resolver.  At each level it counts visible rotation
     * candidates — leaf tasks plus expanded groups (those whose id is in
     * [expandedGroupIds]).  Collapsed groups are excluded because their children
     * are absent from [flatItems] and no representative leaf can be found inside.
     *
     *   ≥ 2 candidates → stop, rotate at this level ([parentId]).
     *   = 1 candidate that is an expanded group → descend one level into it.
     *   anything else (0 candidates, or 1 leaf) → stop at this level.
     */
    private fun resolveEffectiveParentIdAt(
        flatItems: List<TaskDisplayItem>,
        parentId: String?,
        expandedGroupIds: Set<String>,
    ): String? {
        val candidates = flatItems.filter { item ->
            item.task.parentId == parentId &&
            !item.task.isCompleted &&
            !item.task.isInterrupt &&
            (!item.task.isGroup || item.task.id in expandedGroupIds)
        }
        if (candidates.size >= 2) return parentId
        if (candidates.size == 1 && candidates[0].task.isGroup) {
            return resolveEffectiveParentIdAt(flatItems, candidates[0].task.id, expandedGroupIds)
        }
        return parentId
    }

    /**
     * Returns the direct child of [targetParentId] that is an ancestor-or-equal
     * of [task].  Used by [rotateGlobal] to locate the representative slot the
     * current task occupies at the effective rotation depth.
     *
     * When [targetParentId] is null this is identical to the former
     * rootAncestorOf: it returns the ancestor whose own parentId is null.
     */
    private fun ancestorUnder(tasks: List<Task>, task: Task, targetParentId: String?): Task? {
        if (task.parentId == targetParentId) return task
        val parent = tasks.find { it.id == task.parentId } ?: return task
        return ancestorUnder(tasks, parent, targetParentId)
    }
}
