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
public object AppRoutes {

    public const val MAIN: String        = "com.eevdf.feature.task.list.MainActivity"
    public const val ADD_TASK: String    = "com.eevdf.feature.task.addtask.AddTaskActivity"
    public const val STATS: String       = "com.eevdf.feature.stats.StatsActivity"
    public const val SETTINGS: String    = "com.eevdf.feature.settings.SettingsActivity"
    public const val BACKUP: String      = "com.eevdf.feature.backup.DataBackupActivity"
    public const val AUTO_SWITCH: String = "com.eevdf.feature.autoswitch.AutoSwitchActivity"
    public const val SYNC: String        = "com.eevdf.feature.sync.MultiUserSyncActivity"
    public const val LINKS: String       = "com.eevdf.feature.links.LinksActivity"

    /** Every route, for the resolution test. Keep in step with the constants. */
    public val ALL_ROUTES: List<String> = listOf(MAIN, ADD_TASK, STATS, SETTINGS, BACKUP, AUTO_SWITCH, SYNC, LINKS)

    /**
     * Builds an explicit Intent for [className] within this application.
     *
     * Explicit — `setClassName` with the app's own package — so this is not an
     * implicit intent and cannot be intercepted by another app.
     */
    public fun intent(context: Context, className: String): Intent =
        Intent().setClassName(context.packageName, className)

    public fun main(context: Context): Intent = intent(context, MAIN)
    public fun addTask(context: Context): Intent = intent(context, ADD_TASK)
    public fun stats(context: Context): Intent = intent(context, STATS)
    public fun settings(context: Context): Intent = intent(context, SETTINGS)
    public fun backup(context: Context): Intent = intent(context, BACKUP)
    public fun autoSwitch(context: Context): Intent = intent(context, AUTO_SWITCH)
    public fun sync(context: Context): Intent = intent(context, SYNC)
    public fun links(context: Context): Intent = intent(context, LINKS)
}
