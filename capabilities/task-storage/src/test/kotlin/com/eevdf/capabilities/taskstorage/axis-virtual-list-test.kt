package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisVirtualListTest {

    private fun t(id: String, axis: String = Axes.X, group: Boolean = false, parent: String? = null) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, isGroup = group, parentId = parent, axisId = axis)

    private fun places(n: Int) = (1..n).map { t("L$it") }
    private fun yDefs(n: Int) = (1..n).map { t("y$it", Axes.Y) }

    /** "hide new" OFF: every place offers every entry — the include-everything mode that used to copy it all. */
    private fun everything(defs: List<Task>, containers: Set<String> = emptySet(), nodes: List<AxisNode> = emptyList()) =
        AxisVirtualEngine(defs, nodes, containers, emptyList(), emptyList(), emptyMap(), globalHideNew = false)

    private fun build(real: List<Task>, engine: AxisVirtualEngine, open: Set<String> = emptySet(), max: Int = AxisVirtualList.MAX_DERIVED,
                      closed: MutableList<String> = ArrayList()) =
        AxisVirtualList.build(real, engine, isOpen = { it in open }, onClosedPlace = { closed += it }, maxDerived = max)

    // ── the scenario that froze the app: many places, none opened ────────

    @Test fun `many places, none opened - nothing is derived and the list is no bigger than the real one`() {
        val real = places(99)
        val r = build(real, everything(yDefs(11)))
        assertTrue(r.derivedIds.isEmpty())
        assertEquals(99, r.tasks.size)
        assertFalse(r.truncated)
    }

    @Test fun `places that hold untouched entries are drawn as groups and reported closed`() {
        val closed = ArrayList<String>()
        val r = build(places(3), everything(yDefs(2)), closed = closed)
        assertTrue(r.tasks.all { it.isGroup })
        assertEquals(listOf("L1", "L2", "L3"), closed)
    }

    @Test fun `a place that already holds touched copies is closed by default too`() {
        val container = t("L1", group = true)          // a leaf that became a container
        val r = build(listOf(container), everything(yDefs(5), containers = setOf("L1")))
        assertTrue(r.derivedIds.isEmpty())
    }

    @Test fun `990 places times 11 entries, none opened - still nothing derived`() {
        val z = (1..11).map { t("z$it", Axes.Z) }
        val yCopies = (1..990).map { t("y$it", group = true) }        // real copies that are containers
        val engine = everything(z, containers = yCopies.mapTo(HashSet()) { it.id })
        val nodes = yCopies.map { AxisNode(it.id, "ydef", Axes.Y, "L") }
        val e = AxisVirtualEngine(z, nodes, yCopies.mapTo(HashSet()) { it.id }, emptyList(), emptyList(), emptyMap(), false)
        val r = build(yCopies, e)
        assertTrue(r.derivedIds.isEmpty()); assertEquals(990, r.tasks.size)
        assertTrue(engine.isEmpty.not())
    }

    // ── opening a place derives only that place ──────────────────────────

    @Test fun `opening one place derives its entries and no other place's`() {
        val r = build(places(5), everything(yDefs(3)), open = setOf("L2"))
        assertEquals(setOf("cp:L2|y1", "cp:L2|y2", "cp:L2|y3"), r.derivedIds)
        assertEquals(8, r.tasks.size)
    }

    @Test fun `opening a derived entry that holds more derives the next axis`() {
        val z = listOf(t("z1", Axes.Z), t("z2", Axes.Z))
        val e = everything(yDefs(1) + z)
        val r = build(listOf(t("L1")), e, open = setOf("L1", "cp:L1|y1"))
        assertEquals(setOf("cp:L1|y1", "cp:L1/y1|z1", "cp:L1/y1|z2"), r.derivedIds)
    }

    @Test fun `an opened place whose entries are all touched derives nothing`() {
        val nodes = listOf(AxisNode("real1", "y1", Axes.Y, "L1"), AxisNode("real2", "y2", Axes.Y, "L1"))
        val r = build(listOf(t("L1")), everything(yDefs(2), nodes = nodes), open = setOf("L1"))
        assertTrue(r.derivedIds.isEmpty())
    }

    // ── the hard cap ─────────────────────────────────────────────────────

    @Test fun `no amount of entries can exceed the cap, and it says so`() {
        val r = build(listOf(t("L1")), everything(yDefs(500)), open = setOf("L1"), max = 50)
        assertEquals(50, r.derivedIds.size)
        assertTrue(r.truncated)
    }

    @Test fun `the cap is shared across places`() {
        val r = build(places(10), everything(yDefs(20)), open = (1..10).map { "L$it" }.toSet(), max = 60)
        assertEquals(60, r.derivedIds.size); assertTrue(r.truncated)
    }

    @Test fun `under the cap nothing is truncated`() {
        val r = build(listOf(t("L1")), everything(yDefs(10)), open = setOf("L1"), max = 50)
        assertFalse(r.truncated)
    }

    // ── the common case costs nothing ────────────────────────────────────

    @Test fun `with no later-axis entries the real list is returned untouched`() {
        val real = places(5)
        assertSame(real, AxisVirtualList.build(real, everything(emptyList()), isOpen = { true }).tasks)
    }

    @Test fun `rows that are not places are left exactly as they are`() {
        val group = t("G", group = true); val interrupt = t("I").copy(isInterrupt = true)
        val r = build(listOf(group, interrupt), everything(yDefs(3)), open = setOf("G", "I"))
        assertEquals(listOf(group, interrupt), r.tasks); assertTrue(r.derivedIds.isEmpty())
    }
}
