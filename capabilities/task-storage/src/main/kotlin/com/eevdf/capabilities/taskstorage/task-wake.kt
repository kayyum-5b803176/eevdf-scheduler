package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A task's scheduled wake (`task-wake` capability) — its own table, NOT columns
 * on `tasks` (that table is frozen; see TaskSchemaFreezeTest).
 *
 * One row per task. It exists as soon as the task has a wake PROFILE (set on the
 * Edit screen) or an armed wake, and carries both:
 *
 *   profile        — which sound / vibration profile the preempt card uses:
 *                    DEFAULT / NOTIFICATION / ALARM / CUSTOM (the same values as
 *                    the Task Config "Task Profile"). Default ALARM ("Alert").
 *   mode           — ALARM (a calendar date + time) or TIMER (a duration from the
 *                    moment it was set). Informational once armed; [targetEpochMs]
 *                    is what counts.
 *   targetEpochMs  — when the wake is due. 0 = no wake armed.
 *   phase          — IDLE: nothing armed. ARMED: waiting for its time (and, once
 *                    due, for the running task to stop outranking it). CARD: the
 *                    preempt card is up and waiting for Preempt / Dismiss; it
 *                    survives an app restart.
 *
 * There is deliberately NO foreign key to `tasks`: Room's REPLACE conflict
 * strategy (restore, sync merge) deletes-then-inserts a task row, which would
 * cascade-delete the wake of a task that merely got re-written. Wakes of deleted
 * tasks are cleaned up lazily, when they come due.
 *
 * Wakes are device-local: absolute times and AlarmManager entries mean nothing on
 * another phone, so `SyncSchema` excludes this table (and [WakeReturn]).
 */
@Entity(tableName = "task_wake")
data class TaskWake(
    @PrimaryKey val taskId: String,
    val profile: String = PROFILE_ALARM,
    val mode: String = MODE_ALARM,
    val targetEpochMs: Long = 0L,
    val phase: String = PHASE_IDLE,
) {
    val isArmed: Boolean get() = phase != PHASE_IDLE && targetEpochMs > 0L

    companion object {
        const val PROFILE_DEFAULT      = "DEFAULT"
        const val PROFILE_NOTIFICATION = "NOTIFICATION"
        const val PROFILE_ALARM        = "ALARM"
        const val PROFILE_CUSTOM       = "CUSTOM"

        const val MODE_ALARM = "ALARM"
        const val MODE_TIMER = "TIMER"

        const val PHASE_IDLE  = "IDLE"
        const val PHASE_ARMED = "ARMED"
        const val PHASE_CARD  = "CARD"
    }
}

/**
 * The return stack of preempts: when [wakeTaskId] preempted [returnTaskId], the
 * latter is selected again (paused) once the former ends. A stack, newest on top,
 * because a wake can itself be preempted by a later one. Kept in its own table —
 * never in INT-A / INT-B's `interrupt_return` — so the two features cannot touch
 * each other.
 */
@Entity(tableName = "wake_return")
data class WakeReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val wakeTaskId: String,
    val returnTaskId: String,
    val createdAt: Long = 0L,
)

@Dao
interface TaskWakeDao {

    // ── task_wake ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM task_wake")
    fun observeAll(): LiveData<List<TaskWake>>

    @Query("SELECT * FROM task_wake")
    suspend fun getAll(): List<TaskWake>

    @Query("SELECT * FROM task_wake WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskWake?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wake: TaskWake)

    /** Creates the row (default profile) only if the task has none yet. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(wake: TaskWake)

    @Query("UPDATE task_wake SET profile = :profile WHERE taskId = :taskId")
    suspend fun setProfile(taskId: String, profile: String)

    @Query("UPDATE task_wake SET phase = :phase WHERE taskId = :taskId")
    suspend fun setPhase(taskId: String, phase: String)

    /** Disarms: keeps the row (and so the task's profile), clears the time. */
    @Query("UPDATE task_wake SET phase = 'IDLE', targetEpochMs = 0 WHERE taskId = :taskId")
    suspend fun clearWake(taskId: String)

    @Query("DELETE FROM task_wake WHERE taskId = :taskId")
    suspend fun delete(taskId: String)

    // ── wake_return ───────────────────────────────────────────────────────────

    @Insert
    suspend fun pushReturn(entry: WakeReturn)

    /** The newest return entry of [wakeTaskId], or null. */
    @Query("SELECT * FROM wake_return WHERE wakeTaskId = :wakeTaskId ORDER BY id DESC LIMIT 1")
    suspend fun topReturn(wakeTaskId: String): WakeReturn?

    @Query("DELETE FROM wake_return WHERE id = :id")
    suspend fun deleteReturn(id: Long)

    @Query("DELETE FROM wake_return WHERE wakeTaskId = :wakeTaskId")
    suspend fun clearReturns(wakeTaskId: String)

    @Query("SELECT * FROM wake_return")
    suspend fun getAllReturns(): List<WakeReturn>
}
