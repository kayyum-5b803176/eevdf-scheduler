package com.eevdf.capabilities.alarmringer

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object AlarmRingerManifest : CapabilityManifest {
    override val capabilityId = "alarm-ringer"
    override val publishes: Set<Topic<*>> = setOf(Topics.ALARM_RINGING, Topics.ALARM_STOPPED)
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.ALARM_TIMER_START_REQUESTED,
        Topics.ALARM_TIMER_PAUSE_REQUESTED,
        Topics.ALARM_TIMER_EXPIRE_REQUESTED,
        Topics.ALARM_STOP_REQUESTED,
        Topics.ALARM_CANCEL_SCHEDULED_REQUESTED,
        Topics.ALARM_DELAY_START_REQUESTED,
        Topics.TIMER_EXPIRED,
        Topics.REALTIME_WINDOW_EXPIRED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If alarm-ringer is unavailable, in-app alarm commands are dropped —
        // but the AlarmManager entry scheduled at timer-start time is owned by
        // the OS, not this process, so a genuinely expiring timer STILL fires
        // and still wakes the device. That is the doze-immune path, and it is
        // deliberately not dependent on this capability being attached.
    }
}

/**
 * RESOLVED — both `TIMER_EXPIRED` and `REALTIME_WINDOW_EXPIRED` (previously
 * dead) now have real, deliberately diagnostic-only subscribers; see
 * [TimerExpiryHandler]'s KDoc for why neither re-triggers or stops the
 * alarm — `TIMER_EXPIRED` would double-fire against the already-real
 * `ALARM_TIMER_EXPIRE_REQUESTED`, and `REALTIME_WINDOW_EXPIRED`'s
 * task-id payload can't be safely matched against this capability's
 * name-only `AlarmState` without a task-storage dependency this capability
 * deliberately doesn't have.
 */
