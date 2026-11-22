package com.eevdf.capabilities.addtaskscreen

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.settingsstorage.state.AxisPrefs
import com.eevdf.capabilities.taskstorage.AxisCopy
import com.eevdf.capabilities.taskstorage.Axes
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
        val cost = viewModel.axes.movePreview(row, targetAxisId, axisPathRoute)
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
            if (cost.resetsRuntime) add("Its timer progress and completion are reset — a template never runs itself. Total time and runs are kept.")
            if (cost.copiesRemoved > 0) add(
                "${cost.copiesRemoved} cop${if (cost.copiesRemoved == 1) "y" else "ies"} of it " +
                    "(and what hangs under them) ${if (cost.copiesRemoved == 1) "is" else "are"} removed. " +
                    "What ${if (cost.copiesRemoved == 1) "it" else "they"} ran stays counted.",
            )
            if (cost.linksRemoved > 0) add(
                "${cost.linksRemoved} hardlink${if (cost.linksRemoved == 1) "" else "s"}/symlink${if (cost.linksRemoved == 1) "" else "s"} " +
                    "to or inside it ${if (cost.linksRemoved == 1) "is" else "are"} removed.",
            )
            if (cost.rowsMoved > 1) add("${cost.rowsMoved} items move together.")
        }
        MaterialAlertDialogBuilder(this@confirmAxisMove)
            .setTitle(
                if (targetAxisId != row.axisId) "Move \"${row.name}\" to ${targetAxisId.uppercase()}?"
                else "Place \"${row.name}\" only on the chosen route?",
            )
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("Move") { _, _ -> axisMoveConfirmed = true; saveTask() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * With "hide new" on, a new entry is not copied anywhere by itself. Save asks, in plain words, only when there is something
 * to copy: for a new later-axis entry, whether to copy it into the places it could appear; for a new place (a first-axis leaf),
 * whether to copy the existing entries into it. "Don't copy" saves it alone — it can still be added later with + in an ACL tab
 * or placed on a path. "Copy" is just '+' rules written before the row, so nothing else changes.
 *
 * Never asked when the "Ask before copying" setting is off (then nothing is copied), when nothing can be copied, or in path
 * mode (the path already chose). Answering sets [AddTaskActivity.axisCopyAsked] and runs Save again.
 */
internal fun AddTaskActivity.offerAxisCopy(taskId: String, name: String, isGroup: Boolean, parentId: String?, interrupt: Boolean) {
    lifecycleScope.launch {
        val offer = when {
            !AxisPrefs.isAskBeforeCopy(this@offerAxisCopy) -> null
            targetAxisId != Axes.DEFAULT -> viewModel.axes.copyOfferForEntry(taskId, targetAxisId, parentId, name)
            !isGroup && !interrupt       -> viewModel.axes.copyOfferForPlace(taskId, name)
            else                         -> null
        }
        if (offer == null) {
            axisCopyRules = emptyList(); axisCopyAsked = true
            saveTask()
            return@launch
        }
        val n = offer.count
        val (title, message, yes) = when (offer.kind) {
            AxisCopy.Kind.ENTRY -> Triple(
                "Copy \"${offer.name}\"?",
                "It can be copied into $n place${if (n == 1) "" else "s"}.",
                "Copy to all $n",
            )
            AxisCopy.Kind.PLACE -> Triple(
                "Copy existing entries into \"${offer.name}\"?",
                "$n entr${if (n == 1) "y" else "ies"} on ${offer.axisId.uppercase()} can be copied here.",
                "Copy all $n",
            )
        }
        MaterialAlertDialogBuilder(this@offerAxisCopy)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> axisCopyRules = offer.rules; axisCopyAsked = true; saveTask() }
            .setNegativeButton("Don't copy") { _, _ -> axisCopyRules = emptyList(); axisCopyAsked = true; saveTask() }
            .show()
    }
}

