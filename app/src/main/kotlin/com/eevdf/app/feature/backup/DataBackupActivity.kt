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
        exportLauncher.launch("eevdf_backup_$timestamp.db")
    }

    private fun performExport(uri: Uri) {
        setBusy(true, "Exporting…")
        lifecycleScope.launch {
            try {
                viewModel.prepareForDbExport()
                val dbFile: File = withContext(Dispatchers.IO) {
                    TaskDatabase.getDatabaseFile(applicationContext)
                }
                withContext(Dispatchers.IO) {
                    if (!dbFile.exists()) error("Database file not found at ${dbFile.absolutePath}")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        dbFile.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open output stream")
                }
                val sizeMb = "%.2f".format(dbFile.length() / 1_048_576.0)
                setStatus("Export complete ($sizeMb MB)")
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
                "This will replace ALL current tasks with the selected database file.\n\n" +
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
                    contentResolver.openInputStream(uri)?.use { input ->
                        dbFile.outputStream().use { input.copyTo(it) }
                    } ?: error("Could not open the selected file")
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

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setBusy(busy: Boolean, message: String = "") {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        btnExport.isEnabled    = !busy
        btnImport.isEnabled    = !busy
        if (busy) tvStatus.text = message
    }
    private fun setStatus(msg: String) { tvStatus.text = msg }
}
