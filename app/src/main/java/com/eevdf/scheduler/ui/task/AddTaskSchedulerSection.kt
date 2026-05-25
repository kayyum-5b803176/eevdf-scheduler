package com.eevdf.scheduler.ui.task

import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.scheduler.RtScheduler
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

/**
 * Scheduler class section for [AddTaskActivity].
 *
 * Covers the scheduler-class dropdown and the two conditional sub-sections:
 *   • DL (SCHED_DEADLINE) — runtime, deadline, period
 *   • RT (SCHED_FIFO / SCHED_RR) — priority slider, policy, day checkboxes, time fields
 *
 * Separated so adding a new scheduler class or changing DL/RT field semantics
 * (e.g. adding a SCHED_ISO option) only touches this file.
 *
 * Domain:
 *   • [schedulerClassValues] / [schedulerClassLabels] / [schedulerClassDescriptions]
 *   • [AddTaskActivity.setupSchedulerClassSection]
 *   • [AddTaskActivity.populateSchedulerSection]
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
