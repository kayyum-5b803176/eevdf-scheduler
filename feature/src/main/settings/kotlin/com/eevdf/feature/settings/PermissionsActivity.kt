package com.eevdf.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.feature.R
import com.eevdf.platform.notification.AlarmReliabilityChecker

/**
 * Every permission/capability the app relies on, in one place, backed
 * entirely by [AlarmReliabilityChecker] — the same object the service uses
 * for logging, so this screen and the service can never silently disagree
 * about what's actually granted.
 *
 * Previously these checks (and their "tap to fix" dialogs) were duplicated
 * inline inside NotificationSettingsActivity. Centralizing them here means:
 *   • one place to add a new permission check as the app grows,
 *   • the Notification settings page goes back to being just its two actual
 *     feature toggles, not a mix of feature + permission plumbing,
 *   • a single, consistent way to present "granted" vs "not granted".
 */
class PermissionsActivity : AppCompatActivity() {

    private data class PermissionEntry(
        val title: String,
        val description: String,
        val isGranted: () -> Boolean,
        val onFix: () -> Unit
    )

    private lateinit var container: LinearLayout
    private lateinit var entries: List<PermissionEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        val toolbar = findViewById<Toolbar>(R.id.permissionsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Permissions"

        container = findViewById(R.id.permissionListContainer)
        entries = buildEntries()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        // Every one of these is only grantable through system Settings, so
        // state can only have changed while this Activity was paused —
        // refresh on every return rather than caching anything.
        renderList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun buildEntries(): List<PermissionEntry> = listOf(
        PermissionEntry(
            title = "Notifications",
            description = "Required for any alarm or timer notification to show at all.",
            isGranted = { AlarmReliabilityChecker.hasNotificationPermission(this) },
            onFix = { openAppNotificationSettings() }
        ),
        PermissionEntry(
            title = "Full screen intents",
            description = "Lets a timer expiry launch the full-screen alarm overlay while the device is locked. Without it, a locked-device expiry falls back to a normal notification.",
            isGranted = { AlarmReliabilityChecker.canUseFullScreenIntent(this) },
            onFix = { openFullScreenIntentSettings() }
        ),
        PermissionEntry(
            title = "Alarms & reminders",
            description = "Lets timers fire at the exact scheduled second instead of being delayed by the system.",
            isGranted = { AlarmReliabilityChecker.hasExactAlarmPermission(this) },
            onFix = { openExactAlarmSettings() }
        ),
        PermissionEntry(
            title = "Battery optimization",
            description = "While optimized, the system can throttle how alarms alert — including the full-screen overlay — especially on repeated firings close together. Set to Unrestricted for reliable alarms.",
            isGranted = { AlarmReliabilityChecker.isIgnoringBatteryOptimizations(this) },
            onFix = { openBatteryOptimizationSettings() }
        ),
        PermissionEntry(
            title = "Usage access",
            description = "Needed by the Exclude App feature (Notification settings) to detect which app is in the foreground when a timer expires.",
            isGranted = { AlarmReliabilityChecker.hasUsageStatsPermission(this) },
            onFix = { openUsageAccessSettings() }
        ),
        PermissionEntry(
            title = "Display over other apps",
            description = "Used by the bubble/auto-switch overlay feature to draw over other apps.",
            isGranted = { AlarmReliabilityChecker.hasOverlayPermission(this) },
            onFix = { openOverlaySettings() }
        )
    )

    private fun renderList() {
        container.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_permission_row, container, false)

            val granted = entry.isGranted()
            row.findViewById<TextView>(R.id.tvPermissionTitle).text = entry.title
            row.findViewById<TextView>(R.id.tvPermissionDescription).text = entry.description
            row.findViewById<TextView>(R.id.tvPermissionStatus).apply {
                text = if (granted) "Granted" else "Not granted — tap to fix"
                setTextColor(
                    ContextCompat.getColor(
                        this@PermissionsActivity,
                        if (granted) R.color.app_success_text else R.color.quotaTextExceeded
                    )
                )
            }
            row.findViewById<View>(R.id.rowPermission).setOnClickListener { entry.onFix() }
            container.addView(row)

            if (index != entries.lastIndex) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.app_divider)
                    )
                    setBackgroundColor(ContextCompat.getColor(this@PermissionsActivity, R.color.infoBoxBackground))
                })
            }
        }
    }

    // ── Settings launchers ──────────────────────────────────────────────────

    private fun openAppNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } catch (_: Exception) {
            toastCantOpen()
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {
            toastCantOpen()
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {
            toastCantOpen()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toastCantOpen()
            }
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            toastCantOpen()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {
            toastCantOpen()
        }
    }

    private fun toastCantOpen() {
        Toast.makeText(this, "Couldn't open that settings screen", Toast.LENGTH_SHORT).show()
    }
}
