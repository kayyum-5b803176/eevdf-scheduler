package com.eevdf.core.platform

/**
 * Platform-capability ports.
 *
 * These describe, in pure terms, the OS-level effects the app needs. The
 * platform/ module implements them with real Android APIs; features/ and the
 * scheduler depend only on these interfaces. This is what lets a ViewModel ask
 * for an alarm or a sound without importing an Android Service — and removes
 * the reference app's `viewmodel -> ui` upward dependency.
 */

/** Schedules/cancels a Doze-immune alarm at an absolute epoch time. */
interface AlarmPort {
    fun scheduleExact(requestId: String, triggerAtEpochMillis: Long)
    fun cancel(requestId: String)
}

/** Plays/stops expiry feedback. The "how" (MediaPlayer, Ringtone) lives in platform/. */
interface SoundPort {
    fun play(profile: String)
    fun stop()
}

/** Posts/cancels user-visible notifications. */
interface NotificationPort {
    fun show(channelId: String, id: Int, title: String, body: String)
    fun cancel(id: Int)
}

/** Vibration feedback. */
interface VibrationPort {
    fun vibrate(pattern: LongArray)
    fun cancel()
}
