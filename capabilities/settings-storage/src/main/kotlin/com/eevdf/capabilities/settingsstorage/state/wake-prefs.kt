package com.eevdf.capabilities.settingsstorage.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences of the scheduled-wake feature (`task-wake`), set on Settings →
 * platform → alarm.
 *
 *   • Re-arm after reboot — ON (default): the boot receiver puts every still-future
 *     wake back into AlarmManager, so a wake survives a restart of the phone. OFF:
 *     nothing is re-armed at boot; the wakes are re-armed the next time the app is
 *     opened. Missed wakes (their time passed while the phone was off) are reported
 *     either way — this switch never silences them.
 */
object WakePrefs {

    private const val PREFS_NAME = "wake_prefs"
    private const val KEY_REARM_ON_BOOT = "rearm_on_boot"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isRearmOnBootEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_REARM_ON_BOOT, true)

    fun setRearmOnBootEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REARM_ON_BOOT, enabled).apply()
    }
}
