package com.eevdf.capabilities.settingsstorage.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences for entries that live on more than one axis (the "axis" feature).
 *
 *  - **Hide new by default** (ON out of the box): a new entry is not copied anywhere by itself and a new place starts
 *    empty; copies that already exist are never removed by this. Turning it OFF makes every new entry appear in every
 *    place automatically, as it did before.
 *  - **Ask before copying** (ON out of the box): when a new entry or a new place is saved, the screen asks whether to copy
 *    it. OFF means "don't copy" is assumed and nothing is asked.
 */
object AxisPrefs {

    private const val PREFS_NAME            = "axis_settings_prefs"
    private const val KEY_HIDE_NEW_DEFAULT  = "hide_new_by_default"
    private const val KEY_ASK_BEFORE_COPY   = "ask_before_copy"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHideNewByDefault(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_NEW_DEFAULT, true)

    fun setHideNewByDefault(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_NEW_DEFAULT, on).apply()
    }

    fun isAskBeforeCopy(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ASK_BEFORE_COPY, true)

    fun setAskBeforeCopy(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ASK_BEFORE_COPY, on).apply()
    }
}
