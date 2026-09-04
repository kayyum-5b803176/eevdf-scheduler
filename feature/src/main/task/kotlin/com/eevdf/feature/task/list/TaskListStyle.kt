package com.eevdf.feature.task.list

/**
 * How a tab presents the task hierarchy. Purely a display/navigation choice —
 * see [DrillState] doc comment for why this can NEVER be allowed to change
 * what [TaskViewModel.flatActiveTasks]/[TaskViewModel.flatScheduleOrder]
 * contain.
 */
enum class TaskListStyle { FLAT_OUTLINE, DRILL_DOWN }

/** How the user arrived at a [DrillFrame] — determines what "back" means, and
 *  the breadcrumb glyph shown for the hop. */
enum class ArrivedVia { REAL, SYMLINK, HARDLINK }

/**
 * One level of drill-down: [groupId] is the group whose children are being
 * shown, [arrivedVia] records whether this frame was entered through the
 * group's real parent-child relationship, by following a symlink, or by
 * entering a hardlink placement directly.
 *
 * [doorMembershipId] is the "door" every REAL descendant rendered under this
 * frame (and every frame pushed below it, until a nested door overrides it)
 * should be credited through when run — see
 * [com.eevdf.data.task.TaskDisplayItem.entryMembershipId] and
 * [TaskRepository.updateVruntimeAfterRun]'s door parameter for why this must
 * be threaded all the way down to whichever leaf actually runs, not just
 * applied to this frame's own top row. Set when [arrivedVia] is HARDLINK
 * (this frame's own boundary), inherited unchanged from the frame below when
 * entering a plain REAL subgroup nested inside an already-open door, and null
 * whenever no door is currently open at all.
 *
 * Note there is no separate "return to" field: because [DrillState.stack] is a
 * plain stack, popping it always returns to whatever frame was on screen right
 * before this one was pushed — for a SYMLINK or HARDLINK frame that is
 * naturally the placement's HOST group (never the real group's own real
 * parent), and for a REAL frame that is naturally the real parent. Ordinary
 * stack semantics are already exactly the "logical path" behavior a shell's
 * `cd ..` has through a symlink — no extra bookkeeping needed.
 */
data class DrillFrame(
    val groupId: String?,
    val arrivedVia: ArrivedVia,
    val highlightTaskId: String? = null,
    val doorMembershipId: String? = null,
)

/**
 * A tab's drill-down navigation state. Session-only (in-memory, not persisted
 * across process death) — reopening the app always starts back at Home, same
 * as the flat outline always does today.
 *
 * IMPORTANT: this state only ever affects what a SINGLE screen's worth of rows
 * gets displayed (see [ListBuilderDelegate]'s queueDisplayList/scheduleDisplayList).
 * It must never be allowed to replace [TaskViewModel.flatActiveTasks] or
 * [TaskViewModel.flatScheduleOrder] themselves — every scheduling/rotation
 * function (SchedulerDelegate.nextSibling, scheduleNext, rotateGlobal,
 * jumpToFirst, MenuSyncDelegate's leaf checks, navigateToRealLocation, …) reads
 * the FULL multi-depth tree from those two properties and would silently break
 * if they ever only contained one drill level's rows.
 */
data class DrillState(val stack: List<DrillFrame> = emptyList()) {
    /** null = sitting at Home/root — either because the stack is empty, or
     *  because the top frame itself names root (a symlink to a root-level leaf
     *  task; see TaskViewModel.drillInto). Both cases correctly build the same
     *  root-level rows, so buildQueueDrillLevel/buildScheduleDrillLevel never
     *  need to distinguish them — only canGoBack (stack.isNotEmpty()) does. */
    val currentFrameId: String? get() = stack.lastOrNull()?.groupId
    /** Which row (if any) the CURRENT frame should visually highlight — set only
     *  when this frame was reached by following a symlink to a leaf task. */
    val currentHighlightTaskId: String? get() = stack.lastOrNull()?.highlightTaskId
    /** The door every leaf run from the CURRENT frame's level should be
     *  credited through — see [DrillFrame.doorMembershipId]. */
    val currentDoorMembershipId: String? get() = stack.lastOrNull()?.doorMembershipId
    val canGoBack: Boolean get() = stack.isNotEmpty()
}
