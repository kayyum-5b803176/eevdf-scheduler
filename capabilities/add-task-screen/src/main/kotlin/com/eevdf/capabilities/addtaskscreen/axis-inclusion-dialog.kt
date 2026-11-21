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
    val self = existingTaskId ?: return
    val index = Axes.ALL.indexOf(axisId)
    val nextAxis = Axes.next(axisId) ?: return
    when (index) {
        0 -> showInclusionChecklist(scope = self, nextAxis = nextAxis)
        1 -> lifecycleScope.launch {
            // This entry lives inside a first-axis leaf; the switches belong to that pairing ("<outer>/<this>").
            val outers = viewModel.axes.definitions(Axes.ALL[0]).filter { !it.isGroup }.sortedBy { it.name.lowercase() }
            if (outers.isEmpty()) return@launch
            MaterialAlertDialogBuilder(this@showAxisInclusionDialog)
                .setTitle("Inside which ${Axes.ALL[0].uppercase()} entry?")
                .setItems(outers.map { it.name }.toTypedArray()) { _, i ->
                    showInclusionChecklist(scope = Axes.scopeKey(listOf(outers[i].id, self)), nextAxis = nextAxis)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        else -> Toast.makeText(this, "Open this from its outer entry", Toast.LENGTH_SHORT).show()
    }
}

private fun AddTaskActivity.showInclusionChecklist(scope: String, nextAxis: String) {
    lifecycleScope.launch {
        val defs = viewModel.axes.definitions(nextAxis)
        if (defs.isEmpty()) {
            Toast.makeText(this@showInclusionChecklist, "No ${nextAxis.uppercase()} entries defined yet", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val excluded = viewModel.axes.exclusions(scope, nextAxis)
        val rows     = AxisChecklist.flatten(defs)
        val byId     = defs.associateBy { it.id }
        val checked  = rows.map { AxisRules.isIncluded(it.entry, byId, excluded) }.toBooleanArray()
        val labels   = rows.map { "    ".repeat(it.depth) + (if (it.entry.isGroup) "\u25B8 " else "") + it.entry.name }.toTypedArray()

        MaterialAlertDialogBuilder(this@showInclusionChecklist)
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
                lifecycleScope.launch { viewModel.axes.saveInclusion(scope, nextAxis, defs, state) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
