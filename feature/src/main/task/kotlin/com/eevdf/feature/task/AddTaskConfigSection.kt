package com.eevdf.feature.task

import android.view.View
import android.widget.ArrayAdapter
import com.eevdf.data.task.Task
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

/**
 * Task Config card setup and populate for [AddTaskActivity].
 *
 * Owns the task profile dropdown, the notice-only sub-fields that expand when
 * "Notice" is selected, and the global resume type dropdown.
 *
 * Moved here from AddTaskTypeSection.kt so that each card in the form has its
 * own dedicated section file.
 *
 * Domain:
 *   • [AddTaskActivity.setupTaskConfigSection]    — wires dropdowns and notice field watchers
 *   • [AddTaskActivity.populateTaskConfigSection] — restores all fields from existing task
 */

internal fun AddTaskActivity.setupTaskConfigSection() {
    // ── Task Profile dropdown ─────────────────────────────────────────────────
    val typeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, taskTypeLabels)
    actvTaskType.setAdapter(typeAdapter)
    actvTaskType.setText(taskTypeLabels[taskTypeValues.indexOf(selectedTaskType).coerceAtLeast(0)], false)

    actvTaskType.setOnItemClickListener { _, _, pos, _ ->
        selectedTaskType = taskTypeValues.getOrElse(pos) { "DEFAULT" }
        layoutNoticeSection.visibility =
            if (selectedTaskType == "NOTIFICATION") View.VISIBLE else View.GONE
    }

    // ── Notice-only field watchers ────────────────────────────────────────────
    fun watchDelay(et: TextInputEditText, preview: TextView) {
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                preview.text = formatDelaySecs(parseDelayInput(s?.toString() ?: ""))
            }
        })
    }
    watchDelay(etNotifDelay, tvNotifDelayPreview)
    watchDelay(etNoticeRest, tvNoticeRestPreview)

    // ── Resume type dropdown (global — always visible) ────────────────────────
    val resumeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resumeTypeLabels)
    actvResumeType.setAdapter(resumeAdapter)
}

/** Restores task profile, notice fields, and resume type from [task]. */
internal fun AddTaskActivity.populateTaskConfigSection(task: Task) {
    // Task profile
    val typeIdx = taskTypeValues.indexOf(task.taskType).coerceAtLeast(0)
    actvTaskType.setText(taskTypeLabels[typeIdx], false)
    selectedTaskType = task.taskType

    // Resume type — global, always restored
    val resumeIdx = resumeTypeValues.indexOf(task.resumeType).coerceAtLeast(0)
    actvResumeType.setText(resumeTypeLabels[resumeIdx], false)

    // Notice-only fields — only shown and populated for NOTIFICATION tasks
    if (task.taskType != "NOTIFICATION") return

    layoutNoticeSection.visibility = View.VISIBLE
    val dm = task.notificationDelaySeconds
    etNotifDelay.setText(if (dm == 0L) "" else "%02d-%02d".format(dm / 60, dm % 60))
    val rm = task.notificationRestSeconds
    etNoticeRest.setText(if (rm == 0L) "" else "%02d-%02d".format(rm / 60, rm % 60))
    etNoticeRepeat.setText(if (task.notificationRepeatCount == 0) "" else task.notificationRepeatCount.toString())
}
