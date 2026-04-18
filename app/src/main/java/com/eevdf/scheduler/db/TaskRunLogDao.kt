package com.eevdf.scheduler.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.eevdf.scheduler.model.TaskRunLog

@Dao
interface TaskRunLogDao {

    @Insert
    suspend fun insert(log: TaskRunLog)

    // Sum durationSeconds for taskId whose startEpoch falls within the moving window.
    // Returns 0 if there are no matching rows.
    @Query("""
        SELECT COALESCE(SUM(durationSeconds), 0)
        FROM task_run_logs
        WHERE taskId = :taskId
          AND startEpoch >= :windowStartEpoch
    """)
    suspend fun sumDurationInWindow(taskId: String, windowStartEpoch: Long): Long

    /** Prune logs older than [cutoffEpoch] to keep the table lean. */
    @Query("DELETE FROM task_run_logs WHERE startEpoch < :cutoffEpoch")
    suspend fun deleteBefore(cutoffEpoch: Long)
}
