package com.eevdf.capabilities.appforeground

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks whether this app itself currently has any Activity started
 * (visible) — i.e. whether the app is in the foreground — and publishes
 * [Topics.APP_FOREGROUND_CHANGED] on every 0↔1 transition. Reached ONLY via
 * that topic now: there is deliberately no direct-read property here (no
 * `isAppInForeground` val) — every consumer calls
 * `bus.getLast(Topics.APP_FOREGROUND_CHANGED) ?: false`, so this capability
 * never grows a second, direct-call access path alongside the bus one.
 *
 * Deliberately dependency-free otherwise: no `androidx.lifecycle:
 * lifecycle-process` artifact, no `UsageStatsManager`. Same counting
 * mechanism `ProcessLifecycleOwner` itself is built on: count Activity
 * starts/stops via [Application.ActivityLifecycleCallbacks]. A config change
 * (e.g. rotation) destroys and recreates an Activity but does NOT dip the
 * aggregate count to zero in between (the framework starts the recreated
 * instance before stopping the old one), so a single rotating Activity never
 * produces a false "went to background" blip here.
 *
 * Genuinely generic — zero domain knowledge, no per-Activity exceptions.
 * See this capability's manifest.kt for why that's a deliberate, permanent
 * property, not an oversight: a caller with a special case (e.g. alarm-ringer
 * not wanting its own full-screen alarm overlay counted) handles that
 * locally instead of this class growing an exception per caller.
 *
 * [install] must be called once, from `Application.onCreate`.
 */
class AppForegroundTracker(
    private val bus: EventBus,
) {
    // ActivityLifecycleCallbacks methods aren't suspend; publish is. Lives for
    // the app's whole process lifetime — never cancelled, same pattern as
    // TimerEngine's publishScope.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    private var startedActivityCount = 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = startedActivityCount == 0
                startedActivityCount++
                if (wasBackground) publish(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) publish(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun publish(foreground: Boolean) {
        scope.launch { bus.publish(Topics.APP_FOREGROUND_CHANGED, foreground, CAPABILITY_ID) }
    }

    private companion object {
        const val CAPABILITY_ID = "app-foreground"
    }
}
