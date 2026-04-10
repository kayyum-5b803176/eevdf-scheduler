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

    /**
     * cgroup-aware vruntime update.
     * Updates the leaf task, then propagates elapsed time upward through all
     * ancestor groups — exactly like Linux cgroups crediting the task's CPU
     * time to every cgroup it belongs to.
     */
    suspend fun updateVruntimeAfterRun(task: Task, secondsRan: Long) = withContext(Dispatchers.IO) {
        // Update leaf task (also increments runCount via EEVDFScheduler)
        EEVDFScheduler.updateVruntime(task, secondsRan)
        dao.update(task)

        // Propagate up the ancestor chain — credit time but do NOT increment runCount
        var parentId = task.parentId
        while (parentId != null) {
            val parent = dao.getTaskById(parentId) ?: break
            parent.totalRunTime += secondsRan
            if (parent.weight > 0) {
                parent.vruntime += secondsRan.toDouble() / parent.weight
            }
            dao.update(parent)
            parentId = parent.parentId
        }

        val allActive = dao.getActiveTasksSync()
        EEVDFScheduler.recalculate(allActive)
        allActive.forEach { dao.update(it) }
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
