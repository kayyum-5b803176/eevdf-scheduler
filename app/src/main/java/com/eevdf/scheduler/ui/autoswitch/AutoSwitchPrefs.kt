package com.eevdf.scheduler.ui.autoswitch

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin helpers around the "auto_switch" SharedPreferences file.
 * Keeps key names in one place and avoids scattered magic strings.
 */
object AutoSwitchPrefs {

    private const val PREFS_NAME            = "auto_switch_prefs"
    private const val KEY_CALL_ENABLED      = "call_detection_enabled"
    private const val KEY_CALL_TASK_ID      = "call_task_id"
    private const val KEY_CALL_TASK_NAME    = "call_task_name"
    private const val KEY_LAST_CALL_STATE   = "last_call_state"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Call detection toggle ─────────────────────────────────────────────────

    fun isCallDetectionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CALL_ENABLED, false)

    fun setCallDetectionEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CALL_ENABLED, enabled).apply()
    }

    // ── Assigned call task ────────────────────────────────────────────────────

    fun getCallTaskId(ctx: Context): String? =
        prefs(ctx).getString(KEY_CALL_TASK_ID, null)

    fun getCallTaskName(ctx: Context): String? =
        prefs(ctx).getString(KEY_CALL_TASK_NAME, null)

    fun setCallTask(ctx: Context, taskId: String, taskName: String) {
        prefs(ctx).edit()
            .putString(KEY_CALL_TASK_ID,   taskId)
            .putString(KEY_CALL_TASK_NAME, taskName)
            .apply()
    }

    fun clearCallTask(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_CALL_TASK_ID)
            .remove(KEY_CALL_TASK_NAME)
            .apply()
    }

    // ── Last known phone state (persisted for transition detection) ───────────

    fun getLastCallState(ctx: Context): String? =
        prefs(ctx).getString(KEY_LAST_CALL_STATE, null)

    fun saveLastCallState(ctx: Context, state: String) {
        prefs(ctx).edit().putString(KEY_LAST_CALL_STATE, state).apply()
    }

    // ── Hover bubble ──────────────────────────────────────────────────────────

    private const val KEY_BUBBLE_ENABLED   = "bubble_enabled"
    private const val KEY_BUBBLE_DRAGGABLE = "bubble_draggable"
    private const val KEY_BUBBLE_APP_LIST  = "bubble_app_list"   // Set<String> of package names
    private const val KEY_BUBBLE_X         = "bubble_x"
    private const val KEY_BUBBLE_Y         = "bubble_y"

    fun isBubbleEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BUBBLE_ENABLED, false)

    fun setBubbleEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    /** true = draggable, false = fixed at saved position */
    fun isBubbleDraggable(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BUBBLE_DRAGGABLE, false)

    fun setBubbleDraggable(ctx: Context, draggable: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE_DRAGGABLE, draggable).apply()
    }

    /**
     * Package names of apps on which the bubble should appear during a call.
     * Empty set = show on ALL apps (except EEVDF itself).
     */
    fun getBubbleAppList(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_BUBBLE_APP_LIST, emptySet()) ?: emptySet()

    fun setBubbleAppList(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_BUBBLE_APP_LIST, packages).apply()
    }

    /** Last saved X position of the bubble window (-1 = use default). */
    fun getBubbleX(ctx: Context): Int = prefs(ctx).getInt(KEY_BUBBLE_X, -1)

    /** Last saved Y position of the bubble window (-1 = use default). */
    fun getBubbleY(ctx: Context): Int = prefs(ctx).getInt(KEY_BUBBLE_Y, -1)

    fun saveBubblePosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
    }
}
