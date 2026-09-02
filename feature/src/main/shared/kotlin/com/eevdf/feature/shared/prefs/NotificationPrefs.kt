package com.eevdf.feature.shared.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin helpers around the "notification_prefs" SharedPreferences file.
 *
 * Replaces the old combined "Overlay Intent" pref (lock-only + app-list baked
 * into one on/off feature). Split into two independent controls that map onto
 * the two notification styles a timer expiry can use:
 *
 *   • Lock Screen Overlay — governs the FULL-SCREEN style only (shown while
 *     the device is locked). Does not affect the banner.
 *   • Exclude App         — governs the BANNER style only (shown while the
 *     device is unlocked). Does not affect the lock-screen overlay — a
 *     listed app never suppresses the lock-screen overlay, since the device
 *     being locked means no app is meaningfully "in front" anyway.
 *
 * Both are irrelevant while the EEVDF app itself is foreground: that case is
 * governed unconditionally by [com.eevdf.platform.notification.AppForegroundTracker]
 * and always suppresses both styles, regardless of these prefs.
 */
object NotificationPrefs {

    private const val PREFS_NAME = "notification_prefs"

    private const val KEY_LOCK_SCREEN_OVERLAY_ENABLED = "lock_screen_overlay_enabled"
    private const val KEY_EXCLUDE_APP_LIST             = "exclude_app_list" // Set<String> pkgs

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Lock screen overlay ────────────────────────────────────────────────────
    //
    // ON  → while the device is locked, a timer expiry shows the full-screen
    //       overlay (AlarmActivity), same as the AOSP Clock app.
    // OFF → a timer expiry always shows the banner notification, even while
    //       the device is locked.

    fun isLockScreenOverlayEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOCK_SCREEN_OVERLAY_ENABLED, false)

    fun setLockScreenOverlayEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCK_SCREEN_OVERLAY_ENABLED, enabled).apply()
    }

    // ── Exclude app (banner only) ──────────────────────────────────────────────
    //
    // The banner notification is not shown while one of these apps is in the
    // foreground. Needs Usage Access to detect the foreground app.

    fun getExcludeAppList(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_EXCLUDE_APP_LIST, emptySet()) ?: emptySet()

    fun setExcludeAppList(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_EXCLUDE_APP_LIST, packages).apply()
    }

    fun isAppExcluded(ctx: Context, foregroundPkg: String?): Boolean =
        !foregroundPkg.isNullOrBlank() && foregroundPkg in getExcludeAppList(ctx)
}
