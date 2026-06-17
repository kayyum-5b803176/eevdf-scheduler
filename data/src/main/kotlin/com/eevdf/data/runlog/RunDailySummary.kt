package com.eevdf.data.runlog

import androidx.room.Entity
import androidx.room.Index

/**
 * Daily aggregate for one task on one calendar day.
 * Produced by compacting [RunLogEntry] rows older than 30 days.
 *
 * Retention policy:
 *   • Rows older than 365 days are compacted into [RunMonthlySummary] then deleted.
 *   • Total row count is soft-capped at 500K.
 *
 * Storage budget: 500K rows × ~80 bytes ≈ 40 MB.
 */
@Entity(
    tableName  = "run_daily",
    primaryKeys = ["taskId", "dayEpoch"],
    indices     = [Index("dayEpoch"), Index("taskId")]
)
data class RunDailySummary(
    val taskId: String,

    /**
     * Epoch ms of the UTC midnight that starts this calendar day.
     * Stored as a Long so range queries are fast B-tree scans.
     */
    val dayEpoch: Long,

    /** Sum of [RunLogEntry.durationSecs] for this task on this day. */
    val totalSecs: Long,

    /** Number of sessions completed on this day. */
    val runCount: Int,

    /**
     * How many times this task was switched *to* from a different task on this day.
     * Derived from [RunLogEntry.prevTaskId] during compaction.
     */
    val switchInCount: Int = 0,

    /**
     * Day-of-week (1=Sun … 7=Sat).  Redundant with dayEpoch but avoids repeated
     * Calendar lookups in StatsActivity grouping queries.
     */
    val weekDay: Int = 0
) {
    companion object {
        const val MAX_ROWS  = 500_000L
        const val TTL_DAYS  = 365L
    }
}
