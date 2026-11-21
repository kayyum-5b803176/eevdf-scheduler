package com.eevdf.capabilities.addtaskscreen

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.taskstorage.AxisChecklist
import com.eevdf.capabilities.taskstorage.AxisRules
import com.eevdf.capabilities.taskstorage.Axes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * "Which entries of the NEXT axis does this entry include?" — a checklist of that
 * axis's definition tree (indented), everything on by default.
 *
 * Unchecking a group unchecks everything inside it (children follow the parent);
 * only the topmost switched-off entries are stored. The scope is this entry's own
 * id, so each entry of the first axis keeps its own list (user-1: only a;
 * user-2: b, c, a). Editing a definition elsewhere changes every scope at once.
 */
internal fun AddTaskActivity.showAxisInclusionDialog() {
    val outer = existingTaskId ?: return
    val nextAxis = Axes.next(axisId) ?: return
    lifecycleScope.launch {
        val defs = viewModel.axes.definitions(nextAxis)
        if (defs.isEmpty()) {
            Toast.makeText(this@showAxisInclusionDialog, "No ${nextAxis.uppercase()} entries defined yet", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val excluded = viewModel.axes.exclusions(outer, nextAxis)
        val rows     = AxisChecklist.flatten(defs)
        val byId     = defs.associateBy { it.id }
        val checked  = rows.map { AxisRules.isIncluded(it.entry, byId, excluded) }.toBooleanArray()
        val labels   = rows.map { "    ".repeat(it.depth) + (if (it.entry.isGroup) "\u25B8 " else "") + it.entry.name }.toTypedArray()

        MaterialAlertDialogBuilder(this@showAxisInclusionDialog)
            .setTitle("Include on ${nextAxis.uppercase()}")
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                checked[which] = isChecked
                // Children follow the parent: cascade the new state down the visible list.
                val list = (dialog as AlertDialog).listView
                var i = which + 1
                while (i < rows.size && rows[i].depth > rows[which].depth) {
                    checked[i] = isChecked; list.setItemChecked(i, isChecked); i++
                }
            }
            .setPositiveButton("Save") { _, _ ->
                val state = rows.indices.associate { rows[it].entry.id to checked[it] }
                lifecycleScope.launch { viewModel.axes.saveInclusion(outer, nextAxis, defs, state) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
