package com.eevdf.data.task

import androidx.lifecycle.LiveData
import androidx.room.*
import com.eevdf.data.task.Task

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY virtualDeadline ASC, priority DESC")
    fun getAllTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY virtualDeadline ASC")
    fun getActiveTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY createdAt DESC")
    fun getCompletedTasks(): LiveData<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: String): Task?

    /** First non-completed task matching the given name — used as a restart
     *  fallback when the in-memory expired task is gone (e.g. process death). */
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND name = :name LIMIT 1")
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

    @Query("SELECT * FROM tasks WHERE isCompleted = 0")
    suspend fun getActiveTasksSync(): List<Task>

    /** All non-completed tasks whose parent is the given id (or root if null). */
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND parentId IS :parentId")
    suspend fun getChildrenOf(parentId: String?): List<Task>

    /** All group tasks (containers) that are not completed. */
    @Query("SELECT * FROM tasks WHERE isGroup = 1 AND isCompleted = 0 ORDER BY name ASC")
    fun getActiveGroups(): LiveData<List<Task>>

    /** Returns the task/group flagged as interrupt for slot A, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND interruptSlot = 'A' AND isCompleted = 0 LIMIT 1")
    suspend fun getInterruptTask(): Task?

    /** Returns the task/group flagged as interrupt for slot B, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND interruptSlot = 'B' AND isCompleted = 0 LIMIT 1")
    suspend fun getInterruptTaskB(): Task?

    /** Returns the task/group flagged as interrupt, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND isCompleted = 0 LIMIT 1")
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
}
