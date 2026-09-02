package com.eevdf.platform.notification

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether the EEVDF app itself currently has any Activity started
 * (visible) — i.e. whether the app is in the foreground.
 *
 * Deliberately dependency-free: no `androidx.lifecycle:lifecycle-process`
 * artifact, no `UsageStatsManager` (that needs the special, user-granted
 * `PACKAGE_USAGE_STATS` permission and is already used elsewhere for a
 * different, best-effort purpose — see [AlarmForegroundService.getForegroundPackage]).
 * This is the same counting mechanism `ProcessLifecycleOwner` itself is built
 * on: count Activity starts/stops via [Application.ActivityLifecycleCallbacks].
 * A config change (e.g. rotation) destroys and recreates the Activity but does
 * NOT dip the count to zero in between, so it never produces a false
 * "went to background" blip.
 *
 * [install] must be called once, from `Application.onCreate`.
 */
object AppForegroundTracker {

    @Volatile
    private var startedActivityCount = 0

    /** True while at least one of this app's Activities is started (visible). */
    val isAppInForeground: Boolean
        get() = startedActivityCount > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
