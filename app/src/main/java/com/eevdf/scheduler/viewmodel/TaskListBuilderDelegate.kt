package com.eevdf.scheduler.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TaskDisplayItem
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.RtScheduler

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
            .filter { it.isDlConfigured && !it.isCompleted && !it.isGroup }
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
        // Captured once so every partition/sort in both flat and grouped paths
        // uses the same instant — avoids boundary flicker mid-render.
        val nowMs  = System.currentTimeMillis()
        if (!groupsEnabled) {
            val leaves = tasks.filter { !it.isGroup }

            // Priority order: DL (rank 1) → RT (rank 2) → EEVDF (rank 3)
            // DL: budget-active tasks sorted by EDF urgency (shortest period remaining first)
            // RT: window-active tasks sorted by descending rtPriority (highest first)
            // EEVDF: remaining tasks sorted by virtual deadline
            val (dlActive, nonDl)  = leaves.partition { it.isDlBudgetActive }
            val (rtActive, eevdfRest) = nonDl.partition { RtScheduler.isRtWindowActive(it, nowMs) }
            val dlSorted    = dlActive.sortedBy { it.dlPeriodRemainingSeconds }
            val rtSorted    = rtActive.sortedByDescending { it.rtPriority }
            val eevdfSorted = eevdfRest.sortedBy { it.virtualDeadline }
            val ordered     = dlSorted + rtSorted + eevdfSorted

            return ordered.mapIndexed { index, it ->
                TaskDisplayItem(it, 0,
                    cpuShare               = shares[it.id] ?: 0.0,
                    effectiveQuotaExceeded = it.isQuotaExceeded,
                    effectiveQuotaWarning  = it.isQuotaWarning,
                    queueNumber            = "${index + 1}",
                    isDlActive             = it.isDlBudgetActive,
                    isRtActive             = RtScheduler.isRtWindowActive(it, nowMs))
            }
        }
        val result = mutableListOf<TaskDisplayItem>()
        // When groups are enabled we build the tree level-by-level.
        // Priority order at each level: DL-urgent → RT-urgent → EEVDF
        //   DL-urgent: task/group has active DL budget (propagated from descendants for groups)
        //   RT-urgent: task/group has an active RT window (propagated from descendants for groups)
        //   EEVDF: everything else, sorted by virtual deadline
        fun addLevel(parentId: String?, depth: Int, parentNumber: String,
                     parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean,
                     counter: IntArray) {
            val children = tasks.filter { it.parentId == parentId }

            // DL partition: same as before
            val (dlChildren, nonDlChildren) = children.partition { child ->
                if (child.isGroup) EEVDFScheduler.hasActiveDlDescendant(child, tasks)
                else child.isDlBudgetActive
            }

            // RT partition: from the non-DL children
            val (rtChildren, restChildren) = nonDlChildren.partition { child ->
                if (child.isGroup) RtScheduler.hasActiveRtDescendant(child, tasks, nowMs)
                else RtScheduler.isRtWindowActive(child, nowMs)
            }

            fun dlUrgency(task: Task): Long =
                if (!task.isGroup) task.dlPeriodRemainingSeconds
                else tasks.filter { it.parentId == task.id && !it.isCompleted }
                         .minOfOrNull { dlUrgency(it) } ?: Long.MAX_VALUE

            val sorted =
                dlChildren.sortedBy  { dlUrgency(it) } +
                rtChildren.sortedByDescending { if (it.isGroup) 0 else it.rtPriority } +
                restChildren.sortedBy { it.virtualDeadline }

            sorted.forEach { task ->
                val dc               = tasks.filter { it.parentId == task.id }
                val quotaExceeded    = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning     = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                val isDlGroupHoisted = task.isGroup && EEVDFScheduler.hasActiveDlDescendant(task, tasks)
                val isRtActive       = !task.isGroup && RtScheduler.isRtWindowActive(task, nowMs)
                val isRtGroupHoisted = task.isGroup && RtScheduler.hasActiveRtDescendant(task, tasks, nowMs)
                counter[0]++
                val number = if (parentNumber.isEmpty()) "${counter[0]}" else "$parentNumber.${counter[0]}"
                result.add(TaskDisplayItem(task, depth,
                    childCount             = dc.size,
                    childTotalRuntime      = dc.sumOf { it.totalRunTime },
                    cpuShare               = shares[task.id] ?: 0.0,
                    effectiveQuotaExceeded = quotaExceeded,
                    effectiveQuotaWarning  = quotaWarning,
                    queueNumber            = number,
                    isDlActive             = task.isDlBudgetActive,
                    isDlGroupHoisted       = isDlGroupHoisted,
                    isRtActive             = isRtActive,
                    isRtGroupHoisted       = isRtGroupHoisted,
                    isExpanded             = if (task.isGroup) (vm.groupExpand.scheduleExpandState[task.id] ?: true) else true))
                if (task.isGroup && (vm.groupExpand.scheduleExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, number, quotaExceeded, quotaWarning, IntArray(1))
            }
        }
        addLevel(null, 0, "", false, false, IntArray(1))
        return result
    }
}
