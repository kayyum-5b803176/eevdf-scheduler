package com.eevdf.feature.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Preference storage for every layout-token scale. `paddingScale` used to
 * delegate to `DisplayPrefs.getCardHeightScale` — that separate "Card
 * Height" slider on Display Settings has since been removed, and this file
 * now owns padding as its own preference, the same way it already owns
 * margin/text/corner-radius. One padding scale, one owner, one slider (on
 * the Layout demo page's "scale" tab) — not two independent preferences
 * that happened to agree.
 */
public object LayoutTokenPrefs {

    private const val PREFS_NAME              = "display_settings_prefs"
    private const val KEY_PADDING_SCALE       = "layout_padding_scale"
    private const val KEY_MARGIN_SCALE        = "layout_margin_scale"
    private const val KEY_TEXT_SCALE          = "layout_text_scale"
    private const val KEY_CORNER_RADIUS_SCALE = "layout_corner_radius_scale"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Each getter's fallback (used only when the key has never been written —
     * a fresh install, or data explicitly cleared) is [DesignTokens.DEFAULT],
     * currently 4 for every scale. A value the user has actually set, however
     * it got there, is never overwritten — this is the same "remember what's
     * stored, default only when truly absent" behavior every other
     * preference in this app already has.
     */
    public fun getPaddingScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_PADDING_SCALE, DesignTokens.DEFAULT.paddingScale).coerceIn(1, 7)

    public fun setPaddingScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_PADDING_SCALE, scale.coerceIn(1, 7)).apply()
    }

    public fun getMarginScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MARGIN_SCALE, DesignTokens.DEFAULT.marginScale).coerceIn(1, 7)

    public fun setMarginScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_MARGIN_SCALE, scale.coerceIn(1, 7)).apply()
    }

    public fun getTextScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TEXT_SCALE, DesignTokens.DEFAULT.textScale).coerceIn(1, 7)

    public fun setTextScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_TEXT_SCALE, scale.coerceIn(1, 7)).apply()
    }

    public fun getCornerRadiusScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CORNER_RADIUS_SCALE, DesignTokens.DEFAULT.cornerRadiusScale).coerceIn(1, 7)

    public fun setCornerRadiusScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_CORNER_RADIUS_SCALE, scale.coerceIn(1, 7)).apply()
    }

    /**
     * Reads every layout-token preference and returns the resolved
     * [DesignTokens] for the current settings. This is the ONE place a screen
     * should call to find out "what are the current layout tokens" — nothing
     * should read the individual preference getters above directly outside
     * this file and the Settings screens that let the user change them.
     */
    public fun current(ctx: Context): DesignTokens = DesignTokens(
        paddingScale = getPaddingScale(ctx),
        marginScale = getMarginScale(ctx),
        textScale = getTextScale(ctx),
        cornerRadiusScale = getCornerRadiusScale(ctx),
    )
}
