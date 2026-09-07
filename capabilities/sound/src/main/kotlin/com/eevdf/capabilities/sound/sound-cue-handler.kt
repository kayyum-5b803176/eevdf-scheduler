package com.eevdf.capabilities.sound

import android.content.Context
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.SoundCue
import com.eevdf.kernel.eventbus.Topics

/**
 * This capability's ONLY bus wiring. [SoundManager] is never called from
 * anywhere else — every trigger arrives here as a topic, and every real
 * playback action this handler causes is itself published back out as a
 * topic, so the event-log screen shows the actual thing that happened, not
 * just the request for it.
 *
 * WHY [Topics.SOUND_STOPPED] ONLY FOLLOWS [Topics.ALARM_STOPPED]
 * ------------------------------------------------------------------
 * [Topics.SOUND_CUE_REQUESTED] triggers a one-shot action cue
 * (`playExecuteSound`/`playWaitSound`) that completes on its own — there is
 * no explicit "stop" call for it to publish a stopped event from, and no
 * Android API gives a reliable external completion callback for it either.
 * Only the alarm path has an explicit, externally-triggered stop
 * ([Topics.ALARM_STOPPED]), so only that path has a corresponding
 * [Topics.SOUND_STOPPED].
 */
class SoundCueHandler(
    private val appContext: Context,
    private val bus: EventBus,
) {
    init {
        bus.subscribe(Topics.ALARM_RINGING, CAPABILITY_ID) { event ->
            SoundManager.startAlarmForType(appContext, prefs(), event.taskType)
            bus.publish(Topics.SOUND_STARTED, "alarm:${event.taskType}", CAPABILITY_ID)
        }
        bus.subscribe(Topics.ALARM_STOPPED, CAPABILITY_ID) {
            SoundManager.stop(appContext)
            bus.publish(Topics.SOUND_STOPPED, Unit, CAPABILITY_ID)
        }
        bus.subscribe(Topics.SOUND_CUE_REQUESTED, CAPABILITY_ID) { cue ->
            when (cue) {
                SoundCue.EXECUTE -> SoundManager.playExecuteSound(appContext, prefs())
                SoundCue.WAIT    -> SoundManager.playWaitSound(appContext, prefs())
            }
            bus.publish(Topics.SOUND_STARTED, "cue:${cue.name}", CAPABILITY_ID)
        }
    }

    private fun prefs() = appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val CAPABILITY_ID = "sound"
    }
}
