package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * "This task was forked from that one" — the process tree's parent link (PPID), kept in a table of its
 * own so `tasks` does not get wider (TaskSchemaFreezeTest) and so a full-row write of a task can never
 * touch it (the identity bug that made that matter is documented on [TaskDao.update]).
 *
 * One row per forked task: [taskId] is the child, [parentTaskId] the task it was copied from. Only forks
 * have a row — a task made any other way has no recorded parent. The ids are task UUIDs, shared between
 * devices, so this table syncs like any small record (unlike `tasks.identity`, which is a per-device number).
 *
 * Rows are not removed when a task is deleted: a missing child just stops being listed, and a missing
 * parent reads as "removed" (a real OS reparents such a child to init; here the link is only informational).
 * [TaskForkDao.deleteOrphans] sweeps the rows whose child is gone.
 */
@Entity(tableName = "task_fork")
data class TaskFork(
    @PrimaryKey val taskId: String,
    val parentTaskId: String,
    val createdAt: Long,
)

@Dao
interface TaskForkDao {

    /** Records (or re-records) that [TaskFork.taskId] was forked from [TaskFork.parentTaskId]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fork: TaskFork)

    /** Every fork, newest first — what the Fork page lists. */
    @Query("SELECT * FROM task_fork ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<TaskFork>>

    /** Forgets the forks whose child task no longer exists. */
    @Query("DELETE FROM task_fork WHERE taskId NOT IN (SELECT id FROM tasks)")
    suspend fun deleteOrphans()
}
