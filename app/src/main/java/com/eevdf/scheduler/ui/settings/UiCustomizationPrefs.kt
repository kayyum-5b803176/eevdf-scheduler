package com.eevdf.scheduler.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * Thin helpers around the "ui_customization" SharedPreferences file.
 * Keeps all UI Customization preference keys in one place.
 */
object UiCustomizationPrefs {

    private const val PREFS_NAME               = "ui_customization_prefs"
    private const val KEY_CARD_HEIGHT_SCALE    = "card_height_scale"
    private const val KEY_AUTO_ADJUST_ENABLED  = "auto_adjust_enabled"
    private const val KEY_SIMPLE_MODE_ENABLED  = "simple_mode_enabled"
    private const val KEY_UNIT_FORMAT_ENABLED  = "unit_format_enabled"

    // ── Window Calibrate profile keys ─────────────────────────────────────────
    private const val KEY_CAL_FLOAT_W  = "cal_float_w"
    private const val KEY_CAL_FLOAT_H  = "cal_float_h"
    private const val KEY_CAL_NORMAL_W = "cal_normal_w"
    private const val KEY_CAL_NORMAL_H = "cal_normal_h"
    private const val KEY_CAL_MINI_W   = "cal_mini_w"
    private const val KEY_CAL_MINI_H   = "cal_mini_h"

    /** Default scale = 5 → current (original) card size. */
    const val DEFAULT_CARD_HEIGHT_SCALE = 5

    /** Sentinel stored when a profile has not been calibrated yet. */
    const val CALIBRATE_NOT_SET = -1

    /**
     * Named window calibration profiles.
     *   FLOAT / MINI → compact mode ON (stat rows collapsed)
     *   NORMAL       → compact mode OFF (override multi-window fallback)
     */
    enum class CalibrateProfile { FLOAT, NORMAL, MINI }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Card height scale (1 = smallest … 5 = default full size) ─────────────

    fun getCardHeightScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CARD_HEIGHT_SCALE, DEFAULT_CARD_HEIGHT_SCALE).coerceIn(1, 5)

    fun setCardHeightScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_CARD_HEIGHT_SCALE, scale.coerceIn(1, 5)).apply()
    }

    // ── Window Calibrate toggle ───────────────────────────────────────────────

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

    // ── Unit format (SI suffixes on VRT / VDL / Runs / TRT) ─────────────────

    fun isUnitFormatEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_UNIT_FORMAT_ENABLED, false)

    fun setUnitFormatEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UNIT_FORMAT_ENABLED, enabled).apply()
    }

    // ── Window Calibrate profile storage ─────────────────────────────────────

    private fun keyW(p: CalibrateProfile) = when (p) {
        CalibrateProfile.FLOAT  -> KEY_CAL_FLOAT_W
        CalibrateProfile.NORMAL -> KEY_CAL_NORMAL_W
        CalibrateProfile.MINI   -> KEY_CAL_MINI_W
    }

    private fun keyH(p: CalibrateProfile) = when (p) {
        CalibrateProfile.FLOAT  -> KEY_CAL_FLOAT_H
        CalibrateProfile.NORMAL -> KEY_CAL_NORMAL_H
        CalibrateProfile.MINI   -> KEY_CAL_MINI_H
    }

    fun getCalibrateW(ctx: Context, p: CalibrateProfile): Int =
        prefs(ctx).getInt(keyW(p), CALIBRATE_NOT_SET)

    fun getCalibrateH(ctx: Context, p: CalibrateProfile): Int =
        prefs(ctx).getInt(keyH(p), CALIBRATE_NOT_SET)

    fun setCalibrate(ctx: Context, p: CalibrateProfile, wDp: Int, hDp: Int) {
        prefs(ctx).edit()
            .putInt(keyW(p), wDp)
            .putInt(keyH(p), hDp)
            .apply()
    }

    fun clearCalibrate(ctx: Context, p: CalibrateProfile) {
        prefs(ctx).edit()
            .putInt(keyW(p), CALIBRATE_NOT_SET)
            .putInt(keyH(p), CALIBRATE_NOT_SET)
            .apply()
    }

    /**
     * Returns the first [CalibrateProfile] whose saved dimensions are within
     * [toleranceDp] of the given window size, or null if no profile matches.
     * FLOAT is checked before NORMAL before MINI so the tightest semantic
     * intent wins when profiles are close to each other.
     */
    fun matchProfile(
        ctx: Context,
        widthDp: Int,
        heightDp: Int,
        toleranceDp: Int = 50
    ): CalibrateProfile? {
        for (p in CalibrateProfile.values()) {
            val w = getCalibrateW(ctx, p)
            val h = getCalibrateH(ctx, p)
            if (w == CALIBRATE_NOT_SET || h == CALIBRATE_NOT_SET) continue
            if (abs(widthDp - w) <= toleranceDp && abs(heightDp - h) <= toleranceDp) return p
        }
        return null
    }

    /**
     * Returns true if [p] should trigger compact (row-collapse) mode.
     * FLOAT and MINI → compact; NORMAL → full layout.
     */
    fun isCompactProfile(p: CalibrateProfile): Boolean = p != CalibrateProfile.NORMAL
}
