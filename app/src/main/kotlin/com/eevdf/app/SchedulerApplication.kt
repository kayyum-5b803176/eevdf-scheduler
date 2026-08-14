package com.eevdf.app

import android.app.Application
import com.eevdf.app.core.LogcatCrashReporter
import com.eevdf.shared.CrashIsolation
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

    override fun onCreate() {
        super.onCreate()
        // Install the sink for feature-level contained failures before any
        // feature can produce one. See com.eevdf.shared.safeFeature.
        CrashIsolation.install(LogcatCrashReporter)
    }
}
