package com.eevdf.contract.nav

import android.content.Context
import android.content.Intent

/**
 * The single place where one screen learns how to reach another.
 *
 * WHY STRINGS INSTEAD OF `Activity::class.java`
 * ---------------------------------------------
 * A direct class reference is a compile-time dependency. `MainActivity` knowing
 * `StatsActivity::class.java` is exactly the edge that stops `:feature:task`
 * and `:feature:stats` from becoming separate Gradle modules — a module cannot
 * depend on a sibling it is not allowed to depend on.
 *
 * Resolving by class NAME breaks that link. Every screen depends on this one
 * object; no screen depends on another screen. When Phase 2 promotes the
 * feature packages to real modules, navigation keeps working with no changes.
 *
 * THE TRADE-OFF, AND HOW IT IS COVERED
 * ------------------------------------
 * Strings are not checked by the compiler, so renaming or moving an Activity
 * would fail at runtime instead of at build time. `AppRoutesTest` closes that
 * hole: it resolves every constant below with `Class.forName` and asserts each
 * one is a real Activity subclass. Rename a screen without updating this file
 * and the unit test fails — same feedback the compiler would have given, one
 * test run later.
 *
 * ADDING A SCREEN
 * ---------------
 * Add a constant, add an `Intent` factory, add it to [ALL_ROUTES]. That is the
 * only shared file a new screen touches.
 */
object AppRoutes {

    const val MAIN        = "com.eevdf.feature.task.list.MainActivity"
    const val STATS       = "com.eevdf.feature.stats.StatsActivity"
    const val SETTINGS    = "com.eevdf.feature.settings.SettingsActivity"
    const val BACKUP      = "com.eevdf.feature.backup.DataBackupActivity"
    const val AUTO_SWITCH = "com.eevdf.feature.autoswitch.AutoSwitchActivity"
    const val SYNC        = "com.eevdf.feature.sync.MultiUserSyncActivity"

    /** Every route, for the resolution test. Keep in step with the constants. */
    val ALL_ROUTES: List<String> = listOf(MAIN, STATS, SETTINGS, BACKUP, AUTO_SWITCH, SYNC)

    /**
     * Builds an explicit Intent for [className] within this application.
     *
     * Explicit — `setClassName` with the app's own package — so this is not an
     * implicit intent and cannot be intercepted by another app.
     */
    fun intent(context: Context, className: String): Intent =
        Intent().setClassName(context.packageName, className)

    fun main(context: Context): Intent = intent(context, MAIN)
    fun stats(context: Context): Intent = intent(context, STATS)
    fun settings(context: Context): Intent = intent(context, SETTINGS)
    fun backup(context: Context): Intent = intent(context, BACKUP)
    fun autoSwitch(context: Context): Intent = intent(context, AUTO_SWITCH)
    fun sync(context: Context): Intent = intent(context, SYNC)
}
