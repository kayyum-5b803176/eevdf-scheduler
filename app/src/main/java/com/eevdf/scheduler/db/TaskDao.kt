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
}
