package com.eevdf.app.feature.sync

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.app.R
import com.eevdf.data.sync.MultiUserSyncManager
import com.eevdf.data.sync.SyncConflict
import com.eevdf.data.sync.SyncState
import com.eevdf.data.sync.SyncWriteStats
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings screen for the multi-user live sync feature.
 *
 * The user:
 *  1. Toggles sync on/off with the master switch.
 *  2. Picks a shared folder (e.g. on a NAS, cloud-synced drive, or USB) that all
 *     participants point at.  The folder URI is persisted via
 *     [takePersistableUriPermission] so the app can access it across reboots.
 *  3. Can force an immediate manual sync with the "Sync Now" button.
 *
 * The live sync status (OK / Error / Syncing) is shown in a status text view that
 * observes [MultiUserSyncManager.syncState].
 */
class MultiUserSyncActivity : AppCompatActivity() {

    private lateinit var switchEnable:     SwitchMaterial
    private lateinit var tvFolderPath:     TextView
    private lateinit var tvSyncStatus:     TextView
    private lateinit var btnPickFolder:    MaterialButton
    private lateinit var btnSyncNow:       MaterialButton
    private lateinit var layoutFolderRow:  View

    // Write stats card views
    private lateinit var cardWriteStats:     View
    private lateinit var tvAccessMode:       TextView
    private lateinit var tvLastExportTime:   TextView
    private lateinit var tvDbSize:           TextView
    private lateinit var tvBytesWritten:     TextView
    private lateinit var rowPageStats:       View
    private lateinit var tvPageStats:        TextView
    private lateinit var rowWriteRatio:      View
    private lateinit var tvWriteRatioPct:    TextView
    private lateinit var progressWriteRatio: android.widget.ProgressBar
    private lateinit var tvSessionExports:   TextView
    private lateinit var tvSessionTotal:     TextView

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            persistFolderPermission(uri)
            MultiUserSyncManager.setSyncFolder(uri)
            updateFolderLabel()
            // Kick off an immediate export so the file appears in the folder right away
            MultiUserSyncManager.scheduleExport()
        }
    }

    /**
     * Launcher for the MANAGE_EXTERNAL_STORAGE system settings screen
     * (Android 11+).  When the user returns we re-check the permission and
     * proceed to the folder picker if it was granted.
     */
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check after returning from Settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()) {
            launchFolderPicker()
        }
        // If not granted, the user chose not to — SAF fallback will be used silently
    }

    /** Launcher for READ/WRITE_EXTERNAL_STORAGE on API 26-29. */
    private val legacyStoragePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result ignored — SAF fallback covers the denied case */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiuser_sync)

        val toolbar = findViewById<Toolbar>(R.id.multiUserSyncToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Multiuser Sync"

        switchEnable    = findViewById(R.id.switchSyncEnable)
        tvFolderPath    = findViewById(R.id.tvSyncFolderPath)
        tvSyncStatus    = findViewById(R.id.tvSyncStatus)
        btnPickFolder   = findViewById(R.id.btnPickSyncFolder)
        btnSyncNow      = findViewById(R.id.btnSyncNow)
        layoutFolderRow = findViewById(R.id.layoutSyncFolderRow)

        // Write stats card
        cardWriteStats     = findViewById(R.id.cardWriteStats)
        tvAccessMode       = findViewById(R.id.tvAccessMode)
        tvLastExportTime   = findViewById(R.id.tvLastExportTime)
        tvDbSize           = findViewById(R.id.tvDbSize)
        tvBytesWritten     = findViewById(R.id.tvBytesWritten)
        rowPageStats       = findViewById(R.id.rowPageStats)
        tvPageStats        = findViewById(R.id.tvPageStats)
        rowWriteRatio      = findViewById(R.id.rowWriteRatio)
        tvWriteRatioPct    = findViewById(R.id.tvWriteRatioPct)
        progressWriteRatio = findViewById(R.id.progressWriteRatio)
        tvSessionExports   = findViewById(R.id.tvSessionExports)
        tvSessionTotal     = findViewById(R.id.tvSessionTotal)

        // Restore persisted state
        switchEnable.isChecked = MultiUserSyncManager.isEnabled()
        updateFolderLabel()
        updateFolderRowVisibility()

        switchEnable.setOnCheckedChangeListener { _, checked ->
            MultiUserSyncManager.setEnabled(checked)
            updateFolderRowVisibility()
        }

        btnPickFolder.setOnClickListener {
            requestStorageAccessThenPickFolder()
        }

        btnSyncNow.setOnClickListener {
            MultiUserSyncManager.scheduleExport()
            tvSyncStatus.text = "Syncing…"
        }

        // Observe live sync state
        MultiUserSyncManager.syncState.observe(this) { state ->
            tvSyncStatus.text = when (state) {
                SyncState.Disabled           -> "Sync disabled"
                SyncState.Idle               -> "Idle — waiting for changes"
                SyncState.Syncing            -> "Syncing…"
                SyncState.OK                 -> "✓  Last sync successful"
                is SyncState.Error           -> "⚠  ${state.message}"
                is SyncState.ConflictPending ->
                    "⚠  Sync blocked — ${state.conflicts.size} conflict(s) need review"
            }
            tvSyncStatus.setTextColor(
                resources.getColor(
                    when (state) {
                        is SyncState.Error           -> android.R.color.holo_red_dark
                        is SyncState.ConflictPending -> android.R.color.holo_orange_dark
                        SyncState.OK                 -> R.color.syncDotOK  // green
                        SyncState.Syncing            -> android.R.color.holo_orange_dark
                        else                         -> R.color.textSecondary
                    },
                    theme
                )
            )

            // Show conflict warning dialog whenever the state transitions to ConflictPending
            if (state is SyncState.ConflictPending) {
                showConflictDialog(state)
            }
        }

        // Observe write statistics
        MultiUserSyncManager.writeStats.observe(this) { stats ->
            bindWriteStats(stats)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateFolderLabel() {
        val uri = MultiUserSyncManager.getSyncFolderUri()
        tvFolderPath.text = if (uri != null) {
            uriToDisplayPath(uri)
        } else {
            "No folder selected"
        }
    }

    private fun updateFolderRowVisibility() {
        layoutFolderRow.visibility =
            if (switchEnable.isChecked) View.VISIBLE else View.GONE
        btnSyncNow.visibility =
            if (switchEnable.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * Grants persistent access to the URI so the app can read/write the sync
     * folder across reboots and process deaths.
     */
    private fun persistFolderPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Persistable permission not available (e.g. non-document URIs).
            // The URI will still work for the current session.
        }
    }

    // ── Write stats binding ───────────────────────────────────────────────────

    private fun bindWriteStats(stats: SyncWriteStats) {
        if (stats.lastExportMs == 0L) {
            tvAccessMode.text = "waiting for first export…"
            tvAccessMode.backgroundTintList =
                resources.getColorStateList(R.color.syncDotDisabled, theme)
            tvLastExportTime.text  = "—"
            tvDbSize.text          = "—"
            tvBytesWritten.text    = "—"
            rowPageStats.visibility  = View.GONE
            rowWriteRatio.visibility = View.GONE
            tvSessionExports.text  = "0"
            tvSessionTotal.text    = "0 B"
            return
        }

        // Export is always direct RandomAccessFile — show confirmation
        tvAccessMode.text = "Direct  (page diff active)"
        tvAccessMode.backgroundTintList =
            resources.getColorStateList(R.color.syncDotOK, theme)

        tvLastExportTime.text = formatAgo(stats.lastExportMs)
        tvDbSize.text = formatBytes(stats.lastDbSizeBytes)
        tvBytesWritten.text =
            "${formatBytes(stats.lastBytesWritten)} of ${formatBytes(stats.lastDbSizeBytes)}"

        // Page stats
        if (stats.hasDiffStats) {
            rowPageStats.visibility = View.VISIBLE
            tvPageStats.text =
                "${stats.lastPagesWritten} written  /  ${stats.lastPagesSkipped} skipped"
        } else {
            rowPageStats.visibility = View.GONE
        }

        // Write ratio bar
        rowWriteRatio.visibility = View.VISIBLE
        val pct = stats.writeRatioPct
        tvWriteRatioPct.text = "$pct%"
        progressWriteRatio.progress = pct
        val barColor = when {
            pct <= 25 -> R.color.syncDotOK
            pct <= 75 -> R.color.syncDotSyncing
            else      -> R.color.syncDotError
        }
        progressWriteRatio.progressTintList =
            resources.getColorStateList(barColor, theme)

        tvSessionExports.text = stats.sessionExportCount.toString()
        tvSessionTotal.text   = formatBytes(stats.sessionBytesWritten)
    }

    /** Formats a byte count as B / KB / MB with one decimal place. */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L              -> "$bytes B"
        bytes < 1024L * 1024L      -> "%.1f KB".format(bytes / 1024.0)
        else                       -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** Returns a human-readable "time ago" string for an epoch-ms timestamp. */
    private fun formatAgo(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < 5_000L   -> "just now"
            diffMs < 60_000L  -> "${diffMs / 1000}s ago"
            diffMs < 3600_000L -> "${diffMs / 60_000}m ago"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = epochMs
                "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY),
                                   cal.get(java.util.Calendar.MINUTE))
            }
        }
    }

    /**
     * Requests the strongest available external-storage permission for the
     * running API level, then launches the folder picker.
     *
     *  API 30+ (Android 11+) : MANAGE_EXTERNAL_STORAGE — opens the system
     *                          settings page if not already granted.
     *  API 26-29             : READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
     *                          runtime permission request.
     *
     * In all cases the SAF folder picker is launched regardless of outcome;
     * direct file access will be attempted at sync time and the code will
     * silently fall back to SAF ContentResolver I/O if the permission was
     * not granted.
     */
    private fun requestStorageAccessThenPickFolder() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Storage access")
                        .setMessage(
                            "For fastest sync EEVDF needs the 'All files access' permission " +
                            "so it can write directly to the sync folder without going through " +
                            "the slower Storage Access Framework.\n\n" +
                            "You can skip this — sync will still work, just slightly slower."
                        )
                        .setPositiveButton("Grant access") { _, _ ->
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${packageName}")
                            )
                            manageStorageLauncher.launch(intent)
                        }
                        .setNegativeButton("Skip") { _, _ -> launchFolderPicker() }
                        .show()
                } else {
                    launchFolderPicker()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val perms = arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                val missing = perms.filter {
                    ContextCompat.checkSelfPermission(this, it) !=
                        PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    legacyStoragePermLauncher.launch(missing.toTypedArray())
                }
                // Launch picker regardless — SAF is the fallback
                launchFolderPicker()
            }
            else -> launchFolderPicker()
        }
    }

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        folderPickerLauncher.launch(intent)
    }

    /** Converts a DocumentsContract tree URI to a short human-readable path. */
    private fun uriToDisplayPath(uri: Uri): String {
        return try {
            val path = uri.lastPathSegment ?: uri.toString()
            // e.g. "primary:Download/SyncFolder"  → "Download/SyncFolder"
            path.substringAfter(':').ifBlank { path }
        } catch (_: Exception) {
            uri.toString()
        }
    }

    // ── Conflict warning dialog ───────────────────────────────────────────────

    /**
     * Step 1 — Warning dialog.
     *
     * Lists every conflict and offers two choices:
     *   "Keep local data" (safe default) → [MultiUserSyncManager.skipPendingImport]
     *   "Accept remote"   (destructive)  → opens [showAcceptConfirmationDialog]
     *
     * Non-cancelable so the user must make an explicit choice.
     */
    private fun showConflictDialog(state: SyncState.ConflictPending) {
        val conflicts = state.conflicts

        val sb = StringBuilder()
        sb.appendLine("A remote sync would overwrite local task data with blank or deleted values.")
        sb.appendLine()

        val deleted = conflicts.filter { it.kind == SyncConflict.Kind.TASK_DELETED }
        val blanked = conflicts.filter { it.kind == SyncConflict.Kind.FIELD_BLANKED }

        if (deleted.isNotEmpty()) {
            sb.appendLine("Deleted on remote (${deleted.size}):")
            deleted.take(5).forEach { sb.appendLine("  • ${it.summary()}") }
            if (deleted.size > 5) sb.appendLine("  … and ${deleted.size - 5} more")
            sb.appendLine()
        }

        if (blanked.isNotEmpty()) {
            sb.appendLine("Blank overwrite on remote (${blanked.size}):")
            blanked.take(5).forEach { sb.appendLine("  • ${it.summary()}") }
            if (blanked.size > 5) sb.appendLine("  … and ${blanked.size - 5} more")
            sb.appendLine()
        }

        sb.append("Sync is paused. Choose how to proceed:")

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠ Sync Conflict Detected")
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton("Accept remote") { _, _ ->
                // Destructive path — require a second explicit confirmation
                showAcceptConfirmationDialog(state)
            }
            .setNegativeButton("Keep local data") { _, _ ->
                MultiUserSyncManager.skipPendingImport(state.pendingToken)
            }
            .show()
            .also { dialog ->
                // Colour the destructive button red so the risk is visually clear
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    /**
     * Step 2 — Confirmation dialog shown only when the user taps "Accept remote"
     * in [showConflictDialog].
     *
     * Restates the consequence in plain language with the confirm button in red.
     * Backing out returns to Idle (same as "Keep local data") rather than
     * re-showing the first dialog, to avoid an infinite loop.
     */
    private fun showAcceptConfirmationDialog(state: SyncState.ConflictPending) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Overwrite local data?")
            .setMessage(
                "This will replace your local tasks with the remote snapshot.\n\n" +
                "Any data listed in the previous screen will be lost. " +
                "This cannot be undone."
            )
            .setCancelable(false)
            .setPositiveButton("Yes, overwrite") { _, _ ->
                MultiUserSyncManager.forceAcceptPendingImport(state.pendingToken)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Treat cancel as "keep local" — skip this snapshot
                MultiUserSyncManager.skipPendingImport(state.pendingToken)
            }
            .show()
            .also { dialog ->
                // Red confirm button reinforces the destructive action
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }
}
