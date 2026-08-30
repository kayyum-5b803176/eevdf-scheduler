package com.eevdf.feature.task.group

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the last manually-selected task per group.
 *
 * WHY THIS EXISTS
 * ---------------
 * Queue tab Next used to rotate siblings alphabetically with no memory.
 * If a user always works on the same task inside a group, they had to
 * re-select it every session. Now each group remembers the last task the
 * user opened with the timer-icon button, and Next returns to it
 * automatically — until the user deliberately opens a different one.
 *
 * KEY SCHEME
 * ----------
 * "group_task_pref:<groupId>" → taskId
 *
 * Root-level tasks (parentId == null) are not covered here; they keep
 * the existing alphabetical rotation.
 *
 * TRIGGER RULE
 * ------------
 * Only a *different* task in the same group updates the preference.
 * Re-opening the same task is a no-op so the preference is stable.
 */
object GroupTaskPrefs {

    private const val PREFS_NAME = "eevdf_group_task_prefs"
    private const val KEY_PREFIX = "group_task_pref:"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records [taskId] as the preferred task for [groupId].
     * No-op when [taskId] is already the stored value.
     */
    fun set(context: Context, groupId: String, taskId: String) {
        val p = prefs(context)
        if (p.getString(KEY_PREFIX + groupId, null) == taskId) return
        p.edit().putString(KEY_PREFIX + groupId, taskId).apply()
    }

    /**
     * Returns the stored preferred task id for [groupId], or null if
     * none has been set yet.
     */
    fun get(context: Context, groupId: String): String? =
        prefs(context).getString(KEY_PREFIX + groupId, null)

    /** Clears the preference for [groupId]. Call when the group is deleted. */
    fun clear(context: Context, groupId: String) {
        prefs(context).edit().remove(KEY_PREFIX + groupId).apply()
    }
}
