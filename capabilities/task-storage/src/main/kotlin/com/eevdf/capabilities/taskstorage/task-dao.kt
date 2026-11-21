package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.*
import com.eevdf.capabilities.taskstorage.Task

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE axisId = 'x' ORDER BY virtualDeadline ASC, priority DESC")
    fun getAllTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 0 ORDER BY virtualDeadline ASC")
    fun getActiveTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 1 ORDER BY createdAt DESC")
    fun getCompletedTasks(): LiveData<List<Task>>

    /** Suspend/sync variant of [getCompletedTasks] — used by
     *  TaskRepository.clearCompleted() to run per-task deleteOrPromote instead
     *  of a single bulk DELETE, so a completed task with a surviving hardlink
     *  placement elsewhere is promoted rather than destroyed outright. */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 1")
    suspend fun getCompletedTasksSync(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: String): Task?

    /** First non-completed task matching the given name — used as a restart
     *  fallback when the in-memory expired task is gone (e.g. process death). */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 0 AND name = :name LIMIT 1")
    suspend fun getActiveTaskByName(name: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task)

    @Update
    suspend fun update(task: Task)

    /** Batch-updates multiple tasks in a single transaction — used for weight sync. */
    @Update
    suspend fun updateAll(tasks: List<Task>)

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM tasks WHERE isCompleted = 1")
    suspend fun clearCompleted()

    @Query("UPDATE tasks SET isRunning = 0")
    suspend fun stopAllRunning()

    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 0")
    suspend fun getActiveTasksSync(): List<Task>

    /**
     * Rewrites ONLY the scheduler's cached columns, for every item in one
     * transaction (Room wraps a list update in one). A partial entity, so no
     * other column is touched and a concurrent edit of the same row is not
     * overwritten with a stale copy, which a full-row [update] would do.
     */
    @Update(entity = Task::class)
    suspend fun updateScheduleCache(items: List<ScheduleCache>)

    /** All non-completed tasks whose parent is the given id (or root if null). */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isCompleted = 0 AND parentId IS :parentId")
    suspend fun getChildrenOf(parentId: String?): List<Task>

    /** All group tasks (containers) that are not completed. */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isGroup = 1 AND isCompleted = 0 ORDER BY name ASC")
    fun getActiveGroups(): LiveData<List<Task>>

    /** Returns the task/group flagged as interrupt for slot A, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isInterrupt = 1 AND interruptSlot = 'A' AND isCompleted = 0 LIMIT 1")
    suspend fun getInterruptTask(): Task?

    /** Returns the task/group flagged as interrupt for slot B, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isInterrupt = 1 AND interruptSlot = 'B' AND isCompleted = 0 LIMIT 1")
    suspend fun getInterruptTaskB(): Task?

    /** Returns the task/group flagged as interrupt, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE axisId = 'x' AND isInterrupt = 1 AND isCompleted = 0 LIMIT 1")
    suspend fun getAnyInterruptTask(): Task?

    /** Returns the task currently running with a live epoch anchor, or null.
     *  startTimeEpoch > 0 is the canonical "timer is active" signal — isRunning
     *  alone is insufficient because a crash could leave isRunning=1 with
     *  startTimeEpoch=0 (migration step 4 cleans existing data; this guards future). */
    @Query("SELECT * FROM tasks WHERE isRunning = 1 AND startTimeEpoch > 0 LIMIT 1")
    suspend fun getRunningTask(): Task?

    /** Clears the interrupt flag on all tasks (used before setting a new one). */
    @Query("UPDATE tasks SET isInterrupt = 0")
    suspend fun clearAllInterrupts()

    /** Clears the interrupt flag only on tasks in the given slot. */
    @Query("UPDATE tasks SET isInterrupt = 0 WHERE interruptSlot = :slot")
    suspend fun clearInterruptsForSlot(slot: String)

    // ── Sleep / wake ──────────────────────────────────────────────────────────
    //
    // Column-targeted writes on purpose: sleeping a task must never rewrite the
    // rest of the row from a possibly-stale in-memory copy (the running timer
    // keeps its own copy), and a wake must never resurrect old values.

    /** Puts [id] to sleep until [untilEpochMs], remembering the [lag] it slept with. */
    @Query("UPDATE tasks SET sleepUntilEpoch = :untilEpochMs, sleepLag = :lag WHERE id = :id")
    suspend fun setSleep(id: String, untilEpochMs: Long, lag: Double)

    /** Wakes [id]: clears the sleep state and writes the re-placed EEVDF state. */
    @Query(
        "UPDATE tasks SET sleepUntilEpoch = 0, sleepLag = 0.0, vruntime = :vruntime, " +
        "eligibleTime = :eligibleTime, virtualDeadline = :virtualDeadline, lag = :lag WHERE id = :id"
    )
    suspend fun wake(id: String, vruntime: Double, eligibleTime: Double, virtualDeadline: Double, lag: Double)

    // ── Semaphore ─────────────────────────────────────────────────────────────
    //
    // Column-targeted for the same reason as sleep: a slot change must never
    // rewrite the rest of the row from a possibly-stale in-memory copy.

    /** Sets [id]'s slot state: held or waiting, with its arrival stamp (0 = none). */
    @Query("UPDATE tasks SET semaphoreHeld = :held, semaphoreQueuedAt = :queuedAt WHERE id = :id")
    suspend fun setSemaphoreSlot(id: String, held: Boolean, queuedAt: Long)

    /** Admits a waiter from the queue: takes the slot and is re-placed at [vruntime] like a new arrival. */
    @Query("UPDATE tasks SET semaphoreHeld = 1, semaphoreQueuedAt = :queuedAt, vruntime = :vruntime WHERE id = :id")
    suspend fun admitFromQueue(id: String, queuedAt: Long, vruntime: Double)

    /** Forgets every slot of [parentId]'s children — the semaphore was switched off. */
    @Query("UPDATE tasks SET semaphoreHeld = 0, semaphoreQueuedAt = 0 WHERE parentId = :parentId")
    suspend fun resetSemaphoreChildren(parentId: String)

    // ── Backup / Restore ──────────────────────────────────────────────────────

    /** Returns every task (active + completed) for full backup. */
    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    suspend fun getAllTasksForBackup(): List<Task>

    /** All tasks for stats — every row, no filter. */
    @Query("SELECT * FROM tasks ORDER BY totalRunTime DESC")
    suspend fun getAllTasksForStats(): List<Task>

    /** Delete everything — used before restoring a backup. */
    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    /**
     * All distinct, non-empty category strings saved across every task, sorted
     * alphabetically.  Used to populate the category autocomplete suggestions in
     * the Add / Edit task screen so any category a user has ever typed is
     * immediately available as a suggestion when editing another task.
     *
     * Backed by the tasks table's existing `category` column — no extra table or
     * migration needed.  The result is a LiveData so the suggestion list updates
     * automatically whenever a task is saved with a new category.
     */
    @Query("SELECT DISTINCT category FROM tasks WHERE category != '' ORDER BY category ASC")
    fun getDistinctCategories(): LiveData<List<String>>

    // ── Axis-aware reads (y, z, …). The queries above are X-only on purpose:
    //    every existing screen and scheduler pass keeps seeing exactly today's rows.

    @Query("SELECT * FROM tasks WHERE axisId = :axisId AND isCompleted = 0 ORDER BY virtualDeadline ASC")
    fun getActiveTasksOfAxis(axisId: String): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE axisId = :axisId AND isCompleted = 0")
    suspend fun getActiveTasksOfAxisSync(axisId: String): List<Task>

    @Query("SELECT * FROM tasks WHERE axisId != :axisId AND isCompleted = 0")
    fun getActiveTasksNotOfAxis(axisId: String): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE axisId = :axisId AND isGroup = 1 AND isCompleted = 0 ORDER BY name ASC")
    fun getActiveGroupsOfAxis(axisId: String): LiveData<List<Task>>
}
