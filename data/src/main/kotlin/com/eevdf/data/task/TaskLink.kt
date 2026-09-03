package com.eevdf.data.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.lifecycle.LiveData
import java.util.UUID

/**
 * A symlink: a pure pointer shown inside [hostGroupId], referencing the real
 * task/group [targetTaskId].
 *
 * A symlink is NOT a [Task] row and carries no config of its own — no name,
 * no priority, no time slice. Wherever it is rendered it always shows the
 * target's live, current data. It never competes for EEVDF weight/CPU-share
 * in [hostGroupId]'s pool, and tapping it (the timer icon) navigates the user
 * to the target's real location instead of running anything from here.
 *
 * [totalRunTime] / [runCount] are the ONE thing that IS specific to this
 * placement: they credit only sessions that were started by tapping this
 * symlink and running the target from here — never sessions run from the
 * target's real, actual parent, and never any other symlink of the same
 * target. This mirrors exactly how a normal group only ever accumulates
 * runtime for what happened directly under it (see
 * [TaskRepository.updateVruntimeAfterRun]) — a symlink placement is just
 * another place a run can be credited, tracked the same simple way.
 *
 * Cascade: deleting the target task deletes every [TaskLink] pointing at it
 * (see [TaskRepository.delete]) — there is never a "broken link" state.
 */
@Entity(tableName = "task_links")
data class TaskLink(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val targetTaskId: String,
    val hostGroupId: String,
    var totalRunTime: Long = 0L,
    var runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TaskLinkDao {

    @Query("SELECT * FROM task_links")
    fun getAll(): LiveData<List<TaskLink>>

    @Query("SELECT * FROM task_links")
    suspend fun getAllSync(): List<TaskLink>

    @Query("SELECT * FROM task_links WHERE hostGroupId = :groupId")
    suspend fun getForHost(groupId: String): List<TaskLink>

    @Query("SELECT * FROM task_links WHERE id = :id")
    suspend fun getById(id: String): TaskLink?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: TaskLink)

    @Query("UPDATE task_links SET totalRunTime = :totalRunTime, runCount = :runCount WHERE id = :id")
    suspend fun updateStats(id: String, totalRunTime: Long, runCount: Int)

    @Query("DELETE FROM task_links WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Cascade: called whenever a task/group is deleted so no broken links remain. */
    @Query("DELETE FROM task_links WHERE targetTaskId = :targetTaskId")
    suspend fun deleteByTarget(targetTaskId: String)

    /** Cascade: called whenever a group is deleted so its hosted links go with it. */
    @Query("DELETE FROM task_links WHERE hostGroupId = :hostGroupId")
    suspend fun deleteByHost(hostGroupId: String)
}
