package com.eevdf.data.runlog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-run log entry.  One row per completed timer session.
 *
 * Retention policy (enforced by RunLogRepository):
 *   • Rows older than 30 days are compacted into [RunDailySummary] then deleted.
 *   • Total row count is hard-capped at 100K (oldest deleted first when exceeded).
 *
 * Storage budget: 100K rows × ~120 bytes ≈ 12 MB.
 */
@Entity(
    tableName  = "run_log",
    indices    = [
        Index("taskId"),
        Index("startEpoch"),
        Index("prevTaskId")
    ]
)
data class RunLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** Task that ran. */
    val taskId: String,

    /** Epoch ms when this session started. */
    val startEpoch: Long,

    /** How long this session lasted, in whole seconds. */
    val durationSecs: Long,

    /**
     * Task that was running immediately before this one (null = first ever run,
     * or gap between sessions longer than [SWITCH_GAP_THRESHOLD_MS]).
     *
     * Used to compute per-pair context-switch overhead:
     *   overhead = this.startEpoch − (prev.startEpoch + prev.durationSecs × 1000)
     */
    val prevTaskId: String? = null,

    /**
     * Day-of-week at [startEpoch], stored at insert time so analytics can group
     * by weekday without repeated Calendar calls.
     * 1 = Sunday … 7 = Saturday  (java.util.Calendar.DAY_OF_WEEK convention).
     */
    val weekDay: Int = 0
) {
    companion object {
        /** Gaps longer than this are NOT counted as context switches. */
        const val SWITCH_GAP_THRESHOLD_MS = 5L * 60L * 1_000L  // 5 minutes
        const val MAX_ROWS = 100_000L
        const val TTL_DAYS = 30L
    }
}
