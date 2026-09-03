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
 * A hardlink: an EXTRA real placement of [taskId] inside [groupId], on top of
 * the task's one primary [Task.parentId] home.
 *
 * A hardlink is not a copy — it's the same underlying task genuinely living
 * in a second place. Everything about it is SHARED with every other
 * placement and lives on the single [Task] row: name, description, priority,
 * timeSliceSeconds, quota state, DL/RT budget state, `totalRunTime`,
 * `runCount`, load/EWMA — edit it, run it, or exhaust its quota from ANY
 * placement and every placement sees the same result, because it's the same
 * row. This is deliberate: `totalRunTime`/`runCount` in particular are meant
 * to be a true lifetime statistic ("how much has this task actually been
 * worked on, no matter where"), not a per-placement fragment you'd have to
 * sum up to get the real total.
 *
 * The ONE thing that genuinely cannot be shared is [vruntime] — EEVDF
 * fairness is only meaningful relative to a specific set of siblings, and
 * `Work`'s children and `Personal`'s children are a completely different
 * population. So this placement gets its own vruntime, seeded like a fresh
 * arrival in [groupId] (see [TaskRepository.createHardlink]) and advanced
 * independently every time a run session is credited here (see
 * [TaskRepository.creditMembershipRun]).
 *
 * `virtualDeadline`/`eligibleTime`/`lag` deliberately have NO field here at
 * all — they are pure functions of vruntime plus the task's own (shared)
 * timeSliceSeconds/weight (`EevdfScheduler.recalculate()`: eligibleTime =
 * vruntime, virtualDeadline = eligibleTime + timeSlice/weight), recomputed
 * fresh every time the scheduler needs them. Persisting them here would just
 * be a second copy of a value already fully determined by [vruntime] — never
 * a second source of truth to keep in sync, and never a corruption risk.
 */
@Entity(tableName = "task_memberships")
data class TaskMembership(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val groupId: String,
    var vruntime: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TaskMembershipDao {

    @Query("SELECT * FROM task_memberships")
    fun getAll(): LiveData<List<TaskMembership>>

    @Query("SELECT * FROM task_memberships")
    suspend fun getAllSync(): List<TaskMembership>

    @Query("SELECT * FROM task_memberships WHERE groupId = :groupId")
    suspend fun getForGroup(groupId: String): List<TaskMembership>

    /** Every OTHER real placement a task has — used to promote one to primary
     *  when the task's current primary placement is deleted (see
     *  TaskRepository.deleteOrPromote): real hardlink semantics mean the data
     *  survives as long as ANY placement of it still exists. */
    @Query("SELECT * FROM task_memberships WHERE taskId = :taskId")
    suspend fun getForTask(taskId: String): List<TaskMembership>

    @Query("SELECT * FROM task_memberships WHERE id = :id")
    suspend fun getById(id: String): TaskMembership?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(membership: TaskMembership)

    /** The only mutable scheduling state a placement has — see class doc
     *  comment for why virtualDeadline/eligibleTime/lag need no field, let
     *  alone an update query, of their own. */
    @Query("UPDATE task_memberships SET vruntime = :vruntime WHERE id = :id")
    suspend fun updateVruntime(id: String, vruntime: Double)

    @Query("DELETE FROM task_memberships WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Cascade: called whenever a task/group is deleted so it drops every extra placement. */
    @Query("DELETE FROM task_memberships WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    /** Cascade: called whenever a group is deleted so memberships hosted there go with it. */
    @Query("DELETE FROM task_memberships WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)
}
