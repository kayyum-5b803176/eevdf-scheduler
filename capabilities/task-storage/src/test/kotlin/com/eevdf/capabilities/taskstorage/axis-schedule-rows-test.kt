package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisScheduleRowsTest {

    private fun t(id: String, axis: String, parent: String? = null, group: Boolean = false, vrt: Double = 0.0) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group,
            axisId = axis, vruntime = vrt)

    private fun base(vararg ids: String) = ids.mapIndexed { i, id ->
        TaskDisplayItem(task = t(id, Axes.X), depth = 0, queueNumber = "${i + 1}")
    }

    private fun expand(
        base: List<TaskDisplayItem>, later: List<Task>,
        excl: List<AxisExclusion> = emptyList(), inst: List<AxisInstance> = emptyList(),
        leafOpen: Set<String> = emptySet(), groupClosed: Set<String> = emptySet(),
    ) = AxisScheduleRows.expand(
        base = base, later = later, exclusions = excl, instances = inst, nowMs = 1_000_000L,
        leafExpanded = { it in leafOpen },
        groupExpanded = { scope, id -> "$scope|$id" !in groupClosed },
    )

    private val y = listOf(t("a", Axes.Y), t("b", Axes.Y))
    private val z = listOf(t("p", Axes.Z), t("q", Axes.Z))

    @Test fun `no later entries - the list is returned untouched`() {
        val b = base("L1", "L2")
        assertSame(b, expand(b, emptyList()))
    }

    @Test fun `collapsed by default - leaf gets an arrow and a count, no extra rows`() {
        val out = expand(base("L1"), y)
        assertEquals(1, out.size)
        assertEquals("L1", out[0].axisChildScope)
        assertEquals(2, out[0].axisChildCount)
        assertFalse(out[0].isExpanded)
    }

    @Test fun `expanded - y rows hang under the x leaf with scope, depth and number`() {
        val out = expand(base("L1", "L2"), y, leafOpen = setOf("L1"))
        assertEquals(listOf("L1", "a", "b", "L2"), out.map { it.task.id })
        assertEquals(listOf(0, 1, 1, 0), out.map { it.depth })
        assertEquals(listOf("1", "1.1", "1.2", "2"), out.map { it.queueNumber })
        assertEquals(listOf(Axes.X, Axes.Y, Axes.Y, Axes.X), out.map { it.axisId })
        assertEquals(listOf("", "L1", "L1", ""), out.map { it.scopeKey })
    }

    @Test fun `every axis chains to the one above - z hangs under a y leaf`() {
        val out = expand(base("L1"), y + z, leafOpen = setOf("L1", "L1/a"))
        assertEquals(listOf("L1", "a", "p", "q", "b"), out.map { it.task.id })
        assertEquals(listOf(0, 1, 2, 2, 1), out.map { it.depth })
        assertEquals(listOf("1", "1.1", "1.1.1", "1.1.2", "1.2"), out.map { it.queueNumber })
        assertEquals("L1/a", out[2].scopeKey)
        assertEquals(Axes.Z, out[2].axisId)
    }

    @Test fun `a y leaf with z entries is expandable but closed until opened`() {
        val out = expand(base("L1"), y + z, leafOpen = setOf("L1"))
        val a = out.first { it.task.id == "a" }
        assertEquals("L1/a", a.axisChildScope)
        assertEquals(2, a.axisChildCount)
        assertFalse(out.any { it.axisId == Axes.Z })
    }

    @Test fun `same definition under two leaves is two rows with two identities`() {
        val out = expand(base("L1", "L2"), y, leafOpen = setOf("L1", "L2"))
        val aRows = out.filter { it.task.id == "a" }
        assertEquals(2, aRows.size)
        assertEquals(setOf("L1", "L2"), aRows.map { it.scopeKey }.toSet())
        assertEquals(aRows.size, aRows.map { Triple(it.axisId, it.scopeKey, it.task.id) }.toSet().size)
    }

    @Test fun `switch-offs are per outer leaf`() {
        val ex = listOf(AxisExclusion("L1", Axes.Y, "b"))
        val out = expand(base("L1", "L2"), y, excl = ex, leafOpen = setOf("L1", "L2"))
        assertEquals(listOf("L1", "a", "L2", "a", "b"), out.map { it.task.id })
        assertEquals(1, out[0].axisChildCount); assertEquals(2, out[2].axisChildCount)
    }

    @Test fun `all entries switched off - the leaf has no arrow`() {
        val ex = listOf(AxisExclusion("L1", Axes.Y, "a"), AxisExclusion("L1", Axes.Y, "b"))
        val out = expand(base("L1"), y, excl = ex)
        assertNull(out[0].axisChildScope); assertEquals(0, out[0].axisChildCount)
    }

    @Test fun `each scope shows its own runtime`() {
        val inst = listOf(AxisInstance("L1", "a", vruntime = 42.0, runCount = 7))
        val out = expand(base("L1", "L2"), y, inst = inst, leafOpen = setOf("L1", "L2"))
        val a1 = out.first { it.task.id == "a" && it.scopeKey == "L1" }.task
        val a2 = out.first { it.task.id == "a" && it.scopeKey == "L2" }.task
        assertEquals(42.0, a1.vruntime, 0.0); assertEquals(7, a1.runCount)
        assertEquals(0.0, a2.vruntime, 0.0); assertEquals(0, a2.runCount)
    }

    @Test fun `siblings are ordered like the run picks - lower vruntime first`() {
        val inst = listOf(AxisInstance("L1", "a", vruntime = 100.0))     // a has run more under L1
        val out = expand(base("L1", "L2"), y, inst = inst, leafOpen = setOf("L1", "L2"))
        assertEquals(listOf("b", "a"), out.filter { it.scopeKey == "L1" }.map { it.task.id })
        assertEquals(listOf("a", "b"), out.filter { it.scopeKey == "L2" }.map { it.task.id })
    }

    @Test fun `groups hold children inside a scope and can be closed`() {
        val tree = listOf(t("g", Axes.Y, group = true), t("g1", Axes.Y, parent = "g"), t("solo", Axes.Y))
        val open = expand(base("L1"), tree, leafOpen = setOf("L1"))
        assertEquals(setOf("g", "g1", "solo"), open.drop(1).map { it.task.id }.toSet())
        assertEquals(2, open.first { it.task.id == "g1" }.depth)
        val closed = expand(base("L1"), tree, leafOpen = setOf("L1"), groupClosed = setOf("L1|g"))
        assertEquals(setOf("g", "solo"), closed.drop(1).map { it.task.id }.toSet())
        assertFalse(closed.first { it.task.id == "g" }.isExpanded)
    }

    @Test fun `groups and link rows on the first axis get no children`() {
        val grp = TaskDisplayItem(task = t("G", Axes.X, group = true), depth = 0)
        val sym = TaskDisplayItem(task = t("S", Axes.X), depth = 0, symlinkId = "link1")
        val out = expand(listOf(grp, sym), y, leafOpen = setOf("G", "S"))
        assertEquals(listOf("G", "S"), out.map { it.task.id })
        assertNull(out[1].axisChildScope)
    }

    @Test fun `class filter keeps only matching leaves, groups only as context`() {
        val tree = listOf(
            t("g", Axes.Y, group = true), t("keepme", Axes.Y, parent = "g"), t("dropme", Axes.Y, parent = "g"),
            t("other", Axes.Y),
        )
        val out = AxisScheduleRows.expand(
            base("L1"), tree, emptyList(), emptyList(), 1_000_000L,
            leafExpanded = { true }, groupExpanded = { _, _ -> true },
            keep = { it.id == "keepme" },
        )
        assertEquals(setOf("g", "keepme"), out.drop(1).map { it.task.id }.toSet())
        assertTrue(out.first { it.task.id == "g" }.isFilterContextOnly)
    }

    @Test fun `input tasks are never mutated`() {
        val defs = listOf(t("a", Axes.Y, vrt = 5.0), t("b", Axes.Y, vrt = 1.0))
        val before = defs.map { it.virtualDeadline to it.vruntime }
        expand(base("L1"), defs, inst = listOf(AxisInstance("L1", "a", vruntime = 99.0)), leafOpen = setOf("L1"))
        assertEquals(before, defs.map { it.virtualDeadline to it.vruntime })
    }
}
