package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership
import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import com.eevdf.capabilities.taskstorage.scheduling.MEMBERSHIP_SYNTHETIC_PREFIX

/**
 * A state the timer card's phase-status bar can show. Fixed priority order —
 * this is also the order used to build the ping-pong segment pattern (QUOTA
 * outermost, WAIT innermost when all three are active at once).
 *
 * Which of these is active, and how badly quota is exceeded, is decided HERE; the strip itself
 * (the 7 segments, their colours, the ping-pong and the blinking) is drawn by the timer-card
 * capability from a `CardStrip` — see `MainActivity.refreshPhaseStatusBar`.
 */
internal enum class PhaseStatusState { QUOTA, DELAY, WAIT }

/**
 * Finds [ref]'s root-to-self ancestor chain by searching the FULL real +
 * membership tree from root — the SAME data the list builders draw from
 * (`EEVDFScheduler.withMemberships`, door-threading through every hop) —
 * but with NO collapse-based pruning. This replaced two earlier, both
 * wrong, approaches:
 *
 *  - Walking `task.parentId` directly: only correct for a task's own real
 *    position, wrong for any linked placement.
 *  - Reading the chain off the currently-RENDERED display list: correct at
 *    any hardlink depth, but a collapsed ancestor anywhere above the
 *    selection removes it (and everything below it) from that list
 *    entirely — quota state doesn't stop existing just because a folder is
 *    collapsed, so reading from something collapse-PRUNES was the wrong
 *    source, not a small bug in how it was read.
 *
 * A hardlink or symlink nested inside ANOTHER hardlink's rendered position
 * can't be resolved from [ref] alone at depth > 1 — which specific ancestor
 * placement applies depends on which actual path in the tree reaches this
 * exact instance. This function finds that path directly: a depth-first
 * search from every root, threading the effective "door" (own membership,
 * or whatever was inherited from an enclosing hardlink) through each hop
 * exactly like [ListBuilderDelegate]'s builders do — just never stopping
 * early for a collapsed group along the way, since this search doesn't
 * care what's currently expanded on screen.
 *
 * Returns empty if [ref] doesn't resolve to anything in the current tree
 * (e.g. the task or its link was deleted) — a safe, quiet "not exceeded"
 * rather than a guess.
 */
internal fun findAncestorChain(
    ref: TaskInstanceRef, allTasks: List<Task>, links: List<TaskLink>, memberships: List<TaskMembership>,
): List<Task> {
    val active          = allTasks.filter { !it.isCompleted }
    val tasksById       = active.associateBy { it.id }
    val effectiveTasks  = EEVDFScheduler.withMemberships(active, memberships)
    val byParentEff     = effectiveTasks.groupBy { it.parentId }
    val byHostGroup     = links.groupBy { it.hostGroupId }

    fun realIdOf(entry: Task): String =
        if (entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX))
            entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX).let { membershipId ->
                memberships.find { it.id == membershipId }?.taskId
            } ?: entry.id
        else entry.id

    fun search(entry: Task, pathSoFar: List<Task>, inheritedDoor: String?): List<Task>? {
        val realTask = tasksById[realIdOf(entry)] ?: return null
        val newPath  = pathSoFar + realTask
        val ownMembershipId = if (entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX))
            entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX) else null
        val effectiveDoor = ownMembershipId ?: inheritedDoor

        if (ref.symlinkId == null && realTask.id == ref.taskId && effectiveDoor == ref.membershipId) {
            return newPath
        }
        if (ref.symlinkId != null) {
            byHostGroup[realTask.id].orEmpty().forEach { link ->
                if (link.id == ref.symlinkId && link.targetTaskId == ref.taskId) {
                    val target = tasksById[ref.taskId] ?: return@forEach
                    return newPath + target
                }
            }
        }
        for (child in byParentEff[realTask.id].orEmpty()) {
            search(child, newPath, effectiveDoor)?.let { return it }
        }
        return null
    }

    for (root in byParentEff[null].orEmpty()) {
        search(root, emptyList(), null)?.let { return it }
    }
    return emptyList()
}

/**
 * True when any task in [chain] (already resolved root-to-self — see
 * [findAncestorChain]) has its own quota exhausted.
 */
internal fun isQuotaChainExhausted(chain: List<Task>, nowMs: Long): Boolean =
    chain.any { it.isQuotaEnabled && it.isQuotaExceeded(nowMs) }

/**
 * How many multiples over quota [task] currently is, at [nowMs]. 0 when
 * quota is disabled or not exceeded — only a genuinely exceeded task
 * contributes anything to the blink-severity comparison.
 */
private fun quotaOverageRatio(task: Task, nowMs: Long): Double {
    if (!task.isQuotaEnabled || task.quotaSeconds <= 0L || !task.isQuotaExceeded(nowMs)) return 0.0
    return task.currentQuotaUsed(nowMs).toDouble() / task.quotaSeconds
}

/**
 * Which segment (0-based) should blink for quota overage, and how fast, or
 * null when nothing should blink.
 *
 * Two independent questions, checked in this order — do not swap them:
 *
 *  1. THE GATE: blinking only makes sense when the bar has nothing else to
 *     say — [activeStates] must be EXACTLY `[QUOTA]`, no DELAY/WAIT mixed
 *     in. This is a property of what the SELECTED card is showing right
 *     now, checked first, regardless of how bad any ancestor's overage is.
 *  2. THE SEVERITY: only once the gate passes, take the WORST overage found
 *     anywhere in [chain] (already resolved root-to-self — see
 *     [findAncestorChain]) — not the first one found (unlike
 *     [isQuotaChainExhausted], which only needs a yes/no answer, this needs
 *     the actual worst number, so a distant ancestor at 8x correctly
 *     outweighs the selected task's own 2x). Each doubling of overage
 *     advances the blink one segment further: 1x → segment 0, 2x → 1,
 *     4x → 2, ... up to segment 6 at 64x. Beyond that, segment 6 has its
 *     own adjustable range instead of a fixed doubling: it blinks at the
 *     normal rate from 64x up to 128x, then switches to a faster blink at
 *     128x and beyond — "off the end of the scale" reads as faster, not as
 *     "stuck," the same way a Geiger counter clicks faster rather than
 *     just staying lit once it's pegged.
 */
internal data class QuotaBlink(val segmentIndex: Int, val fast: Boolean)

internal fun quotaBlinkSegment(
    activeStates: List<PhaseStatusState>, chain: List<Task>, nowMs: Long,
): QuotaBlink? {
    if (activeStates != listOf(PhaseStatusState.QUOTA)) return null   // the gate

    val worstRatio = chain.maxOfOrNull { quotaOverageRatio(it, nowMs) } ?: 0.0
    if (worstRatio < 1.0) return null   // nothing in the chain is actually exceeded

    val level = kotlin.math.floor(kotlin.math.ln(worstRatio) / kotlin.math.ln(2.0)).toInt()
    val fast  = worstRatio >= FAST_BLINK_THRESHOLD_RATIO
    return QuotaBlink(level.coerceIn(0, SEGMENT_COUNT - 1), fast)
}

/** Overage at or beyond this ratio switches the last segment to a faster blink. */
private const val FAST_BLINK_THRESHOLD_RATIO = 128.0

/** Fixed segment count of the strip the card draws — never grows, even for a future 4th state. */
private const val SEGMENT_COUNT = 7
