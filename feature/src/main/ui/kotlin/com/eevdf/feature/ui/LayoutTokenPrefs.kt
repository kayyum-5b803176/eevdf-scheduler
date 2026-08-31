package com.eevdf.feature.ui

import android.content.Context
import android.content.SharedPreferences
import com.eevdf.feature.shared.prefs.DisplayPrefs

/**
 * Preference storage for the layout-token scales that don't already have a
 * home. [DesignTokens.paddingScale] deliberately reuses
 * [DisplayPrefs.getCardHeightScale] rather than duplicating it here — that
 * preference already exists, is already wired to a Display-settings control,
 * and this file introducing a second, independent "padding scale" preference
 * would immediately create exactly the kind of divergence [DesignTokens]'s
 * own doc comment describes fixing.
 *
 * Same SharedPreferences file as [DisplayPrefs] ("display_settings_prefs") —
 * these are the same category of setting from the user's point of view, they
 * just didn't exist as preferences until this file.
 */
public object LayoutTokenPrefs {

    private const val PREFS_NAME             = "display_settings_prefs"
    private const val KEY_MARGIN_SCALE       = "layout_margin_scale"
    private const val KEY_TEXT_SCALE         = "layout_text_scale"
    private const val KEY_CORNER_RADIUS_SCALE = "layout_corner_radius_scale"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    public fun getMarginScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MARGIN_SCALE, DesignTokens.DEFAULT.marginScale).coerceIn(1, 5)

    public fun setMarginScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_MARGIN_SCALE, scale.coerceIn(1, 5)).apply()
    }

    public fun getTextScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TEXT_SCALE, DesignTokens.DEFAULT.textScale).coerceIn(1, 5)

    public fun setTextScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_TEXT_SCALE, scale.coerceIn(1, 5)).apply()
    }

    public fun getCornerRadiusScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CORNER_RADIUS_SCALE, DesignTokens.DEFAULT.cornerRadiusScale).coerceIn(1, 5)

    public fun setCornerRadiusScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_CORNER_RADIUS_SCALE, scale.coerceIn(1, 5)).apply()
    }

    /**
     * Reads every layout-token preference and returns the resolved
     * [DesignTokens] for the current settings. This is the ONE place a screen
     * should call to find out "what are the current layout tokens" — nothing
     * should read the four individual preference getters above directly
     * outside this file and the Settings screen that lets the user change them.
     */
    public fun current(ctx: Context): DesignTokens = DesignTokens(
        paddingScale = DisplayPrefs.getCardHeightScale(ctx),
        marginScale = getMarginScale(ctx),
        textScale = getTextScale(ctx),
        cornerRadiusScale = getCornerRadiusScale(ctx),
    )
}
