package com.eevdf.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
            // Android 14+ gates full-screen intents behind a user-granted
            // special access. Without it, the platform silently downgrades
            // the full-screen intent to a normal heads-up notification even
            // while the device is locked — which looks exactly like "nothing
            // happened" to the user. Prompt once, right when they turn it on.
            if (isChecked && !canUseFullScreenIntent()) showFullScreenIntentAccessDialog()
        }
        rowExcludeApp.setOnClickListener {
            // Exclude App needs Usage Access to read the foreground app.
            // Prompt for it instead of opening the picker — without the
            // permission, whatever gets selected can never actually match.
            if (!hasUsageStatsPermission()) {
                showUsageAccessDialog()
            } else {
                showExcludeAppPicker()
            }
        }
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

    // ── Permissions ─────────────────────────────────────────────────────────

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun showUsageAccessDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Usage access needed")
            .setMessage(
                "Exclude App needs Usage Access to detect which app is in the " +
                "foreground when a timer expires. Without it, the banner is " +
                "never suppressed for the selected apps."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't open Usage Access settings",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Android 14+ gates full-screen intent notifications behind a special,
     * user-granted access (Settings ▸ Special app access ▸ Full screen
     * intents). On API < 34 the permission is a normal, always-granted
     * manifest permission, so there is nothing to check or prompt for.
     */
    private fun canUseFullScreenIntent(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.canUseFullScreenIntent()
    }

    private fun showFullScreenIntentAccessDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Full-screen access needed")
            .setMessage(
                "Lock Screen Overlay needs the \"Full screen intents\" special " +
                "access to launch over the lock screen. Without it, a timer " +
                "expiry while locked shows a normal notification instead."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't open Full Screen Intent settings",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
