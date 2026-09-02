package com.eevdf.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.feature.R
import com.eevdf.feature.shared.prefs.NotificationPrefs
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Notification settings screen.
 *
 * Two independent controls, each governing exactly one notification style:
 *
 *   • Lock Screen Overlay — full-screen style, shown while the device is
 *     locked (AlarmActivity), AOSP Clock-style.
 *   • Exclude App         — banner style, suppressed while one of the
 *     selected apps is in the foreground.
 *
 * Neither applies while the EEVDF app itself is foreground — that case is
 * unconditional and handled entirely in AlarmForegroundService.
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchLockScreenOverlay: SwitchMaterial
    private lateinit var rowExcludeApp: LinearLayout
    private lateinit var tvExcludeApp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        val toolbar = findViewById<Toolbar>(R.id.notificationToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Notification"

        switchLockScreenOverlay = findViewById(R.id.switchLockScreenOverlay)
        rowExcludeApp           = findViewById(R.id.rowExcludeApp)
        tvExcludeApp            = findViewById(R.id.tvExcludeApp)

        // ── Load saved prefs ────────────────────────────────────────────────
        switchLockScreenOverlay.isChecked = NotificationPrefs.isLockScreenOverlayEnabled(this)
        refreshExcludeAppSummary()

        // ── Switches / rows ──────────────────────────────────────────────────
        switchLockScreenOverlay.setOnCheckedChangeListener { _, isChecked ->
            NotificationPrefs.setLockScreenOverlayEnabled(this, isChecked)
        }
        rowExcludeApp.setOnClickListener { showExcludeAppPicker() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Exclude App: app picker (same picker UI as Auto Switch / bubble) ──────

    private data class AppInfo(val packageName: String, val label: String)

    private fun getInstalledUserApps(): List<AppInfo> =
        packageManager
            .getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .filter { ai ->
                ai.packageName != packageName &&
                packageManager.getLaunchIntentForPackage(ai.packageName) != null
            }
            .map { ai -> AppInfo(ai.packageName, ai.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    private fun showExcludeAppPicker() {
        val apps         = getInstalledUserApps()
        val currentSet   = NotificationPrefs.getExcludeAppList(this)
        val mutableCheck = apps.map { it.packageName in currentSet }.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hide banner on these apps")
            .setMultiChoiceItems(
                apps.map { it.label }.toTypedArray(), mutableCheck
            ) { _, which, isChecked -> mutableCheck[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> mutableCheck[i] }
                    .map { it.packageName }.toSet()
                NotificationPrefs.setExcludeAppList(this, selected)
                refreshExcludeAppSummary()
            }
            .setNeutralButton("Clear all") { _, _ ->
                NotificationPrefs.setExcludeAppList(this, emptySet())
                refreshExcludeAppSummary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshExcludeAppSummary() {
        val list = NotificationPrefs.getExcludeAppList(this)
        tvExcludeApp.text = if (list.isEmpty()) "No apps selected"
        else list.joinToString(", ") { pkg ->
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
        }
    }
}
