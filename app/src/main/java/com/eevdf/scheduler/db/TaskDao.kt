package com.eevdf.scheduler.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.eevdf.scheduler.model.Task

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task)

    @Update
    suspend fun update(task: Task)

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

    /** Returns the task/group flagged as interrupt, or null if none assigned. */
    @Query("SELECT * FROM tasks WHERE isInterrupt = 1 AND isCompleted = 0 LIMIT 1")
    suspend fun getInterruptTask(): Task?

    /** Returns the task currently running with an active wall-clock deadline, or null.
     *  Used on app resume to restore timer state after a kill or device sleep. */
    @Query("SELECT * FROM tasks WHERE isRunning = 1 AND timerDeadlineEpoch > 0 LIMIT 1")
    suspend fun getRunningTask(): Task?

    /** Clears the interrupt flag on all tasks (used before setting a new one). */
    @Query("UPDATE tasks SET isInterrupt = 0")
    suspend fun clearAllInterrupts()

    // ── Backup / Restore ──────────────────────────────────────────────────────

    /** Returns every task (active + completed) for full backup. */
    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    suspend fun getAllTasksForBackup(): List<Task>

    /** Removes every task row — used before restoring a backup. */
    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()
}
