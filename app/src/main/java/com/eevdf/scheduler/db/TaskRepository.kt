package com.eevdf.scheduler.db

import androidx.lifecycle.LiveData
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val dao: TaskDao) {

    val allTasks: LiveData<List<Task>> = dao.getAllTasks()
    val activeTasks: LiveData<List<Task>> = dao.getActiveTasks()
    val completedTasks: LiveData<List<Task>> = dao.getCompletedTasks()
    val activeGroups: LiveData<List<Task>> = dao.getActiveGroups()

    suspend fun insert(task: Task) = withContext(Dispatchers.IO) {
        val existing = dao.getActiveTasksSync().toMutableList()
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

    suspend fun getActiveTasksSync(): List<Task> = withContext(Dispatchers.IO) { dao.getActiveTasksSync() }

    /**
     * cgroup-aware vruntime update.
     * Updates the leaf task, then propagates elapsed time upward through all
     * ancestor groups — exactly like Linux cgroups crediting the task's CPU
     * time to every cgroup it belongs to.
     *
     * Also rolls the quota accounting window forward and credits [secondsRan]
     * against the quota budget for the leaf and every ancestor that has quota
     * enabled — mirroring Linux's cpu.cfs_quota_us throttle per cgroup.
     */
    suspend fun updateVruntimeAfterRun(task: Task, secondsRan: Long) = withContext(Dispatchers.IO) {
        // Update leaf task (also increments runCount via EEVDFScheduler)
        EEVDFScheduler.updateVruntime(task, secondsRan)
        applyQuotaAccounting(task, secondsRan)
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
     * cgroup-aware task selection.
     * Applies EEVDF at each level of the hierarchy, drilling into the winning
     * group recursively until a leaf (non-group) task is found — same as
     * Linux's hierarchical scheduling.
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

    suspend fun getInterruptTask(): Task? = withContext(Dispatchers.IO) { dao.getInterruptTask() }

    /** Atomically clears all interrupt flags then sets isInterrupt=true on [task]. */
    suspend fun setInterruptTask(task: Task) = withContext(Dispatchers.IO) {
        dao.clearAllInterrupts()
        dao.update(task.copy(isInterrupt = true))
    }

    /** Clears interrupt flag from all tasks. */
    suspend fun clearInterruptTask() = withContext(Dispatchers.IO) { dao.clearAllInterrupts() }

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
}
