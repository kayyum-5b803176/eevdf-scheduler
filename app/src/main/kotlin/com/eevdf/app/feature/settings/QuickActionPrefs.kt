package com.eevdf.app.feature.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin helpers around the "button_action_prefs" SharedPreferences file.
 * Keeps all Button Action preference keys in one place.
 */
object QuickActionPrefs {

    private const val PREFS_NAME            = "button_action_prefs"
    private const val KEY_QUICK_ACTION      = "quick_action_enabled"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Quick Action toggle ───────────────────────────────────────────────────

    /**
     * Returns true when the Quick Action floating button should be shown in
     * MainActivity. Default is off so the existing UI is unchanged until the
     * user explicitly enables the feature.
     */
    fun isQuickActionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_QUICK_ACTION, false)

    fun setQuickActionEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_QUICK_ACTION, enabled).apply()
    }
}
