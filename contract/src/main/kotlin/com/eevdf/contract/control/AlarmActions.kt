package com.eevdf.contract.control

/**
 * Local-broadcast actions related to the alarm.
 *
 * These are strings on a wire, not behaviour, so they belong in a neutral place
 * rather than on a class inside the alarm feature. `MainActivity` registering a
 * receiver for `AlarmStopReceiver.ACTION_STOP_ALARM` was a compile-time
 * dependency on another feature purely to read a constant.
 *
 * The alarm feature re-exports these from its own companion objects for
 * backward compatibility, so existing call sites inside that feature are
 * unaffected.
 */
object AlarmActions {

    /**
     * Broadcast locally when the alarm is stopped, so any screen showing alarm
     * UI can dismiss itself. Sent by
     * [com.eevdf.feature.alarm.AlarmStopReceiver].
     */
    const val ACTION_STOP_ALARM = "com.eevdf.scheduler.ACTION_STOP_ALARM"
}
