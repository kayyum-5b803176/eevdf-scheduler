package com.eevdf.app.feature.task

import android.view.View
import android.widget.ArrayAdapter
import com.eevdf.data.task.Task
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

/**
 * Task type and notice-delay section for [AddTaskActivity].
 *
 * Separated so changes to task types (e.g. adding a new type), notice delay
 * format, or resume options don't touch the core activity file.
 *
 * Domain:
 *   • [taskTypeLabels] / [taskTypeValues]          — type dropdown entries
 *   • [resumeTypeLabels] / [resumeTypeValues]      — resume mode entries (global, all task types)
 *   • [AddTaskActivity.setupTaskTypeSection]        — spinners + delay TextWatchers
 *   • [parseDelayInput]                             — mm-ss → seconds (pure)
 *   • [formatDelaySecs]                             — seconds → readable string (pure)
 *   • [AddTaskActivity.populateTaskTypeSection]     — restores from existing task
 */

internal val taskTypeLabels = listOf("Default", "Notice", "Alert", "Custom")
internal val taskTypeValues = listOf("DEFAULT", "NOTIFICATION", "ALARM", "CUSTOM")

internal val resumeTypeLabels = listOf("Middle", "Initial")
internal val resumeTypeValues  = listOf("MIDDLE", "INITIAL")

internal fun AddTaskActivity.setupTaskTypeSection() {
    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, taskTypeLabels)
    actvTaskType.setAdapter(adapter)
    actvTaskType.setText(taskTypeLabels[taskTypeValues.indexOf(selectedTaskType).coerceAtLeast(0)], false)

    actvTaskType.setOnItemClickListener { _, _, pos, _ ->
        selectedTaskType = taskTypeValues.getOrElse(pos) { "DEFAULT" }
        layoutNoticeSection.visibility =
            if (selectedTaskType == "NOTIFICATION") View.VISIBLE else View.GONE
    }

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

    // Resume type is global — wired unconditionally regardless of task type.
    val resumeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resumeTypeLabels)
    actvNoticeResumeType.setAdapter(resumeAdapter)
}

/**
 * Parses mm-ss format (e.g. "01-30") into total seconds.
 * Also accepts plain seconds. Result is clamped to [0, 300].
 */
internal fun parseDelayInput(raw: String): Long {
    val trimmed = raw.trim()
    return if (trimmed.contains('-')) {
        val parts = trimmed.split('-')
        val mm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val ss = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        (mm * 60 + ss).coerceIn(0, 300)
    } else {
        trimmed.toLongOrNull()?.coerceIn(0, 300) ?: 0L
    }
}

internal fun formatDelaySecs(secs: Long): String = when {
    secs == 0L        -> "0s (no delay)"
    secs < 60         -> "${secs}s"
    secs % 60 == 0L   -> "${secs / 60} min"
    else              -> "${secs / 60}m ${secs % 60}s"
}

/** Restores task type spinner and notice fields from [task]. */
internal fun AddTaskActivity.populateTaskTypeSection(task: Task) {
    val typeIdx = taskTypeValues.indexOf(task.taskType).coerceAtLeast(0)
    actvTaskType.setText(taskTypeLabels[typeIdx], false)
    selectedTaskType = task.taskType

    // Resume type is global — always restored regardless of task type.
    val resumeIdx = resumeTypeValues.indexOf(task.resumeType).coerceAtLeast(0)
    actvNoticeResumeType.setText(resumeTypeLabels[resumeIdx], false)

    // Notice-only fields — only shown and populated for NOTIFICATION tasks.
    if (task.taskType != "NOTIFICATION") return

    layoutNoticeSection.visibility = View.VISIBLE
    val dm = task.notificationDelaySeconds
    etNotifDelay.setText(if (dm == 0L) "" else "%02d-%02d".format(dm / 60, dm % 60))
    val rm = task.notificationRestSeconds
    etNoticeRest.setText(if (rm == 0L) "" else "%02d-%02d".format(rm / 60, rm % 60))
    etNoticeRepeat.setText(if (task.notificationRepeatCount == 0) "" else task.notificationRepeatCount.toString())
}
