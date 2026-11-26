package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.logic.SortHelper

/**
 * Pure rules for the shared group picker's DRILL-DOWN: how a list of candidates with repeated names is
 * narrowed to one specific task without showing the same name twice.
 *
 *   • The picker opens as usual but lists each DISTINCT name once (exact text), however many tasks have it.
 *   • Picking a name that only one task has chooses that task at once.
 *   • Picking a name several tasks share shows WHERE they are instead — the groups that hold them, once per
 *     distinct group name — and picking one of those narrows the candidates to the ones inside it. If several
 *     of those groups share a name too, the row they share still leads to a list one level higher, and so on,
 *     until the choice is unique. Every level is shown, even one with a single row.
 *   • If the climb runs out (two top-level tasks with one name, or two with the same name in one group), the
 *     candidates are listed separately, each with its identity badge (`104.process`).
 *
 * [label] is the text the parent field shows afterwards: the name, or — for a name that is not unique — the path
 * as identity badges, outermost first, up to the level that made it unique: `104.pgroup › process1`.
 *
 * Names are compared exactly. Nothing here changes any task; it only decides what the person is shown.
 */
object PickerDrill {

    /** Label for "no ancestor" — the candidate sits at the root. */
    const val ROOT_LABEL = "None (root level)"

    /** No real tree is this deep; the cap only stops a corrupted parent chain (a cycle) from looping forever. */
    private const val MAX_DEPTH = 64

    /** One selectable row: [label] stands for every task in [candidates] (all of them share that label). */
    data class Row(val label: String, val candidates: List<Task>)

    sealed class Next {
        /** The choice is unique: this is the task. */
        data class Chosen(val task: Task) : Next()

        /**
         * Ask again: show [rows] under [title]. [depth] is how many levels up the rows describe, to be passed
         * back to [next] when a row is picked.
         */
        data class Ask(val title: String, val rows: List<Row>, val depth: Int) : Next()
    }

    /** `104.pgroup` — a task's identity as `id.kind`; `undefined` while it has none. */
    fun badge(task: Task): String =
        IdentityRules.parse(task.identity)?.let { "${it.id}.${it.kind.key}" } ?: IdentityKind.UNDEFINED.key

    /** The first step: one row per distinct name, in the order the names first appear. */
    fun nameRows(candidates: List<Task>): List<Row> =
        candidates.groupBy { it.name }.map { (name, tasks) -> Row(name, tasks) }

    /** The [levels]-th ancestor of [task] (1 = its parent), or null when the chain ends first or is not known. */
    private fun ancestor(task: Task, levels: Int, byId: Map<String, Task>): Task? {
        var cur: Task = task
        repeat(levels) { cur = cur.parentId?.let { byId[it] } ?: return null }
        return cur
    }

    /** What picking a row whose tasks are [candidates] leads to; [depth] is 0 for the first step. */
    fun next(candidates: List<Task>, byId: Map<String, Task>, depth: Int = 0): Next {
        if (candidates.size == 1) return Next.Chosen(candidates[0])
        val name = candidates[0].name
        val d = depth + 1
        val keys = candidates.map { ancestor(it, d, byId)?.name }
        if (keys.all { it == null } || d > MAX_DEPTH) {
            // The climb has run out and these still cannot be told apart by position: list them one by one.
            val rows = candidates.map { Row("$name · ${badge(it)}", listOf(it)) }
            return Next.Ask("$name: which one?", rows, d - 1)
        }
        // One row per distinct ancestor name — even a single one: the list is shown at every level, as the
        // person asked, until the path is unique.
        val rows = candidates.groupBy { ancestor(it, d, byId)?.name }
            .map { (anc, tasks) -> Row(anc ?: ROOT_LABEL, tasks) }
            .sortedWith(Comparator { a, b ->
                when {
                    a.label == ROOT_LABEL -> -1
                    b.label == ROOT_LABEL -> 1
                    else -> SortHelper.nameComparator.compare(a.label, b.label)
                }
            })
        return Next.Ask("$name: which one?", rows, d)
    }

    /**
     * The text for the parent field once [task] is chosen from [candidates]: just the name when no other
     * candidate has it, else the badge path up to the level that told them apart (see the class comment).
     */
    fun label(task: Task, candidates: List<Task>, byId: Map<String, Task>): String {
        val same = candidates.filter { it.name == task.name && it.id != task.id }
        if (same.isEmpty()) return task.name

        // The names of the first d ancestors — what a person would have had to pick to get here.
        fun prefix(t: Task, d: Int): List<String?> = (1..d).map { ancestor(t, it, byId)?.name }

        fun chainOf(t: Task): List<Task> =
            generateSequence(1) { it + 1 }.take(MAX_DEPTH).map { ancestor(t, it, byId) }.takeWhile { it != null }.map { it!! }.toList()
        val chain   = chainOf(task)
        val longest = maxOf(chain.size, same.maxOf { chainOf(it).size })
        for (d in 1..longest + 1) {
            val mine = prefix(task, d)
            if (same.none { prefix(it, d) == mine }) {
                val path = chain.take(d).reversed().joinToString(" › ") { badge(it) }
                return if (path.isEmpty()) task.name else "$path › ${task.name}"
            }
        }
        // Never told apart by position: show where it is, then which one it is.
        val path = chain.reversed().joinToString(" › ") { badge(it) }
        return (if (path.isEmpty()) task.name else "$path › ${task.name}") + " · ${badge(task)}"
    }
}
