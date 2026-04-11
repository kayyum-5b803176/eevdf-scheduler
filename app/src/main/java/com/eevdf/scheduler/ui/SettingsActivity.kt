package com.eevdf.scheduler.ui

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
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.button.MaterialButton
import android.content.Context
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var btnExport:      MaterialButton
    private lateinit var btnImport:      MaterialButton
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvStatus:       TextView

    // Vibration UI
    private lateinit var rgVibPattern:   RadioGroup
    private lateinit var btnPreviewVib:  MaterialButton
    private lateinit var sliderTimeout:  Slider
    private lateinit var tvTimeoutLabel: TextView
    private lateinit var switchHaptic:   SwitchMaterial

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE) }

    // ── SAF launchers ─────────────────────────────────────────────────────────

    /** Lets the user pick where to save the exported .db file. */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) performExport(uri)
    }

    /** Lets the user pick a .db file to restore. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) confirmImport(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        btnExport       = findViewById(R.id.btnExport)
        btnImport       = findViewById(R.id.btnImport)
        progressBar     = findViewById(R.id.settingsProgress)
        tvStatus        = findViewById(R.id.tvSettingsStatus)
        rgVibPattern    = findViewById(R.id.rgVibPattern)
        btnPreviewVib   = findViewById(R.id.btnPreviewVib)
        sliderTimeout   = findViewById(R.id.sliderVibTimeout)
        tvTimeoutLabel  = findViewById(R.id.tvVibTimeoutLabel)
        switchHaptic    = findViewById(R.id.switchHaptic)

        btnExport.setOnClickListener { launchExport() }
        btnImport.setOnClickListener { launchImport() }
        setupVibrationSection()
    }

    private fun setupVibrationSection() {
        // Populate RadioGroup with pattern names
        VibrationManager.PATTERNS.forEach { pat ->
            val rb = RadioButton(this).apply {
                id = pat.id
                text = pat.name
                textSize = 14f
                setPadding(8, 8, 8, 8)
            }
            rgVibPattern.addView(rb)
        }
        val savedPattern = prefs.getInt(VibrationManager.KEY_PATTERN, VibrationManager.DEFAULT_PATTERN)
        rgVibPattern.check(savedPattern)
        rgVibPattern.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(VibrationManager.KEY_PATTERN, id).apply()
        }

        // Preview button
        btnPreviewVib.setOnClickListener {
            val id = prefs.getInt(VibrationManager.KEY_PATTERN, VibrationManager.DEFAULT_PATTERN)
            VibrationManager.preview(this, id)
        }

        // Timeout slider — 0..900 seconds (0=no timeout, 900=15 min), step 15
        val savedTimeout = prefs.getInt(VibrationManager.KEY_TIMEOUT_SEC, VibrationManager.DEFAULT_TIMEOUT_SEC)
        sliderTimeout.value = savedTimeout.toFloat()
        tvTimeoutLabel.text = formatTimeout(savedTimeout)
        sliderTimeout.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(VibrationManager.KEY_TIMEOUT_SEC, sec).apply()
            tvTimeoutLabel.text = formatTimeout(sec)
        }

        // Haptic toggle
        switchHaptic.isChecked = prefs.getBoolean(VibrationManager.KEY_HAPTIC, VibrationManager.DEFAULT_HAPTIC)
        switchHaptic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(VibrationManager.KEY_HAPTIC, checked).apply()
        }
    }

    private fun formatTimeout(seconds: Int): String = when {
        seconds == 0   -> "No timeout"
        seconds < 60   -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else           -> "${seconds / 60}m ${seconds % 60}s"
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

    /**
     * 1. Tell the ViewModel to stop the timer, checkpoint WAL → main file,
     *    and close the Room connection.
     * 2. Copy the raw .db file bytes into the SAF URI the user chose.
     * 3. Room will reconnect automatically next time a DAO is used.
     */
    private fun performExport(uri: Uri) {
        setBusy(true, "Exporting…")
        lifecycleScope.launch {
            try {
                // Flush WAL and drop Room's file lock
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
        // Accept .db files — file managers may report various MIME types
        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restore Backup")
            .setMessage(
                "This will replace ALL current tasks with the selected database file.\n\n" +
                "Any running timer will be stopped and the app will restart.\n\n" +
                "Continue?"
            )
            .setPositiveButton("Restore") { _, _ -> performImport(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * 1. Stop the timer and close Room's connection (same as export prep).
     * 2. Overwrite the on-disk .db file with the bytes from the chosen URI.
     *    Also delete any stale WAL / SHM files so SQLite starts fresh.
     * 3. Restart the app so Room re-opens the replaced file cleanly.
     */
    private fun performImport(uri: Uri) {
        setBusy(true, "Importing…")
        lifecycleScope.launch {
            try {
                // Drop Room's connection before touching the file
                viewModel.prepareForDbExport()   // reuses the same checkpoint+close logic

                val dbFile: File = withContext(Dispatchers.IO) {
                    TaskDatabase.getDatabaseFile(applicationContext)
                }

                withContext(Dispatchers.IO) {
                    // Write the incoming bytes directly onto the existing DB file
                    contentResolver.openInputStream(uri)?.use { input ->
                        dbFile.outputStream().use { input.copyTo(it) }
                    } ?: error("Could not open the selected file")

                    // Remove WAL / SHM siblings so SQLite doesn't merge stale data
                    File("${dbFile.path}-wal").delete()
                    File("${dbFile.path}-shm").delete()
                }

                // Restart: launch MainActivity fresh and kill this back-stack
                val restart = packageManager.getLaunchIntentForPackage(packageName)!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(restart)
                // Give the intent a moment to land before the process exits
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
