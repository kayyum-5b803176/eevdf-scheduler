package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.logic.SortHelper
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership
import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import com.eevdf.capabilities.taskstorage.scheduling.MEMBERSHIP_SYNTHETIC_PREFIX
import com.eevdf.capabilities.taskstorage.scheduling.RtScheduler
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.launch

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
    /** Per-class counts for the Schedule tab's popup-menu badge — see [classCounts]. */
    lateinit var scheduleClassCounts: MediatorLiveData<Map<ScheduleClassFilter, Int>>

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
        val nowMs = vm.clock.nowEpochMillis()
        val soonestMs = tasks
            .filter { it.isDlConfigured && !it.isCompleted }
            .mapNotNull { task ->
                val remaining = task.dlPeriodRemainingSeconds(nowMs)
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

    /**
     * Ids of RT-configured tasks that were window-active as of the most
     * recent [rescheduleRtResort] call — captured so the callback below can
     * tell WHICH of them just deactivated, not just that "something" did.
     */
    private var rtActiveTaskIdsAtSchedule: List<String> = emptyList()

    private val rtResortHandler  = Handler(Looper.getMainLooper())
    private val rtResortRunnable = Runnable {
        // Topics.REALTIME_WINDOW_EXPIRED (rule 3): this callback fires at the
        // exact millisecond RtScheduler computed as the next activation OR
        // deactivation boundary among the tasks captured at schedule time —
        // the one well-defined, non-polled instant a window transition
        // actually happens, rather than something inferred by comparing
        // snapshots across unrelated recompute passes. Only tasks that were
        // active then and are NOT active now count as "just expired"; a
        // fire caused by a different task's ACTIVATION is correctly not
        // reported here.
        val justExpired = rtActiveTaskIdsAtSchedule.filter { taskId ->
            val task = vm.activeTasks.value?.find { it.id == taskId } ?: return@filter false
            !RtScheduler.isRtWindowActive(task, vm.clock.nowEpochMillis())
        }
        if (justExpired.isNotEmpty()) {
            vm.viewModelScope.launch {
                justExpired.forEach { taskId -> vm.bus.publish(Topics.REALTIME_WINDOW_EXPIRED, taskId, "task-list-screen") }
            }
        }
        _rtResortTick.value = Unit
    }

    private fun rescheduleRtResort(tasks: List<Task>) {
        rtResortHandler.removeCallbacks(rtResortRunnable)
        val nowMs = vm.clock.nowEpochMillis()
        rtActiveTaskIdsAtSchedule = tasks
            .filter { it.isRtConfigured && !it.isCompleted && RtScheduler.isRtWindowActive(it, nowMs) }
            .map { it.id }
        val nextMs = RtScheduler.nextResortMs(tasks, nowMs)
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
                val filter = vm.scheduleClassFilter.value ?: ScheduleClassFilter.SCHEDULE
                value = when {
                    vm.settings.scheduleListStyle.value == TaskListStyle.DRILL_DOWN -> {
                        val tasks       = vm.activeTasks.value       ?: emptyList()
                        val links       = vm.allTaskLinks.value       ?: emptyList()
                        val memberships = vm.allTaskMemberships.value ?: emptyList()
                        val drill       = vm.scheduleDrillState.value
                        buildScheduleDrillLevel(drill?.currentFrameId, tasks, links, memberships, drill?.currentHighlightTaskId, drill?.currentDoorMembershipId)
                    }
                    filter != ScheduleClassFilter.SCHEDULE ->
                        buildFilteredScheduleList(vm.activeTasks.value ?: emptyList(), filter, vm.allTaskLinks.value ?: emptyList())
                    else -> flatScheduleOrder.value ?: emptyList()
                }
            }
            addSource(flatScheduleOrder)             { rebuild() }
            addSource(vm.settings.scheduleListStyle) { rebuild() }
            addSource(vm.scheduleDrillState)         { rebuild() }
            addSource(vm.scheduleClassFilter)        { rebuild() }
            // Explicit — the class-filtered branch reads vm.allTaskLinks.value
            // directly rather than through flatScheduleOrder's value, so a
            // link added/removed/retargeted needs its own direct trigger here
            // too, same reasoning as the expand-state trigger below.
            addSource(vm.allTaskLinks)               { rebuild() }
            // Explicit, not just relying on flatScheduleOrder's own rebuild to
            // "poke" this — the class-filtered branch reads scheduleExpandState
            // directly rather than through flatScheduleOrder's value, so it
            // needs its own direct trigger to stay correct if that indirect
            // coupling ever changes (fixes issue 2: collapse not working).
            addSource(vm.groupExpand.scheduleExpandTrigger) { rebuild() }
        }

        scheduleClassCounts = MediatorLiveData<Map<ScheduleClassFilter, Int>>().apply {
            fun rebuild() { value = classCounts(vm.activeTasks.value ?: emptyList(), vm.clock.nowEpochMillis()) }
            addSource(vm.activeTasks) { rebuild() }
            // flatScheduleOrder already re-fires at the exact wall-clock
            // moment a DL budget replenishes or an RT window opens/closes
            // (_dlResortTick / _rtResortTick above) — reusing that as this
            // badge's own "tick" instead of polling on a separate timer, so
            // the count updates live as tasks enter/leave their active window.
            addSource(flatScheduleOrder) { rebuild() }
        }
    }

    // ── Schedule-class filter (Queue-tab style tabs, keyed by scheduler class) ──
    //
    // A task's own schedulerClass never changes based on nesting (fundamental
    // #2 — see current-task-owner.kt / TaskRepository.selectNextCgroup for the
    // real scheduling decision, which this filter has zero effect on). This is
    // a pure VIEW narrowing: which pre-existing tree do we show.
    //
    // Ownership rule:
    //   - A GROUP with its own non-FAIR class (DEADLINE/RT) owns its ENTIRE
    //     subtree for tab purposes — every descendant at any depth, regardless
    //     of its own individual class, appears only in that one tab.
    //   - A plain FAIR-owned group never claims descendants this way. Each
    //     matching descendant (found at any depth) is shown under only its
    //     own IMMEDIATE parent as one level of context — intermediate FAIR
    //     ancestors above that are not reproduced, keeping the filtered view
    //     flat rather than a full breadcrumb.

    /** Per-class counts of tasks currently ACTIVE right now — not just
     * existing. A Deadline task whose budget is already exhausted this
     * period, or an RT task outside its window, doesn't count until it's
     * actually live again. Fair has no such "window" concept, so every
     * incomplete Fair-class task counts.
     */
    internal fun classCounts(tasks: List<Task>, nowMs: Long): Map<ScheduleClassFilter, Int> {
        val active = tasks.filter { !it.isCompleted }
        val counts = ScheduleClassFilter.values().associateWith { 0 }.toMutableMap()
        active.forEach { t ->
            val cls = t.ownScheduleClass()
            val isActiveNow = when (cls) {
                ScheduleClassFilter.DEADLINE -> t.isDlBudgetActive(nowMs)
                ScheduleClassFilter.REALTIME -> RtScheduler.isRtWindowActive(t, nowMs)
                else                         -> true
            }
            if (isActiveNow) counts[cls] = (counts[cls] ?: 0) + 1
        }
        return counts
    }

    /**
     * Builds the class-filtered Schedule tab as a REAL sub-tree of the actual
     * hierarchy — not a flattened "one level of context" view. A branch is
     * kept whenever it leads to something relevant; every branch that leads
     * nowhere is pruned entirely. See the design discussion: this matches how
     * `htop`'s tree-filter or an IDE's "find in files" folder view works —
     * full real ancestor chain to root, not truncated.
     *
     * Symlinks ([TaskLink]) participate too, using the same "upward
     * propagation" the default Schedule tab already relies on: a symlink row
     * carries a live snapshot of its REAL target's fields, so wherever a
     * task is symlinked, that location shows the task's current class/state
     * — not a stale copy. A symlink's tab membership is decided by its
     * target's EFFECTIVE class (own class, or an owning group's class if the
     * target itself lives inside a DL/RT-owned subtree) — exactly the same
     * rule a real task gets. Broken links (target deleted) have no
     * resolvable class and are excluded from every filtered tab (they still
     * show on the default "Schedule" tab, which is unaffected by this).
     */
    internal fun buildFilteredScheduleList(
        tasks: List<Task>, filter: ScheduleClassFilter, links: List<TaskLink> = emptyList(),
    ): List<TaskDisplayItem> {
        val active      = tasks.filter { !it.isCompleted }
        val byId        = active.associateBy { it.id }
        val byParent    = active.groupBy { it.parentId }
        val byHostGroup = links.groupBy { it.hostGroupId }

        // Pass 1 — resolve every node's EFFECTIVE class for TAB MEMBERSHIP
        // only (never touches Task.schedulerClass itself — pure view
        // concept, fundamental #2 still holds). A node's effective class is
        // its own class UNLESS it sits inside an already class-owned group's
        // subtree, in which case the whole subtree inherits that owner's
        // class regardless of each descendant's individual class — the
        // established "owned subtree belongs wholly to one tab" rule.
        // Computed over the REAL tree, ignoring collapse state entirely —
        // relevance must not depend on what's currently expanded (fixes the
        // "collapse removes the whole branch" bug).
        val effectiveClass = mutableMapOf<String, ScheduleClassFilter>()
        fun resolveEffective(node: Task, inheritedOwner: ScheduleClassFilter?) {
            val resolved = inheritedOwner ?: node.ownScheduleClass()
            effectiveClass[node.id] = resolved
            val childOwner = when {
                inheritedOwner != null                                       -> inheritedOwner
                node.isGroup && resolved != ScheduleClassFilter.FAIR         -> resolved
                else                                                         -> null
            }
            byParent[node.id].orEmpty().forEach { resolveEffective(it, childOwner) }
        }
        byParent[null].orEmpty().forEach { resolveEffective(it, null) }

        val directlyRelevant = effectiveClass.filterValues { it == filter }.keys

        // Symlinks whose TARGET's effective class matches this tab — same
        // membership rule as a real task, just resolved through the target.
        val relevantLinkIds = links.filter { effectiveClass[it.targetTaskId] == filter }
            .mapTo(mutableSetOf()) { it.id }

        // Pass 2 — every REAL ancestor (full chain, to root) of a directly
        // relevant node is also kept, so the branch stays intact. Nothing
        // else survives — that's the pruning. A matching symlink keeps its
        // HOST group's chain alive too, even if nothing else in that branch
        // matches — the symlink itself is the reason it needs to stay.
        val relevant = mutableSetOf<String>()
        fun markAncestors(id: String?) {
            var cur: Task? = id?.let { byId[it] }
            while (cur != null) {
                if (!relevant.add(cur.id)) break   // already walked this chain
                cur = cur.parentId?.let { byId[it] }
            }
        }
        directlyRelevant.forEach { markAncestors(it) }
        links.filter { it.id in relevantLinkIds }.forEach { markAncestors(it.hostGroupId) }

        fun itemFor(t: Task, depth: Int) = TaskDisplayItem(
            task = t, depth = depth,
            isDlActive = t.ownScheduleClass() == ScheduleClassFilter.DEADLINE,
            isRtActive = t.ownScheduleClass() == ScheduleClassFilter.REALTIME,
            isExpanded = if (t.isGroup) (vm.groupExpand.scheduleExpandState[t.id] ?: true) else true,
            // Context-only = kept purely as ancestor path, not itself a match
            // — used by SchedulerDelegate's "Next" to skip it as a target.
            isFilterContextOnly = t.id !in directlyRelevant,
        )

        // Pass 3 — render the pruned real tree at REAL depth (no artificial
        // flattening). Collapse controls ONLY whether a group's children (and
        // any symlinks hosted in it) are drawn beneath it — the row itself
        // always stays if it's on a relevant path, regardless of collapse
        // state (fixes: collapsing used to remove the whole branch with no
        // way back in).
        val result = mutableListOf<TaskDisplayItem>()
        val tasksById = active.associateBy { it.id }
        fun renderLinksAt(hostGroupId: String?, depth: Int) {
            byHostGroup[hostGroupId].orEmpty()
                .filter { it.id in relevantLinkIds }
                .forEach { link ->
                    val target = tasksById[link.targetTaskId]
                    result.add(linkDisplayItem(link, target, depth, "").copy(
                        childTotalRuntime = link.totalRunTime,
                    ))
                }
        }
        fun render(node: Task, depth: Int) {
            if (node.id !in relevant) return
            result.add(itemFor(node, depth))
            if (!node.isGroup) return
            if (!(vm.groupExpand.scheduleExpandState[node.id] ?: true)) return
            byParent[node.id].orEmpty().forEach { render(it, depth + 1) }
            renderLinksAt(node.id, depth + 1)
        }
        byParent[null].orEmpty().forEach { render(it, 0) }
        renderLinksAt(null, 0)
        return result
    }



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
        cpuShare: Double, descGroups: Int, descTasks: Int, nowMs: Long,
    ): TaskDisplayItem =
        TaskDisplayItem(
            task               = task,
            depth              = depth,
            queueNumber        = number,
            membershipId       = membership.id,
            cpuShare           = cpuShare,
            childGroupCount    = descGroups,
            childTaskCount     = descTasks,
            effectiveQuotaExceeded = task.isQuotaExceeded(nowMs),
            effectiveQuotaWarning  = task.isQuotaWarning(nowMs),
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

    /**
     * True when [taskId] is targeted by at least one symlink, or is the real
     * task of at least one hardlink placement, anywhere in the app. Drives
     * the "R" square badge on an ordinary (non-link) row — see
     * [TaskDisplayItem.isLinkedElsewhere].
     */
    private fun isLinkedElsewhere(taskId: String, links: List<TaskLink>, memberships: List<TaskMembership>): Boolean =
        links.any { it.targetTaskId == taskId } || memberships.any { it.taskId == taskId }

    // ── List builders ─────────────────────────────────────────────────────────

    /**
     * Queue tab: tasks sorted by the first number in their name (static order).
     * Groups are shown when [groupsEnabled] is true; only leaf tasks otherwise.
     */
    private fun buildQueueList(
        tasks: List<Task>, groupsEnabled: Boolean,
        links: List<TaskLink> = emptyList(), memberships: List<TaskMembership> = emptyList(),
    ): List<TaskDisplayItem> {
        // Sampled ONCE for this list rebuild — every row's quota pill in this
        // pass reads the same instant (kernel rule 1).
        val nowMs = vm.clock.nowEpochMillis()
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
                        effectiveQuotaExceeded = it.isQuotaExceeded(nowMs),
                        effectiveQuotaWarning  = it.isQuotaWarning(nowMs),
                        queueNumber            = "${index + 1}",
                        isLinkedElsewhere      = isLinkedElsewhere(it.id, links, memberships))
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
                val quotaExceeded  = parentQuotaExceeded || realTask.isQuotaExceeded(nowMs)
                val quotaWarning   = !quotaExceeded && (parentQuotaWarning || realTask.isQuotaWarning(nowMs))
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
                        descGroups = descGroups, descTasks = descTasks, nowMs = nowMs,
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
                        isLinkedElsewhere      = isLinkedElsewhere(realTask.id, links, memberships),
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
     * A group's bucket is decided ENTIRELY by its own class — never by what's
     * nested inside it (fundamental #2: a fair parent never needs to know
     * whether its child is DL/RT/fair to rank itself among its own siblings):
     *
     *   group-b (DL)                ← promoted at root by its OWN class only
     *     1.1 b1 (CFS)              ← children sort by EEVDF among themselves
     *     1.2 b2 (CFS)
     *
     *   group-c (CFS, has a DL child)   ← NOT promoted — c's own class is fair,
     *     1.1 c-dl-task (DL)             so it competes with its real siblings
     *     1.2 c1 (CFS)                   purely as a fair-class entity; the DL
     *                                     child inside only affects ITS OWN
     *                                     level's ordering, never leaks upward.
     *
     * Ordering rules at every level (root, group, nested group):
     *   1. DL-bucket: entity's OWN class is DL and its OWN budget is currently active.
     *      Sorted by EDF urgency — most urgent first.
     *   2. RT-bucket: entity's OWN class is RT and its OWN window is currently open.
     *      Sorted by descending RT priority.
     *   3. Fair-bucket: everything else not in buckets 1 or 2 (fair-class leaves,
     *      groups, and any DL/RT entity whose budget/window isn't active right now).
     *      Sorted by EEVDF virtual deadline.
     *
     * A group's class does not cascade into its children, and a child's class
     * never cascades up into its parent either. Children inside a DL group
     * still sort among themselves by their own classes via recursion. No
     * entity ever leaves its group for display.
     */
    private fun buildScheduleList(
        tasks: List<Task>, groupsEnabled: Boolean,
        links: List<TaskLink> = emptyList(), memberships: List<TaskMembership> = emptyList(),
    ): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        // Captured once so all partitions and sorts use the same instant.
        val nowMs = vm.clock.nowEpochMillis()

        if (!groupsEnabled) {
            val leaves   = tasks.filter { !it.isGroup }
            val dlActive = leaves.filter { it.isDlBudgetActive(nowMs) }
                .sortedBy { it.dlPeriodRemainingSeconds(nowMs) }
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
                    effectiveQuotaExceeded = it.isQuotaExceeded(nowMs),
                    effectiveQuotaWarning  = it.isQuotaWarning(nowMs),
                    queueNumber            = "${index + 1}",
                    isDlActive             = it.isDlBudgetActive(nowMs),
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

        // DL urgency for sorting within the DL bucket. Only ever called on a
        // node that's already in the DL bucket (own budget currently active),
        // so this is always the node's own remaining budget — never a
        // descendant's, since a group's own class decides its own bucket,
        // full stop (fundamental #2: a fair parent never needs to know
        // whether its child is DL/RT/fair to rank itself among siblings).
        fun dlUrgency(task: Task): Long = task.dlPeriodRemainingSeconds(nowMs)

        // Shared with buildScheduleDrillLevel below — one level's worth of
        // children, DL → RT → EEVDF tier-sorted. Factored out so drill-down
        // mode shows exactly the same per-level ordering the flat outline does,
        // rather than a second, drift-prone copy of this partitioning.
        //
        // Bucket membership is decided ENTIRELY by each child's OWN class —
        // never by what's nested inside it. A plain (fair-class) group
        // containing a DL/RT descendant does NOT get hoisted into the DL/RT
        // bucket here; it competes against its actual siblings purely on its
        // own class, same as any leaf would. Only a group whose OWN class is
        // DL/RT (and whose own budget/window is currently active) belongs to
        // that bucket.
        fun orderChildren(children: List<Task>): List<Task> {
            val dlActive = children.filter { it.isDlBudgetActive(nowMs) }
                .sortedBy { dlUrgency(it) }
            val dlIds = dlActive.mapTo(HashSet()) { it.id }

            val rtActive = children.filter { it.id !in dlIds && RtScheduler.isRtWindowActive(it, nowMs) }
                .sortedByDescending { it.rtPriority }
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
                val quotaExceeded   = parentQuotaExceeded || task.isQuotaExceeded(nowMs)
                val quotaWarning    = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning(nowMs))
                val isTaskDlActive  = task.isDlBudgetActive(nowMs)
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
                        descGroups = descGroups, descTasks = descTasks, nowMs = nowMs,
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
                        entryMembershipId = inheritedDoor,
                        isLinkedElsewhere = isLinkedElsewhere(task.id, links, memberships))
                }
                result.add(baseItem.copy(
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number,
                    isDlActive             = isTaskDlActive,
                    // Own class only — never a descendant's (fundamental #2).
                    isDlGroupHoisted       = task.isGroup && isTaskDlActive,
                    isRtActive             = isTaskRtActive,
                    isRtGroupHoisted       = task.isGroup && isTaskRtActive,
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
        // Sampled ONCE for this level rebuild (kernel rule 1).
        val nowMs = vm.clock.nowEpochMillis()
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
                            descGroups = descGroups, descTasks = descTasks, nowMs = nowMs,
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
                            isJumpHighlighted = task.id == highlightTaskId,
                            isLinkedElsewhere = isLinkedElsewhere(task.id, links, memberships))
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
        // Sampled ONCE for this level rebuild (kernel rule 1).
        val nowMs           = vm.clock.nowEpochMillis()
        val result = mutableListOf<TaskDisplayItem>()
        var counter = 0

        fun dlUrgency(task: Task): Long = task.dlPeriodRemainingSeconds(nowMs)

        val children = effectiveTasks.filter { it.parentId == frameId }
        // Bucket membership decided ENTIRELY by each child's OWN class — see
        // buildScheduleList's identical rule and its doc comment.
        val dlActive = children.filter { it.isDlBudgetActive(nowMs) }
            .sortedBy { dlUrgency(it) }
        val dlIds = dlActive.mapTo(HashSet()) { it.id }
        val rtActive = children.filter { it.id !in dlIds && RtScheduler.isRtWindowActive(it, nowMs) }
            .sortedByDescending { it.rtPriority }
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
                    descGroups = descGroups, descTasks = descTasks, nowMs = nowMs,
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
                    entryMembershipId = inheritedDoor,
                    isLinkedElsewhere = isLinkedElsewhere(task.id, links, memberships))
            }
            result.add(baseItem.copy(
                queueNumber      = number,
                isDlActive       = task.isDlBudgetActive(nowMs),
                isDlGroupHoisted = task.isGroup && task.isDlBudgetActive(nowMs),
                isRtActive       = RtScheduler.isRtWindowActive(task, nowMs),
                isRtGroupHoisted = task.isGroup && RtScheduler.isRtWindowActive(task, nowMs),
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
