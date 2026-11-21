package com.eevdf.capabilities.addtaskscreen

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.taskstorage.Task
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Moving a row to another axis (choosing a group on that axis in the parent group picker) can lose
 * things, so Save says exactly what before doing it — and says nothing when nothing would be lost.
 * Confirming sets [AddTaskActivity.axisMoveConfirmed] and runs Save again.
 */
internal fun AddTaskActivity.confirmAxisMove(row: Task) {
    lifecycleScope.launch {
        val cost = viewModel.axes.movePreview(row, targetAxisId)
        if (cost.blockedByRunning) {
            Toast.makeText(this@confirmAxisMove, "Stop it first — something in it is running.", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (!cost.hasLoss) {
            axisMoveConfirmed = true
            saveTask()
            return@launch
        }
        val lines = buildList {
            if (cost.resetsRuntime) add("Its timer progress, run counts and completion are reset — a template never runs itself.")
            if (cost.copiesRemoved > 0) add(
                "${cost.copiesRemoved} cop${if (cost.copiesRemoved == 1) "y" else "ies"} of it " +
                    "(and what hangs under them) ${if (cost.copiesRemoved == 1) "is" else "are"} removed.",
            )
            if (cost.linksRemoved > 0) add(
                "${cost.linksRemoved} hardlink${if (cost.linksRemoved == 1) "" else "s"}/symlink${if (cost.linksRemoved == 1) "" else "s"} " +
                    "to or inside it ${if (cost.linksRemoved == 1) "is" else "are"} removed.",
            )
            if (cost.rowsMoved > 1) add("${cost.rowsMoved} items move together.")
        }
        MaterialAlertDialogBuilder(this@confirmAxisMove)
            .setTitle("Move \"${row.name}\" to ${targetAxisId.uppercase()}?")
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("Move") { _, _ -> axisMoveConfirmed = true; saveTask() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
