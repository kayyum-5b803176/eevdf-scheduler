package com.eevdf.data.task

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update

/**
 * Persistence access. Pure Room — no scheduling logic lives here (the reference
 * `TaskRepository` imported the scheduler; that inversion is gone). The DAO
 * speaks only [TaskEntity]; mapping to the domain happens in the repository.
 */
@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks WHERE isCompleted = 0")
    suspend fun activeTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE parentId IS :parentId AND isCompleted = 0")
    suspend fun childrenOf(parentId: String?): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE isRunning = 1 LIMIT 1")
    suspend fun running(): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Update
    suspend fun updateAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tasks SET isRunning = 0 WHERE isRunning = 1")
    suspend fun stopAllRunning()
}

@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
