package com.eevdf.capabilities.notification

/**
 * RESOLVED: `cancelExpired` used to live here as a hardcoded, alarm-specific
 * cancel (notification id 3001, "owned by AlarmForegroundService"). That's
 * exactly the kind of alarm-specific knowledge this capability isn't
 * supposed to have any more — see this capability's manifest.kt. Cancelling
 * any notification, by any capability, now goes through the generic
 * `Topics.NOTIFICATION_CANCEL_REQUESTED` bus topic (see
 * [NotificationCancelHandler]) instead of a capability-specific method here.
 *
 * [formatElapsed] stays: a pure, side-effect-free "seconds as m:ss" string
 * formatter, shared by alarm-ringer (`AlarmActivity`) and task-list-screen
 * (both display an alarm's elapsed ringing time) — the same category as
 * settings-storage's `SoundPrefs`/`VibrationPrefs`: deterministic data
 * formatting, not a capability action, safe to share directly.
 */
object NotificationHelper {

    /** Format seconds as "0:05", "1:23", "1:02:34" — same style as Google Clock */
    fun formatElapsed(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m >= 60) {
            val h = m / 60
            val rm = m % 60
            "$h:${"%02d".format(rm)}:${"%02d".format(s)}"
        } else {
            "$m:${"%02d".format(s)}"
        }
    }
}
