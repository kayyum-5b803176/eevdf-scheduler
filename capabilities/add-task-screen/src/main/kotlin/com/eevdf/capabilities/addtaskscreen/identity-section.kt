package com.eevdf.capabilities.addtaskscreen

import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.taskstorage.IdentityKind
import com.eevdf.capabilities.taskstorage.IdentityRules
import com.eevdf.capabilities.taskstorage.Task
import kotlinx.coroutines.launch

/**
 * Identity section of [AddTaskActivity] — replaces the old free-text Category field.
 *
 * A task's identity is a KIND the person picks and an ID the system generates, shown
 * as `kind:id` everywhere else (`process:101`). The person never types the ID, the way
 * a PID is never typed: it is the smallest free number >= 101 in that kind's own pool
 * (1-100 are reserved for the system — see IdentityRules in task-storage).
 *
 * Domain:
 *   • [AddTaskActivity.setupIdentityInput]        — wires the Kind dropdown
 *   • [AddTaskActivity.refreshIdentityId]         — recomputes the number shown for the chosen kind
 *   • [AddTaskActivity.populateIdentitySection]   — restores kind + number from an existing task
 *   • [AddTaskActivity.identityForSave]           — the `kind:id` the save handler writes
 *
 * What is shown is a PREVIEW. Nothing is reserved until Save: the repository picks the
 * final number under a lock, so two saves at the same moment can never take the same one.
 *
 * Edit rules:
 *   • Kind left alone  → the task keeps the number it has.
 *   • Kind changed     → the number shown is the smallest free one in the NEW kind's pool.
 *   • Kind put back    → the original number is shown again.
 * The actual move to the new pool happens on Save (TaskRepository.changeIdentityKind).
 */

internal val identityKinds: List<IdentityKind> = IdentityKind.values().toList()
internal val identityKindLabels: List<String> = identityKinds.map { it.label }

/** Wires the Kind dropdown and shows the number for the starting kind. */
internal fun AddTaskActivity.setupIdentityInput() {
    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, identityKindLabels)
    actvIdentityKind.setAdapter(adapter)
    actvIdentityKind.setText(selectedIdentityKind.label, false)

    actvIdentityKind.setOnItemClickListener { _, _, pos, _ ->
        selectedIdentityKind = identityKinds.getOrElse(pos) { IdentityKind.DEFAULT }
        revalidateParentForKind()          // the parent picker follows the kind; a parent it no longer allows is cleared
        refreshIdentityId()
    }
    refreshIdentityId()
}

/**
 * Shows the number for [AddTaskActivity.selectedIdentityKind] and names it for that
 * kind (UID, GID, PID, TID, PGID, SID, or ID for Undefined).
 */
internal fun AddTaskActivity.refreshIdentityId() {
    val kind = selectedIdentityKind
    tilIdentityId.hint = kind.idLabel

    // Editing and the kind is the one the task already has (never touched, or put back):
    // it keeps its own number — nothing to look up.
    val owned = existingTask?.let { IdentityRules.parse(it.identity) }
    if (owned != null && owned.kind == kind) {
        showIdentityId(owned.id)
        return
    }

    lifecycleScope.launch {
        val next = viewModel.nextIdentityId(kind, existingTaskId)
        // Another kind may have been picked while this was running — only the latest pick counts.
        if (kind != selectedIdentityKind) return@launch
        // In edit mode this lookup can start before the task has loaded (onCreate runs
        // first). If the task arrived meanwhile and already owns a number in this kind,
        // that number wins over the one just looked up.
        val ownedNow = existingTask?.let { IdentityRules.parse(it.identity) }
        if (ownedNow != null && ownedNow.kind == kind) { showIdentityId(ownedNow.id); return@launch }
        if (next == null) {
            identityPoolFull = true
            identityPreviewId = null
            etIdentityId.setText("")
            tilIdentityId.error = "No free ID left for this kind"
        } else {
            showIdentityId(next)
        }
    }
}

private fun AddTaskActivity.showIdentityId(id: Int) {
    identityPoolFull = false
    identityPreviewId = id
    tilIdentityId.error = null
    etIdentityId.setText(id.toString())
}

/** Restores the kind (and its number) from [task]. */
internal fun AddTaskActivity.populateIdentitySection(task: Task) {
    selectedIdentityKind = IdentityRules.parseKind(task.identity) ?: IdentityKind.DEFAULT
    actvIdentityKind.setText(selectedIdentityKind.label, false)
    refreshIdentityId()
}

/**
 * The identity string to hand to the repository: `kind:id` using the number on screen,
 * or just `kind:` while it is still being looked up (the repository then picks the
 * number itself). The repository keeps the number only if it is still free.
 */
internal fun AddTaskActivity.identityForSave(): String {
    val id = identityPreviewId
    return if (id != null) IdentityRules.format(selectedIdentityKind, id)
    else "${selectedIdentityKind.key}:"
}
