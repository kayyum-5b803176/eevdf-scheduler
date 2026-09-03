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
 * Unlike a symlink, a hardlink is not a mirror — it is the same underlying
 * task genuinely living in a second place. All of the task's own config
 * (name, description, priority, timeSliceSeconds, quota, schedulerClass, …)
 * stays on the single [Task] row and is shared everywhere it appears: edit it
 * from any location and every location shows the change.
 *
 * What CANNOT be shared across two simultaneous real placements is the EEVDF
 * scheduling state and the runtime ledger — each is inherently relative to
 * "my siblings in this one pool", so this row carries its own independent
 * copy of exactly those fields, mirroring [Task]'s own vruntime/runtime
 * fields one-for-one:
 *
 *   • [vruntime]/[eligibleTime]/[virtualDeadline]/[lag] — this placement's own
 *     EEVDF state, computed against [groupId]'s other children, independent
 *     of whatever the task's primary vruntime under its real parent is doing.
 *   • [totalRunTime]/[runCount] — credited only when a run session is started
 *     from THIS placement (i.e. from inside [groupId]); a session started
 *     from the task's real, primary parent (or another hardlink placement)
 *     never touches these fields, and vice versa. Each placement — primary
 *     or hardlink — only ever knows about what happened under it, exactly
 *     like a normal group only rolls up its own direct children.
 *
 * Cascade: deleting [taskId] deletes every [TaskMembership] row for it.
 * Deleting [groupId] deletes every membership hosted there (without touching
 * the task itself if it still has its primary home, or other memberships,
 * elsewhere).
 */
@Entity(tableName = "task_memberships")
data class TaskMembership(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val groupId: String,
    var totalRunTime: Long = 0L,
    var runCount: Int = 0,
    var vruntime: Double = 0.0,
    var eligibleTime: Double = 0.0,
    var virtualDeadline: Double = 0.0,
    var lag: Double = 0.0,
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

    @Query("SELECT * FROM task_memberships WHERE id = :id")
    suspend fun getById(id: String): TaskMembership?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(membership: TaskMembership)

    @Query("""
        UPDATE task_memberships SET
            totalRunTime = :totalRunTime, runCount = :runCount,
            vruntime = :vruntime, eligibleTime = :eligibleTime,
            virtualDeadline = :virtualDeadline, lag = :lag
        WHERE id = :id
    """)
    suspend fun updateSchedState(
        id: String,
        totalRunTime: Long,
        runCount: Int,
        vruntime: Double,
        eligibleTime: Double,
        virtualDeadline: Double,
        lag: Double,
    )

    @Query("DELETE FROM task_memberships WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Cascade: called whenever a task/group is deleted so it drops every extra placement. */
    @Query("DELETE FROM task_memberships WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    /** Cascade: called whenever a group is deleted so memberships hosted there go with it. */
    @Query("DELETE FROM task_memberships WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)
}
