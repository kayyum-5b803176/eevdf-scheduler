package com.eevdf.app.boot

import android.app.Application
import com.eevdf.capabilities.alarmringer.AlarmCommandHandler
import com.eevdf.capabilities.alarmringer.TimerExpiryHandler
import com.eevdf.capabilities.callautoswitch.OverlayCommandHandler
import com.eevdf.capabilities.featuretoggles.LogcatCrashReporter
import com.eevdf.capabilities.feedbackcues.AlarmCueHandler
import com.eevdf.capabilities.multidevicesync.logic.TaskSavedSyncHandler
import com.eevdf.capabilities.remindernotifier.AlarmDeliveryHandler
import com.eevdf.capabilities.remindernotifier.AppForegroundTracker
import com.eevdf.capabilities.runhistory.TaskSavedCompactionHandler
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
 * draws from. The bindings themselves live in `:composition`'s
 * `capability-bindings.kt` -- a separate module is fine for a Hilt @Module,
 * only the @HiltAndroidApp entry point below has the same-module restriction.
 *
 * Was `app/SchedulerApplication.kt`, then briefly lived at
 * `composition/boot/application-entry.kt` — moved back to `:app` because Hilt
 * requires the `@HiltAndroidApp` class to be compiled inside the actual
 * `com.android.application` module, not a library module composition depends
 * on. `composition/capability-bindings.kt` (a plain `@Module`, not the entry
 * point itself) has no such restriction and stays where it is.
 */
@HiltAndroidApp
class ApplicationEntry : Application() {

    /**
     * Requesting these at startup constructs each capability's bus subscriber,
     * which is what registers its subscriptions. They must exist before any
     * publisher can fire — see BackupCheckpointHandler's KDoc for why ordering
     * matters in the backup case specifically. The same ordering requirement
     * applies to [alarmCueHandler]/[alarmDeliveryHandler]: `Application.onCreate()`
     * always runs before any `Service` (including `AlarmForegroundService`,
     * `alarm.ringing`'s publisher) is created in this process, so listing them
     * here guarantees they exist before the first alarm can ever ring.
     *
     * This list, and `capability-bindings.kt`, are the only two places that
     * know which capabilities participate in the bus (rule 6).
     */
    @Inject lateinit var backupCheckpointHandler: BackupCheckpointHandler
    @Inject lateinit var alarmCommandHandler: AlarmCommandHandler
    @Inject lateinit var overlayCommandHandler: OverlayCommandHandler
    @Inject lateinit var taskSavedSyncHandler: TaskSavedSyncHandler
    @Inject lateinit var taskSavedCompactionHandler: TaskSavedCompactionHandler
    @Inject lateinit var alarmCueHandler: AlarmCueHandler
    @Inject lateinit var alarmDeliveryHandler: AlarmDeliveryHandler
    @Inject lateinit var timerExpiryHandler: TimerExpiryHandler

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
