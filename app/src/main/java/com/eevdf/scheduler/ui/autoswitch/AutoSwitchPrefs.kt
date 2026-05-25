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
}
