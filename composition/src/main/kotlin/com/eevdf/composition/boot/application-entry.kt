package com.eevdf.composition.boot

import android.app.Application
import com.eevdf.capabilities.alarmringer.AlarmCommandHandler
import com.eevdf.capabilities.callautoswitch.OverlayCommandHandler
import com.eevdf.capabilities.featuretoggles.LogcatCrashReporter
import com.eevdf.capabilities.remindernotifier.AppForegroundTracker
import com.eevdf.capabilities.settingsstorage.state.DisplayPrefs
import com.eevdf.capabilities.taskstorage.logic.BackupCheckpointHandler
import com.eevdf.kernel.crashguard.CrashIsolation
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt composition root.
 *
 * `@HiltAndroidApp` triggers Hilt code generation, creating the
 * application-level dependency container that every `@AndroidEntryPoint`
 * (Activity / Service / Fragment / BroadcastReceiver) and `@HiltViewModel`
 * draws from. The bindings themselves live in one file next door:
 * `composition/capability-bindings.kt`.
 *
 * Was `app/SchedulerApplication.kt`. Renamed per rule 8 — the file name now
 * says what it does (this is where the application starts) rather than which
 * framework class it happens to extend.
 */
@HiltAndroidApp
class ApplicationEntry : Application() {

    /**
     * Requesting these at startup constructs each capability's bus subscriber,
     * which is what registers its subscriptions. They must exist before any
     * publisher can fire — see BackupCheckpointHandler's KDoc for why ordering
     * matters in the backup case specifically.
     *
     * This list, and `capability-bindings.kt`, are the only two places that
     * know which capabilities participate in the bus (rule 6).
     */
    @Inject lateinit var backupCheckpointHandler: BackupCheckpointHandler
    @Inject lateinit var alarmCommandHandler: AlarmCommandHandler
    @Inject lateinit var overlayCommandHandler: OverlayCommandHandler

    override fun onCreate() {
        super.onCreate()
        // Apply before any Activity is drawn.
        DisplayPrefs.applyDarkMode(this)
        // Route crash-guard-contained failures to logcat. The kernel contains
        // them; this decides what to do with the report.
        CrashIsolation.install(LogcatCrashReporter)
        // Needed by AlarmForegroundService to suppress the timer-expired
        // overlay while this app itself is already in the foreground.
        AppForegroundTracker.install(this)
    }
}
