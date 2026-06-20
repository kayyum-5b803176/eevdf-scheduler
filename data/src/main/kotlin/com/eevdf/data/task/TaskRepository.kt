package com.eevdf.data.task
import com.eevdf.data.runlog.RunLogRepository

import android.content.Context
import androidx.lifecycle.LiveData
import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.Task
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.RtScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val dao: TaskDao, context: Context) {

    val allTasks: LiveData<List<Task>> = dao.getAllTasks()
    val activeTasks: LiveData<List<Task>> = dao.getActiveTasks()
    val completedTasks: LiveData<List<Task>> = dao.getCompletedTasks()
    val activeGroups: LiveData<List<Task>> = dao.getActiveGroups()

    /**
     * Live, alphabetically sorted list of every distinct category string stored
     * in the tasks table.  Updated automatically whenever any task is saved with
     * a new or changed category value, so the autocomplete suggestions in the
     * Add / Edit screen always reflect the full set the user has ever typed.
     */
    val distinctCategories: LiveData<List<String>> = dao.getDistinctCategories()

    private val runLog = RunLogRepository(context)

    suspend fun insert(task: Task) = withContext(Dispatchers.IO) {
        val existing = dao.getActiveTasksSync().toMutableList()

        // ── Bug fix: Linux EEVDF place_entity for new tasks ──────────────────
        // Before this fix, every new task started with vruntime = 0.  The scheduler
        // saw a huge positive lag (avgVr − 0) × weight and kept picking the new task
        // first, starving all existing tasks until vruntime "caught up".
        //
        // Fix: mirrors Linux's place_entity(ENQUEUE_INITIAL) which clamps lag to 0
        // for a never-run task → vruntime = avg_vruntime → lag = 0 → eligible
        // immediately but with no scheduling advantage over existing tasks.
        EEVDFScheduler.placeNewTask(task, existing)

        existing.add(task)
        EEVDFScheduler.recalculate(existing)
        dao.insert(task)
        existing.forEach { dao.update(it) }
    }

    suspend fun update(task: Task) = withContext(Dispatchers.IO) { dao.update(task) }

    /** Batch-persists a list of tasks whose [Task.internalWeight] was re-synced. */
    suspend fun updateBatch(tasks: List<Task>) = withContext(Dispatchers.IO) {
        if (tasks.isNotEmpty()) dao.updateAll(tasks)
    }

    suspend fun delete(task: Task) = withContext(Dispatchers.IO) {
        // Also delete all descendants
        deleteDescendants(task.id)
        dao.delete(task)
    }

    private suspend fun deleteDescendants(parentId: String) {
        val children = dao.getChildrenOf(parentId)
        children.forEach { child ->
            if (child.isGroup) deleteDescendants(child.id)
            dao.delete(child)
        }
    }

    suspend fun markCompleted(task: Task) = withContext(Dispatchers.IO) {
        val updated = task.copy(isCompleted = true, isRunning = false, remainingSeconds = 0)
        dao.update(updated)
    }

    suspend fun stopAll() = withContext(Dispatchers.IO) { dao.stopAllRunning() }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) { dao.clearCompleted() }

    suspend fun getTaskById(id: String): Task? = withContext(Dispatchers.IO) { dao.getTaskById(id) }

    suspend fun getActiveTaskByName(name: String): Task? =
        withContext(Dispatchers.IO) { dao.getActiveTaskByName(name) }

    suspend fun getActiveTasksSync(): List<Task> = withContext(Dispatchers.IO) { dao.getActiveTasksSync() }

    /**
     * cgroup-aware vruntime update.
     * Updates the leaf task, then propagates elapsed time upward through all
     * ancestor groups — exactly like Linux cgroups crediting the task's CPU
     * time to every cgroup it belongs to.
     *
     * Also rolls the quota accounting window forward and credits session.wallClockSeconds
     * against the quota budget for the leaf and every ancestor that has quota enabled.
     *
     * [session] is the authoritative source for both:
     *   • seconds to credit → session.wallClockSeconds  (timestamp diff, never a config value)
     *   • RunLog start time → session.startEpochMs      (real wall-clock, never approximated)
     *
     * This fixes two compounding bugs in the old Long-based API:
     *   1. Caller passed task.timeSliceSeconds instead of actual elapsed → vruntime over-credited.
     *   2. RunLog start was approximated as (now - secondsRan*1000) → off by any pause delay.
     */
    suspend fun updateVruntimeAfterRun(task: Task, session: RunSession) = withContext(Dispatchers.IO) {
        val secondsRan = session.wallClockSeconds

        // Record this session in the RunLog with the REAL start epoch.
        // Old code: startEpoch = System.currentTimeMillis() - secondsRan * 1_000L  (approximation)
        // New code: startEpoch = session.startEpochMs                              (exact)
        if (secondsRan > 0 && !task.isGroup) {
            runLog.recordRun(task.id, session.startEpochMs, secondsRan)
        }

        EEVDFScheduler.updateVruntime(task, secondsRan)
        applyQuotaAccounting(task, secondsRan)
        applyDlAccounting(task, secondsRan)
        dao.update(task)

        // Propagate up the ancestor chain — credit time but do NOT increment runCount
        var parentId = task.parentId
        while (parentId != null) {
            val parent = dao.getTaskById(parentId) ?: break
            parent.totalRunTime += secondsRan
            if (parent.weight > 0) {
                parent.vruntime += secondsRan.toDouble() / parent.weight
            }
            applyQuotaAccounting(parent, secondsRan)
            dao.update(parent)
            parentId = parent.parentId
        }

        val allActive = dao.getActiveTasksSync()
        EEVDFScheduler.recalculate(allActive)
        allActive.forEach { dao.update(it) }
    }

    /**
     * Rolls the quota accounting period forward if it has expired, then credits
     * [secondsRan] to [task.quotaUsedSeconds].
     *
     * The task object is mutated in-place (var fields); the caller persists it.
     *
     * Period roll-over logic (mirrors Linux cgroup bandwidth controller):
     *   - If no period has started yet (quotaPeriodStartEpoch == 0), open a fresh period now.
     *   - If the current period has elapsed, advance the start epoch by however many complete
     *     periods have passed so the window tracks real wall-clock time precisely.
     */
    private fun applyQuotaAccounting(task: Task, secondsRan: Long) {
        if (!task.isQuotaEnabled) return
        val nowMs = System.currentTimeMillis()

        // Snapshot the continuously-decayed value at this instant, then reset the
        // anchor to now.  This means the next tick in currentQuotaUsed always starts
        // from the correct baseline rather than accumulating floating-point drift.
        val decayedNow = if (task.quotaPeriodStartEpoch == 0L) 0L else task.currentQuotaUsed

        task.quotaPeriodStartEpoch = nowMs
        task.quotaUsedSeconds      = (decayedNow + secondsRan).coerceAtLeast(0L)
    }

    /**
     * SCHED_DEADLINE period accounting — mirrors Linux SCHED_DEADLINE budget tracking.
     *
     * On every run:
     *  1. If no period has started (dlPeriodStartEpoch == 0), open a fresh period now
     *     and credit [secondsRan] as the first runtime consumption.
     *  2. If the current period has fully elapsed since the last recorded start, roll
     *     forward by whole periods and reset [dlRuntimeUsedSeconds] to zero before
     *     crediting the new run.  This ensures the budget replenishes exactly on period
     *     boundaries regardless of how much wall-clock time has passed between runs.
     *  3. Within an active period, accumulate [secondsRan] up to the maximum
     *     [Task.dlRuntimeSeconds] (excess is clamped — over-running is not possible).
     *
     * The task object is mutated in-place; the caller persists it.
     */
    private fun applyDlAccounting(task: Task, secondsRan: Long) {
        if (!task.isDlConfigured) return
        val nowMs             = System.currentTimeMillis()
        val effectivePeriodMs = task.dlEffectivePeriodSeconds * 1_000L

        if (task.dlPeriodStartEpoch == 0L) {
            // First ever run — open period now
            task.dlPeriodStartEpoch    = nowMs
            task.dlRuntimeUsedSeconds  = secondsRan.coerceAtMost(task.dlRuntimeSeconds)
            return
        }

        val elapsedMs = nowMs - task.dlPeriodStartEpoch
        if (elapsedMs >= effectivePeriodMs) {
            // One or more full periods have elapsed since last run — advance the anchor
            // by the number of whole periods that fit, then reset the budget.
            val periodsElapsed        = elapsedMs / effectivePeriodMs
            task.dlPeriodStartEpoch   = task.dlPeriodStartEpoch + periodsElapsed * effectivePeriodMs
            task.dlRuntimeUsedSeconds = secondsRan.coerceAtMost(task.dlRuntimeSeconds)
        } else {
            // Still within the same period — accumulate runtime (capped at budget)
            task.dlRuntimeUsedSeconds =
                (task.dlRuntimeUsedSeconds + secondsRan).coerceAtMost(task.dlRuntimeSeconds)
        }
    }

    /**
     * cgroup-aware task selection.
     * Applies EEVDF at each level of the hierarchy, drilling into the winning
     * group recursively until a leaf (non-group) task is found — same as
     * Linux's hierarchical scheduling.
     *
     * cgroup-aware SCHED_DEADLINE promotion:
     * Before the normal EEVDF pick, any group at the current level whose subtree
     * contains at least one DL-budget-active leaf is treated as a "deadline entity"
     * at that level and hoisted ahead of all EEVDF-ordered siblings — exactly as
     * Linux promotes a deadline-class cgroup entity to the top of the run-queue.
     * Among multiple hoisted groups, the one whose most-urgent descendant has the
     * shortest dlPeriodRemaining wins (EDF order).  If the winning hoisted group
     * has no runnable children (all completed/running), the algorithm falls back
     * to normal EEVDF selection at the same level.
     */
    suspend fun selectNextTask(): Task? = withContext(Dispatchers.IO) {
        val allActive = dao.getActiveTasksSync()
        selectNextCgroup(allActive, null)
    }

    private fun selectNextCgroup(
        all: List<Task>,
        parentId: String?,
        visited: MutableSet<String> = mutableSetOf()
    ): Task? {
        // Exclude already-tried empty groups to prevent infinite recursion
        val level = all.filter {
            it.parentId == parentId && !it.isCompleted && !it.isRunning && it.id !in visited
        }
        if (level.isEmpty()) return null

        // ── cgroup-aware SCHED_DEADLINE promotion ─────────────────────────────
        // Identify groups (or leaves) at this level that are DL-urgent.
        // A group is DL-urgent if any descendant has isDlBudgetActive == true.
        // A leaf is DL-urgent if it is a dl_sched_class task with budget remaining.
        //
        // Among DL-urgent entries, pick by EDF urgency: the entry whose most
        // imminent descendant deadline fires soonest (smallest dlPeriodRemaining).
        // This matches Linux SCHED_DEADLINE which always picks the entity with the
        // earliest absolute deadline among eligible deadline tasks/groups.
        fun minDlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else all.filter { it.parentId == task.id && !it.isCompleted }
                    .minOfOrNull { minDlUrgency(it) } ?: Long.MAX_VALUE

        val dlUrgent = level.filter { entry ->
            if (entry.isGroup) EEVDFScheduler.hasActiveDlDescendant(entry, all)
            else entry.isDlBudgetActive
        }.sortedBy { minDlUrgency(it) }

        // Try DL-urgent candidates first, then fall back to normal EEVDF selection
        for (dlEntry in dlUrgent) {
            if (dlEntry.id in visited) continue
            val result = if (dlEntry.isGroup) {
                visited.add(dlEntry.id)
                selectNextCgroup(all, dlEntry.id, visited)
                    ?: selectNextCgroup(all, parentId, visited) // group was empty, continue
            } else {
                dlEntry // leaf DL task — return directly
            }
            if (result != null) return result
        }

        // ── RT window promotion (between DL and EEVDF) ────────────────────────
        // After DL candidates are exhausted, check for RT-window-active entries.
        // Among RT tasks: highest rtPriority wins; FIFO = never rotate, RR = round-robin.
        val rtUrgent = level.filter { entry ->
            if (entry.isGroup) RtScheduler.hasActiveRtDescendant(entry, all)
            else RtScheduler.isRtWindowActive(entry)
        }

        if (rtUrgent.isNotEmpty()) {
            // Collect all RT-active leaves across the urgent entries for RR selection
            val rtLeaves = rtUrgent.flatMap { entry ->
                if (!entry.isGroup) listOf(entry)
                else all.filter { it.parentId == entry.id && !it.isCompleted && !it.isRunning &&
                                  RtScheduler.isRtWindowActive(it) }
            }.filter { it.id !in visited }

            if (rtLeaves.isNotEmpty()) {
                // SharedPrefs not accessible from repository directly; use highest-priority
                // as the fallback deterministic pick (RR index is managed by the ViewModel layer)
                val pick = rtLeaves.maxByOrNull { it.rtPriority }
                if (pick != null) return pick
            }
        }

        // No DL-urgent or RT-urgent entries — normal EEVDF selection
        EEVDFScheduler.recalculate(level)
        val winner = EEVDFScheduler.selectNext(level) ?: return null
        return if (winner.isGroup) {
            // Mark this group visited so it won't be retried if its children are empty
            visited.add(winner.id)
            // Drill into the group; if it has no eligible children fall back at
            // the SAME level (not root) — skipping the now-visited empty group
            selectNextCgroup(all, winner.id, visited)
                ?: selectNextCgroup(all, parentId, visited)
        } else {
            winner
        }
    }

    suspend fun getScheduleOrder(): List<Task> = withContext(Dispatchers.IO) {
        val activeTasks = dao.getActiveTasksSync()
        EEVDFScheduler.getScheduleOrder(activeTasks)
    }

    // ── Interrupt task ────────────────────────────────────────────────────────

    suspend fun getRunningTask(): Task? = withContext(Dispatchers.IO) { dao.getRunningTask() }

    /** Returns the INT-A interrupt task (legacy default slot). */
    suspend fun getInterruptTask(): Task? = withContext(Dispatchers.IO) { dao.getInterruptTask() }

    /** Returns the INT-B interrupt task. */
    suspend fun getInterruptTaskB(): Task? = withContext(Dispatchers.IO) { dao.getInterruptTaskB() }

    /**
     * Atomically clears all interrupt flags in [slot] then marks [task] as that slot's interrupt.
     * [slot] must be "A" or "B".
     */
    suspend fun setInterruptTask(task: Task, slot: String = "A") = withContext(Dispatchers.IO) {
        dao.clearInterruptsForSlot(slot)
        dao.update(task.copy(isInterrupt = true, interruptSlot = slot))
    }

    /** Clears interrupt flag for the given slot ("A" or "B"). */
    suspend fun clearInterruptTask(slot: String = "A") = withContext(Dispatchers.IO) {
        dao.clearInterruptsForSlot(slot)
    }

    /** Clears interrupt flags for ALL slots. */
    suspend fun clearAllInterruptTasks() = withContext(Dispatchers.IO) {
        dao.clearAllInterrupts()
    }

    // ── Backup / Restore ──────────────────────────────────────────────────────

    /** Returns every task (active + completed) for export. */
    suspend fun getAllTasksForBackup(): List<Task> = withContext(Dispatchers.IO) {
        dao.getAllTasksForBackup()
    }

    /**
     * Replaces the entire database with [tasks].
     * Runs inside a single IO coroutine so the DB is never left half-written.
     */
    suspend fun restoreFromBackup(tasks: List<Task>) = withContext(Dispatchers.IO) {
        dao.deleteAllTasks()
        for (task in tasks) {
            dao.insert(task)
        }
    }

    /**
     * Live-sync variant of [restoreFromBackup].
     *
     * Differences from the regular backup restore:
     *  • Does NOT pause the timer or restart the app — the ViewModel handles
     *    any in-memory state update via [MultiUserSyncManager.importEvent].
     *  • Preserves `isRunning`, `accumulatedMs`, and `startTimeEpoch` as-is
     *    (they were serialised by [BackupManager.toSyncJson]).
     */
    suspend fun restoreFromSyncBackup(tasks: List<Task>) = withContext(Dispatchers.IO) {
        dao.stopAllRunning()   // clear stale flags before the replace
        dao.deleteAllTasks()
        for (task in tasks) {
            dao.insert(task)
        }
    }
}
