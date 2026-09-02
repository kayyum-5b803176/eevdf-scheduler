package com.eevdf.platform.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Single shared source of truth for every permission/capability check this
 * app relies on for reliable alarm delivery. Previously these checks were
 * duplicated ad hoc inside settings screens (each one re-implementing its own
 * AppOpsManager/PowerManager calls). Now there is exactly one place that
 * knows how to answer "is X granted?" — the Permissions page renders from it,
 * and anything else that needs to know (e.g. the service, for logging or
 * future adaptive behavior) reads the same answer, so the two can never
 * silently disagree.
 *
 * Every function here is a plain, side-effect-free read of current system
 * state — safe to call from anywhere, any time, no caching.
 */
object AlarmReliabilityChecker {

    /** POST_NOTIFICATIONS — runtime permission on Android 13+, normal (always granted) before. */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * "Full screen intents" special access — Android 14+. Without it, a
     * full-screen-intent notification silently downgrades to a normal
     * notification, even while the device is locked.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    /**
     * "Alarms & reminders" special access — Android 12+. Without it, exact
     * alarms silently become inexact, so the timer can fire late.
     */
    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * Battery optimization exemption ("Unrestricted"). While "Optimized",
     * the system can throttle notification alerting — including full-screen
     * launches — especially on repeated firings close together.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Usage Access special access — needed to read the foreground app for
     * the Exclude App feature. Without it, Exclude App can never match.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** "Display over other apps" — used by the bubble/auto-switch overlay feature. */
    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
}
