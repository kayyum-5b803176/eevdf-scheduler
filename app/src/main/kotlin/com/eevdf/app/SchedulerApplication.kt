package com.eevdf.app

import android.app.Application
import com.eevdf.capabilities.featuretoggles.LogcatCrashReporter
import com.eevdf.capabilities.settingsstorage.state.DisplayPrefs
import com.eevdf.capabilities.remindernotifier.AppForegroundTracker
import com.eevdf.capabilities.alarmringer.AlarmCommandHandler
import com.eevdf.capabilities.callautoswitch.OverlayCommandHandler
import com.eevdf.capabilities.taskstorage.logic.BackupCheckpointHandler
import com.eevdf.kernel.crashguard.CrashIsolation
import javax.inject.Inject
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt composition root.
 *
 * `@HiltAndroidApp` triggers Hilt code generation, creating the application-level
 * dependency container that every `@AndroidEntryPoint` (Activity / Service /
 * Fragment / BroadcastReceiver) and `@HiltViewModel` draws from.
 *
 * Migration note: this replaces the former empty `EevdfApp`. ViewModels and
 * services no longer reach for `TaskDatabase.getDatabase(...)` directly; the
 * database, DAOs, repositories, scheduler services and platform services are all
 * provided through the Hilt modules in `com.eevdf.app.di`.
 */
@HiltAndroidApp
class SchedulerApplication : Application() {

    /**
     * Requesting this at startup constructs task-storage's backup subscriber,
     * which registers its bus subscriptions. It must exist before any
     * backup-restore screen can publish to those topics — see
     * BackupCheckpointHandler's KDoc for why the ordering matters.
     */
    @Inject lateinit var backupCheckpointHandler: BackupCheckpointHandler

    /** Constructing these registers their bus subscriptions at startup — see
     *  AlarmCommandHandler / OverlayCommandHandler KDoc. */
    @Inject lateinit var alarmCommandHandler: AlarmCommandHandler
    @Inject lateinit var overlayCommandHandler: OverlayCommandHandler


    override fun onCreate() {
        super.onCreate()
        // Apply before any Activity is drawn.
        DisplayPrefs.applyDarkMode(this)
        CrashIsolation.install(LogcatCrashReporter)
        // Needed by AlarmForegroundService to suppress the timer-expired overlay
        // while this app itself is already in the foreground.
        AppForegroundTracker.install(this)
    }
}
