package com.eevdf.capabilities.multidevicesync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.multidevicesync.R
import com.eevdf.capabilities.multidevicesync.logic.SyncCoordinator
import com.eevdf.kernel.eventbus.SyncPhase
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings screen for multi-user sync.
 *
 * The user:
 *  1. Chooses the shared folder on the SD card (the same one in every user).
 *  2. Switches sync on. If the folder already holds other users' data and
 *     this user has tasks of their own, they are asked once what to do with
 *     them — keep them too (the default) or use the shared data only.
 *  3. Can force a sync with "Sync Now"; normally there is no need.
 *
 * Everything shown comes from [SyncCoordinator.ui], collected as a flow.
 * This screen holds no sync state of its own and never touches the folder.
 */
@AndroidEntryPoint
class MultiUserSyncActivity : AppCompatActivity() {

    @Inject lateinit var coordinator: SyncCoordinator

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var tvFolderPath: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var btnPickFolder: MaterialButton
    private lateinit var btnSyncNow: MaterialButton
    private lateinit var layoutFolderRow: View

    /** True while the switch is being set from code, so its listener ignores the change. */
    private var bindingSwitch = false

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            persistFolderPermission(uri)
            coordinator.setFolder(uri)
            coordinator.refreshMembers()
        }
    }

    /** Returning from the system "All files access" page. */
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) launchFolderPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiuser_sync)

        val toolbar = findViewById<Toolbar>(R.id.multiUserSyncToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Multiuser Sync"

        switchEnable = findViewById(R.id.switchSyncEnable)
        tvFolderPath = findViewById(R.id.tvSyncFolderPath)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvDetails = findViewById(R.id.tvSyncDetails)
        btnPickFolder = findViewById(R.id.btnPickSyncFolder)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        layoutFolderRow = findViewById(R.id.layoutSyncFolderRow)

        switchEnable.setOnCheckedChangeListener { _, checked ->
            if (!bindingSwitch) onSwitchChanged(checked)
        }
        btnPickFolder.setOnClickListener { requestStorageAccessThenPickFolder() }
        btnSyncNow.setOnClickListener {
            coordinator.syncNow()
            coordinator.refreshMembers()
        }

        lifecycleScope.launch {
            coordinator.ui.collect { state -> bind(state) }
        }
        coordinator.refreshMembers()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Switch ────────────────────────────────────────────────────────────────

    private fun onSwitchChanged(checked: Boolean) {
        if (!checked) {
            coordinator.setEnabled(false)
            return
        }
        if (coordinator.folderUri() == null) {
            // Nothing to sync with yet. Leave the switch off and send the
            // user to the one thing that is missing.
            setSwitch(false)
            MaterialAlertDialogBuilder(this)
                .setTitle("Choose a folder first")
                .setMessage("Pick the shared folder on the SD card, then switch sync on.")
                .setPositiveButton("Choose folder") { _, _ -> requestStorageAccessThenPickFolder() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        lifecycleScope.launch {
            if (!coordinator.wouldJoinExistingGroup()) {
                coordinator.setEnabled(true)
                return@launch
            }
            // Hold the switch off until the user has answered.
            setSwitch(false)
            MaterialAlertDialogBuilder(this@MultiUserSyncActivity)
                .setTitle("Join the shared data")
                .setMessage(
                    "This folder already has data from other users, and this user has tasks too.\n\n" +
                        "KEEP MINE TOO — anything only this user has is added for everyone. Where both " +
                        "have the same task, the shared version is used. Nothing is lost.\n\n" +
                        "SHARED ONLY — this user ends up with exactly the shared data. Choose this if " +
                        "this user's copy is old and may still contain tasks the others deleted.\n\n" +
                        "Either way a safety copy of this user's data is kept first."
                )
                .setPositiveButton("Keep mine too") { _, _ ->
                    coordinator.setEnabled(true, SyncCoordinator.JoinMode.KEEP_MINE)
                }
                .setNeutralButton("Shared only") { _, _ ->
                    coordinator.setEnabled(true, SyncCoordinator.JoinMode.GROUP_ONLY)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setSwitch(checked: Boolean) {
        bindingSwitch = true
        switchEnable.isChecked = checked
        bindingSwitch = false
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private fun bind(state: SyncCoordinator.UiState) {
        if (switchEnable.isChecked != state.enabled) setSwitch(state.enabled)
        tvFolderPath.text = state.folderLabel ?: "No folder selected"
        layoutFolderRow.visibility = View.VISIBLE
        btnSyncNow.visibility = if (state.enabled) View.VISIBLE else View.GONE

        tvSyncStatus.text = when (state.phase) {
            SyncPhase.DISABLED -> "Sync is off"
            SyncPhase.IDLE -> "Waiting for changes"
            SyncPhase.SYNCING -> "Syncing…"
            SyncPhase.OK -> "✓  Up to date"
            SyncPhase.ERROR -> "⚠  ${state.message ?: "Sync problem"}"
        }
        tvSyncStatus.setTextColor(
            resources.getColor(
                when (state.phase) {
                    SyncPhase.ERROR -> android.R.color.holo_red_dark
                    SyncPhase.SYNCING -> android.R.color.holo_orange_dark
                    SyncPhase.OK -> R.color.syncDotOK
                    else -> R.color.textSecondary
                },
                theme
            )
        )
        tvDetails.text = details(state)
    }

    private fun details(s: SyncCoordinator.UiState): String {
        val lines = ArrayList<String>()
        lines.add("This user: ${s.memberName}")
        lines.add(if (s.joined) "Joined the shared data" else "Not joined yet")
        if (s.lastSyncMs > 0L) lines.add("Last sync: ${formatAgo(s.lastSyncMs)}")
        if (s.pendingChanges > 0L) lines.add("Waiting to be written: ${s.pendingChanges} change(s)")
        lines.add("Written to the card this session: ${formatBytes(s.sessionWrittenBytes)}")
        if (s.lastWrittenBytes > 0L) lines.add("Last write: ${formatBytes(s.lastWrittenBytes)}")
        if (s.folderBytes > 0L) lines.add("Sync data on the card: ${formatBytes(s.folderBytes)}")
        if (s.members.isNotEmpty()) {
            lines.add("")
            lines.add("Members (${s.members.size}):")
            for (m in s.members) {
                val who = if (m.isMe) "${m.name} (this user)" else m.name
                lines.add("  • $who — last change ${formatAgo(m.lastWriteMs)}")
            }
        }
        return lines.joinToString("\n")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun formatAgo(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < 5_000L -> "just now"
            diffMs < 60_000L -> "${diffMs / 1000}s ago"
            diffMs < 3_600_000L -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000L -> "${diffMs / 3_600_000}h ago"
            else -> "${diffMs / 86_400_000}d ago"
        }
    }

    // ── Folder ────────────────────────────────────────────────────────────────

    private fun persistFolderPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Not every provider offers a persistable grant. The folder is
            // reached by its real path anyway, so this is not fatal.
        }
    }

    /**
     * The sync folder is read and appended to by its real path, which on
     * Android 11+ needs "All files access". Without it a folder on an SD
     * card cannot be reached at all, so this is asked for before the picker
     * rather than offered as optional.
     */
    private fun requestStorageAccessThenPickFolder() {
        if (Environment.isExternalStorageManager()) {
            launchFolderPicker()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Storage access")
            .setMessage(
                "To use a folder on the SD card, allow 'All files access' for this app on the next screen."
            )
            .setPositiveButton("Continue") { _, _ ->
                manageStorageLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        folderPickerLauncher.launch(intent)
    }
}
