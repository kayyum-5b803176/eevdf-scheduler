package com.eevdf.app.feature.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.app.R
import com.eevdf.data.backup.BackupManager
import com.eevdf.data.task.TaskDatabase
import com.eevdf.app.feature.task.TaskViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DataBackupActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var btnExport:   MaterialButton
    private lateinit var btnImport:   MaterialButton
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
        progressBar = findViewById(R.id.dataBackupProgress)
        tvStatus    = findViewById(R.id.tvDataBackupStatus)

        btnExport.setOnClickListener { launchExport() }
        btnImport.setOnClickListener { launchImport() }
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
                    TaskDatabase.getDatabase(applicationContext).taskDao().getAllTasksForBackup()
                }
                val tasksJson = BackupManager.exportTasksJson(tasks)
                val settingsJson = SettingsBackup.exportJson(applicationContext)
                val manifestJson = BackupManager.manifestJson(tasks.size)

                // 2. Checkpoint + close so the raw .db file on disk is consistent.
                viewModel.prepareForDbExport()
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

    // ── Import ────────────────────────────────────────────────────────────────
    private fun launchImport() {
        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restore Backup")
            .setMessage(
                "This will replace ALL current tasks AND settings with the selected backup.\n\n" +
                "Any running timer will be stopped and the app will restart.\n\nContinue?"
            )
            .setPositiveButton("Restore") { _, _ -> performImport(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performImport(uri: Uri) {
        setBusy(true, "Importing…")
        lifecycleScope.launch {
            try {
                viewModel.prepareForDbExport()
                val dbFile: File = withContext(Dispatchers.IO) {
                    TaskDatabase.getDatabaseFile(applicationContext)
                }
                withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open the selected file")

                    if (isZip(bytes)) {
                        // New container: database.db + settings.json
                        var settingsJson: String? = null
                        ZipInputStream(bytes.inputStream()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                when (entry.name) {
                                    ENTRY_DB -> dbFile.outputStream().use { zis.copyTo(it) }
                                    ENTRY_SETTINGS -> settingsJson = zis.readBytes().toString(Charsets.UTF_8)
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        settingsJson?.let { SettingsBackup.importJson(applicationContext, it) }
                    } else {
                        // Legacy raw .db backup (no settings) — import tasks only.
                        dbFile.outputStream().use { it.write(bytes) }
                    }
                    File("${dbFile.path}-wal").delete()
                    File("${dbFile.path}-shm").delete()
                }
                val restart = packageManager.getLaunchIntentForPackage(packageName)!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(restart)
                kotlinx.coroutines.delay(300)
                finish()
            } catch (e: Exception) {
                setStatus("Import failed: ${e.message}")
                setBusy(false)
            }
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
        if (busy) tvStatus.text = message
    }
    private fun setStatus(msg: String) { tvStatus.text = msg }

    private companion object {
        const val ENTRY_DB = "database.db"
        const val ENTRY_TASKS = "tasks.json"
        const val ENTRY_SETTINGS = "settings.json"
        const val ENTRY_MANIFEST = "manifest.json"
    }
}
