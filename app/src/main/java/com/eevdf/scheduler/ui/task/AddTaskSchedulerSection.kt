package com.eevdf.scheduler.ui.task

import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.scheduler.RtScheduler
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scheduler class section for [AddTaskActivity].
 *
 * Covers the scheduler-class dropdown and the two conditional sub-sections:
 *   • DL (SCHED_DEADLINE) — runtime, deadline, period, RT Sync
 *   • RT (SCHED_FIFO / SCHED_RR) — priority slider, policy, day checkboxes, time fields
 *
 * Separated so adding a new scheduler class or changing DL/RT field semantics
 * (e.g. adding a SCHED_ISO option) only touches this file.
 *
 * Domain:
 *   • [schedulerClassValues] / [schedulerClassLabels] / [schedulerClassDescriptions]
 *   • [AddTaskActivity.setupSchedulerClassSection]
 *   • [AddTaskActivity.populateSchedulerSection]
 *
 * RT Sync (DL sub-section):
 *   • [AddTaskActivity.pendingDlPeriodStartEpoch] — epoch ms captured by the button;
 *     0L means "not synced, do not override the stored value"
 *   • [formatRtSyncTimestamp] — converts epoch ms → "yyyy-MM-dd HH:mm:ss"
 */

// Ordered by Linux priority: highest → lowest
internal val schedulerClassValues = listOf(
    "stop_sched_class",
    "dl_sched_class",
    "rt_sched_class",
    "fair_sched_class",
    "idle_sched_class"
)
internal val schedulerClassLabels = listOf(
    "Stop — CPU migration / kernel",
    "Deadline EDF (SCHED_DEADLINE)",
    "Realtime — FIFO / RR",
    "Normal EEVDF — default",
    "Idle — lowest priority"
)
internal val schedulerClassDescriptions = listOf(
    "Kernel-internal class for CPU stop and migration tasks. Not intended for user tasks.",
    "Earliest Deadline First. Requires runtime and deadline parameters below.",
    "Fixed-priority realtime scheduling (SCHED_FIFO or SCHED_RR). Priority set via slider.",
    "Normal CFS / EEVDF scheduling. Default for all user tasks.",
    "Runs only when no other work exists. Lowest possible CPU priority (SCHED_IDLE)."
)

/** Format for the RT Sync timestamp display field. */
private val RT_SYNC_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/** Converts an epoch-ms value to the display string used by the RT Sync field. */
internal fun formatRtSyncTimestamp(epochMs: Long): String = RT_SYNC_FMT.format(Date(epochMs))

// ─────────────────────────────────────────────────────────────────────────────

internal fun AddTaskActivity.setupSchedulerClassSection() {
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, schedulerClassLabels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinnerSchedulerClass.adapter = adapter
    // Default to fair_sched_class (index 3)
    spinnerSchedulerClass.setSelection(schedulerClassValues.indexOf("fair_sched_class"))

    switchSchedulerEnabled.setOnCheckedChangeListener { _, checked ->
        layoutSchedulerFields.visibility = if (checked) View.VISIBLE else View.GONE
        if (!checked) {
            tvSchedulerWarning.visibility = View.GONE
            layoutDlFields.visibility     = View.GONE
            tvDlError.visibility          = View.GONE
            layoutRtFields.visibility     = View.GONE
            tvRtError.visibility          = View.GONE
        }
    }

    spinnerSchedulerClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
            val cls = schedulerClassValues.getOrElse(pos) { "fair_sched_class" }
            tvSchedulerClassDesc.text     = schedulerClassDescriptions.getOrElse(pos) { "" }
            layoutDlFields.visibility     = if (cls == "dl_sched_class")   View.VISIBLE else View.GONE
            layoutRtFields.visibility     = if (cls == "rt_sched_class")   View.VISIBLE else View.GONE
            tvSchedulerWarning.visibility = if (cls == "stop_sched_class") View.VISIBLE else View.GONE
            if (cls != "dl_sched_class") tvDlError.visibility = View.GONE
            if (cls != "rt_sched_class") tvRtError.visibility = View.GONE
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    // Live previews for DL fields — reuse the quota duration parser/formatter
    fun watchDlField(et: TextInputEditText, preview: TextView) {
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val secs = parseQuotaInput(s?.toString() ?: "")
                preview.text = if (secs > 0L) formatQuotaDuration(secs) else ""
            }
        })
    }
    watchDlField(etDlRuntime,  tvDlRuntimePreview)
    watchDlField(etDlDeadline, tvDlDeadlinePreview)
    watchDlField(etDlPeriod,   tvDlPeriodPreview)

    // ── RT Sync button (DL sub-section) ───────────────────────────────────────
    //
    // Tapping btnDlRtSync captures System.currentTimeMillis() into
    // pendingDlPeriodStartEpoch and reflects it in tvDlRtSyncValue.
    //
    // If a non-zero epoch is already pending (either freshly set or restored from
    // an existing task via populateSchedulerSection), an AlertDialog warns the user
    // that the existing period start will be overwritten before proceeding.

    btnDlRtSync.setOnClickListener {
        if (pendingDlPeriodStartEpoch != 0L) {
            // Warn: overwriting an already-set epoch resets the period clock
            AlertDialog.Builder(this)
                .setTitle("Reset period start?")
                .setMessage(
                    "A period start is already set:\n" +
                    "${formatRtSyncTimestamp(pendingDlPeriodStartEpoch)}\n\n" +
                    "Syncing now will overwrite it with the current device time " +
                    "and reset the SCHED_DEADLINE period clock. Continue?"
                )
                .setPositiveButton("Sync now") { _, _ ->
                    applyRtSync()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            applyRtSync()
        }
    }

    // ── RT section setup ──────────────────────────────────────────────────────

    sliderRtPriority.valueFrom = 1f
    sliderRtPriority.valueTo   = 99f
    sliderRtPriority.stepSize  = 1f
    sliderRtPriority.value     = 50f
    tvRtPriorityValue.text     = "50"
    sliderRtPriority.addOnChangeListener { _, value, _ ->
        tvRtPriorityValue.text = value.toInt().toString()
    }

    val rtPolicyAdapter = ArrayAdapter(
        this, android.R.layout.simple_spinner_item,
        listOf("RR — Round Robin", "FIFO — First In First Out")
    )
    rtPolicyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinnerRtPolicy.adapter = rtPolicyAdapter
    spinnerRtPolicy.setSelection(0)   // default RR

    etRtSliceTimeout.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val secs = parseQuotaInput(s?.toString() ?: "")
            tvRtSliceTimeoutPreview.text = if (secs > 0L) formatQuotaDuration(secs) else ""
        }
    })
}

/** Stamps [pendingDlPeriodStartEpoch] with now and updates the display. */
private fun AddTaskActivity.applyRtSync() {
    pendingDlPeriodStartEpoch = System.currentTimeMillis()
    tvDlRtSyncValue.text = formatRtSyncTimestamp(pendingDlPeriodStartEpoch)
}

// ─────────────────────────────────────────────────────────────────────────────

/** Restores scheduler class, DL, and RT fields from [task]. */
internal fun AddTaskActivity.populateSchedulerSection(task: Task) {
    if (!task.isSchedulerClassOverridden) return

    switchSchedulerEnabled.isChecked    = true
    layoutSchedulerFields.visibility    = View.VISIBLE

    val schedIdx = schedulerClassValues.indexOf(task.schedulerClass).coerceAtLeast(0)
    spinnerSchedulerClass.setSelection(schedIdx)
    tvSchedulerClassDesc.text   = schedulerClassDescriptions.getOrElse(schedIdx) { "" }
    tvSchedulerWarning.visibility =
        if (task.schedulerClass == "stop_sched_class") View.VISIBLE else View.GONE

    if (task.schedulerClass == "dl_sched_class") {
        layoutDlFields.visibility = View.VISIBLE
        if (task.dlRuntimeSeconds  > 0L) etDlRuntime.setText(formatQuotaDuration(task.dlRuntimeSeconds))
        if (task.dlDeadlineSeconds > 0L) etDlDeadline.setText(formatQuotaDuration(task.dlDeadlineSeconds))
        if (task.dlPeriodSeconds   > 0L) etDlPeriod.setText(formatQuotaDuration(task.dlPeriodSeconds))

        // Restore existing period start into the RT Sync field so the user can
        // see the current anchor and the overwrite-warning fires correctly.
        if (task.dlPeriodStartEpoch > 0L) {
            pendingDlPeriodStartEpoch = task.dlPeriodStartEpoch
            tvDlRtSyncValue.text = formatRtSyncTimestamp(task.dlPeriodStartEpoch)
        }
    }

    if (task.schedulerClass == "rt_sched_class") {
        layoutRtFields.visibility  = View.VISIBLE
        sliderRtPriority.value     = task.rtPriority.toFloat()
        tvRtPriorityValue.text     = task.rtPriority.toString()
        val policyIdx = if (task.rtPolicy == "FIFO") 1 else 0   // 0=RR 1=FIFO
        spinnerRtPolicy.setSelection(policyIdx)
        cbRtSun.isChecked = (task.rtActiveDays and RtScheduler.DAY_SUN) != 0
        cbRtMon.isChecked = (task.rtActiveDays and RtScheduler.DAY_MON) != 0
        cbRtTue.isChecked = (task.rtActiveDays and RtScheduler.DAY_TUE) != 0
        cbRtWed.isChecked = (task.rtActiveDays and RtScheduler.DAY_WED) != 0
        cbRtThu.isChecked = (task.rtActiveDays and RtScheduler.DAY_THU) != 0
        cbRtFri.isChecked = (task.rtActiveDays and RtScheduler.DAY_FRI) != 0
        cbRtSat.isChecked = (task.rtActiveDays and RtScheduler.DAY_SAT) != 0
        etRtHour.setText(task.rtActivationHour.toString())
        etRtMinute.setText(String.format("%02d", task.rtActivationMinute))
        etRtSecond.setText(String.format("%02d", task.rtActivationSecond))
        if (task.rtSliceTimeoutSeconds > 0L)
            etRtSliceTimeout.setText(formatQuotaDuration(task.rtSliceTimeoutSeconds))
    }
}
