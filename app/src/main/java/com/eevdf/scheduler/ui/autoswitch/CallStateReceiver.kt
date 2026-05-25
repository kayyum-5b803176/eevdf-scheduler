package com.eevdf.scheduler.ui.autoswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.eevdf.scheduler.ui.autoswitch.AutoSwitchPrefs

/**
 * Listens for phone state changes and posts to [CallEvents].
 *
 * Registered in the manifest so it fires even when the app is in the
 * background. The actual task-switch logic lives in the ViewModel (called
 * from MainActivity via [CallEvents] observation) so that DB / timer
 * operations stay on the correct thread with proper lifecycle scope.
 *
 * State machine:
 *   IDLE  → RINGING / OFFHOOK : call started  → post CALL_STARTED
 *   any   → IDLE              : call ended     → post CALL_ENDED
 *
 * We track the previous state in SharedPreferences so the transition is
 * detected correctly even if the receiver process was killed between events.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // Feature guard — do nothing if call detection is disabled
        if (!AutoSwitchPrefs.isCallDetectionEnabled(context)) return
        // Guard — do nothing if no task has been assigned for call detection
        if (AutoSwitchPrefs.getCallTaskId(context) == null) return

        val newState = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val prevState = AutoSwitchPrefs.getLastCallState(context)

        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (prevState == TelephonyManager.EXTRA_STATE_IDLE ||
                    prevState == null
                ) {
                    CallEvents.event.postValue(CallEvents.Type.CALL_STARTED)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (prevState == TelephonyManager.EXTRA_STATE_RINGING ||
                    prevState == TelephonyManager.EXTRA_STATE_OFFHOOK
                ) {
                    CallEvents.event.postValue(CallEvents.Type.CALL_ENDED)
                }
            }
        }

        AutoSwitchPrefs.saveLastCallState(context, newState)
    }
}
