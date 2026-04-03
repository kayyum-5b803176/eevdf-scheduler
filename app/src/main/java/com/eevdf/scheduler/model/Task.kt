package com.eevdf.scheduler.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val priority: Int,           // 1–10, maps to weight
    val timeSliceSeconds: Long,  // Requested time in seconds
    val category: String = "General",
    val color: Int = 0,

    // EEVDF scheduler state
    var vruntime: Double = 0.0,          // Virtual runtime accumulated
    var eligibleTime: Double = 0.0,      // When task becomes eligible
    var virtualDeadline: Double = 0.0,   // eligibleTime + slice/weight
    var lag: Double = 0.0,               // Lag = (avg_vruntime - vruntime) * weight

    // Timer state
    var remainingSeconds: Long = timeSliceSeconds,
    var isRunning: Boolean = false,
    var isCompleted: Boolean = false,
    var totalRunTime: Long = 0L,         // Total seconds this task has been active
    var runCount: Int = 0,               // How many times it has been scheduled

    val createdAt: Long = System.currentTimeMillis()
) {
    // Weight derived from priority (priority 10 → weight 10, priority 1 → weight 1)
    val weight: Double get() = priority.toDouble()

    // Nice display string for time
    val timeSliceDisplay: String get() {
        val h = timeSliceSeconds / 3600
        val m = (timeSliceSeconds % 3600) / 60
        val s = timeSliceSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    val remainingDisplay: String get() {
        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        return when {
            h > 0 -> String.format("%d:%02d:%02d", h, m, s)
            else -> String.format("%02d:%02d", m, s)
        }
    }

    val progressPercent: Int get() {
        if (timeSliceSeconds == 0L) return 0
        return ((timeSliceSeconds - remainingSeconds) * 100 / timeSliceSeconds).toInt().coerceIn(0, 100)
    }
}
