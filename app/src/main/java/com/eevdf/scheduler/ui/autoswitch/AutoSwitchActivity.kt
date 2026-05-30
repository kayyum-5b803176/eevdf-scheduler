package com.eevdf.scheduler.ui.autoswitch

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.viewmodel.task.TaskViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class AutoSwitchActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    // ── Call detection views ──────────────────────────────────────────────────
    private lateinit var switchCallDetection:  SwitchMaterial
    private lateinit var layoutCallTaskPicker: View
    private lateinit var actvCallTask:         AutoCompleteTextView
    private lateinit var tvCallTaskHint:       TextView
    private lateinit var tvPermissionStatus:   TextView

    // ── Hover bubble views ────────────────────────────────────────────────────
    private lateinit var switchBubble:          SwitchMaterial
    private lateinit var tvOverlayPermStatus:   TextView
    private lateinit var tvUsageStatsPermStatus: TextView
    private lateinit var layoutBubbleOptions:   View
    private lateinit var rgBubblePosition:      RadioGroup
    private lateinit var rbFixed:               RadioButton
    private lateinit var rbDraggable:           RadioButton
    private lateinit var btnConfigureApps:      MaterialButton
    private lateinit var tvConfiguredApps:      TextView

    private var taskList: List<Task> = emptyList()

    // ── Inner helper ──────────────────────────────────────────────────────────
    private data class AppInfo(val packageName: String, val label: String)

    // ── Permission launchers ──────────────────────────────────────────────────

    private val requestPhonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AutoSwitchPrefs.setCallDetectionEnabled(this, true)
            applyCallToggleUi(true)
        } else {
            switchCallDetection.isChecked = false
            showDeniedDialog("Phone permission denied",
                "Call Detection is disabled. Grant the permission in App Settings.")
        }
        refreshPermissionBadge()
    }

    /** Launched when we redirect to the overlay settings screen. */
    private val overlaySettingsResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBubblePermBadges() }

    /** Launched when we redirect to the usage-access settings screen. */
    private val usageAccessResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBubblePermBadges() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_switch)

        setSupportActionBar(findViewById<Toolbar>(R.id.autoSwitchToolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Auto Switch"

        bindCallViews()
        bindBubbleViews()
        setupTaskPicker()
    }

    override fun onResume() {
        super.onResume()
        // Refresh badges every time we return (user may have granted perms in Settings)
        refreshPermissionBadge()
        refreshBubblePermBadges()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Call detection setup ──────────────────────────────────────────────────

    private fun bindCallViews() {
        switchCallDetection  = findViewById(R.id.switchCallDetection)
        layoutCallTaskPicker = findViewById(R.id.layoutCallTaskPicker)
        actvCallTask         = findViewById(R.id.actvCallTask)
        tvCallTaskHint       = findViewById(R.id.tvCallTaskHint)
        tvPermissionStatus   = findViewById(R.id.tvPermissionStatus)

        val enabled = AutoSwitchPrefs.isCallDetectionEnabled(this)
        switchCallDetection.isChecked = enabled
        applyCallToggleUi(enabled)
        refreshPermissionBadge()
        AutoSwitchPrefs.getCallTaskName(this)?.let { actvCallTask.setText(it) }

        switchCallDetection.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                when {
                    hasPhonePermission() -> {
                        AutoSwitchPrefs.setCallDetectionEnabled(this, true)
                        applyCallToggleUi(true)
                    }
                    shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) ->
                        showPhonePermRationale()
                    else ->
                        requestPhonePermission.launch(Manifest.permission.READ_PHONE_STATE)
                }
            } else {
                AutoSwitchPrefs.setCallDetectionEnabled(this, false)
                applyCallToggleUi(false)
            }
        }
    }

    private fun setupTaskPicker() {
        viewModel.activeTasks.observe(this) { tasks ->
            taskList = tasks.filter { !it.isGroup && !it.isCompleted && !it.isInterrupt }
            actvCallTask.setAdapter(ArrayAdapter(
                this, android.R.layout.simple_dropdown_item_1line, taskList.map { it.name }
            ))
        }
        actvCallTask.setOnItemClickListener { _, _, position, _ ->
            val name = (actvCallTask.adapter?.getItem(position) as? String) ?: return@setOnItemClickListener
            val task = taskList.firstOrNull { it.name == name } ?: return@setOnItemClickListener
            AutoSwitchPrefs.setCallTask(this, task.id, task.name)
            tvCallTaskHint.text       = "Assigned: \"${task.name}\""
            tvCallTaskHint.visibility = View.VISIBLE
        }
        AutoSwitchPrefs.getCallTaskName(this)?.let {
            tvCallTaskHint.text       = "Assigned: \"$it\""
            tvCallTaskHint.visibility = View.VISIBLE
        }
    }

    private fun applyCallToggleUi(enabled: Boolean) {
        layoutCallTaskPicker.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun refreshPermissionBadge() {
        val granted = hasPhonePermission()
        tvPermissionStatus.text = if (granted) "Phone permission granted"
                                  else         "Phone permission not granted — tap to grant"
        tvPermissionStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) android.R.color.holo_green_dark
                                          else         android.R.color.holo_red_dark)
        )
        tvPermissionStatus.setOnClickListener {
            if (!granted) requestPhonePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    // ── Hover bubble setup ────────────────────────────────────────────────────

    private fun bindBubbleViews() {
        switchBubble           = findViewById(R.id.switchBubble)
        tvOverlayPermStatus    = findViewById(R.id.tvOverlayPermStatus)
        tvUsageStatsPermStatus = findViewById(R.id.tvUsageStatsPermStatus)
        layoutBubbleOptions    = findViewById(R.id.layoutBubbleOptions)
        rgBubblePosition       = findViewById(R.id.rgBubblePosition)
        rbFixed                = findViewById(R.id.rbFixed)
        rbDraggable            = findViewById(R.id.rbDraggable)
        btnConfigureApps       = findViewById(R.id.btnConfigureApps)
        tvConfiguredApps       = findViewById(R.id.tvConfiguredApps)

        // Restore saved state
        val bubbleOn = AutoSwitchPrefs.isBubbleEnabled(this)
        switchBubble.isChecked = bubbleOn
        applyBubbleToggleUi(bubbleOn)

        if (AutoSwitchPrefs.isBubbleDraggable(this)) rbDraggable.isChecked = true
        else                                          rbFixed.isChecked     = true
        refreshConfiguredAppsText()

        // Toggle
        switchBubble.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val overlayOk = Settings.canDrawOverlays(this)
                val usageOk   = hasUsageStatsPermission()
                if (!overlayOk || !usageOk) {
                    switchBubble.isChecked = false
                    showBubblePermissionsDialog(overlayOk, usageOk)
                    return@setOnCheckedChangeListener
                }
            }
            AutoSwitchPrefs.setBubbleEnabled(this, checked)
            applyBubbleToggleUi(checked)
        }

        // Position radio
        rgBubblePosition.setOnCheckedChangeListener { _, checkedId ->
            AutoSwitchPrefs.setBubbleDraggable(this, checkedId == R.id.rbDraggable)
        }

        // App list
        btnConfigureApps.setOnClickListener { showAppPickerDialog() }

        // Permission tappable badges
        tvOverlayPermStatus.setOnClickListener {
            if (!Settings.canDrawOverlays(this))
                overlaySettingsResult.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                           Uri.parse("package:$packageName"))
                )
        }
        tvUsageStatsPermStatus.setOnClickListener {
            if (!hasUsageStatsPermission())
                usageAccessResult.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun applyBubbleToggleUi(enabled: Boolean) {
        layoutBubbleOptions.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun refreshBubblePermBadges() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk   = hasUsageStatsPermission()

        tvOverlayPermStatus.text = if (overlayOk) "Overlay permission granted"
                                   else            "Overlay permission not granted — tap to grant"
        tvOverlayPermStatus.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) android.R.color.holo_green_dark
                                          else           android.R.color.holo_red_dark)
        )

        tvUsageStatsPermStatus.text = if (usageOk) "Usage access granted"
                                      else          "Usage access not granted — tap to grant"
        tvUsageStatsPermStatus.setTextColor(
            ContextCompat.getColor(this, if (usageOk) android.R.color.holo_green_dark
                                          else         android.R.color.holo_red_dark)
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ── App picker ────────────────────────────────────────────────────────────

    /**
     * Returns all installed user-visible apps, sorted by label.
     *
     * Uses [PackageManager.getInstalledApplications] rather than
     * queryIntentActivities so the full package list is returned on Android 11+
     * (API 30+), where queryIntentActivities is limited by package-visibility
     * rules and only returns a handful of system apps without explicit <queries>
     * declarations.  The QUERY_ALL_PACKAGES permission in the manifest lifts
     * that restriction for getInstalledApplications.
     *
     * Apps are filtered to those that have a launcher entry (getLaunchIntent ≠
     * null) so purely background services and libraries are excluded.
     * EEVDF itself is always excluded from the list.
     *
     * Package names are used as identifiers so reinstalling an app does not
     * require reconfiguring the bubble app list.
     */
    private fun getInstalledUserApps(): List<AppInfo> {
        return packageManager
            .getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { ai ->
                ai.packageName != packageName &&
                packageManager.getLaunchIntentForPackage(ai.packageName) != null
            }
            .map { ai -> AppInfo(ai.packageName, ai.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun showAppPickerDialog() {
        val apps        = getInstalledUserApps()
        val currentSet  = AutoSwitchPrefs.getBubbleAppList(this)
        val checkedArr  = apps.map { it.packageName in currentSet }.toBooleanArray()
        val mutableCheck = checkedArr.copyOf()

        AlertDialog.Builder(this)
            .setTitle("Show bubble on these apps")
            .setMultiChoiceItems(
                apps.map { it.label }.toTypedArray(), mutableCheck
            ) { _, which, isChecked -> mutableCheck[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> mutableCheck[i] }
                    .map { it.packageName }.toSet()
                AutoSwitchPrefs.setBubbleAppList(this, selected)
                refreshConfiguredAppsText()
            }
            .setNeutralButton("Clear all") { _, _ ->
                AutoSwitchPrefs.setBubbleAppList(this, emptySet())
                refreshConfiguredAppsText()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshConfiguredAppsText() {
        val list = AutoSwitchPrefs.getBubbleAppList(this)
        tvConfiguredApps.text = if (list.isEmpty()) "All apps (no filter)"
        else list.joinToString(", ") { pkg ->
            // Try to resolve a friendly name; fall back to package name
            try { packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: PackageManager.NameNotFoundException) { pkg }
        }
    }

    // ── Permission dialogs ────────────────────────────────────────────────────

    private fun showBubblePermissionsDialog(overlayOk: Boolean, usageOk: Boolean) {
        val missing = buildString {
            if (!overlayOk) appendLine("• Overlay permission (draw over other apps)")
            if (!usageOk)   appendLine("• Usage access (detect foreground app)")
        }
        AlertDialog.Builder(this)
            .setTitle("Permissions required for Hover Bubble")
            .setMessage("The following permissions are needed:\n\n$missing\nTap the red badges above to grant each one.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPhonePermRationale() {
        AlertDialog.Builder(this)
            .setTitle("Phone Permission Required")
            .setMessage("Call Detection needs to read your phone call state to automatically " +
                        "pause and resume tasks.\n\nNo call content or phone numbers are accessed.")
            .setPositiveButton("Grant") { _, _ ->
                requestPhonePermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
            .setNegativeButton("Cancel") { _, _ -> switchCallDetection.isChecked = false }
            .show()
    }

    private fun showDeniedDialog(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
