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
    val priority: Int,
    val timeSliceSeconds: Long,
    val category: String = "General",
    val color: Int = 0,

    // cgroup hierarchy
    val parentId: String? = null,
    val isGroup: Boolean = false,
    var isGroupExpanded: Boolean = true,

    // EEVDF scheduler state
    var vruntime: Double = 0.0,
    var eligibleTime: Double = 0.0,
    var virtualDeadline: Double = 0.0,
    var lag: Double = 0.0,

    // Timer state
    var remainingSeconds: Long = timeSliceSeconds,
    var isRunning: Boolean = false,
    var isCompleted: Boolean = false,
    var totalRunTime: Long = 0L,
    var runCount: Int = 0,

    val isInterrupt: Boolean = false,

    /** Epoch ms when the running countdown expires. 0 = not running.
     *  Written to DB the instant Start is pressed so app-kill / phone-off
     *  cannot lose this anchor. Cleared to 0 on pause or finish. */
    var timerDeadlineEpoch: Long = 0L,

    val createdAt: Long = System.currentTimeMillis()
) {
    val weight: Double get() = priority.toDouble()

    val timeSliceDisplay: String get() {
        val h = timeSliceSeconds / 3600
        val m = (timeSliceSeconds % 3600) / 60
        val s = timeSliceSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    val remainingDisplay: String get() {
        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        return when {
            h > 0 -> String.format("%d:%02d:%02d", h, m, s)
            else  -> String.format("%02d:%02d", m, s)
        }
    }

    val progressPercent: Int get() {
        if (timeSliceSeconds == 0L) return 0
        return ((timeSliceSeconds - remainingSeconds) * 100 / timeSliceSeconds)
            .toInt().coerceIn(0, 100)
    }
}
