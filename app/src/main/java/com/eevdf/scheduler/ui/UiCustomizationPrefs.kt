package com.eevdf.scheduler.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin helpers around the "ui_customization" SharedPreferences file.
 * Keeps all UI Customization preference keys in one place.
 */
object UiCustomizationPrefs {

    private const val PREFS_NAME               = "ui_customization_prefs"
    private const val KEY_CARD_HEIGHT_SCALE    = "card_height_scale"
    private const val KEY_AUTO_ADJUST_ENABLED  = "auto_adjust_enabled"
    private const val KEY_SIMPLE_MODE_ENABLED  = "simple_mode_enabled"

    /** Default scale = 5 → current (original) card size. */
    const val DEFAULT_CARD_HEIGHT_SCALE = 5

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Card height scale (1 = smallest … 5 = default full size) ─────────────

    fun getCardHeightScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CARD_HEIGHT_SCALE, DEFAULT_CARD_HEIGHT_SCALE)
            .coerceIn(1, 5)

    fun setCardHeightScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_CARD_HEIGHT_SCALE, scale.coerceIn(1, 5)).apply()
    }

    // ── Auto-adjust (hide non-essential stats when floating / PiP) ───────────

    fun isAutoAdjustEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_ADJUST_ENABLED, false)

    fun setAutoAdjustEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_ADJUST_ENABLED, enabled).apply()
    }

    // ── Simple mode (collapse stats on non-selected cards) ───────────────────

    fun isSimpleModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SIMPLE_MODE_ENABLED, false)

    fun setSimpleModeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SIMPLE_MODE_ENABLED, enabled).apply()
    }
}
