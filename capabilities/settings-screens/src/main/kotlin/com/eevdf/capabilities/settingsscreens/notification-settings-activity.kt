package com.eevdf.capabilities.settingsscreens

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.settingsstorage.state.NotificationPrefs
import com.eevdf.capabilities.permissions.PermissionChecker
import com.eevdf.capabilities.designsystem.entities.NavCardEntity
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.output.NavCardView
import com.eevdf.capabilities.designsystem.renderers.renderNavCard
import com.eevdf.capabilities.designsystem.renderers.renderToggleCard

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
 *
 * Permission/capability status for these features (Full Screen Intent
 * access, Usage Access, etc.) lives entirely in [PermissionsActivity] now —
 * this screen only owns the two feature preferences themselves, and nudges
 * to that screen when a feature's prerequisite isn't met, rather than
 * duplicating the check-and-dialog logic here.
 *
 * RESOLVED (typed-entity + centralized-renderer redesign): both rows used
 * to be hand-authored View trees duplicating design-system's ToggleCard/
 * NavCard shapes by hand. Now real instances via the shared renderers —
 * see `activity_notification.xml`'s comments for the matching XML-side
 * change.
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var excludeAppNavCard: NavCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        val toolbar = findViewById<Toolbar>(R.id.notificationToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Notification"

        findViewById<FrameLayout>(R.id.lockScreenOverlayContainer).addView(
            renderToggleCard(
                this,
                ToggleCardEntity(
                    title = "Lock Screen Overlay",
                    description = "When on, a timer expiry shows the full-screen alarm overlay while the device is locked, like the Clock app. When off, it always shows a normal notification instead, even while locked.",
                    checked = NotificationPrefs.isLockScreenOverlayEnabled(this),
                    onCheckedChange = { isChecked ->
                        NotificationPrefs.setLockScreenOverlayEnabled(this, isChecked)
                        // Nudge to the Permissions page rather than duplicating the
                        // check and dialog here — that page is now the single
                        // source of truth for "is this actually going to work".
                        if (isChecked && !PermissionChecker.canUseFullScreenIntent(this)) {
                            showGoToPermissionsDialog(
                                "Full-screen access needed",
                                "Lock Screen Overlay needs the \"Full screen intents\" permission to actually launch over the lock screen. Check it on the Permissions page."
                            )
                        }
                    },
                ),
            )
        )

        excludeAppNavCard = renderNavCard(
            this,
            NavCardEntity(
                title = "Exclude App",
                subtitle = excludeAppSummary(),
                onNavigate = {
                    if (!PermissionChecker.hasUsageStatsPermission(this)) {
                        showGoToPermissionsDialog(
                            "Usage access needed",
                            "Exclude App needs Usage Access to detect which app is in the foreground when a timer expires. Grant it on the Permissions page, then come back."
                        )
                    } else {
                        showExcludeAppPicker()
                    }
                },
            ),
        )
        findViewById<FrameLayout>(R.id.excludeAppCardContainer).addView(excludeAppNavCard)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showGoToPermissionsDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open Permissions") { _, _ ->
                startActivity(Intent(this, PermissionsActivity::class.java))
            }
            .setNegativeButton("Later", null)
            .show()
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

        AlertDialog.Builder(this)
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
        excludeAppNavCard.subtitle = excludeAppSummary()
    }

    private fun excludeAppSummary(): String {
        val list = NotificationPrefs.getExcludeAppList(this)
        return if (list.isEmpty()) "No apps selected"
        else list.joinToString(", ") { pkg ->
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
        }
    }
}
