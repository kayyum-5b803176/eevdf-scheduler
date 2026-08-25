package com.eevdf.app.core.settings

import android.content.SharedPreferences
import android.util.Log

/**
 * Central logger for every preference change across all settings pages.
 *
 * Register once in Application.onCreate() or in a base settings Activity.
 * Every write to SharedPreferences fires through this listener, which
 * resolves the owning [SettingsPage] and logs the change with full context.
 *
 * ## Setup
 * ```kotlin
 * val logger = SettingsChangeLogger(prefs)
 * logger.registerPage(uiCustomizationActivity)
 * ```
 *
 * ## Log format
 * ```
 * [SETTINGS] settings.platform.display :: pref_dark_mode = "dark" (was "system")
 * ```
 */
class SettingsChangeLogger(
    private val prefs: SharedPreferences
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val pages = mutableListOf<SettingsPage>()
    private val snapshot = mutableMapOf<String, String?>()

    init {
        // Snapshot current values so we can report old → new
        prefs.all.forEach { (key, value) ->
            snapshot[key] = value?.toString()
        }
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    /** Register a page so its keys can be attributed. */
    fun registerPage(page: SettingsPage) {
        pages.add(page)
    }

    /** Unregister a page (call in onDestroy). */
    fun unregisterPage(page: SettingsPage) {
        pages.remove(page)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == null) return

        val newValue = prefs.all[key]?.toString()
        val oldValue = snapshot[key]

        // Find owning page
        val owner = pages.firstOrNull { key in it.getTrackedKeys() }
        val pageId = owner?.pageId ?: "unknown"

        Log.i(TAG, "[$pageId] $key = \"$newValue\" (was \"$oldValue\")")

        // Update snapshot
        snapshot[key] = newValue
    }

    fun dispose() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        pages.clear()
        snapshot.clear()
    }

    companion object {
        private const val TAG = "SETTINGS"
    }
}
