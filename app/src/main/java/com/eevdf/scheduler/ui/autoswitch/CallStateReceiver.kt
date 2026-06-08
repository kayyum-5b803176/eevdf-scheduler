package com.eevdf.scheduler.ui.autoswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Listens for phone state changes and starts [CallSwitchService] to perform
 * the actual task switch in the background.
 *
 * Registered in the manifest with PHONE_STATE so it fires even when the app
 * process is dead (Android will restart it for the broadcast).
 *
 * ── Architecture change ───────────────────────────────────────────────────────
 *
 * Previously this receiver posted to [CallEvents] LiveData, which required
 * MainActivity to be alive and observing.  The flow is now:
 *
 *   CallStateReceiver → CallSwitchService (foreground service, does DB work)
 *                     → CallEvents.postValue() (to sync ViewModel if Activity alive)
 *
 * This means:
 *   • Call arrives while app is completely dead → process starts for the
 *     broadcast, CallSwitchService starts, switch happens in DB, process may
 *     then die again.  When user opens the app, TaskViewModel.syncFromDb()
 *     reads the correct DB state on onResume().
 *   • Call arrives while app is backgrounded → same path, no Activity needed.
 *   • Call arrives while app is in foreground → CallSwitchService does the
 *     DB work AND posts to CallEvents; ViewModel receives both paths (service
 *     posts first, then its CALL_STARTED post is consumed and ignored by the
 *     guard in TaskCallSwitchDelegate because callInProgress is already true
 *     from the service's DB write being reflected on the next DB read).
 *
 * ── "Open once" ───────────────────────────────────────────────────────────────
 *
 * After the user opens the app once, MainActivity registers its LiveData
 * observers.  From that point on, CallEvents keeps the ViewModel's in-memory
 * state (savedTaskBeforeCall, wasTimerRunning) in sync so that when the user
 * later opens the app mid-call or after a call, the displayed state matches DB.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *   IDLE  → RINGING / OFFHOOK : call started  → start CallSwitchService(CALL_STARTED)
 *   any   → IDLE              : call ended     → start CallSwitchService(CALL_ENDED)
 *
 * Transition is tracked via [AutoSwitchPrefs.saveLastCallState] so it works
 * correctly even if the receiver process was killed between events.
 *
 * ── Robustness: ongoing call on first boot ────────────────────────────────────
 *
 * If EXTRA_STATE arrives as OFFHOOK with prevState == null (e.g. app installed
 * mid-call, or prefs cleared), we treat it as a call-started transition so the
 * switch still fires rather than being silently skipped.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // Feature guard — do nothing if call detection is disabled
        if (!AutoSwitchPrefs.isCallDetectionEnabled(context)) return
        // Guard — do nothing if no task has been assigned for call detection
        if (AutoSwitchPrefs.getCallTaskId(context) == null) return

        val newState  = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val prevState = AutoSwitchPrefs.getLastCallState(context)

        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Fire on:
                //   • Normal transition from IDLE
                //   • prevState == null (first event ever, e.g. app was installed
                //     while a call was already ongoing, or prefs were cleared)
                if (prevState == TelephonyManager.EXTRA_STATE_IDLE || prevState == null) {
                    ContextCompat.startForegroundService(
                        context,
                        CallSwitchService.intentStarted(context)
                    )
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (prevState == TelephonyManager.EXTRA_STATE_RINGING ||
                    prevState == TelephonyManager.EXTRA_STATE_OFFHOOK
                ) {
                    ContextCompat.startForegroundService(
                        context,
                        CallSwitchService.intentEnded(context)
                    )
                }
            }
        }

        AutoSwitchPrefs.saveLastCallState(context, newState)
    }
}
