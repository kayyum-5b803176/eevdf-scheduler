package com.eevdf.scheduler.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.switchmaterial.SwitchMaterial

class AutoSwitchActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var switchCallDetection: SwitchMaterial
    private lateinit var layoutCallTaskPicker: View
    private lateinit var actvCallTask:         AutoCompleteTextView
    private lateinit var tvCallTaskHint:        TextView
    private lateinit var tvPermissionStatus:   TextView

    /** Live snapshot of non-completed, non-group leaf tasks for the picker. */
    private var taskList: List<Task> = emptyList()

    // ── Permission launcher ───────────────────────────────────────────────────

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission just granted — enable call detection
            AutoSwitchPrefs.setCallDetectionEnabled(this, true)
            applyToggleUi(true)
        } else {
            // Denied — revert switch
            switchCallDetection.isChecked = false
            showPermissionDeniedDialog()
        }
        refreshPermissionBadge()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_switch)

        val toolbar = findViewById<Toolbar>(R.id.autoSwitchToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Auto Switch"

        switchCallDetection  = findViewById(R.id.switchCallDetection)
        layoutCallTaskPicker = findViewById(R.id.layoutCallTaskPicker)
        actvCallTask         = findViewById(R.id.actvCallTask)
        tvCallTaskHint       = findViewById(R.id.tvCallTaskHint)
        tvPermissionStatus   = findViewById(R.id.tvPermissionStatus)

        // Restore saved state
        val enabled = AutoSwitchPrefs.isCallDetectionEnabled(this)
        switchCallDetection.isChecked = enabled
        applyToggleUi(enabled)
        refreshPermissionBadge()

        // Show previously-selected task name in the field
        AutoSwitchPrefs.getCallTaskName(this)?.let { actvCallTask.setText(it) }

        switchCallDetection.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                when {
                    hasPhonePermission() -> {
                        AutoSwitchPrefs.setCallDetectionEnabled(this, true)
                        applyToggleUi(true)
                    }
                    shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) -> {
                        showPermissionRationale()
                    }
                    else -> {
                        requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
                    }
                }
            } else {
                AutoSwitchPrefs.setCallDetectionEnabled(this, false)
                applyToggleUi(false)
            }
        }

        setupTaskPicker()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Task picker ───────────────────────────────────────────────────────────

    private fun setupTaskPicker() {
        viewModel.activeTasks.observe(this) { tasks ->
            // Only leaf (non-group), non-completed, non-interrupt tasks
            taskList = tasks.filter { !it.isGroup && !it.isCompleted && !it.isInterrupt }

            val names = taskList.map { it.name }
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                names
            )
            actvCallTask.setAdapter(adapter)
        }

        actvCallTask.setOnItemClickListener { _, _, position, _ ->
            val adapter = actvCallTask.adapter ?: return@setOnItemClickListener
            val name    = adapter.getItem(position) as? String ?: return@setOnItemClickListener
            // Find the matching task by name
            val task = taskList.firstOrNull { it.name == name } ?: return@setOnItemClickListener
            AutoSwitchPrefs.setCallTask(this, task.id, task.name)
            tvCallTaskHint.text = "Assigned: \"${task.name}\""
            tvCallTaskHint.visibility = View.VISIBLE
        }

        // Restore hint if task was already assigned
        AutoSwitchPrefs.getCallTaskName(this)?.let { name ->
            tvCallTaskHint.text = "Assigned: \"$name\""
            tvCallTaskHint.visibility = View.VISIBLE
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun applyToggleUi(enabled: Boolean) {
        layoutCallTaskPicker.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun refreshPermissionBadge() {
        if (hasPhonePermission()) {
            tvPermissionStatus.text       = "Phone permission granted"
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvPermissionStatus.text       = "Phone permission not granted"
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Phone Permission Required")
            .setMessage(
                "Call Detection needs to read your phone call state to automatically " +
                "pause and resume tasks when a call starts or ends.\n\n" +
                "No call content or phone numbers are accessed."
            )
            .setPositiveButton("Grant Permission") { _, _ ->
                requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
            .setNegativeButton("Cancel") { _, _ ->
                switchCallDetection.isChecked = false
            }
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage(
                "Call Detection is disabled because phone permission was denied.\n\n" +
                "To enable it, grant the permission in your device's App Settings."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
