package com.eevdf.app.core.settings

/**
 * Contract for every page that contains user-modifiable settings.
 *
 * Every Activity or Fragment hosting settings cards implements this interface.
 * [SettingsChangeLogger] uses it to automatically record which page triggered
 * a preference change without per-page wiring.
 *
 * ## Example
 * ```kotlin
 * class UiCustomizationActivity : AppCompatActivity(), SettingsPage {
 *     override val pageId    = "settings.platform.display"
 *     override val pageTitle = "display"
 *     override fun getTrackedKeys() = listOf(
 *         "pref_dark_mode", "pref_simple_mode", "pref_si_format"
 *     )
 * }
 * ```
 */
interface SettingsPage {

    /** Dot-path identifier matching the navigation hierarchy.
     *  Example: "settings.platform.display", "settings.app.control" */
    val pageId: String

    /** Human-readable title shown in the toolbar. */
    val pageTitle: String

    /** SharedPreferences keys this page owns. Used by [SettingsChangeLogger]
     *  to attribute a preference write to the correct page. */
    fun getTrackedKeys(): List<String>
}
