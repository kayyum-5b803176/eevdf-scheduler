package com.eevdf.app.feature.autoswitch

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eevdf.contract.control.OverlayController
import com.eevdf.app.core.prefs.AutoSwitchPrefs
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The autoswitch feature's implementation of [OverlayController].
 *
 * Note that the "is the bubble enabled" preference check now lives HERE rather
 * than at the call site in the task feature. That is the improvement, not an
 * accident: the caller should not have to read another feature's preferences to
 * work out whether it is allowed to make a request.
 */
@Singleton
internal class OverlayControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : OverlayController {

    override fun onCallStarted() {
        if (!AutoSwitchPrefs.isBubbleEnabled(context)) return
        // Foreground start: a call may arrive while the app is backgrounded.
        ContextCompat.startForegroundService(
            context,
            Intent(context, BubbleOverlayService::class.java)
                .apply { action = BubbleOverlayService.ACTION_CALL_STARTED },
        )
    }

    override fun onCallEnded() {
        // Plain startService — the service is already running and this only
        // asks it to tear itself down.
        context.startService(
            Intent(context, BubbleOverlayService::class.java)
                .apply { action = BubbleOverlayService.ACTION_CALL_ENDED },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OverlayControlModule {
    @Binds
    abstract fun bindOverlayController(impl: OverlayControllerImpl): OverlayController
}
