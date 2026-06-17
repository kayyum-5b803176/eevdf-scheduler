package com.eevdf.data.sync

/**
 * Cumulative and per-export write statistics for the multi-user sync feature.
 *
 * Exposed via [MultiUserSyncManager.writeStats] as LiveData so the
 * MultiUserSyncActivity can display them without polling.
 *
 * @param lastExportMs          Epoch-ms when the most recent export finished.
 *                              0 = no export has run yet this session.
 * @param lastDbSizeBytes       Size of the local DB at the time of the last export.
 * @param lastBytesWritten      Bytes actually written to the sync file during the
 *                              last export (page-diff in Direct mode; full size
 *                              in SAF mode).
 * @param lastPagesWritten      Number of 4 KB pages written during the last export.
 *                              -1 = SAF mode (page-level diff not available).
 * @param lastPagesSkipped      Number of 4 KB pages that were identical and skipped.
 *                              -1 = SAF mode.
 * @param sessionExportCount    Number of exports completed this app session.
 * @param sessionBytesWritten   Total bytes written to the sync file this session.
 * @param accessMode            "direct" if RandomAccessFile page-diff is active,
 *                              "saf" if ContentResolver stream fallback is in use.
 */
data class SyncWriteStats(
    val lastExportMs:        Long   = 0L,
    val lastDbSizeBytes:     Long   = 0L,
    val lastBytesWritten:    Long   = 0L,
    val lastPagesWritten:    Int    = 0,
    val lastPagesSkipped:    Int    = 0,
    val sessionExportCount:  Int    = 0,
    val sessionBytesWritten: Long   = 0L,
    val accessMode:          String = "unknown"
) {
    /** Percentage of the DB that was actually written (0–100). */
    val writeRatioPct: Int
        get() = if (lastDbSizeBytes > 0)
            ((lastBytesWritten * 100L) / lastDbSizeBytes).toInt().coerceIn(0, 100)
        else 0

    /** True when page-level diff stats are meaningful (Direct mode). */
    val hasDiffStats: Boolean
        get() = lastPagesWritten >= 0 && lastPagesSkipped >= 0

    companion object {
        val EMPTY = SyncWriteStats()
    }
}
