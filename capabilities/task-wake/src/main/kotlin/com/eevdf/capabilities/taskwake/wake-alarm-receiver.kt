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
 * AlarmManager says a wake came due. The receiver only hands over to [WakeCoordinator], which
 * does the deciding (preempt card, keep waiting, or clear) and tells the person when the app is
 * not on screen. Cold process: Hilt builds the coordinator, which re-arms and evaluates itself.
 *
 * Ghost guard: a cancel can lose the race against the broadcast on some ROMs. The coordinator
 * only acts on wakes that are still ARMED in the table and actually due, so a cancelled wake
 * that is delivered anyway finds nothing to do.
 */
@AndroidEntryPoint
class WakeAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: WakeCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                coordinator.onWakeDue()
            } finally {
                pending.finish()
            }
        }
    }
}
