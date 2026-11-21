package com.eevdf.capabilities.taskstorage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AxisDescentTest {

    private fun t(id: String, axis: String, parent: String? = null, group: Boolean = false) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = axis)

    /** Fake per-level pass: first root by id; real policy is the repository's job. */
    private val firstRoot: (List<Task>) -> Task? = { lvl -> lvl.filter { it.parentId == null }.minByOrNull { it.id } }

    private fun run(
        first: Task,
        defs: List<Task>,
        excluded: Map<Pair<String, String>, Set<String>> = emptyMap(),
        inst: Map<String, Map<String, AxisInstance>> = emptyMap(),
        pick: (List<Task>) -> Task? = firstRoot,
    ) = runBlocking {
        AxisDescent.descend(
            firstLeaf = first,
            definitions = { axis -> defs.filter { it.axisId == axis } },
            excluded = { axis, scope -> excluded[axis to scope] ?: emptySet() },
            instances = { scope -> inst[scope] ?: emptyMap() },
            pickLevel = pick,
        )
    }

    private val xLeaf = t("L", Axes.X)

    @Test fun `no y or z definitions - path is just the first leaf (today's behaviour)`() {
        val path = run(xLeaf, emptyList())
        assertEquals(listOf("L"), path.map { it.entry.id })
    }

    @Test fun `descends x then y then z with scope keys of the outer leaves`() {
        val defs = listOf(t("a", Axes.Y), t("p", Axes.Z))
        val path = run(xLeaf, defs)
        assertEquals(listOf("L", "a", "p"), path.map { it.entry.id })
        assertEquals(listOf("", "L", "L/a"), path.map { it.scopeKey })
        assertEquals(listOf(Axes.X, Axes.Y, Axes.Z), path.map { it.axisId })
    }

    @Test fun `exclusions are per outer leaf`() {
        val defs = listOf(t("a", Axes.Y), t("b", Axes.Y), t("c", Axes.Y))
        val u1 = t("u1", Axes.X); val u2 = t("u2", Axes.X)
        val ex = mapOf((Axes.Y to "u1") to setOf("b", "c"))   // user-1 only a
        // make the picker choose the LAST id so exclusion visibly matters
        val last: (List<Task>) -> Task? = { l -> l.filter { it.parentId == null }.maxByOrNull { it.id } }
        assertEquals("a", run(u1, defs, ex, pick = last)[1].entry.id)
        assertEquals("c", run(u2, defs, ex, pick = last)[1].entry.id)
    }

    @Test fun `all entries of an axis excluded - descent stops, outer leaf stands`() {
        val defs = listOf(t("a", Axes.Y), t("p", Axes.Z))
        val path = run(xLeaf, defs, excluded = mapOf((Axes.Y to "L") to setOf("a")))
        assertEquals(listOf("L"), path.map { it.entry.id })      // z is never reached either
    }

    @Test fun `private runtime overlays the shared definition per scope`() {
        val defs = listOf(t("a", Axes.Y))
        val u1 = t("u1", Axes.X); val u2 = t("u2", Axes.X)
        val inst = mapOf("u1" to mapOf("a" to AxisInstance("u1", "a", vruntime = 42.0, runCount = 7)))
        assertEquals(42.0, run(u1, defs, inst = inst)[1].entry.vruntime, 0.0)
        assertEquals(7, run(u1, defs, inst = inst)[1].entry.runCount)
        assertEquals(0.0, run(u2, defs, inst = inst)[1].entry.vruntime, 0.0)   // other scope untouched
    }

    @Test fun `nothing runnable on an axis - descent stops there`() {
        val defs = listOf(t("a", Axes.Y), t("p", Axes.Z))
        val path = run(xLeaf, defs, pick = { null })
        assertEquals(listOf("L"), path.map { it.entry.id })
    }

    @Test fun `instance round-trips through overlay`() {
        val d = t("a", Axes.Y)
        val i = AxisInstance("L", "a", 3.5, 10, 20, 30, 40, 50, 6)
        assertEquals(i, i.applyTo(d).toAxisInstance("L"))
    }
}
