package com.eevdf.capabilities.notification

import android.content.Context

/**
 * The most recent `alarm.ringing` delivery, kept for future reliability
 * diagnostics (see [AlarmReliabilityChecker]'s KDoc: "the service, for
 * logging or future adaptive behavior"). Deliberately minimal — a single
 * last-value record, not a history — since nothing reads this yet; it exists
 * so [AlarmDeliveryHandler]'s subscription to `alarm.ringing` does real work
 * instead of being an empty placeholder.
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
