package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler

/**
 * COPY-ON-WRITE copies.
 *
 * A later-axis entry shown in a place is a copy of its definition with fresh runtime. Until something TOUCHES it — runs it,
 * completes it, sleeps it, edits it on its own — nothing about it needs to be stored: it is derived, on demand, from the
 * definition and the place. Touching it writes the row (with the very same id), and from then on it is an ordinary task.
 * So storage and work follow what was touched or is on screen, never places × entries.
 *
 * A virtual copy's id is deterministic — `cp:<scope>|<definitionId>` — so the same copy has the same identity before and
 * after it becomes a row: a list row, a timer, a link or an expand state never has to be re-pointed.
 */
object AxisVirtualId {
    const val PREFIX = "cp:"

    fun of(scope: String, definitionId: String): String = "$PREFIX$scope|$definitionId"

    fun isVirtual(id: String): Boolean = id.startsWith(PREFIX)

    /** (scope, definition id) of a virtual id, or null for an ordinary one. Scopes never contain '|' (they are ids joined by '/'). */
    fun parse(id: String): Pair<String, String>? {
        if (!isVirtual(id)) return null
        val body = id.removePrefix(PREFIX)
        val bar = body.lastIndexOf('|')
        return if (bar <= 0 || bar == body.lastIndex) null else body.substring(0, bar) to body.substring(bar + 1)
    }
}

/**
 * Derives the virtual copies of one place and answers "does this place have any?" cheaply. Pure: it is built from plain
 * snapshots of the data, so the repository (selection, materializing) and the screens (lists) share exactly one rule, and
 * the tests exercise it without a database.
 *
 * A PLACE is a row that can hold the next axis's entries: a first-axis task, or a copy, that is a leaf (or a leaf that is
 * a container because something under it was already touched). Groups hold real children only.
 */
class AxisVirtualEngine(
    laterDefinitions: List<Task>,
    nodes: List<AxisNode>,
    private val containers: Set<String>,
    acl: List<AxisAcl>,
    doors: List<AxisDoor>,
    private val routes: Map<String, String>,
    private val globalHideNew: Boolean,
    /** Rows that really exist under a parent — only used to place a virtual copy like a new arrival among them. */
    private val realChildrenOf: (parentId: String) -> List<Task> = { emptyList() },
) {
    private val defsById   = laterDefinitions.associateBy { it.id }
    private val defsByAxis = laterDefinitions.groupBy { it.axisId }
    private val nodeByTask = nodes.associateBy { it.taskId }
    /** door -> (definition id -> the real copy that already exists there) */
    private val copiesAt   = nodes.groupBy({ it.scopeKey }, { it.definitionId to it.taskId }).mapValues { it.value.toMap() }
    private val rulesAt    = acl.groupBy { it.scopeKey }.mapValues { (_, rows) -> rows.associate { it.childKey to it.hidden } }
    private val flagAt     = doors.associate { it.scopeKey to it.newChildrenHidden }

    /** True when there is nothing to derive at all — the common case costs nothing. */
    val isEmpty: Boolean get() = defsById.isEmpty()

    /** Can [place] hold the next axis's entries? */
    fun isPlace(place: Task): Boolean =
        !place.isInterrupt && !place.isCompleted && place.axisId == Axes.DEFAULT && (!place.isGroup || place.id in containers)

    /** The door [place]'s entries live under: its own id on the first axis, else its copy's path. Null when it is not a place. */
    fun scopeOf(place: Task): String? {
        nodeByTask[place.id]?.let { return "${it.scopeKey}/${it.definitionId}" }
        AxisVirtualId.parse(place.id)?.let { (scope, def) -> return "$scope/$def" }
        return if (place.axisId == Axes.DEFAULT) place.id else null
    }

    private fun axisOf(place: Task): String =
        nodeByTask[place.id]?.axisId
            ?: AxisVirtualId.parse(place.id)?.let { defsById[it.second]?.axisId }
            ?: Axes.DEFAULT

    private class Door(val scope: String, val next: String, val rules: Map<String, Boolean>, val hideNew: Boolean)

    private fun doorOf(place: Task): Door? {
        if (!isPlace(place)) return null
        val next  = Axes.next(axisOf(place)) ?: return null
        val scope = scopeOf(place) ?: return null
        val layer = Axes.layerDoorOf(scope)
        val rules = AxisAclRules.merge(layer?.let { rulesAt[it] }.orEmpty(), rulesAt[scope].orEmpty())
        val hideNew = AxisAclRules.effectiveHideNew(flagAt[scope], layer?.let { flagAt[it] }, globalHideNew)
        return Door(scope, next, rules, hideNew)
    }

    /**
     * Cheap: does [place] have anything to offer? Never builds the list. Exact enough to draw an expand arrow — with
     * "hide new" off it only says "the next axis has entries" (the exact list is built when the place is opened).
     */
    fun hasVirtual(place: Task): Boolean {
        val d = doorOf(place) ?: return false
        if (defsByAxis[d.next].isNullOrEmpty()) return false
        if (!d.hideNew) return true
        if (d.rules.values.any { !it }) return true
        return routes.any { (id, route) -> defsById[id]?.axisId == d.next && AxisRoute.matches(route, d.scope) }
    }

    /** The virtual copies of [place], parents first: the entries that belong here and were never touched. */
    fun childrenOf(place: Task): List<Task> {
        val d = doorOf(place) ?: return emptyList()
        val defs = defsByAxis[d.next] ?: return emptyList()
        val have = copiesAt[d.scope].orEmpty()
        val route = AxisRoute.at(routes, d.scope)
        val missing = AxisReconcile.missing(d.next, defs, d.rules, d.hideNew, have.keys, route.hidden, route.shown)
        if (missing.isEmpty()) return emptyList()
        val missingIds = missing.mapTo(HashSet()) { it.id }
        val siblings = realChildrenOf(place.id)
        return missing.map { def ->
            val parent = def.parentId
            val parentId = when {
                parent != null && parent in missingIds -> AxisVirtualId.of(d.scope, parent)
                parent != null && have.containsKey(parent) -> have.getValue(parent)
                else -> place.id
            }
            AxisReconcile.newCopy(def, AxisVirtualId.of(d.scope, def.id), parentId)
                .also { EEVDFScheduler.placeNewTask(it, siblings) }       // a new arrival among what really exists
        }
    }
}
