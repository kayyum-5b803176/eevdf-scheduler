package com.eevdf.capabilities.alarmringer

import android.content.Context

/**
 * The most recent alarm-ringing delivery, kept for future reliability
 * diagnostics (see [PermissionChecker]'s KDoc: "the service, for logging or
 * future adaptive behavior"). Deliberately minimal — a single last-value
 * record, not a history — since nothing reads this yet.
 *
 * RESOLVED: this used to be recorded from a separate capability
 * (`notification`'s `AlarmDeliveryHandler`, subscribed to `alarm.ringing`)
 * specifically because `AlarmNotificationPolicy`'s decision-making lived
 * there too. Now that the whole decision (and this log) live inside
 * alarm-ringer alongside the code that actually rings the alarm,
 * [recordRinging] is called directly from [AlarmForegroundService] — a
 * bus round trip was only ever needed to cross a capability boundary that
 * no longer exists for this data.
 */
object AlarmDeliveryLog {
    private const val PREFS_NAME  = "eevdf_alarm_delivery_log"
    private const val KEY_TASK    = "last_ringing_task_name"
    private const val KEY_EPOCH   = "last_ringing_epoch_ms"

    fun recordRinging(context: Context, taskName: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_TASK, taskName)
            .putLong(KEY_EPOCH, nowMs)
            .apply()
    }

    /** The last recorded (taskName, epochMs), or null if no alarm has rung yet. */
    fun lastRinging(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val taskName = p.getString(KEY_TASK, null) ?: return null
        return taskName to p.getLong(KEY_EPOCH, 0L)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
