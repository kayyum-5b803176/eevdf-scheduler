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
    val weekDay: Int = 0,

    /**
     * EWMA snapshot at the moment this session ended — the person-load state
     * immediately after the task stopped running.  Stored in the 0–7 raw scale
     * (matching the slider range) so [LoadAverage.combinedLoad] can be applied
     * directly without any rescaling.
     *
     * Defaults to 0.0 for entries recorded before v4.13.0 (no snapshot available);
     * the reconstructor treats such entries as zero-state anchors, which causes a
     * graceful rebuild from the next recorded session onward.
     */
    val loadSnapshotCognitive: Double = 0.0,
    val loadSnapshotPhysical:  Double = 0.0,
    val loadSnapshotEmotional: Double = 0.0,
) {
    companion object {
        /** Gaps longer than this are NOT counted as context switches. */
        const val SWITCH_GAP_THRESHOLD_MS = 5L * 60L * 1_000L  // 5 minutes
        const val MAX_ROWS = 100_000L
        const val TTL_DAYS = 30L
    }
}
