package com.eevdf.scheduler.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.eevdf.scheduler.sync.MultiUserSyncManager
import com.eevdf.scheduler.sync.SyncState
import com.google.android.material.button.MaterialButton
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

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            persistFolderPermission(uri)
            MultiUserSyncManager.setSyncFolder(uri)
            updateFolderLabel()
            // Kick off an immediate export so the file appears in the folder right away
            MultiUserSyncManager.scheduleExport()
        }
    }

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

        // Restore persisted state
        switchEnable.isChecked = MultiUserSyncManager.isEnabled()
        updateFolderLabel()
        updateFolderRowVisibility()

        switchEnable.setOnCheckedChangeListener { _, checked ->
            MultiUserSyncManager.setEnabled(checked)
            updateFolderRowVisibility()
        }

        btnPickFolder.setOnClickListener {
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

        btnSyncNow.setOnClickListener {
            MultiUserSyncManager.scheduleExport()
            tvSyncStatus.text = "Sync requested…"
        }

        // Observe live sync state
        MultiUserSyncManager.syncState.observe(this) { state ->
            tvSyncStatus.text = when (state) {
                SyncState.Disabled -> "Sync disabled"
                SyncState.Idle     -> "Idle — waiting for changes"
                SyncState.Syncing  -> "Syncing…"
                SyncState.OK       -> "✓  Last sync successful"
                is SyncState.Error -> "⚠  ${state.message}"
            }
            tvSyncStatus.setTextColor(
                resources.getColor(
                    when (state) {
                        is SyncState.Error -> android.R.color.holo_red_dark
                        SyncState.OK       -> R.color.syncDotOK
                        SyncState.Syncing  -> android.R.color.holo_orange_dark
                        else               -> R.color.textSecondary
                    },
                    theme
                )
            )
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
}
