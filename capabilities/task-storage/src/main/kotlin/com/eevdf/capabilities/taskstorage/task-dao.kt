package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.*
import com.eevdf.capabilities.taskstorage.Task

@Dao
abstract class TaskDao {
    // An abstract class rather than an interface so [update] / [updateAll] can be real, transactional
    // methods: every full-row write goes through them, and they are what keep a task's identity safe
    // (see update). Room generates the implementation either way; callers are unaffected.

    @Query("SELECT * FROM tasks ORDER BY virtualDeadline ASC, priority DESC")
    abstract fun getAllTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY virtualDeadline ASC")
    abstract fun getActiveTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY createdAt DESC")
    abstract fun getCompletedTasks(): LiveData<List<Task>>

    /** Suspend/sync variant of [getCompletedTasks] — used by
     *  TaskRepository.clearCompleted() to run per-task deleteOrPromote instead
     *  of a single bulk DELETE, so a completed task with a surviving hardlink
     *  placement elsewhere is promoted rather than destroyed outright. */
    @Query("SELECT * FROM tasks WHERE isCompleted = 1")
    abstract suspend fun getCompletedTasksSync(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun getTaskById(id: String): Task?

    /** First non-completed task matching the given name — used as a restart
     *  fallback when the in-memory expired task is gone (e.g. process death). */
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND name = :name LIMIT 1")
    abstract suspend fun getActiveTaskByName(name: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(task: Task)

    /**
     * The raw full-row write, for [update] only — do NOT call this directly: it would write the
     * caller's identity as it is. (Room needs it to be a plain abstract member of the DAO.)
     */
    @Update
    abstract suspend fun updateRow(task: Task)

    /** The raw batch write, for [updateAll] only — do NOT call this directly (see [updateRow]). */
    @Update
    abstract suspend fun updateAllRows(tasks: List<Task>)

    /**
     * Full-row update of [task] — that can NEVER change its identity.
     *
     * Many callers write a whole row from an in-memory copy that may be old: the run timer keeps
     * its own copy of the running task, the interrupt assignment copies the edited task, the
     * schedule refresh reads rows and writes them back a moment later. Before this guard any of
     * them could put a task's PREVIOUS identity back right after the person changed it, which is
     * why an identity "would not stick" on some tasks and groups however often it was changed.
     *
     * So the identity stored in the database is read and kept here, in the same transaction as the
     * write (and stamped onto [task], so the caller's own object is current too). The identity is
     * changed only by the dedicated column writes — [setIdentity], and an insert.
     */
    @Transaction
    open suspend fun update(task: Task) {
        getIdentityOf(task.id)?.let { task.identity = it }
        updateRow(task)
    }

    /** Batch-updates multiple tasks in a single transaction — used for weight sync. Identity kept, as in [update]. */
    @Transaction
    open suspend fun updateAll(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        val stored = getIdentityRows().associate { it.id to it.identity }
        tasks.forEach { t -> stored[t.id]?.let { t.identity = it } }
        updateAllRows(tasks)
    }

    @Delete
    abstract suspend fun delete(task: Task)

    @Query("DELETE FROM tasks WHERE isCompleted = 1")
    abstract suspend fun clearCompleted()

    @Query("UPDATE tasks SET isRunning = 0")
    abstract suspend fun stopAllRunning()

    @Query("SELECT * FROM tasks WHERE isCompleted = 0")
    abstract suspend fun getActiveTasksSync(): List<Task>

    /**
     * Rewrites ONLY the scheduler's cached columns, for every item in one
     * transaction (Room wraps a list update in one). A partial entity, so no
     * other column is touched and a concurrent edit of the same row is not
     * overwritten with a stale copy, which a full-row [update] would do.
     */
    @Update(entity = Task::class)
    abstract suspend fun updateScheduleCache(items: List<ScheduleCache>)

    /** All non-completed tasks whose parent is the given id (or root if null). */
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND parentId IS :parentId")
    abstract suspend fun getChildrenOf(parentId: String?): List<Task>

    /** All group tasks (containers) that are not completed. */
    @Query("SELECT * FROM tasks WHERE isGroup = 1 AND isCompleted = 0 ORDER BY name ASC")
    abstract fun getActiveGroups(): LiveData<List<Task>>

    /** Returns the task/group flagged as interrupt for slot A, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND interruptSlot = 'A' AND isCompleted = 0 LIMIT 1")
    abstract suspend fun getInterruptTask(): Task?

    /** Returns the task/group flagged as interrupt for slot B, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND interruptSlot = 'B' AND isCompleted = 0 LIMIT 1")
    abstract suspend fun getInterruptTaskB(): Task?

    /** Returns the task/group flagged as interrupt, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND isCompleted = 0 LIMIT 1")
    abstract suspend fun getAnyInterruptTask(): Task?

    /** Returns the task currently running with a live epoch anchor, or null.
     *  startTimeEpoch > 0 is the canonical "timer is active" signal — isRunning
     *  alone is insufficient because a crash could leave isRunning=1 with
     *  startTimeEpoch=0 (migration step 4 cleans existing data; this guards future). */
    @Query("SELECT * FROM tasks WHERE isRunning = 1 AND startTimeEpoch > 0 LIMIT 1")
    abstract suspend fun getRunningTask(): Task?

    /** Clears the interrupt flag on all tasks (used before setting a new one). */
    @Query("UPDATE tasks SET isInterrupt = 0")
    abstract suspend fun clearAllInterrupts()

    /** Clears the interrupt flag only on tasks in the given slot. */
    @Query("UPDATE tasks SET isInterrupt = 0 WHERE interruptSlot = :slot")
    abstract suspend fun clearInterruptsForSlot(slot: String)

    // ── Sleep / wake ──────────────────────────────────────────────────────────
    //
    // Column-targeted writes on purpose: sleeping a task must never rewrite the
    // rest of the row from a possibly-stale in-memory copy (the running timer
    // keeps its own copy), and a wake must never resurrect old values.

    /** Puts [id] to sleep until [untilEpochMs], remembering the [lag] it slept with. */
    @Query("UPDATE tasks SET sleepUntilEpoch = :untilEpochMs, sleepLag = :lag WHERE id = :id")
    abstract suspend fun setSleep(id: String, untilEpochMs: Long, lag: Double)

    /** Wakes [id]: clears the sleep state and writes the re-placed EEVDF state. */
    @Query(
        "UPDATE tasks SET sleepUntilEpoch = 0, sleepLag = 0.0, vruntime = :vruntime, " +
        "eligibleTime = :eligibleTime, virtualDeadline = :virtualDeadline, lag = :lag WHERE id = :id"
    )
    abstract suspend fun wake(id: String, vruntime: Double, eligibleTime: Double, virtualDeadline: Double, lag: Double)

    // ── Semaphore ─────────────────────────────────────────────────────────────
    //
    // Column-targeted for the same reason as sleep: a slot change must never
    // rewrite the rest of the row from a possibly-stale in-memory copy.

    /** Sets [id]'s slot state: held or waiting, with its arrival stamp (0 = none). */
    @Query("UPDATE tasks SET semaphoreHeld = :held, semaphoreQueuedAt = :queuedAt WHERE id = :id")
    abstract suspend fun setSemaphoreSlot(id: String, held: Boolean, queuedAt: Long)

    /** Admits a waiter from the queue: takes the slot and is re-placed at [vruntime] like a new arrival. */
    @Query("UPDATE tasks SET semaphoreHeld = 1, semaphoreQueuedAt = :queuedAt, vruntime = :vruntime WHERE id = :id")
    abstract suspend fun admitFromQueue(id: String, queuedAt: Long, vruntime: Double)

    /** Forgets every slot of [parentId]'s children — the semaphore was switched off. */
    @Query("UPDATE tasks SET semaphoreHeld = 0, semaphoreQueuedAt = 0 WHERE parentId = :parentId")
    abstract suspend fun resetSemaphoreChildren(parentId: String)

    // ── Backup / Restore ──────────────────────────────────────────────────────

    /** Returns every task (active + completed) for full backup. */
    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    abstract suspend fun getAllTasksForBackup(): List<Task>

    /** All tasks for stats — every row, no filter. */
    @Query("SELECT * FROM tasks ORDER BY totalRunTime DESC")
    abstract suspend fun getAllTasksForStats(): List<Task>

    /** Delete everything — used before restoring a backup. */
    @Query("DELETE FROM tasks")
    abstract suspend fun deleteAllTasks()

    // ── Identity (kind:id) — see IdentityRules ───────────────────────────────

    /** The identity stored for [id], or null if there is no such task. */
    @Query("SELECT identity FROM tasks WHERE id = :id")
    abstract suspend fun getIdentityOf(id: String): String?

    /** Every task's stored identity except [excludeId]'s own — the "taken" set for allocation. */
    @Query("SELECT identity FROM tasks WHERE id != :excludeId")
    abstract suspend fun getIdentitiesExcept(excludeId: String): List<String>

    /** Every task's identity, oldest first — the order [IdentityRules.repair] needs. */
    @Query("SELECT id, identity FROM tasks ORDER BY createdAt ASC, rowid ASC")
    abstract suspend fun getIdentityRows(): List<IdentityRow>

    /**
     * Rewrites ONLY the identity column. A column-targeted write, so a concurrent
     * edit of the same row is never overwritten with a stale full-row copy.
     */
    @Query("UPDATE tasks SET identity = :identity WHERE id = :id")
    abstract suspend fun setIdentity(id: String, identity: String)
}
