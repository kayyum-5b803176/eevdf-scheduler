package com.eevdf.capabilities.vibration

import android.content.Context
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * This capability's ONLY bus wiring — mirrors `sound`'s `SoundCueHandler`.
 * See that class's KDoc for why [Topics.VIBRATION_STOPPED] only ever follows
 * [Topics.ALARM_STOPPED], never [Topics.VIBRATION_PREVIEW_REQUESTED] (a
 * one-shot, self-terminating preview has no explicit stop to hang a stopped
 * event off of).
 */
class VibrationCueHandler(
    private val appContext: Context,
    private val bus: EventBus,
) {
    init {
        bus.subscribe(Topics.ALARM_RINGING, CAPABILITY_ID) { event ->
            VibrationManager.startAlarmForType(appContext, prefs(), event.taskType)
            bus.publish(Topics.VIBRATION_STARTED, "alarm:${event.taskType}", CAPABILITY_ID)
        }
        bus.subscribe(Topics.ALARM_STOPPED, CAPABILITY_ID) {
            VibrationManager.stop(appContext)
            bus.publish(Topics.VIBRATION_STOPPED, Unit, CAPABILITY_ID)
        }
        bus.subscribe(Topics.VIBRATION_PREVIEW_REQUESTED, CAPABILITY_ID) { patternId ->
            VibrationManager.preview(appContext, patternId)
            bus.publish(Topics.VIBRATION_STARTED, "preview:$patternId", CAPABILITY_ID)
        }
    }

    private fun prefs() = appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val CAPABILITY_ID = "vibration"
    }
}
