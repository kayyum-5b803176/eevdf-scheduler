package com.eevdf.app.feature.task

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.eevdf.data.task.Task

/**
 * Load Factor inheritance section for [AddTaskActivity].
 *
 * The section title ("Load Factor") never changes.  Inheritance state is
 * communicated instead by showing / hiding [AddTaskActivity.tvLoadFactorAutoLabel]
 * — a right-aligned "(auto)" badge in the same header row — so the indicator
 * is visually associated with the value, not the label.
 *
 * Layout: | Load Factor        (auto) |
 *         | [         1.00          ] |
 *
 * Domain:
 *   • [setupLoadFactorField]      — TextWatcher; hides badge on manual edit
 *   • [applyParentLoadFactor]     — fills field + shows badge; called by
 *                                   [AddTaskGroupSection] on parent change
 *   • [populateLoadFactorSection] — restores field + badge from saved task
 *
 * Inheritance rules:
 *   • New task under a parent → field pre-filled, badge shown.
 *   • Parent changes while badge is visible → value syncs to new parent.
 *   • Parent deselected while badge is visible → resets to 1.00, badge hidden.
 *   • User edits the field → badge hidden, [isLoadFactorInherited] cleared.
 *   • Existing task with loadFactorInherited == true → badge shown on open.
 */

internal fun AddTaskActivity.setupLoadFactorField() {
    etLoadFactor.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!suppressLoadFactorWatcher && isLoadFactorInherited) {
                isLoadFactorInherited = false
                tvLoadFactorAutoLabel.visibility = View.GONE
            }
        }
    })
}

internal fun AddTaskActivity.applyParentLoadFactor(factor: Double) {
    isLoadFactorInherited = true
    suppressLoadFactorWatcher = true
    etLoadFactor.setText("%.2f".format(factor))
    suppressLoadFactorWatcher = false
    tvLoadFactorAutoLabel.visibility = View.VISIBLE
}

internal fun AddTaskActivity.populateLoadFactorSection(task: Task) {
    suppressLoadFactorWatcher = true
    etLoadFactor.setText("%.2f".format(task.loadFactor))
    suppressLoadFactorWatcher = false
    isLoadFactorInherited = task.loadFactorInherited
    tvLoadFactorAutoLabel.visibility =
        if (task.loadFactorInherited) View.VISIBLE else View.GONE
}
