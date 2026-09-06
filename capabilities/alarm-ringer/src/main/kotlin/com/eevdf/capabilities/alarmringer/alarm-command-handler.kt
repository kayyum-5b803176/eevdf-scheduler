package com.eevdf.capabilities.alarmringer

import android.content.Context
import com.eevdf.kernel.eventbus.AlarmDelayStartRequest
import com.eevdf.kernel.eventbus.AlarmTimerExpireRequest
import com.eevdf.kernel.eventbus.AlarmTimerStartRequest
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Subscribes to the 6 alarm command topics and drives [AlarmForegroundService]
 * on their behalf.
 *
 * REPLACES: `com.eevdf.contract.control.AlarmController` and its
 * `AlarmControlModule` Hilt binding. That interface's 6 one-way command
 * methods (`timerStart`, `timerPause`, `timerExpire`, `stopAlarm`,
 * `cancelScheduledAlarm`, `delayStart`) are now bus topics published by
 * countdown-timer, notice-phase, and task-list-screen; this class is the one
 * subscriber that turns each into the same `AlarmForegroundService` static
 * call the old `AlarmControllerImpl` made.
 *
 * The 7th method, `ringingAlarm()`, deliberately did NOT become a topic —
 * see [AlarmRingingQueryImpl] and this capability's manifest.kt for why.
 */
class AlarmCommandHandler(
    private val context: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.ALARM_TIMER_START_REQUESTED, CAPABILITY_ID) { req: AlarmTimerStartRequest ->
            AlarmForegroundService.timerStart(context, req.taskName, req.remainingSecs, req.taskType, req.alarmSecs)
        }
        bus.subscribe(Topics.ALARM_TIMER_PAUSE_REQUESTED, CAPABILITY_ID) {
            AlarmForegroundService.timerPause(context)
        }
        bus.subscribe(Topics.ALARM_TIMER_EXPIRE_REQUESTED, CAPABILITY_ID) { req: AlarmTimerExpireRequest ->
            AlarmForegroundService.timerExpire(context, req.taskName, req.taskType)
        }
        bus.subscribe(Topics.ALARM_STOP_REQUESTED, CAPABILITY_ID) {
            AlarmForegroundService.stopAlarm(context)
        }
        bus.subscribe(Topics.ALARM_CANCEL_SCHEDULED_REQUESTED, CAPABILITY_ID) {
            AlarmForegroundService.cancelScheduledAlarm(context)
        }
        bus.subscribe(Topics.ALARM_DELAY_START_REQUESTED, CAPABILITY_ID) { req: AlarmDelayStartRequest ->
            AlarmForegroundService.delayStart(context, req.taskName, req.delaySecs)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "alarm-ringer"
    }
}
