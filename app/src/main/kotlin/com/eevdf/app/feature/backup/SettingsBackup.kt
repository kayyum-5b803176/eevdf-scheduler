package com.eevdf.app.feature.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs up and restores ALL app settings by dumping the full contents of the
 * settings-bearing SharedPreferences files generically.
 *
 * Why generic (not a hand-listed key set): the previous backup hard-coded three
 * boolean settings, so every other setting — and every setting added later —
 * was silently dropped on restore. Here we serialize the entire `prefs.all`
 * map of each settings file with a type tag, so any current OR future setting
 * is captured automatically with no further changes to this class.
 *
 * Only true *settings* files are included. Transient runtime state is
 * deliberately excluded (restoring it would be wrong, not just useless):
 *   - "call_switch_state"     — live phone-call switch state
 *   - "eevdf_alarm_state_v2"  — live alarm-ringing state
 *   - "run_log_prefs"         — internal compaction bookkeeping
 */
object SettingsBackup {

    private const val VERSION = 1

    /** SharedPreferences files that hold user settings. Extend here if more are added. */
    private val SETTINGS_FILES = listOf(
        "eevdf_prefs",            // main settings + sound/vibration/profile + view prefs
        "auto_switch_prefs",      // Auto-Switch feature settings
        "ui_customization_prefs", // UI customization settings
    )

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("settingsVersion", VERSION)

        val files = JSONObject()
        for (name in SETTINGS_FILES) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((key, value) in prefs.all) {
                if (value == null) continue
                entries.put(key, encode(value))
            }
            files.put(name, entries)
        }
        root.put("prefs", files)
        return root.toString(2)
    }

    /** Encodes a value with a type tag so it round-trips to the exact prefs type. */
    private fun encode(value: Any): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> { put("t", "b"); put("v", value) }
            is Int     -> { put("t", "i"); put("v", value) }
            is Long    -> { put("t", "l"); put("v", value) }
            is Float   -> { put("t", "f"); put("v", value.toDouble()) }
            is String  -> { put("t", "s"); put("v", value) }
            is Set<*>  -> {
                put("t", "ss")
                put("v", JSONArray().apply { value.forEach { put(it.toString()) } })
            }
            else -> { put("t", "s"); put("v", value.toString()) } // defensive fallback
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Restores every backed-up setting. Each settings file is cleared first so
     * the result is an exact restore of the backed-up state (not a merge with
     * whatever the current install happens to have). Uses commit() so values are
     * persisted before the caller restarts the process.
     */
    fun importJson(context: Context, json: String) {
        val root = JSONObject(json)
        val files = root.optJSONObject("prefs") ?: return
        val names = files.keys()
        while (names.hasNext()) {
            val name = names.next()
            // Only restore into known settings files (ignore anything unexpected).
            if (name !in SETTINGS_FILES) continue
            val entries = files.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val cell = entries.optJSONObject(key) ?: continue
                when (cell.optString("t")) {
                    "b"  -> editor.putBoolean(key, cell.optBoolean("v"))
                    "i"  -> editor.putInt(key, cell.optInt("v"))
                    "l"  -> editor.putLong(key, cell.optLong("v"))
                    "f"  -> editor.putFloat(key, cell.optDouble("v").toFloat())
                    "s"  -> editor.putString(key, cell.optString("v"))
                    "ss" -> {
                        val arr = cell.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>(arr.length())
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.commit()
        }
    }
}
