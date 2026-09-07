package com.eevdf.capabilities.feedbackcues

import android.content.Context
import com.eevdf.capabilities.feedbackcues.output.SoundManager
import com.eevdf.capabilities.feedbackcues.output.VibrationManager
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `alarm.ringing`/`alarm.stopped`
 * subscriptions (manifest.kt) to the real thing: playing and stopping the
 * alarm sound + vibration.
 *
 * WHAT THIS REPLACES
 * -------------------
 * `AlarmForegroundService` used to call `SoundManager.startAlarmForType()` /
 * `VibrationManager.startAlarmForType()` directly on `ACTION_TIMER_EXPIRE` —
 * the exact debt this capability's manifest.kt has flagged, unresolved,
 * since Phase 1 ("the direct-call edge is left in place and will be
 * bus-ified when those capabilities move"). This handler is that move: the
 * service now only publishes `alarm.ringing`; this is where the sound
 * actually starts.
 *
 * WHY THE STOP SIDE STAYS DUAL, NOT CUT OVER
 * -------------------------------------------
 * Unlike the start side, `AlarmForegroundService` KEEPS its own direct
 * `SoundManager.stop()` / `VibrationManager.stop()` calls in `onDestroy()`
 * (see that file) in addition to this handler's `alarm.stopped` subscription.
 * Starting sound twice would be audible (restarts the fade-in, resets the
 * stream volume); stopping it twice is a no-op both ways. Keeping a direct
 * stop path guards the case where the bus dispatch never gets to run at all —
 * e.g. process death — where there's no harm in a redundant call that also
 * never runs, but real value in not depending on the bus for the one thing
 * that must never get stuck ringing.
 */
class AlarmCueHandler(
    private val appContext: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.ALARM_RINGING, CAPABILITY_ID) { event ->
            val prefs = appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
            SoundManager.startAlarmForType(appContext, prefs, event.taskType)
            VibrationManager.startAlarmForType(appContext, prefs, event.taskType)
        }
        bus.subscribe(Topics.ALARM_STOPPED, CAPABILITY_ID) {
            SoundManager.stop(appContext)
            VibrationManager.stop(appContext)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "feedback-cues"
    }
}
