package com.eevdf.feature.task.addtask

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.eevdf.data.task.Task

/**
 * Time Slice inheritance section for [AddTaskActivity].
 *
 * Mirrors the load-factor inheritance pattern exactly, extended to cover three
 * fields (hours, minutes, seconds) instead of one.
 *
 * Layout: | Time Slice         (auto) |
 *         | [HH] h  [MM] m  [SS] s   |
 *
 * Domain:
 *   • [setupTimeSliceField]      — attaches TextWatchers on all three fields;
 *                                  hides badge on any manual edit
 *   • [applyParentTimeSlice]     — fills all three fields + shows badge;
 *                                  called by [GroupSection] on parent change
 *   • [populateTimeSliceSection] — restores fields + badge from saved task
 *
 * Inheritance rules:
 *   • New task under a parent → fields pre-filled, badge shown.
 *   • Parent changes while badge is visible → all three fields sync to new parent.
 *   • Parent deselected while badge is visible → inherited flag cleared, badge
 *     hidden, existing values kept (no silent zeroing — user sees what was there).
 *   • User edits any field → badge hidden, [isTimeSliceInherited] cleared.
 *   • Existing task with timeSliceInherited == true → badge shown on open;
 *     any keystroke in any field turns it manual.
 */

/**
 * Attaches a [TextWatcher] to each of the three time-slice fields.
 * Any user keystroke in any field clears [AddTaskActivity.isTimeSliceInherited]
 * and hides [AddTaskActivity.tvTimeSliceAutoLabel].
 *
 * [AddTaskActivity.suppressTimeSliceWatcher] guards against programmatic
 * [android.widget.EditText.setText] calls triggering the same path.
 */
internal fun AddTaskActivity.setupTimeSliceField() {
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!suppressTimeSliceWatcher && isTimeSliceInherited) {
                isTimeSliceInherited = false
                tvTimeSliceAutoLabel.visibility = View.GONE
            }
        }
    }
    etHours.addTextChangedListener(watcher)
    etMinutes.addTextChangedListener(watcher)
    etSeconds.addTextChangedListener(watcher)
}

/**
 * Fills the three time-slice fields from [seconds] and marks the section as
 * inherited from the parent group.
 */
internal fun AddTaskActivity.applyParentTimeSlice(seconds: Long) {
    isTimeSliceInherited = true
    suppressTimeSliceWatcher = true
    etHours.setText((seconds / 3600).toString())
    etMinutes.setText(((seconds % 3600) / 60).toString())
    etSeconds.setText((seconds % 60).toString())
    suppressTimeSliceWatcher = false
    tvTimeSliceAutoLabel.visibility = View.VISIBLE
}

/**
 * Restores the time-slice fields and badge from [task] when editing an existing task.
 */
internal fun AddTaskActivity.populateTimeSliceSection(task: Task) {
    val s = task.timeSliceSeconds
    suppressTimeSliceWatcher = true
    etHours.setText((s / 3600).toString())
    etMinutes.setText(((s % 3600) / 60).toString())
    etSeconds.setText((s % 60).toString())
    suppressTimeSliceWatcher = false
    isTimeSliceInherited = task.timeSliceInherited
    tvTimeSliceAutoLabel.visibility =
        if (task.timeSliceInherited) View.VISIBLE else View.GONE
}
