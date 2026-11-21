package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisVirtualTest {

    private fun t(id: String, axis: String = Axes.X, group: Boolean = false, parent: String? = null) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, isGroup = group, parentId = parent, axisId = axis)

    private val l1 = t("L1"); private val l2 = t("L2")
    private val a = t("a", Axes.Y); private val b = t("b", Axes.Y)
    private val grp = t("grp", Axes.Y, group = true); private val g1 = t("g1", Axes.Y, parent = "grp")
    private val z1 = t("z1", Axes.Z)

    private fun engine(
        defs: List<Task> = listOf(a, b, grp, g1, z1),
        nodes: List<AxisNode> = emptyList(),
        containers: Set<String> = emptySet(),
        acl: List<AxisAcl> = emptyList(),
        doors: List<AxisDoor> = emptyList(),
        routes: Map<String, String> = emptyMap(),
        globalHideNew: Boolean = true,
    ) = AxisVirtualEngine(defs, nodes, containers, acl, doors, routes, globalHideNew)

    private fun ids(list: List<Task>) = list.map { it.id }

    // ── ids ──────────────────────────────────────────────────────────────

    @Test fun `a virtual id round-trips and is deterministic`() {
        val id = AxisVirtualId.of("L1/a", "z1")
        assertEquals("cp:L1/a|z1", id)
        assertEquals("L1/a" to "z1", AxisVirtualId.parse(id))
        assertEquals(id, AxisVirtualId.of("L1/a", "z1"))
    }

    @Test fun `an ordinary id is not virtual`() {
        assertFalse(AxisVirtualId.isVirtual("3f2a-uuid"))
        assertNull(AxisVirtualId.parse("3f2a-uuid"))
        assertNull(AxisVirtualId.parse("cp:nobar"))
        assertNull(AxisVirtualId.parse("cp:|x"))
    }

    // ── hide new by default: nothing is derived ──────────────────────────

    @Test fun `by default nothing is derived anywhere`() {
        val e = engine()
        assertFalse(e.hasVirtual(l1)); assertTrue(e.childrenOf(l1).isEmpty())
    }

    @Test fun `no later-axis entries at all - the engine is empty`() {
        assertTrue(engine(defs = emptyList()).isEmpty)
        assertFalse(engine().isEmpty)
    }

    // ── a plus, a route, or hide new off derives copies ──────────────────

    @Test fun `a plus at a door derives exactly that entry there and nowhere else`() {
        val e = engine(acl = listOf(AxisAcl("L1", "a", hidden = false)))
        assertTrue(e.hasVirtual(l1)); assertFalse(e.hasVirtual(l2))
        val kids = e.childrenOf(l1)
        assertEquals(listOf("cp:L1|a"), ids(kids))
        assertEquals("L1", kids[0].parentId); assertEquals(Axes.X, kids[0].axisId)
        assertTrue(e.childrenOf(l2).isEmpty())
    }

    @Test fun `a derived copy is a fresh row of its definition`() {
        val def = a.copy(name = "Write", priority = 7, quotaSeconds = 600, vruntime = 99.0, runCount = 5, totalRunTime = 1000)
        val kid = engine(defs = listOf(def), acl = listOf(AxisAcl("L1", "a", false))).childrenOf(l1).single()
        assertEquals("Write", kid.name); assertEquals(7, kid.priority); assertEquals(600L, kid.quotaSeconds)
        assertEquals(0, kid.runCount); assertEquals(0L, kid.totalRunTime)
        assertFalse(kid.isRunning); assertFalse(kid.isCompleted)
    }

    @Test fun `with hide new off every included entry is derived, a group's entries under their group copy`() {
        val kids = engine(globalHideNew = false, defs = listOf(a, b, grp, g1)).childrenOf(l1)
        assertEquals(setOf("cp:L1|a", "cp:L1|b", "cp:L1|grp", "cp:L1|g1"), ids(kids).toSet())
        assertEquals("cp:L1|grp", kids.first { it.id == "cp:L1|g1" }.parentId)
        assertEquals("L1", kids.first { it.id == "cp:L1|grp" }.parentId)
        assertTrue(ids(kids).indexOf("cp:L1|grp") < ids(kids).indexOf("cp:L1|g1"))     // parents first
    }

    @Test fun `a route shows the entry at the doors it matches`() {
        val e = engine(defs = listOf(a, b), routes = mapOf("a" to "L1"))
        assertEquals(listOf("cp:L1|a"), ids(e.childrenOf(l1)))
        assertTrue(e.childrenOf(l2).isEmpty()); assertFalse(e.hasVirtual(l2))
    }

    @Test fun `an explicit Hide beats everything`() {
        val kids = engine(globalHideNew = false, defs = listOf(a, b), acl = listOf(AxisAcl("L1", "b", true))).childrenOf(l1)
        assertEquals(listOf("cp:L1|a"), ids(kids))
    }

    @Test fun `a switch the door set itself holds back new entries even when the default would show them`() {
        val e = engine(globalHideNew = false, defs = listOf(a, b), doors = listOf(AxisDoor("L1", true)),
            acl = listOf(AxisAcl("L1", "a", false)))
        assertEquals(listOf("cp:L1|a"), ids(e.childrenOf(l1)))
    }

    // ── what already exists is not derived again ─────────────────────────

    @Test fun `a copy that was touched is a row - it is not derived twice`() {
        val have = AxisNode("real-a", "a", Axes.Y, "L1")
        val kids = engine(globalHideNew = false, defs = listOf(a, b), nodes = listOf(have)).childrenOf(l1)
        assertEquals(listOf("cp:L1|b"), ids(kids))
    }

    @Test fun `an entry inside a group hangs under the group's real copy when that was touched`() {
        val have = AxisNode("real-grp", "grp", Axes.Y, "L1")
        val kids = engine(globalHideNew = false, defs = listOf(grp, g1), nodes = listOf(have)).childrenOf(l1)
        assertEquals(listOf("cp:L1|g1"), ids(kids))
        assertEquals("real-grp", kids.single().parentId)
    }

    // ── what a place is ──────────────────────────────────────────────────

    @Test fun `only a leaf, or a leaf that became a container, is a place`() {
        val e = engine(containers = setOf("C"), acl = listOf(AxisAcl("C", "a", false), AxisAcl("G", "a", false)))
        val group = t("G", group = true); val container = t("C", group = true)
        assertFalse(e.isPlace(group)); assertTrue(e.isPlace(container)); assertTrue(e.isPlace(l1))
        assertFalse(e.hasVirtual(group)); assertTrue(e.hasVirtual(container))
        assertFalse(e.isPlace(l1.copy(isInterrupt = true))); assertFalse(e.isPlace(l1.copy(isCompleted = true)))
    }

    @Test fun `a later-axis definition is not a place`() {
        assertFalse(engine().isPlace(a))
    }

    // ── scope: where an entry's own entries live ─────────────────────────

    @Test fun `scope of a first-axis row, a real copy and a derived copy`() {
        val e = engine(nodes = listOf(AxisNode("real-a", "a", Axes.Y, "L1")))
        assertEquals("L1", e.scopeOf(l1))
        assertEquals("L1/a", e.scopeOf(t("real-a")))
        assertEquals("L1/b", e.scopeOf(t("cp:L1|b")))
    }

    // ── the next axis, under a derived copy ──────────────────────────────

    @Test fun `a derived copy is itself a place for the next axis`() {
        val e = engine(acl = listOf(AxisAcl("L1", "a", false), AxisAcl("L1/a", "z1", false)))
        val derivedA = e.childrenOf(l1).single()
        assertEquals("cp:L1|a", derivedA.id)
        assertTrue(e.hasVirtual(derivedA))
        assertEquals(listOf("cp:L1/a|z1"), ids(e.childrenOf(derivedA)))
        assertEquals("cp:L1|a", e.childrenOf(derivedA).single().parentId)
    }

    @Test fun `an entry's own door applies to every copy of it`() {
        val e = engine(defs = listOf(a, z1), acl = listOf(AxisAcl("def:a", "z1", false)))
        val copyUnderL1 = t("cp:L1|a"); val copyUnderL2 = t("cp:L2|a")
        assertEquals(listOf("cp:L1/a|z1"), ids(e.childrenOf(copyUnderL1)))
        assertEquals(listOf("cp:L2/a|z1"), ids(e.childrenOf(copyUnderL2)))
    }

    @Test fun `the last axis holds nothing`() {
        val e = engine(acl = listOf(AxisAcl("L1/a", "z1", false)))
        assertTrue(e.childrenOf(t("cp:L1/a|z1")).isEmpty())
    }

    // ── the cheap check agrees with the exact list ───────────────────────

    @Test fun `hasVirtual never says no where there is something to derive`() {
        val cases = listOf(
            engine(acl = listOf(AxisAcl("L1", "a", false))),
            engine(globalHideNew = false),
            engine(routes = mapOf("a" to "L1")),
            engine(),
        )
        for (e in cases) if (e.childrenOf(l1).isNotEmpty()) assertTrue(e.hasVirtual(l1))
    }

    // ── the pure "missing" rule ──────────────────────────────────────────

    @Test fun `missing lists what should be there and is not, parents first`() {
        val m = AxisReconcile.missing(Axes.Y, listOf(g1, a, grp), emptyMap(), hideNew = false, haveDefinitionIds = setOf("a"))
        assertEquals(listOf("grp", "g1"), ids(m))
    }

    @Test fun `missing respects hide new and explicit shows`() {
        val m = AxisReconcile.missing(Axes.Y, listOf(a, b), mapOf("b" to false), hideNew = true, haveDefinitionIds = emptySet())
        assertEquals(listOf("b"), ids(m))
    }
}
