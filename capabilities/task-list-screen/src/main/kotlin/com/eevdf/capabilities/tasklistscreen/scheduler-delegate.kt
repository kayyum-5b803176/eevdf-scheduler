package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.viewModelScope
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem
import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import kotlinx.coroutines.launch

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
internal class SchedulerDelegate(private val vm: TaskViewModel) {

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
        if (vm.currentTask.value == null) {
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
        val list  = if (onQueueTab) vm.listBuilder.queueDisplayList.value
                    else            vm.listBuilder.scheduleDisplayList.value
        val firstItem = list
            ?.firstOrNull { !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt && !it.isFilterContextOnly }
            ?: run { vm._toastMessage.value = "No tasks available"; return }
        vm.pauseTimer()
        // Land on the EXACT placement that row is — not the bare real task,
        // which is what grabbing just `.task` here used to do.
        vm.currentTaskOwner.set(firstItem.task, TaskInstanceRef.of(firstItem))
        vm._timerSeconds.value = firstItem.task.remainingSeconds
        vm._toastMessage.value = "Jumped to \"${firstItem.task.name}\""
    }

    /**
     * "Next" button hold with no timer card open.
     * Saves the current timer state (same as a manual pause) then dismisses
     * the timer card so the user sees the plain task list.
     */
    fun pauseAndDismiss() {
        vm.pauseTimer()
        vm.currentTaskOwner.set(null)
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
            vm.currentTaskOwner.setAsync(next)
            vm._timerSeconds.postValue(next.remainingSeconds)
            vm._toastMessage.postValue("Now: \"${next.name}\" (Priority ${next.priority})")
        } else {
            vm.currentTaskOwner.setAsync(null)
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
        vm._stats.postValue(EEVDFScheduler.getStats(allTasks, groupsEnabled, runningId, nowMs = vm.clock.nowEpochMillis()))
    }

    /**
     * "Auto" tap — a one-shot manual jump, exactly like [nextSibling] but
     * with a different selection rule. Never triggered automatically; the
     * only caller is the Next/Auto button's click handler when it's
     * currently armed to "Auto" (see [com.eevdf.capabilities.tasklistscreen.ListTogglesDelegate.toggleNextButtonMode]).
     *
     * No card open → falls back to [jumpToFirst], same as [nextSibling] does.
     */
    fun triggerAutoJump(onQueueTab: Boolean = false) {
        vm.pauseTimer()
        val current    = vm.currentTask.value
        val currentRef = vm.currentInstanceRef.value
        if (current == null || currentRef == null) {
            jumpToFirst(onQueueTab)
            return
        }
        val allTasks = vm.activeTasks.value ?: emptyList()
        val (nextTask, nextRef) = selectAutoNextTask(currentRef, allTasks) ?: run {
            vm._toastMessage.value = "No tasks available"
            return
        }
        vm.currentTaskOwner.set(nextTask, nextRef)
        vm._timerSeconds.value = nextTask.remainingSeconds
        vm._toastMessage.value = "Auto → \"${nextTask.name}\""
    }

    /**
     * Selects the highest-priority leaf task within [currentRef]'s parent
     * group, escalating to successively higher ancestor groups if the
     * current one has no runnable leaf children. "Highest-priority" is read
     * directly from [ListBuilderDelegate.scheduleDisplayList] — the EXACT
     * list the Schedule tab is currently showing on screen (respecting
     * whatever class filter and collapse state are active right now) — not
     * re-derived from raw virtualDeadline here, and not a separate
     * recomputation that could silently drift from what the person is
     * actually looking at. This function's only job is to pick the first
     * entry from that on-screen list that belongs to the target group,
     * escalating outward until one exists.
     *
     * Placement-aware throughout (see [TaskInstanceRef]'s KDoc): the
     * starting group is [currentRef]'s EFFECTIVE parent (its host, if it's a
     * hardlink/symlink — not the underlying task's real parent), and every
     * candidate leaf is matched by ITS OWN effective parent too, since a
     * candidate can itself be a linked placement whose real `task.parentId`
     * doesn't match where it's actually displayed.
     *
     * Root-level tasks (no parent group) are treated as belonging to an
     * implicit top-level group: the search starts by looking for the
     * highest-priority root-level leaf (parentId == null) directly, the same
     * rule as every other level, not a special case.
     *
     * Returns null only when no leaf task exists anywhere in the ancestor
     * chain up to and including the root — the caller falls back to the
     * global [com.eevdf.capabilities.taskstorage.TaskRepository.selectNextTask] in that case.
     */
    fun selectAutoNextTask(currentRef: TaskInstanceRef, allTasks: List<Task>): Pair<Task, TaskInstanceRef>? {
        val orderedLeaves = vm.listBuilder.scheduleDisplayList.value
            ?.filter { !it.isFilterContextOnly }
            ?.filter { !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt }
            ?: return null

        val links       = vm.allTaskLinks.value ?: emptyList()
        val memberships = vm.allTaskMemberships.value ?: emptyList()
        val tasksById   = allTasks.associateBy { it.id }

        var groupId: String? = currentRef.effectiveParentId(links, memberships, tasksById)
        val visited = mutableSetOf<String>()  // guards against a corrupt/cyclic parentId chain
        while (true) {
            val candidate = orderedLeaves.firstOrNull {
                TaskInstanceRef.of(it).effectiveParentId(links, memberships, tasksById) == groupId
            }
            if (candidate != null) return candidate.task to TaskInstanceRef.of(candidate)
            if (groupId == null) return null
            if (!visited.add(groupId)) return null
            groupId = tasksById[groupId]?.parentId
        }
    }

    // ── Structural helpers (depth + list order only) ──────────────────────────
    //
    // Neither function below ever reads task.parentId, task type, or whether a
    // row is a real task / symlink / hardlink placement. A row's position in
    // the flat on-screen list — its own depth, and its order relative to
    // neighbouring rows — is the ONLY thing that decides who its children or
    // parent are. This is what makes a linked task/group exactly as
    // reachable as a plain one: the on-screen list already draws it at a
    // depth, in a position, same as anything else, so there's nothing link-
    // specific left for this logic to need to know about.

    /** Row indices of the direct children of [parentIdx] (-1 = top level). */
    private fun childrenOf(flatItems: List<TaskDisplayItem>, parentIdx: Int): List<Int> {
        val parentDepth = if (parentIdx < 0) -1 else flatItems[parentIdx].depth
        val childDepth  = parentDepth + 1
        val result = mutableListOf<Int>()
        var i = parentIdx + 1
        while (i < flatItems.size && flatItems[i].depth > parentDepth) {
            if (flatItems[i].depth == childDepth) result.add(i)
            i++
        }
        return result
    }

    /** Row index of [idx]'s structural parent, or -1 if it's at the top level. */
    private fun parentIndexOf(flatItems: List<TaskDisplayItem>, idx: Int): Int {
        val depth = flatItems[idx].depth
        if (depth <= 0) return -1
        var i = idx - 1
        while (i >= 0) {
            if (flatItems[i].depth < depth) return i
            i--
        }
        return -1
    }

    // ── Private rotation strategies ───────────────────────────────────────────

    /**
     * Cycles through siblings — "siblings" meaning whatever's drawn at the
     * same depth directly beneath the same structural parent in the current
     * on-screen list, found via [childrenOf]/[parentIndexOf] rather than
     * task.parentId. A linked task or group is exactly as eligible as a
     * plain one: this logic only ever asks "what's here, at this depth,
     * right now" — never what kind of row it is.
     *
     * Queue tab:    [ListBuilderDelegate.queueDisplayList] — static name order.
     * Schedule tab: [ListBuilderDelegate.scheduleDisplayList] — whatever
     *               class filter and collapse state are active, already
     *               DL → RT → EEVDF ordered. Read directly, never re-sorted
     *               here — re-deriving order was the original bug (a DL/RT
     *               sibling in position #1 got skipped because its own vdl
     *               happened to be larger than a plain EEVDF sibling's).
     *
     * NOTIFICATION parent: always jumps to the lowest-VDL sibling (no rotation).
     */
    private fun rotateSiblings(onQueueTab: Boolean) {
        val currentRef = vm.currentInstanceRef.value
        val flatItems  = if (onQueueTab) vm.listBuilder.queueDisplayList.value ?: return
                         else            vm.listBuilder.scheduleDisplayList.value ?: return

        // Match the EXACT placement, not just any row sharing the same task
        // id — when a real task and a hardlink/symlink of it are both on
        // screen, matching by bare id could anchor on the wrong one (see
        // TaskInstanceRef's KDoc).
        val currentIdx = flatItems.indexOfFirst { it.matchesInstance(currentRef) }
        if (currentIdx < 0) {
            vm._toastMessage.value = "No other siblings to rotate"
            return
        }
        val parentIdx  = parentIndexOf(flatItems, currentIdx)
        val parentType = if (parentIdx >= 0) flatItems[parentIdx].task.taskType else null

        val siblingItems = childrenOf(flatItems, parentIdx)
            .map { flatItems[it] }
            .filter { !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt }

        if (siblingItems.size <= 1) {
            vm._toastMessage.value = "No other siblings to rotate"
            return
        }

        val nextItem = if (parentType == "NOTIFICATION") {
            siblingItems.minBy { it.task.virtualDeadline }
        } else {
            val idx = siblingItems.indexOfFirst { it.matchesInstance(currentRef) }
            siblingItems[(idx + 1).mod(siblingItems.size)]
        }

        vm.currentTaskOwner.set(nextItem.task, TaskInstanceRef.of(nextItem))
        vm._timerSeconds.value = nextItem.task.remainingSeconds
        vm._toastMessage.value = "Next: \"${nextItem.task.name}\""
        vm.viewModelScope.launch { refreshSchedule() }
    }

    /**
     * One representative per top-level (or auto-descended) entry, cycling in
     * UI list order. For a leaf, the representative is itself; for a group,
     * it's the first real leaf reachable beneath it IN DISPLAY ORDER — read
     * straight off the screen, never re-sorted or re-derived, since the
     * screen's own order already reflects whatever tier/priority applies.
     *
     * Structural lookups ([childrenOf]/[parentIndexOf]) never touch
     * task.parentId or row type — a hardlink placement or a symlink sitting
     * at some depth on screen is exactly as reachable as a plain task, for
     * the same reason [rotateSiblings] treats them the same way.
     *
     * Auto-depth: descends through a single eligible candidate as long as
     * IT'S A GROUP AND its own children are actually present on screen —
     * [childrenOf] naturally returns nothing for a collapsed group or one
     * whose contents a class filter pruned away, so this can never dive into
     * content that isn't visible.
     *
     * Queue tab's group representative preserves the existing "continue
     * where you left off" behavior via [QueueLastRunDelegate.getLastRunLeaf]
     * — untouched, since that's a different, deliberate feature, not part of
     * this fix.
     */
    private fun rotateGlobal(onQueueTab: Boolean) {
        val currentRef = vm.currentInstanceRef.value
        val flatItems = if (onQueueTab) vm.listBuilder.queueDisplayList.value ?: return
                         else            vm.listBuilder.scheduleDisplayList.value ?: return
        val allTasks  = flatItems.map { it.task }

        // Groups that are ancestors of any interrupt task — excluded so the
        // interrupt group is never a rotation candidate and never descended into.
        val interruptAncestorIds = vm.collectInterruptAncestorIds()
        fun isEligible(idx: Int): Boolean {
            val t = flatItems[idx].task
            return !t.isCompleted && !t.isInterrupt && t.id !in interruptAncestorIds
        }

        // A row only counts toward "how many real candidates are at this
        // level" if it can actually produce something to land on. A leaf
        // always can. A group only can if it has at least one eligible,
        // reachable child right now — a collapsed or empty group cannot,
        // and must NOT block diving into whichever sibling actually has
        // content (that was the bug: closing an empty/collapsed group
        // still counted it as "2 candidates here," so the loop refused to
        // dive into the one group that actually had children).
        fun hasContent(idx: Int): Boolean {
            if (!flatItems[idx].task.isGroup) return true
            return childrenOf(flatItems, idx).any { isEligible(it) && hasContent(it) }
        }

        var parentIdx = -1
        while (true) {
            val level = childrenOf(flatItems, parentIdx).filter { isEligible(it) && hasContent(it) }
            if (level.size != 1) break
            val onlyIdx = level.single()
            if (!flatItems[onlyIdx].task.isGroup) break
            val childLevel = childrenOf(flatItems, onlyIdx).filter { isEligible(it) && hasContent(it) }
            if (childLevel.isEmpty()) break
            parentIdx = onlyIdx
        }

        fun firstLeafBelow(idx: Int): TaskDisplayItem? {
            if (!flatItems[idx].task.isGroup) return flatItems[idx]
            for (childIdx in childrenOf(flatItems, idx)) {
                if (!isEligible(childIdx)) continue
                firstLeafBelow(childIdx)?.let { return it }
            }
            return null
        }

        // Each representative carries its OWN instance ref, not just a bare
        // Task — a hardlink/symlink landing must be remembered as exactly
        // that placement, not silently normalized to the real one (see
        // TaskInstanceRef's KDoc — this was exactly the "jumps to the wrong
        // occurrence" bug).
        val representatives = childrenOf(flatItems, parentIdx)
            .filter { isEligible(it) }
            .mapNotNull { idx ->
                val item = flatItems[idx]
                val leafRef = when {
                    !item.task.isGroup -> TaskInstanceRef.of(item) to item.task
                    onQueueTab         -> vm.lastRun.getLastRunLeaf(item.task.id, allTasks)
                        ?.let { TaskInstanceRef.real(it) to it }
                    else               -> firstLeafBelow(idx)?.let { TaskInstanceRef.of(it) to it.task }
                }
                if (leafRef == null || leafRef.second.isInterrupt) null else idx to leafRef
            }

        if (representatives.size <= 1) {
            vm._toastMessage.value = "No other siblings to rotate"
            return
        }

        // Which representative slot "covers" the currently running task —
        // find its own row (matched by EXACT placement, not just task id —
        // when a real task and a link to it are both visible, bare-id
        // matching could anchor on the wrong occurrence), then walk up by
        // structural parent until reaching a row that's a direct child of
        // parentIdx.
        val currentRowIdx = flatItems.indexOfFirst { it.matchesInstance(currentRef) }.takeIf { it >= 0 }
        val currentAnchorIdx = currentRowIdx?.let { start ->
            var walk = start
            while (parentIndexOf(flatItems, walk) != parentIdx) {
                val p = parentIndexOf(flatItems, walk)
                if (p < 0) { walk = -1; break }
                walk = p
            }
            walk
        }

        val currentIdxInReps = representatives.indexOfFirst { it.first == currentAnchorIdx }
        val nextIdx  = (currentIdxInReps + 1).mod(representatives.size)
        val (nextRef, nextTask) = representatives[nextIdx].second

        vm.currentTaskOwner.set(nextTask, nextRef)
        vm._timerSeconds.value = nextTask.remainingSeconds
        vm._toastMessage.value = "Next: \"${nextTask.name}\""
        vm.viewModelScope.launch { refreshSchedule() }
    }
}
