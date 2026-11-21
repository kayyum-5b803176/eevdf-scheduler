package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import com.eevdf.capabilities.taskstorage.scheduling.RtScheduler

/**
 * The Schedule tab's axis tree.
 *
 * Every axis chains to the one above it: a first-axis LEAF holds its included second-axis
 * entries the way a group holds children; each second-axis leaf holds its included third-axis
 * entries, and so on. So the list reads
 *
 *     x   Task-1
 *      y  Entry a            <- attached under THIS x leaf
 *       z  Item p            <- attached under THIS y leaf
 *      y  Entry b
 *     x   Task-2
 *      y  Entry a            <- same definition, its own copy and its own runtime
 *
 * Each level orders only its own siblings (DL, then RT, then EEVDF), exactly as the run's
 * descent picks, so the first child of a level is the one that runs next there.
 *
 * Pure: no Android, no database. It reads the data it is given and never touches a [Task] it
 * did not copy, so the lists held by LiveData are never mutated.
 *
 * Collapsed by default: a leaf's children are only built when [leafExpanded] says so, so the
 * cost of a collapsed tree is one count per leaf, not one row per copy.
 */
object AxisScheduleRows {

    /**
     * @param base      the existing first-axis rows, untouched and in order
     * @param later     active, awake entries of every axis after the first
     * @param leafExpanded  is the leaf whose children live under this scope open? (default closed)
     * @param groupExpanded is this group, inside this scope, open? (default open)
     * @param keep      class filter applied at every level, by each entry's own class; null = all
     */
    fun expand(
        base: List<TaskDisplayItem>,
        later: List<Task>,
        exclusions: List<AxisExclusion>,
        instances: List<AxisInstance>,
        nowMs: Long,
        leafExpanded: (childScope: String) -> Boolean,
        groupExpanded: (scope: String, groupId: String) -> Boolean,
        keep: ((Task) -> Boolean)? = null,
    ): List<TaskDisplayItem> {
        if (later.isEmpty()) return base
        val first = Axes.ALL.first()
        val nextOfFirst = Axes.next(first) ?: return base

        val defs: Map<String, List<Task>> = later.groupBy { it.axisId }
        val excluded: Map<String, Set<String>> = exclusions
            .groupBy { key(it.outerKey, it.axisId) }
            .mapValues { (_, rows) -> rows.mapTo(HashSet()) { it.entryId } }
        val runtime: Map<String, Map<String, AxisInstance>> = instances
            .groupBy { it.scopeKey }
            .mapValues { (_, rows) -> rows.associateBy { it.entryId } }

        /** How many entries of [axis] are included under [scope] — no copies, O(1) when nothing is switched off. */
        fun countIn(axis: String, scope: String): Int {
            val all = defs[axis] ?: return 0
            val off = excluded[key(scope, axis)].orEmpty()
            return if (off.isEmpty()) all.size else AxisRules.includedEntries(axis, all, off).size
        }

        /** The included entries of [axis] under [scope], as private copies carrying that scope's runtime. */
        fun entriesIn(axis: String, scope: String): List<Task> {
            val all = defs[axis] ?: return emptyList()
            val off = excluded[key(scope, axis)].orEmpty()
            val included = if (off.isEmpty()) all else AxisRules.includedEntries(axis, all, off)
            val inst = runtime[scope].orEmpty()
            return included.map { t -> (inst[t.id]?.applyTo(t) ?: t).copy() }
        }

        fun rows(
            axis: String, scope: String, depth: Int, prefix: String,
            outerExceeded: Boolean, outerWarning: Boolean, out: MutableList<TaskDisplayItem>,
        ) {
            val entries = entriesIn(axis, scope)
            if (entries.isEmpty()) return
            val ids = entries.mapTo(HashSet()) { it.id }
            val children = entries.groupBy { it.parentId?.takeIf { p -> p in ids } }
            EEVDFScheduler.recalculate(entries)                       // virtual deadlines, on our copies
            val shares = EEVDFScheduler.computeShares(entries, true)

            fun matches(t: Task) = keep?.invoke(t) ?: true
            fun subtreeMatches(t: Task): Boolean =
                matches(t) || (t.isGroup && children[t.id].orEmpty().any { subtreeMatches(it) })
            fun descendants(id: String): Pair<Int, Int> {
                var g = 0; var l = 0
                for (c in children[id].orEmpty()) {
                    if (c.isGroup) { g++; val (cg, cl) = descendants(c.id); g += cg; l += cl } else l++
                }
                return g to l
            }

            fun emit(parent: String?, d: Int, pfx: String, exceeded: Boolean, warning: Boolean) {
                val siblings = order(children[parent].orEmpty().filter { subtreeMatches(it) }, nowMs)
                siblings.forEachIndexed { i, t ->
                    val number  = if (pfx.isEmpty()) "" else "$pfx.${i + 1}"
                    val exc     = exceeded || t.isQuotaExceeded(nowMs)
                    val warn    = warning || t.isQuotaWarning(nowMs)
                    if (t.isGroup) {
                        val open = groupExpanded(scope, t.id)
                        val (dg, dl) = descendants(t.id)
                        out += TaskDisplayItem(
                            task = t, depth = d, childGroupCount = dg, childTaskCount = dl,
                            cpuShare = shares[t.id] ?: 0.0,
                            effectiveQuotaExceeded = exc, effectiveQuotaWarning = warn,
                            queueNumber = number, isExpanded = open,
                            isFilterContextOnly = keep != null && !matches(t),
                            axisId = axis, scopeKey = scope,
                        )
                        if (open) emit(t.id, d + 1, number, exc, warn)
                    } else {
                        val next       = Axes.next(axis)
                        val childScope = if (scope.isEmpty()) t.id else "$scope/${t.id}"
                        val n          = if (next != null) countIn(next, childScope) else 0
                        val open       = n > 0 && leafExpanded(childScope)
                        out += TaskDisplayItem(
                            task = t, depth = d,
                            cpuShare = shares[t.id] ?: 0.0,
                            effectiveQuotaExceeded = exc, effectiveQuotaWarning = warn,
                            queueNumber = number,
                            isDlActive = t.isDlBudgetActive(nowMs),
                            isRtActive = RtScheduler.isRtWindowActive(t, nowMs),
                            isExpanded = if (n > 0) open else true,
                            axisId = axis, scopeKey = scope,
                            axisChildScope = if (n > 0) childScope else null, axisChildCount = n,
                        )
                        if (open && next != null) rows(next, childScope, d + 1, number, exc, warn, out)
                    }
                }
            }
            emit(null, depth, prefix, outerExceeded, outerWarning)
        }

        val out = ArrayList<TaskDisplayItem>(base.size)
        for (item in base) {
            val t = item.task
            val eligible = !t.isGroup && t.axisId == first && item.symlinkId == null &&
                item.membershipId == null && !item.isBrokenLink && !item.isFilterContextOnly
            val n = if (eligible) countIn(nextOfFirst, t.id) else 0
            if (n == 0) { out += item; continue }
            val open = leafExpanded(t.id)
            out += item.copy(axisChildScope = t.id, axisChildCount = n, isExpanded = open)
            if (open) rows(nextOfFirst, t.id, item.depth + 1, item.queueNumber,
                item.effectiveQuotaExceeded, item.effectiveQuotaWarning, out)
        }
        return out
    }

    private fun key(scope: String, axis: String) = "$scope\u0000$axis"

    /** One level's siblings in the order the run picks them: DL (soonest deadline), RT (highest priority), then EEVDF. */
    private fun order(level: List<Task>, nowMs: Long): List<Task> {
        val dl = level.filter { !it.isGroup && it.isDlBudgetActive(nowMs) }.sortedBy { it.dlPeriodRemainingSeconds(nowMs) }
        val dlIds = dl.mapTo(HashSet()) { it.id }
        val rt = level.filter { !it.isGroup && it.id !in dlIds && RtScheduler.isRtWindowActive(it, nowMs) }
            .sortedByDescending { it.rtPriority }
        val rtIds = rt.mapTo(HashSet()) { it.id }
        val rest = level.filter { it.id !in dlIds && it.id !in rtIds }.sortedBy { it.virtualDeadline }
        return dl + rt + rest
    }
}
