package com.eevdf.app.feature.task

import android.content.Context

/**
 * Persists the last [MAX] parent-group IDs the user selected in the Add/Edit
 * Task form.  Used by [GroupPickerDialog] to populate the "Recent" section.
 *
 * Storage: "recent_group_prefs" SharedPreferences, single key "recent_ids"
 * holding a comma-joined list (most-recent first).
 */
object RecentGroupPrefs {

    private const val PREFS_NAME = "recent_group_prefs"
    private const val KEY_IDS    = "recent_ids"
    const val MAX                = 8

    /** Returns up to [MAX] group IDs, most-recent first. */
    fun getIds(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IDS, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(",").filter { it.isNotBlank() }.take(MAX)
    }

    /**
     * Prepends [groupId] to the recent list, deduplicates, trims to [MAX],
     * and persists.  No-op if [groupId] is null (= "None / root").
     */
    fun push(ctx: Context, groupId: String?) {
        groupId ?: return
        val updated = (listOf(groupId) + getIds(ctx))
            .distinct()
            .take(MAX)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IDS, updated.joinToString(","))
            .apply()
    }
}
