package com.eevdf.data.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Human-effort load factor parameters for a task, stored in a separate side table.
 *
 * Keeps the core [Task] entity lean while giving the load factor feature its own
 * schema space. One row per task; missing row == not configured (treat as defaults).
 *
 * ── Fields ──────────────────────────────────────────────────────────────────
 * cognitive  — NASA-TLX Mental Demand: concentration, analysis, decisions (1–7).
 * physical   — NASA-TLX Physical Demand: exertion, movement, stamina (1–7).
 * emotional  — NASA-TLX Frustration adapted: stress, stakes, emotional weight (1–7).
 * enabled    — true  = user has configured the sliders manually (toggle ON).
 *              false = load factor is auto-inherited from the nearest ancestor
 *                      whose enabled == true, or falls back to midpoint (50).
 *
 * ── Combined formula ────────────────────────────────────────────────────────
 *   loadFactor = ((cognitive + physical + emotional − 3) / 18) × 100
 *
 *   1,1,1 →   0   (no meaningful load)
 *   4,4,4 →  50   (midpoint / default for unconfigured tasks)
 *   7,7,7 → 100   (maximum human load)
 *
 * The computed [loadFactor] double is written back to [Task.loadFactor] on save
 * so the existing LoadAverage EWMA machinery consumes it unchanged.
 *
 * ── Inheritance ─────────────────────────────────────────────────────────────
 * When [enabled] is false, [TaskRepository.propagateInheritedLoadFactor] copies
 * the parent's row (with enabled = false) into this child's row so the sliders
 * always reflect the current inherited state and are pre-filled when the user
 * later enables the toggle.
 */
@Entity(tableName = "task_load_factor")
data class TaskLoadFactor(
    @PrimaryKey
    val taskId: String,
    val cognitive: Int = 4,
    val physical: Int = 4,
    val emotional: Int = 4,
    /** true = manually configured; false = inherited / default. */
    val enabled: Boolean = false,
) {
    companion object {
        /**
         * Computes the 0–100 combined load value from slider inputs.
         * Extracted here so the formula has one canonical location across
         * both the form (preview) and the save handler.
         */
        fun compute(cognitive: Int, physical: Int, emotional: Int): Double =
            ((cognitive + physical + emotional - 3).toDouble() / 18.0) * 100.0

        /** Midpoint defaults used when no entry exists for a task. */
        const val DEFAULT_COGNITIVE = 4
        const val DEFAULT_PHYSICAL  = 4
        const val DEFAULT_EMOTIONAL = 4
        const val DEFAULT_LOAD      = 50.0
    }
}

@Dao
interface TaskLoadFactorDao {

    /** Returns the entry for [taskId], or null if the task has no configured entry. */
    @Query("SELECT * FROM task_load_factor WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskLoadFactor?

    /**
     * Insert or replace the entry for [entry.taskId].
     * Used on save and on inherited propagation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TaskLoadFactor)

    /** Remove the entry when the owning task is deleted. */
    @Query("DELETE FROM task_load_factor WHERE taskId = :taskId")
    suspend fun clearByTask(taskId: String)

    /** Full export for backup — returns every row. */
    @Query("SELECT * FROM task_load_factor")
    suspend fun getAll(): List<TaskLoadFactor>
}
