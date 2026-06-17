package com.eevdf.data.runlog

import androidx.room.*
import com.eevdf.data.runlog.RunDailySummary
import com.eevdf.data.runlog.RunLogEntry
import com.eevdf.data.runlog.RunMonthlySummary

@Dao
interface RunLogDao {

    // ── run_log ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: RunLogEntry): Long

    /** Most recent entry across all tasks — used to detect the previous task on switch. */
    @Query("SELECT * FROM run_log ORDER BY startEpoch DESC LIMIT 1")
    suspend fun getLatestEntry(): RunLogEntry?

    /** All entries older than [cutoffEpoch] ms, used for compaction. */
    @Query("SELECT * FROM run_log WHERE startEpoch < :cutoffEpoch ORDER BY startEpoch ASC")
    suspend fun getEntriesOlderThan(cutoffEpoch: Long): List<RunLogEntry>

    /** Deletes entries older than [cutoffEpoch]. Called after compaction. */
    @Query("DELETE FROM run_log WHERE startEpoch < :cutoffEpoch")
    suspend fun deleteEntriesOlderThan(cutoffEpoch: Long)

    /** Total row count — for enforcing the 100K hard cap. */
    @Query("SELECT COUNT(*) FROM run_log")
    suspend fun count(): Long

    /** Delete oldest [n] rows to keep total count under the hard cap. */
    @Query("DELETE FROM run_log WHERE id IN (SELECT id FROM run_log ORDER BY startEpoch ASC LIMIT :n)")
    suspend fun deleteOldest(n: Long)

    /** All entries for a specific task within a time range (for switch-overhead analysis). */
    @Query("""
        SELECT * FROM run_log
        WHERE taskId = :taskId AND startEpoch >= :fromEpoch
        ORDER BY startEpoch ASC
    """)
    suspend fun getEntriesForTask(taskId: String, fromEpoch: Long): List<RunLogEntry>

    /** All entries within a time range, ordered by start — for cross-task switch analysis. */
    @Query("""
        SELECT * FROM run_log
        WHERE startEpoch >= :fromEpoch AND startEpoch <= :toEpoch
        ORDER BY startEpoch ASC
    """)
    suspend fun getEntriesInRange(fromEpoch: Long, toEpoch: Long): List<RunLogEntry>

    // ── run_daily ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(summary: RunDailySummary)

    @Query("SELECT * FROM run_daily WHERE taskId = :taskId ORDER BY dayEpoch DESC")
    suspend fun getDailyForTask(taskId: String): List<RunDailySummary>

    @Query("SELECT * FROM run_daily WHERE dayEpoch >= :fromEpoch ORDER BY dayEpoch ASC")
    suspend fun getDailyInRange(fromEpoch: Long): List<RunDailySummary>

    /** All daily rows older than [cutoffEpoch], for monthly compaction. */
    @Query("SELECT * FROM run_daily WHERE dayEpoch < :cutoffEpoch ORDER BY dayEpoch ASC")
    suspend fun getDailyOlderThan(cutoffEpoch: Long): List<RunDailySummary>

    @Query("DELETE FROM run_daily WHERE dayEpoch < :cutoffEpoch")
    suspend fun deleteDailyOlderThan(cutoffEpoch: Long)

    @Query("SELECT COUNT(*) FROM run_daily")
    suspend fun countDaily(): Long

    @Query("DELETE FROM run_daily WHERE rowid IN (SELECT rowid FROM run_daily ORDER BY dayEpoch ASC LIMIT :n)")
    suspend fun deleteOldestDaily(n: Long)

    /** Day-of-week totals for a task (weekday pattern). */
    @Query("""
        SELECT weekDay, SUM(totalSecs) as totalSecs, SUM(runCount) as runCount
        FROM run_daily
        WHERE taskId = :taskId
        GROUP BY weekDay
        ORDER BY weekDay ASC
    """)
    suspend fun getWeekdayTotalsForTask(taskId: String): List<WeekdayTotal>

    /** Global day-of-week totals across all tasks. */
    @Query("""
        SELECT weekDay, SUM(totalSecs) as totalSecs, SUM(runCount) as runCount
        FROM run_daily
        GROUP BY weekDay
        ORDER BY weekDay ASC
    """)
    suspend fun getGlobalWeekdayTotals(): List<WeekdayTotal>

    // ── run_monthly ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthly(summary: RunMonthlySummary)

    @Query("SELECT * FROM run_monthly WHERE taskId = :taskId ORDER BY monthEpoch ASC")
    suspend fun getMonthlyForTask(taskId: String): List<RunMonthlySummary>

    @Query("SELECT * FROM run_monthly WHERE monthEpoch >= :fromEpoch ORDER BY monthEpoch ASC")
    suspend fun getMonthlyInRange(fromEpoch: Long): List<RunMonthlySummary>

    // ── helper data classes (used by @Query projections) ─────────────────────

    data class WeekdayTotal(
        val weekDay:   Int,
        val totalSecs: Long,
        val runCount:  Int
    )
}
