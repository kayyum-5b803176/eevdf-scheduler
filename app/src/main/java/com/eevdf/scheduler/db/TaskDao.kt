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
}
