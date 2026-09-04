package com.eevdf.feature.task.list

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.task.TaskLink
import com.eevdf.data.task.TaskMembership
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.MEMBERSHIP_SYNTHETIC_PREFIX
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

    /**
     * Defense-in-depth against a cycle in the parent graph. Hardlinks give a
     * task multiple real parents, so the tree-building recursion below is no
     * longer walking a provably-acyclic structure by construction — cycle
     * creation is prevented at the source (see LinksActivity.wouldCreateCycle),
     * but this cap means a cycle that somehow got into the data anyway (a
     * future bug, manual DB edit, restored backup, …) makes the list stop
     * growing instead of crashing the app with a StackOverflowError.
     */
    private val MAX_TREE_DEPTH = 64

    lateinit var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>>
    lateinit var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>>

    /**
     * What each tab's RecyclerView actually renders. [flatActiveTasks]/
     * [flatScheduleOrder] above remain the full, always-complete multi-depth
     * tree — every scheduling/rotation function in [SchedulerDelegate] depends
     * on that, and must keep working identically regardless of display style
     * (see [DrillState] doc comment). These two are a purely presentational
     * projection: FLAT_OUTLINE mode just passes the full list straight through;
     * DRILL_DOWN mode substitutes a single level's rows for whichever group the
     * tab is currently drilled into (see [buildQueueDrillLevel]/[buildScheduleDrillLevel]).
     */
    lateinit var queueDisplayList:    MediatorLiveData<List<TaskDisplayItem>>
    lateinit var scheduleDisplayList: MediatorLiveData<List<TaskDisplayItem>>

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
                val tasks       = vm.activeTasks.value        ?: emptyList()
                val enabled     = vm.settings.groupsEnabled.value ?: false
                val links       = vm.allTaskLinks.value        ?: emptyList()
                val memberships = vm.allTaskMemberships.value  ?: emptyList()
                value = buildQueueList(tasks, enabled, links, memberships)
            }
            addSource(vm.activeTasks)                        { rebuild() }
            addSource(vm.settings.groupsEnabled)             { rebuild() }
            addSource(vm.groupExpand.queueExpandTrigger)     { rebuild() }
            addSource(vm.allTaskLinks)                       { rebuild() }
            addSource(vm.allTaskMemberships)                 { rebuild() }
        }

        flatScheduleOrder = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks       = vm.activeTasks.value        ?: emptyList()
                val enabled     = vm.settings.groupsEnabled.value ?: false
                val links       = vm.allTaskLinks.value        ?: emptyList()
                val memberships = vm.allTaskMemberships.value  ?: emptyList()
                value = buildScheduleList(tasks, enabled, links, memberships)
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
            addSource(vm.allTaskLinks)                       { rebuild() }
            addSource(vm.allTaskMemberships)                 { rebuild() }
        }

        queueDisplayList = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                value = if (vm.settings.queueListStyle.value == TaskListStyle.DRILL_DOWN) {
                    val tasks       = vm.activeTasks.value       ?: emptyList()
                    val links       = vm.allTaskLinks.value       ?: emptyList()
                    val memberships = vm.allTaskMemberships.value ?: emptyList()
                    val drill       = vm.queueDrillState.value
                    buildQueueDrillLevel(drill?.currentFrameId, tasks, links, memberships, drill?.currentHighlightTaskId, drill?.currentDoorMembershipId)
                } else {
                    flatActiveTasks.value ?: emptyList()
                }
            }
            addSource(flatActiveTasks)          { rebuild() }
            addSource(vm.settings.queueListStyle) { rebuild() }
            addSource(vm.queueDrillState)         { rebuild() }
        }

        scheduleDisplayList = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                value = if (vm.settings.scheduleListStyle.value == TaskListStyle.DRILL_DOWN) {
                    val tasks       = vm.activeTasks.value       ?: emptyList()
                    val links       = vm.allTaskLinks.value       ?: emptyList()
                    val memberships = vm.allTaskMemberships.value ?: emptyList()
                    val drill       = vm.scheduleDrillState.value
                    buildScheduleDrillLevel(drill?.currentFrameId, tasks, links, memberships, drill?.currentHighlightTaskId, drill?.currentDoorMembershipId)
                } else {
                    flatScheduleOrder.value ?: emptyList()
                }
            }
            addSource(flatScheduleOrder)             { rebuild() }
            addSource(vm.settings.scheduleListStyle) { rebuild() }
            addSource(vm.scheduleDrillState)         { rebuild() }
        }
    }

    // ── Links feature helpers ─────────────────────────────────────────────────

    /**
     * Builds a symlink's display row. Always shows the TARGET's live data
     * (name, running state) — a symlink carries none of its own. Carries zero
     * weight (cpuShare = 0, never fed into EEVDFScheduler) and is marked via
     * [TaskDisplayItem.symlinkId] so the adapter renders it as a jump-to-real-
     * location pointer instead of a runnable row.
     *
     * [target] is null when the real task/group this symlink pointed at has
     * been deleted — see [TaskLink] doc comment: the pointer survives as a
     * broken link rather than being cascade-deleted. A synthetic placeholder
     * [Task] is built purely for display (never persisted, never has a real
     * id anything else looks up) and the row is marked [TaskDisplayItem.isBrokenLink].
     */
    private fun linkDisplayItem(link: TaskLink, target: Task?, depth: Int, number: String): TaskDisplayItem =
        TaskDisplayItem(
            task         = target ?: Task(
                id = "broken-link-placeholder:${link.id}", name = "Broken link",
                priority = 0, timeSliceSeconds = 0L, parentId = link.hostGroupId,
            ),
            depth        = depth,
            queueNumber  = number,
            symlinkId    = link.id,
            cpuShare     = 0.0,
            isBrokenLink = target == null,
        )

    /**
     * Builds a hardlink's display row. [task] must be pre-substituted with
     * THIS placement's own scheduling fields (see call sites below) — its
     * name/config stay genuinely shared, but totalRunTime/runCount/vruntime/
     * eligibleTime/virtualDeadline/lag must reflect [membership], not the
     * real task's primary fields, or the row silently shows the primary
     * location's numbers forever regardless of what's run from here.
     */
    private fun membershipDisplayItem(
        membership: TaskMembership, task: Task, depth: Int, number: String,
        cpuShare: Double, descGroups: Int, descTasks: Int,
    ): TaskDisplayItem =
        TaskDisplayItem(
            task               = task,
            depth              = depth,
            queueNumber        = number,
            membershipId       = membership.id,
            cpuShare           = cpuShare,
            childGroupCount    = descGroups,
            childTaskCount     = descTasks,
            effectiveQuotaExceeded = task.isQuotaExceeded,
            effectiveQuotaWarning  = task.isQuotaWarning,
        )

    /**
     * Computes what a MEMBERSHIP (hardlink) row's vrt/vdl should DISPLAY —
     * derived from [membership]'s own vruntime plus the (shared, correct
     * either way) timeSliceSeconds/weight — WITHOUT touching [realTask]
     * itself. Returned as a plain pair, never a modified `Task` copy: see
     * [TaskDisplayItem.displayVruntime]'s doc comment for exactly why a
     * mutated `Task` object must never be built for this purpose again.
     */
    private fun membershipDisplayVrtVdl(realTask: Task, membership: TaskMembership): Pair<Double, Double> {
        val eligibleTime    = membership.vruntime
        val virtualDeadline = eligibleTime + realTask.timeSliceSeconds.toDouble() / realTask.weight
        return eligibleTime to virtualDeadline
    }

    // ── List builders ─────────────────────────────────────────────────────────

    /**
     * Queue tab: tasks sorted by the first number in their name (static order).
     * Groups are shown when [groupsEnabled] is true; only leaf tasks otherwise.
     */
    private fun buildQueueList(
        tasks: List<Task>, groupsEnabled: Boolean,
        links: List<TaskLink> = emptyList(), memberships: List<TaskMembership> = emptyList(),
    ): List<TaskDisplayItem> {
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
        val effectiveTasks = EEVDFScheduler.withMemberships(tasks, memberships)
        val effectiveShares = EEVDFScheduler.computeShares(effectiveTasks, groupsEnabled)
        val membershipsById = memberships.associateBy { it.id }
        val tasksById = tasks.associateBy { it.id }

        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int, parentNumber: String,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean,
                     inheritedDoor: String?) {
            if (depth > MAX_TREE_DEPTH) return
            val children = effectiveTasks
                .filter { it.parentId == parentId }
                .sortedWith(SortHelper.taskNameComparator)
            val counter = IntArray(1)
            children.forEach { entry ->
                val isMembership = entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX)
                val membership   = if (isMembership) membershipsById[entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX)] else null
                // For a membership row, recurse/rollup using the REAL task's real
                // id — its actual children live under that id, not the synthetic one.
                val realTask = if (membership != null) tasksById[membership.taskId] ?: entry else entry
                val dc             = tasks.filter { it.parentId == realTask.id }
                val quotaExceeded  = parentQuotaExceeded || realTask.isQuotaExceeded
                val quotaWarning   = !quotaExceeded && (parentQuotaWarning || realTask.isQuotaWarning)
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                val (descGroups, descTasks) = countDescendants(realTask.id, tasks)
                // A membership row is itself a fresh door for everything real
                // rendered beneath it; a plain row just passes its own
                // inherited door straight through to its children unchanged.
                val childDoor = membership?.id ?: inheritedDoor

                if (membership != null) {
                    val (vrt, vdl) = membershipDisplayVrtVdl(realTask, membership)
                    result.add(membershipDisplayItem(
                        membership, realTask, depth, number,
                        cpuShare = effectiveShares[entry.id] ?: 0.0,
                        descGroups = descGroups, descTasks = descTasks,
                    ).copy(
                        childTotalRuntime      = dc.sumOf { it.totalRunTime } + realTask.totalRunTime,
                        effectiveQuotaExceeded = quotaExceeded,
                        effectiveQuotaWarning  = quotaWarning,
                        isExpanded             = if (realTask.isGroup) (vm.groupExpand.queueExpandState[realTask.id] ?: true) else true,
                        displayVruntime        = vrt,
                        displayVirtualDeadline = vdl,
                    ))
                } else {
                    result.add(TaskDisplayItem(realTask, depth,
                        childGroupCount        = descGroups,
                        childTaskCount         = descTasks,
                        childTotalRuntime      = dc.sumOf { it.totalRunTime },
                        cpuShare               = effectiveShares[realTask.id] ?: 0.0,
                        effectiveQuotaExceeded = quotaExceeded,
                        effectiveQuotaWarning  = quotaWarning,
                        queueNumber            = number,
                        entryMembershipId      = inheritedDoor,
                        isExpanded             = if (realTask.isGroup) (vm.groupExpand.queueExpandState[realTask.id] ?: true) else true))
                }
                if (realTask.isGroup && (vm.groupExpand.queueExpandState[realTask.id] ?: true))
                    addLevel(realTask.id, depth + 1, number, quotaExceeded, quotaWarning, childDoor)
            }

            // Symlinks hosted at this level: display-only, zero weight, appended
            // after the real/hardlinked children so they never disturb numbering
            // parity with how many "real" siblings exist.
            links.filter { it.hostGroupId == parentId }.forEach { link ->
                val target = tasksById[link.targetTaskId]  // null = broken link, rendered not skipped
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                result.add(linkDisplayItem(link, target, depth, number).copy(
                    childTotalRuntime = link.totalRunTime,
                ))
            }
        }
        addLevel(null, 0, "", false, false, null)
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
    private fun buildScheduleList(
        tasks: List<Task>, groupsEnabled: Boolean,
        links: List<TaskLink> = emptyList(), memberships: List<TaskMembership> = emptyList(),
    ): List<TaskDisplayItem> {
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

        // Links feature: hardlinks compete as real bucket participants (see
        // EEVDFScheduler.withMemberships); symlinks never do and are appended
        // separately per level below. Scope note: DL/RT descendant-hoisting
        // (dlUrgency, hasActiveDlDescendant, etc. below) still only walks real
        // `tasks` — a hardlinked/symlinked DL descendant does not yet promote
        // an ancestor group through those helpers. Acceptable v1 limitation.
        val effectiveTasks   = EEVDFScheduler.withMemberships(tasks, memberships)
        val effectiveShares  = EEVDFScheduler.computeShares(effectiveTasks, groupsEnabled)
        val membershipsById  = memberships.associateBy { it.id }
        val tasksById        = tasks.associateBy { it.id }

        // DL urgency for sorting within the DL bucket: for a promoted group,
        // urgency is the minimum remaining budget across all DL descendants.
        fun dlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else if (task.isDlBudgetActive) task.dlPeriodRemainingSeconds
            else tasks.filter { it.parentId == task.id && !it.isCompleted }
                      .minOfOrNull { dlUrgency(it) } ?: Long.MAX_VALUE

        // Shared with buildScheduleDrillLevel below — one level's worth of
        // children, DL → RT → EEVDF tier-sorted. Factored out so drill-down
        // mode shows exactly the same per-level ordering the flat outline does,
        // rather than a second, drift-prone copy of this partitioning.
        fun orderChildren(children: List<Task>): List<Task> {
            val dlActive = children.filter { child ->
                if (child.isGroup)
                    child.isDlBudgetActive || EEVDFScheduler.hasActiveDlDescendant(child, tasks)
                else
                    child.isDlBudgetActive
            }.sortedBy { dlUrgency(it) }
            val dlIds = dlActive.mapTo(HashSet()) { it.id }

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

            val fairActive = children.filter { child ->
                child.id !in dlIds && child.id !in rtIds &&
                    (child.isGroup || child.schedulerClass == "fair_sched_class")
            }.sortedBy { it.virtualDeadline }

            return dlActive + rtActive + fairActive
        }

        fun addLevel(
            parentId: String?,
            depth: Int,
            parentNumber: String,
            parentQuotaExceeded: Boolean,
            parentQuotaWarning: Boolean,
            counter: IntArray,
            inheritedDoor: String?,
        ) {
            if (depth > MAX_TREE_DEPTH) return
            val children = effectiveTasks.filter { it.parentId == parentId }
            val ordered = orderChildren(children)

            ordered.forEach { entry ->
                val isMembership = entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX)
                val membership   = if (isMembership) membershipsById[entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX)] else null
                // Membership rows recurse/rollup via the REAL task id — its
                // actual children live there, not under the synthetic id.
                val task            = if (membership != null) tasksById[membership.taskId] ?: entry else entry
                val dc              = tasks.filter { it.parentId == task.id }
                val quotaExceeded   = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning    = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                val isTaskDlActive  = task.isDlBudgetActive
                val isTaskRtActive  = RtScheduler.isRtWindowActive(task, nowMs)
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                val (descGroups, descTasks) = countDescendants(task.id, tasks)
                // A membership row is itself a fresh door for everything real
                // rendered beneath it; a plain row passes its inherited door
                // straight through to its children unchanged.
                val childDoor = membership?.id ?: inheritedDoor
                val baseItem = if (membership != null) {
                    val (vrt, vdl) = membershipDisplayVrtVdl(task, membership)
                    membershipDisplayItem(
                        membership, task, depth, number,
                        cpuShare = effectiveShares[entry.id] ?: 0.0,
                        descGroups = descGroups, descTasks = descTasks,
                    ).copy(
                        childTotalRuntime = dc.sumOf { it.totalRunTime } + task.totalRunTime,
                        displayVruntime = vrt,
                        displayVirtualDeadline = vdl,
                    )
                } else {
                    TaskDisplayItem(task, depth,
                        childGroupCount   = descGroups,
                        childTaskCount    = descTasks,
                        childTotalRuntime = dc.sumOf { it.totalRunTime },
                        cpuShare          = effectiveShares[task.id] ?: 0.0,
                        entryMembershipId = inheritedDoor)
                }
                result.add(baseItem.copy(
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
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning, IntArray(1), childDoor)
            }

            // Symlinks hosted at this level: display-only, zero weight, never
            // part of the DL/RT/fair bucket ordering above.
            links.filter { it.hostGroupId == parentId }.forEach { link ->
                val target = tasksById[link.targetTaskId]  // null = broken link, rendered not skipped
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                result.add(linkDisplayItem(link, target, depth, number).copy(
                    childTotalRuntime = link.totalRunTime,
                ))
            }
        }
        addLevel(null, 0, "", false, false, IntArray(1), null)
        return result
    }

    // ── Drill-down: single-level builders ─────────────────────────────────────
    //
    // Both produce exactly one screen's worth of rows (depth 0, no recursion,
    // no indentation) for whichever group [frameId] names — null meaning Home.
    // They deliberately mirror buildQueueList/buildScheduleList's per-level
    // logic (same row construction, same sort/bucket rules) but never recurse,
    // since drill-down shows one level at a time by design. They read directly
    // from vm.activeTasks — NOT from flatActiveTasks/flatScheduleOrder's own
    // value — so a drill rebuild is always computed fresh rather than trying to
    // slice a level back out of an already-flattened multi-depth list.

    private fun buildQueueDrillLevel(
        frameId: String?, tasks: List<Task>, links: List<TaskLink>, memberships: List<TaskMembership>,
        highlightTaskId: String? = null, inheritedDoor: String? = null,
    ): List<TaskDisplayItem> {
        val effectiveTasks  = EEVDFScheduler.withMemberships(tasks, memberships)
        val effectiveShares = EEVDFScheduler.computeShares(effectiveTasks, groupsEnabled = true)
        val membershipsById = memberships.associateBy { it.id }
        val tasksById       = tasks.associateBy { it.id }
        val result = mutableListOf<TaskDisplayItem>()
        var counter = 0

        effectiveTasks.filter { it.parentId == frameId }
            .sortedWith(SortHelper.taskNameComparator)
            .forEach { entry ->
                val isMembership = entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX)
                val membership   = if (isMembership) membershipsById[entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX)] else null
                val task         = if (membership != null) tasksById[membership.taskId] ?: entry else entry
                val dc           = tasks.filter { it.parentId == task.id }
                val (descGroups, descTasks) = countDescendants(task.id, tasks)
                counter++
                val number = "$counter"
                result.add(
                    if (membership != null) {
                        val (vrt, vdl) = membershipDisplayVrtVdl(task, membership)
                        membershipDisplayItem(membership, task, 0, number,
                            cpuShare = effectiveShares[entry.id] ?: 0.0,
                            descGroups = descGroups, descTasks = descTasks
                        ).copy(
                            childTotalRuntime = dc.sumOf { it.totalRunTime } + task.totalRunTime,
                            isJumpHighlighted = task.id == highlightTaskId,
                            displayVruntime = vrt,
                            displayVirtualDeadline = vdl,
                        )
                    } else {
                        TaskDisplayItem(task, 0,
                            childGroupCount   = descGroups,
                            childTaskCount    = descTasks,
                            childTotalRuntime = dc.sumOf { it.totalRunTime },
                            cpuShare          = effectiveShares[task.id] ?: 0.0,
                            queueNumber       = number,
                            entryMembershipId = inheritedDoor,
                            isJumpHighlighted = task.id == highlightTaskId)
                    }
                )
            }

        links.filter { it.hostGroupId == frameId }.forEach { link ->
            val target = tasksById[link.targetTaskId]  // null = broken link, rendered not skipped
            counter++
            result.add(linkDisplayItem(link, target, 0, "$counter")
                .copy(childTotalRuntime = link.totalRunTime))
        }
        return result
    }

    private fun buildScheduleDrillLevel(
        frameId: String?, tasks: List<Task>, links: List<TaskLink>, memberships: List<TaskMembership>,
        highlightTaskId: String? = null, inheritedDoor: String? = null,
    ): List<TaskDisplayItem> {
        val effectiveTasks  = EEVDFScheduler.withMemberships(tasks, memberships)
        val effectiveShares = EEVDFScheduler.computeShares(effectiveTasks, groupsEnabled = true)
        val membershipsById = memberships.associateBy { it.id }
        val tasksById       = tasks.associateBy { it.id }
        val nowMs           = System.currentTimeMillis()
        val result = mutableListOf<TaskDisplayItem>()
        var counter = 0

        fun dlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else if (task.isDlBudgetActive) task.dlPeriodRemainingSeconds
            else tasks.filter { it.parentId == task.id && !it.isCompleted }
                      .minOfOrNull { dlUrgency(it) } ?: Long.MAX_VALUE

        val children = effectiveTasks.filter { it.parentId == frameId }
        val dlActive = children.filter { child ->
            if (child.isGroup) child.isDlBudgetActive || EEVDFScheduler.hasActiveDlDescendant(child, tasks)
            else child.isDlBudgetActive
        }.sortedBy { dlUrgency(it) }
        val dlIds = dlActive.mapTo(HashSet()) { it.id }
        val rtActive = children.filter { child ->
            child.id !in dlIds && (
                if (child.isGroup) RtScheduler.isRtWindowActive(child, nowMs) ||
                    RtScheduler.hasActiveRtDescendant(child, tasks, nowMs)
                else RtScheduler.isRtWindowActive(child, nowMs)
            )
        }.sortedByDescending { it.rtPriority }
        val rtIds = rtActive.mapTo(HashSet()) { it.id }
        val fairActive = children.filter { child ->
            child.id !in dlIds && child.id !in rtIds &&
                (child.isGroup || child.schedulerClass == "fair_sched_class")
        }.sortedBy { it.virtualDeadline }

        (dlActive + rtActive + fairActive).forEach { entry ->
            val isMembership = entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX)
            val membership   = if (isMembership) membershipsById[entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX)] else null
            val task         = if (membership != null) tasksById[membership.taskId] ?: entry else entry
            val dc           = tasks.filter { it.parentId == task.id }
            val (descGroups, descTasks) = countDescendants(task.id, tasks)
            counter++
            val number = "$counter"
            val baseItem = if (membership != null) {
                val (vrt, vdl) = membershipDisplayVrtVdl(task, membership)
                membershipDisplayItem(membership, task, 0, number,
                    cpuShare = effectiveShares[entry.id] ?: 0.0,
                    descGroups = descGroups, descTasks = descTasks
                ).copy(
                    childTotalRuntime = dc.sumOf { it.totalRunTime } + task.totalRunTime,
                    displayVruntime = vrt,
                    displayVirtualDeadline = vdl,
                )
            } else {
                TaskDisplayItem(task, 0,
                    childGroupCount   = descGroups,
                    childTaskCount    = descTasks,
                    childTotalRuntime = dc.sumOf { it.totalRunTime },
                    cpuShare          = effectiveShares[task.id] ?: 0.0,
                    entryMembershipId = inheritedDoor)
            }
            result.add(baseItem.copy(
                queueNumber      = number,
                isDlActive       = task.isDlBudgetActive,
                isDlGroupHoisted = task.isGroup && (task.isDlBudgetActive || EEVDFScheduler.hasActiveDlDescendant(task, tasks)),
                isRtActive       = RtScheduler.isRtWindowActive(task, nowMs),
                isRtGroupHoisted = task.isGroup && (RtScheduler.isRtWindowActive(task, nowMs) || RtScheduler.hasActiveRtDescendant(task, tasks, nowMs)),
                isJumpHighlighted = task.id == highlightTaskId))
        }

        links.filter { it.hostGroupId == frameId }.forEach { link ->
            val target = tasksById[link.targetTaskId]  // null = broken link, rendered not skipped
            counter++
            result.add(linkDisplayItem(link, target, 0, "$counter")
                .copy(childTotalRuntime = link.totalRunTime))
        }
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
