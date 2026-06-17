package com.eevdf.data.runlog

import androidx.room.Entity
import androidx.room.Index

/**
 * Monthly aggregate for one task.  Kept forever — no TTL.
 * Produced by compacting [RunDailySummary] rows older than 365 days.
 *
 * Storage budget:
 *   100 yr × 12 mo × avg 200 active tasks/mo × ~77 bytes ≈ 18 MB.
 *   Even at 1 000 active tasks/month for 100 years: ~92 MB — within the 256 MB budget.
 */
@Entity(
    tableName   = "run_monthly",
    primaryKeys = ["taskId", "monthEpoch"],
    indices     = [Index("monthEpoch"), Index("taskId")]
)
data class RunMonthlySummary(
    val taskId: String,

    /** Epoch ms of the UTC midnight of the 1st of this month. */
    val monthEpoch: Long,

    val totalSecs: Long,
    val runCount:  Int
)
