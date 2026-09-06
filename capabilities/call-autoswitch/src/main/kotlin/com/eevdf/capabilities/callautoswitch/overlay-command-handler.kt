package com.eevdf.capabilities.callautoswitch

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.settingsstorage.state.AutoSwitchPrefs
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Subscribes to the 2 overlay command topics and drives [BubbleOverlayService]
 * on their behalf.
 *
 * REPLACES: `com.eevdf.contract.control.OverlayController` and its
 * `OverlayControlModule` Hilt binding. `task-list-screen`'s
 * `CallSwitchDelegate` now publishes `Topics.OVERLAY_CALL_STARTED_REQUESTED` /
 * `OVERLAY_CALL_ENDED_REQUESTED` instead of calling an injected interface —
 * this class is the one subscriber that turns each into the same
 * `BubbleOverlayService` intent the old `OverlayControllerImpl` sent.
 *
 * The "is the bubble enabled" preference check stays here, same as before —
 * the caller still should not have to read this capability's preferences to
 * decide whether it is allowed to publish.
 */
class OverlayCommandHandler(
    private val context: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.OVERLAY_CALL_STARTED_REQUESTED, CAPABILITY_ID) {
            if (!AutoSwitchPrefs.isBubbleEnabled(context)) return@subscribe
            // Foreground start: a call may arrive while the app is backgrounded.
            ContextCompat.startForegroundService(
                context,
                Intent(context, BubbleOverlayService::class.java)
                    .apply { action = BubbleOverlayService.ACTION_CALL_STARTED },
            )
        }
        bus.subscribe(Topics.OVERLAY_CALL_ENDED_REQUESTED, CAPABILITY_ID) {
            // Plain startService — the service is already running and this
            // only asks it to tear itself down.
            context.startService(
                Intent(context, BubbleOverlayService::class.java)
                    .apply { action = BubbleOverlayService.ACTION_CALL_ENDED },
            )
        }
    }

    private companion object {
        const val CAPABILITY_ID = "call-autoswitch"
    }
}
