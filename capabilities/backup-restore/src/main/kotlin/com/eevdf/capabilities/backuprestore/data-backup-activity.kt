package com.eevdf.capabilities.backuprestore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.eevdf.kernel.eventbus.BackupRestoreRequest
import com.eevdf.kernel.eventbus.BackupRestoreResult
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.RestoreMode
import com.eevdf.kernel.eventbus.Topics
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.backuprestore.R
import com.eevdf.capabilities.backuprestore.logic.BackupManager
import com.eevdf.capabilities.taskstorage.TaskDatabase
import com.eevdf.capabilities.taskstorage.TaskDao
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DataBackupActivity : AppCompatActivity() {

    @Inject lateinit var bus: EventBus

    @Inject lateinit var taskDao: TaskDao

    private lateinit var btnExport:   MaterialButton
    private lateinit var btnImport:   MaterialButton
    private lateinit var btnSafety:   MaterialButton

    /** Settings from the backup being restored, applied only after the data restore succeeds. */
    private var pendingSettingsJson: String? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus:    TextView

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? -> if (uri != null) performExport(uri) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) confirmImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_backup)

        val toolbar = findViewById<Toolbar>(R.id.dataBackupToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Data & Backup"

        btnExport   = findViewById(R.id.btnExport)
        btnImport   = findViewById(R.id.btnImport)
        btnSafety   = findViewById(R.id.btnSafetyCopies)
        progressBar = findViewById(R.id.dataBackupProgress)
        tvStatus    = findViewById(R.id.tvDataBackupStatus)

        btnExport.setOnClickListener { launchExport() }
        btnImport.setOnClickListener { launchImport() }
        btnSafety.setOnClickListener { showSafetyCopies() }

        // The restore itself is carried out elsewhere; this is how the result
        // comes back. The handler only hops to the main thread and returns.
        bus.subscribe(Topics.BACKUP_RESTORE_FINISHED, CAPABILITY_ID) { result ->
            runOnUiThread { onRestoreFinished(result) }
        }
    }

    override fun onDestroy() {
        bus.unsubscribeAll(CAPABILITY_ID)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Export ────────────────────────────────────────────────────────────────
    private fun launchExport() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        exportLauncher.launch("eevdf_backup_$timestamp.zip")
    }

    private fun performExport(uri: Uri) {
        setBusy(true, "Exporting…")
        lifecycleScope.launch {
            try {
                // 1. Read all tasks for tasks.json BEFORE the DB is checkpoint-closed.
                val tasks = withContext(Dispatchers.IO) {
                    taskDao.getAllTasksForBackup()
                }
                val tasksJson = BackupManager.exportTasksJson(tasks)
                val settingsJson = SettingsBackup.exportJson(applicationContext)
                val manifestJson = BackupManager.manifestJson(tasks.size)

                // 2. Checkpoint + close so the raw .db file on disk is consistent.
                // Suspends until task-storage has checkpointed (see BackupCheckpointHandler).
                bus.publish(Topics.BACKUP_EXPORT_REQUESTED, Unit, CAPABILITY_ID)
                val dbFile: File = withContext(Dispatchers.IO) {
                    TaskDatabase.getDatabaseFile(applicationContext)
                }

                // 3. Write everything into one .zip container.
                withContext(Dispatchers.IO) {
                    if (!dbFile.exists()) error("Database file not found at ${dbFile.absolutePath}")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        ZipOutputStream(out).use { zos ->
                            zos.putNextEntry(ZipEntry(ENTRY_DB))
                            dbFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()

                            zos.putNextEntry(ZipEntry(ENTRY_TASKS))
                            zos.write(tasksJson.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()

                            zos.putNextEntry(ZipEntry(ENTRY_SETTINGS))
                            zos.write(settingsJson.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()

                            zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }
                    } ?: error("Could not open output stream")
                }
                setStatus("Export complete — ${tasks.size} tasks + settings (.zip)")
            } catch (e: Exception) {
                setStatus("Export failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────────
    //
    // v6.38.0: a restore no longer overwrites the database file and kills the
    // process. This screen's whole job is now the backup FILE: unpack its
    // database to a private temporary file and publish
    // `backup.restore-requested`. The merge into the live database is done
    // by the capability that owns the merge engine (multi-device-sync), which
    // answers with `backup.restore-finished`. Consequences:
    //
    //  - a backup from ANY app version restores (rows are matched by table
    //    and column name, never by file format);
    //  - a restore cannot collide with a sync — they share one lock;
    //  - nothing is closed, nothing restarts, a running timer keeps running
    //    unless the restore itself changes that task.

    private fun launchImport() {
        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun confirmImport(uri: Uri) = chooseRestoreMode { mode ->
        startRestore(mode) { unpackBackup(uri) }
    }

    /** Asks how to restore; "replace" needs a second, explicit confirmation. */
    private fun chooseRestoreMode(onChosen: (RestoreMode) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Restore Backup")
            .setMessage(
                "ADD MISSING — brings back anything in the backup that is not here now. " +
                    "Nothing you currently have is changed or removed.\n\n" +
                    "REPLACE — makes your tasks and history exactly equal to the backup, and restores " +
                    "its settings. Anything newer than the backup is removed. If sync is on, this " +
                    "reaches every other user too.\n\n" +
                    "A safety copy of the current data is kept first either way."
            )
            .setPositiveButton("Add missing") { _, _ -> onChosen(RestoreMode.MERGE) }
            .setNeutralButton("Replace") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Replace everything?")
                    .setMessage("Tasks and history that are not in the backup will be removed, for every synced user.")
                    .setPositiveButton("Replace") { _, _ -> onChosen(RestoreMode.REPLACE) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** What [unpackBackup] / a safety copy yields: a private database file, and settings if the backup had any. */
    private class Unpacked(val database: File, val settingsJson: String?)

    private fun startRestore(mode: RestoreMode, prepare: () -> Unpacked) {
        setBusy(true, "Reading backup…")
        lifecycleScope.launch {
            try {
                val unpacked = withContext(Dispatchers.IO) { prepare() }
                // Settings are part of "make it equal to the backup", not of
                // "add what is missing".
                pendingSettingsJson = if (mode == RestoreMode.REPLACE) unpacked.settingsJson else null
                setBusy(true, "Restoring…")
                bus.publish(
                    Topics.BACKUP_RESTORE_REQUESTED,
                    BackupRestoreRequest(unpacked.database.absolutePath, mode),
                    CAPABILITY_ID,
                )
                // The answer arrives on Topics.BACKUP_RESTORE_FINISHED — see onRestoreFinished.
            } catch (e: Exception) {
                pendingSettingsJson = null
                setStatus("Restore failed — nothing was changed: ${e.message}")
                setBusy(false)
            }
        }
    }

    private fun onRestoreFinished(result: BackupRestoreResult) {
        val settingsJson = pendingSettingsJson
        pendingSettingsJson = null
        var message = if (result.ok) "Restore complete — ${result.message}" else "Restore failed — ${result.message}"
        if (result.ok && settingsJson != null) {
            try {
                SettingsBackup.importJson(applicationContext, settingsJson)
                message += "\nSettings restored — close and reopen the app to see all of them."
            } catch (e: Exception) {
                message += "\nSettings could not be restored: ${e.message}"
            }
        }
        setStatus(message)
        setBusy(false)
    }

    /**
     * Copies the backup's database into this app's cache as a standalone
     * file. Streams it (the old code read the whole backup into memory), and
     * accepts both containers: the .zip made by Export, and a legacy raw .db.
     */
    private fun unpackBackup(uri: Uri): Unpacked {
        val target = File(cacheDir, "restore-${System.currentTimeMillis()}.db")
        var settingsJson: String? = null
        var foundDatabase = false
        val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected file")
        BufferedInputStream(input).use { stream ->
            stream.mark(8)
            val magic = ByteArray(4)
            val read = stream.read(magic)
            stream.reset()
            if (read == 4 && isZip(magic)) {
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            ENTRY_DB -> { target.outputStream().use { zis.copyTo(it) }; foundDatabase = true }
                            ENTRY_SETTINGS -> settingsJson = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                target.outputStream().use { stream.copyTo(it) }
                foundDatabase = true
            }
        }
        if (!foundDatabase) {
            target.delete()
            error("This file does not contain a database")
        }
        return Unpacked(target, settingsJson)
    }

    // ── Safety copies ─────────────────────────────────────────────────────────

    /**
     * Lists the automatic safety copies (taken before every restore and
     * before first joining a sync group) and restores the chosen one. The
     * list is asked for over the bus; this screen does not know where they
     * are kept.
     */
    private fun showSafetyCopies() {
        lifecycleScope.launch {
            val paths = bus.request(Topics.SAFETY_COPIES_REQUESTED, Unit, CAPABILITY_ID).orEmpty()
            if (paths.isEmpty()) {
                setStatus("No safety copies yet. One is made automatically before each restore.")
                return@launch
            }
            val labels = paths.map { File(it).name.removeSuffix(".db") }.toTypedArray()
            AlertDialog.Builder(this@DataBackupActivity)
                .setTitle("Safety copies (newest first)")
                .setItems(labels) { _, which ->
                    val chosen = File(paths[which])
                    chooseRestoreMode { mode ->
                        startRestore(mode) {
                            // Restore from a copy of the copy: the temporary
                            // file handed over is deleted when the restore ends.
                            val temp = File(cacheDir, "restore-${System.currentTimeMillis()}.db")
                            chosen.copyTo(temp, overwrite = true)
                            Unpacked(temp, null)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** ZIP local-file-header magic: 'P','K',0x03,0x04. Legacy backups are raw SQLite. */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setBusy(busy: Boolean, message: String = "") {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        btnExport.isEnabled    = !busy
        btnImport.isEnabled    = !busy
        btnSafety.isEnabled    = !busy
        if (busy) tvStatus.text = message
    }
    private fun setStatus(msg: String) { tvStatus.text = msg }

    private companion object {
        const val CAPABILITY_ID = "backup-restore"
        const val ENTRY_DB = "database.db"
        const val ENTRY_TASKS = "tasks.json"
        const val ENTRY_SETTINGS = "settings.json"
        const val ENTRY_MANIFEST = "manifest.json"
    }
}
