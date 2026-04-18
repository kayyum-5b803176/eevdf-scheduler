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

    // Task type — drives which sound/vibration profile fires on expiry
    // Values: "DEFAULT" | "NOTIFICATION" | "ALARM" | "CUSTOM"
    val taskType: String = "DEFAULT",

    // Notice type only: seconds to wait before the countdown starts (0–300)
    val notificationDelaySeconds: Long = 0L,

    // Notice type only: rest seconds after task timer (0–300), 0 = skip rest
    val notificationRestSeconds: Long = 0L,

    // Notice type only: how many extra cycles (timer→rest) after the first (0 = run once)
    val notificationRepeatCount: Int = 0,

    /** Epoch ms when the running countdown expires. 0 = not running.
     *  Written to DB the instant Start is pressed so app-kill / phone-off
     *  cannot lose this anchor. Cleared to 0 on pause or finish. */
    var timerDeadlineEpoch: Long = 0L,

    // CPU share pinning — null = auto-float (EEVDF weight-based), 0–100 = fixed %
    val pinnedShare: Int? = null,

    /**
     * Auto-calculated internal scheduling weight derived from [pinnedShare].
     * When non-null, overrides [priority] for all EEVDF weight calculations so
     * that — if the user later removes the pin — the task naturally receives the
     * same CPU share via the float pool.  Null = use [priority] as normal.
     */
    val internalWeight: Double? = null,

    val createdAt: Long = System.currentTimeMillis()
) {
    /** Effective EEVDF weight. Uses auto-calc value when available, else falls back to priority. */
    val weight: Double get() = internalWeight ?: priority.toDouble()

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
