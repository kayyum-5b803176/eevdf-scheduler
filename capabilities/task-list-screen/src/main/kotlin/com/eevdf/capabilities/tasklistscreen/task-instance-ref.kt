package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership

/**
 * Identifies WHICH PLACEMENT of a task is meant, not just which task.
 *
 * A real task has exactly one placement (itself). A hardlink or symlink is a
 * genuinely distinct placement of the SAME underlying task, with its own
 * position in the tree — its own effective parent, potentially its own
 * quota-chain state, its own everything that depends on "where does this
 * live." Before this type existed, "the selected/current task" was
 * represented as a bare [Task] everywhere, which can only ever answer "what
 * is this" — never "which placement of it is meant." That gap is the single
 * root cause behind a whole family of bugs: the quota-exhaustion indicator
 * walking the wrong ancestor chain, "Next" landing on the wrong occurrence
 * when a real task and a link to it are both on screen, navigation getting
 * stuck once it crosses into a linked placement, and accounting/re-seat
 * logic silently reverting to the real placement after an interrupt or
 * expiry cycle — four independent-looking bugs, one shared cause.
 *
 * This mirrors exactly the identity triple [TaskDisplayItem] (and the
 * RecyclerView diff callback) already use for ROW identity
 * (task.id + symlinkId + membershipId) — this is that same concept,
 * promoted to a first-class type so "current selection" — and anything a
 * future feature adds — never again loses track of which placement was
 * actually meant.
 */
data class TaskInstanceRef(
    val taskId: String,
    val symlinkId: String? = null,
    val membershipId: String? = null,
) {
    /** True for the task's own real position — never reached via a link. */
    val isReal: Boolean get() = symlinkId == null && membershipId == null

    companion object {
        /**
         * A row's door is its OWN direct membership if it IS a hardlink
         * placement, otherwise whatever door it INHERITED from an ancestor
         * hardlink placement (`entryMembershipId`) — a leaf merely nested
         * inside a hardlinked group's subtree has no membership of its own,
         * but still needs the SAME door its ancestor established. Building
         * this centrally, once, here — rather than leaving every caller to
         * remember `item.membershipId ?: item.entryMembershipId` correctly
         * on its own — is the actual fix: this exact combination was missed
         * in [matchesInstance] and in every other place that built a ref
         * from an item, even after it was correctly done at the one tap-
         * handler call site. A leaf nested inside a hardlink would build the
         * right ref when selected, then immediately fail every subsequent
         * "is this the selected one" check, because those checks and this
         * factory disagreed about what a row's door even was.
         */
        fun of(item: TaskDisplayItem) =
            TaskInstanceRef(item.task.id, item.symlinkId, item.membershipId ?: item.entryMembershipId)
        fun real(taskId: String) = TaskInstanceRef(taskId)
        fun real(task: Task) = TaskInstanceRef(task.id)
    }
}

/**
 * The ONE canonical way to ask "what's this instance's effective parent."
 * Every feature that needs this must call through here — never read
 * `task.parentId` directly when the subject might be a linked placement,
 * the exact mistake behind every bug this type exists to close off.
 */
fun TaskInstanceRef.effectiveParentId(
    links: List<TaskLink>, memberships: List<TaskMembership>, tasksById: Map<String, Task>,
): String? = when {
    membershipId != null -> memberships.find { it.id == membershipId }?.groupId
    symlinkId    != null -> links.find { it.id == symlinkId }?.hostGroupId
    else                  -> tasksById[taskId]?.parentId
}

/**
 * True when this row IS the exact placement [ref] refers to — not just the
 * same task id. Uses the same combined door [TaskInstanceRef.of] does
 * (own membership, or the inherited one) — a leaf nested inside a
 * hardlinked group has no membership of its own, but still needs to match
 * against the door it inherited, the same way it was built.
 */
fun TaskDisplayItem.matchesInstance(ref: TaskInstanceRef?): Boolean =
    ref != null && task.id == ref.taskId && symlinkId == ref.symlinkId &&
        (membershipId ?: entryMembershipId) == ref.membershipId
