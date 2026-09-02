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
 *
 * ── Why AlarmActivity is excluded from the count ─────────────────────────────
 *
 * This flag exists to answer one question: "is the user actively using the
 * app's normal UI right now?", so the alarm-expiry notification can skip
 * showing a redundant style on top of it. AlarmActivity — the full-screen
 * alarm overlay itself — is also an Activity of this app, so without this
 * exclusion, the overlay showing would flip this flag to true and could
 * suppress the very notification whose full-screen intent launched it, or a
 * second alarm racing shortly after the first is dismissed (this is exactly
 * what caused the reported "works once, then never shows full-screen again"
 * bug: the count could still read >0 from the first alarm's AlarmActivity at
 * the moment the second alarm's suppression decision was made). AlarmActivity
 * is intentionally referenced by string class name, not by import — `platform`
 * cannot and should not depend on `feature:alarm`; see AppRoutes.kt for the
 * same string-based decoupling rationale used elsewhere in this codebase.
 */
object AppForegroundTracker {

    private const val ALARM_ACTIVITY_CLASS_NAME = "com.eevdf.feature.alarm.AlarmActivity"

    @Volatile
    private var startedActivityCount = 0

    /** True while at least one of this app's Activities is started (visible). */
    val isAppInForeground: Boolean
        get() = startedActivityCount > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (isTracked(activity)) startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                if (isTracked(activity)) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isTracked(activity: Activity): Boolean =
        activity.javaClass.name != ALARM_ACTIVITY_CLASS_NAME
}
