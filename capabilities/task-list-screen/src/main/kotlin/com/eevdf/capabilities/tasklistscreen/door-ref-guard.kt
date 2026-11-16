package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.MembershipAclRules

/**
 * Hardlink ACL × current selection.
 *
 * A [TaskInstanceRef] can name a door (hardlink placement) that the user later
 * hides the selected/running task through — from the ACL tab, or because a
 * saved selection is restored after the ACL changed. The task still exists and
 * still has its real placement, so the safe answer is to fall back to that
 * real placement instead of keeping a ref no on-screen row can match (which
 * made Next report "No other siblings" and silently credited a door the task
 * is no longer visible through).
 *
 * Extension functions rather than [TaskViewModel] members, so the ViewModel
 * does not grow.
 */

/**
 * [ref] itself when its door still shows the task (or it has no door, or it is
 * a symlink ref); otherwise the task's REAL placement. Cheap when nothing is
 * hidden anywhere — it runs on every timer tick via [CurrentTaskOwner].
 */
internal fun TaskViewModel.visibleRefOrReal(ref: TaskInstanceRef?): TaskInstanceRef? {
    if (ref == null || ref.symlinkId != null) return ref
    val doorId      = ref.membershipId ?: return ref
    val memberships = allTaskMemberships.value ?: return ref
    val acl         = allMembershipAcl.value ?: return ref
    if (acl.none { it.hidden } && memberships.none { it.newChildrenHidden }) return ref
    val door  = memberships.firstOrNull { it.id == doorId } ?: return ref
    val tasks = activeTasks.value ?: return ref
    val hidden = MembershipAclRules.isTaskHiddenThroughDoor(ref.taskId, door, acl) { id ->
        tasks.firstOrNull { it.id == id }?.parentId
    }
    return if (hidden) TaskInstanceRef.real(ref.taskId) else ref
}

/** Re-seats the current task on its real placement if its door now hides it. */
internal fun TaskViewModel.reseatIfDoorHidesCurrent() {
    val task = currentTask.value ?: return
    val ref  = currentInstanceRef.value ?: return
    val safe = visibleRefOrReal(ref) ?: return
    if (safe == ref) return
    // The run is credited to the real chain from here on (see
    // TaskRepository.updateVruntimeAfterRun, which also re-checks).
    activeRunMembershipId = null
    currentTaskOwner.set(task, safe)
}
