package com.eevdf.capabilities.taskwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After a reboot AlarmManager has forgotten every alarm. [WakeCoordinator.onBoot] does two
 * independent jobs:
 *
 *  • Still-future wakes are put back into AlarmManager — ONLY when Settings → platform → alarm →
 *    "re-arm wakes after reboot" is on. When it is off they are re-armed the next time the app
 *    is opened (the coordinator does that on every start and every return to the foreground).
 *  • Wakes whose time passed while the phone was off get a "Missed wake" notification — ALWAYS,
 *    whatever that setting says. They stay armed, so the preempt card shows for them the next time
 *    the app is opened.
 */
@AndroidEntryPoint
class WakeBootReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: WakeCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                coordinator.onBoot()
            } finally {
                pending.finish()
            }
        }
    }
}
