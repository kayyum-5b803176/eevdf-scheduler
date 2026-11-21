package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisReconcileTest {

    private fun def(id: String, axis: String = Axes.Y, parent: String? = null, group: Boolean = false, name: String = id) =
        Task(id = id, name = name, priority = 3, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = axis)

    private fun existing(defId: String, scope: String, axis: String = Axes.Y, task: Task = def("copy-$defId", Axes.X)) =
        AxisReconcile.Existing(AxisNode(task.id, defId, axis, scope), task)

    // ── plan ─────────────────────────────────────────────────────────────

    @Test fun `nothing exists yet - every included definition is created`() {
        val p = AxisReconcile.plan(Axes.Y, listOf(def("a"), def("b")), emptyMap(), false, emptyList())
        assertEquals(setOf("a", "b"), p.create.map { it.id }.toSet())
        assertTrue(p.remove.isEmpty()); assertTrue(p.keep.isEmpty())
    }

    @Test fun `parents are created before their children`() {
        val defs = listOf(def("g1", parent = "g", group = false), def("g", group = true), def("solo"))
        val order = AxisReconcile.plan(Axes.Y, defs, emptyMap(), false, emptyList()).create.map { it.id }
        assertTrue(order.indexOf("g") < order.indexOf("g1"))
    }

    @Test fun `hidden definitions are not created and their copies are removed`() {
        val defs = listOf(def("a"), def("b"))
        val have = listOf(existing("a", "L"), existing("b", "L"))
        val p = AxisReconcile.plan(Axes.Y, defs, mapOf("b" to true), false, have)
        assertEquals(listOf("b"), p.remove.map { it.node.definitionId })
        assertEquals(listOf("a"), p.keep.map { it.first.node.definitionId })
        assertTrue(p.create.isEmpty())
    }

    @Test fun `hiding a group removes its whole subtree`() {
        val defs = listOf(def("g", group = true), def("g1", parent = "g"), def("solo"))
        val have = listOf(existing("g", "L"), existing("g1", "L"), existing("solo", "L"))
        val p = AxisReconcile.plan(Axes.Y, defs, mapOf("g" to true), false, have)
        assertEquals(setOf("g", "g1"), p.remove.map { it.node.definitionId }.toSet())
        assertEquals(setOf("solo"), p.keep.map { it.first.node.definitionId }.toSet())
    }

    @Test fun `hide new - only entries that are Shown get a copy`() {
        val defs = listOf(def("a"), def("b"), def("c"))
        val p = AxisReconcile.plan(Axes.Y, defs, mapOf("a" to false), hideNew = true, existing = emptyList())
        assertEquals(listOf("a"), p.create.map { it.id })
    }

    @Test fun `hide new - a definition added later does not appear by itself`() {
        val have = listOf(existing("a", "L"))
        val p = AxisReconcile.plan(Axes.Y, listOf(def("a"), def("later")), mapOf("a" to false), hideNew = true, existing = have)
        assertTrue(p.create.isEmpty()); assertTrue(p.remove.isEmpty())
    }

    @Test fun `Show under a hidden group stays hidden - the group locks its inside`() {
        val defs = listOf(def("g", group = true), def("g1", parent = "g"))
        val p = AxisReconcile.plan(Axes.Y, defs, mapOf("g" to true, "g1" to false), false, emptyList())
        assertTrue(p.create.isEmpty())
    }

    @Test fun `a deleted definition removes its copy`() {
        val p = AxisReconcile.plan(Axes.Y, listOf(def("a")), emptyMap(), false, listOf(existing("a", "L"), existing("gone", "L")))
        assertEquals(listOf("gone"), p.remove.map { it.node.definitionId })
    }

    @Test fun `an existing copy is never created twice`() {
        val p = AxisReconcile.plan(Axes.Y, listOf(def("a")), emptyMap(), false, listOf(existing("a", "L")))
        assertTrue(p.create.isEmpty())
        assertEquals(1, p.included.size)
    }

    @Test fun `only the requested axis is considered`() {
        val p = AxisReconcile.plan(Axes.Y, listOf(def("a"), def("p", Axes.Z)), emptyMap(), false, emptyList())
        assertEquals(listOf("a"), p.create.map { it.id })
    }

    // ── scope / parent ────────────────────────────────────────────────────

    @Test fun `scope of a first-axis row is its own id, of a copy its path`() {
        val x = def("L", Axes.X)
        assertEquals("L", AxisReconcile.childScope(x, null))
        val yCopy = def("copy", Axes.X)
        assertEquals("L/a", AxisReconcile.childScope(yCopy, AxisNode("copy", "a", Axes.Y, "L")))
    }

    @Test fun `this matches the checklist's scope key for the third axis`() {
        val yCopy = def("copy", Axes.X)
        assertEquals(Axes.scopeKey(listOf("L", "a")), AxisReconcile.childScope(yCopy, AxisNode("copy", "a", Axes.Y, "L")))
    }

    @Test fun `a copy hangs under its parent definition's copy when included, else under the outer row`() {
        val child = def("g1", parent = "g")
        assertEquals("copy-g", AxisReconcile.parentFor(child, setOf("g", "g1"), mapOf("g" to "copy-g"), "L"))
        assertEquals("L", AxisReconcile.parentFor(child, setOf("g1"), emptyMap(), "L"))
        assertEquals("L", AxisReconcile.parentFor(def("solo"), setOf("solo"), emptyMap(), "L"))
    }

    // ── a copy is a real row ──────────────────────────────────────────────

    @Test fun `new copy is a first-axis row with its own id, fresh runtime and the definition's fields`() {
        val d = def("a", group = false, name = "Write").copy(quotaSeconds = 600, rtPriority = 70, schedulerClass = "rt_sched_class")
        val c = AxisReconcile.newCopy(d, "id-1", "L")
        assertEquals("id-1", c.id); assertEquals("L", c.parentId); assertEquals(Axes.X, c.axisId)
        assertEquals("Write", c.name); assertEquals(600L, c.quotaSeconds); assertEquals(70, c.rtPriority)
        assertEquals("rt_sched_class", c.schedulerClass)
        assertEquals(0.0, c.vruntime, 0.0); assertEquals(0L, c.totalRunTime); assertEquals(0, c.runCount)
        assertFalse(c.isRunning); assertFalse(c.isCompleted)
        assertEquals(60L, c.remainingSeconds)
    }

    @Test fun `copy of a group definition is a group`() {
        assertTrue(AxisReconcile.newCopy(def("g", group = true), "id", "L").isGroup)
    }

    @Test fun `editing the definition never touches a copy's own state`() {
        val copy = def("a", Axes.X).copy(
            id = "c", parentId = "L", vruntime = 42.0, totalRunTime = 900, runCount = 7, isCompleted = true,
            quotaUsedSeconds = 55, sleepUntilEpoch = 123, remainingSeconds = 17, isGroup = true, isRunning = false,
        )
        val edited = def("a").copy(name = "Renamed", priority = 9, timeSliceSeconds = 120, quotaSeconds = 300)
        val out = copy.sharingFrom(edited)
        assertEquals("Renamed", out.name); assertEquals(9, out.priority); assertEquals(300L, out.quotaSeconds)
        assertEquals(42.0, out.vruntime, 0.0); assertEquals(900L, out.totalRunTime); assertEquals(7, out.runCount)
        assertTrue(out.isCompleted); assertEquals(55L, out.quotaUsedSeconds); assertEquals(123L, out.sleepUntilEpoch)
        assertTrue(out.isGroup)                    // a container stays a container
        assertEquals("L", out.parentId)            // structure is the reconciler's call, not a field copy
        assertEquals("c", out.id)
    }

    @Test fun `an untouched timer follows a new slice, a partly used one is left alone`() {
        val idle = def("a", Axes.X).copy(timeSliceSeconds = 60, remainingSeconds = 60)
        val used = def("a", Axes.X).copy(timeSliceSeconds = 60, remainingSeconds = 20)
        val edited = def("a").copy(timeSliceSeconds = 90)
        assertEquals(90L, idle.sharingFrom(edited).remainingSeconds)
        assertEquals(20L, used.sharingFrom(edited).remainingSeconds)
        assertEquals(90L, used.sharingFrom(edited).timeSliceSeconds)
    }

    @Test fun `internal weight is derived per sibling pool, so it is not copied`() {
        val copy = def("a", Axes.X).copy(internalWeight = 4.2)
        val d = def("a").copy(internalWeight = 9.9, pinnedShare = 25.0)
        val out = copy.sharingFrom(d)
        assertEquals(25.0, out.pinnedShare!!, 0.0)
        assertEquals(4.2, out.internalWeight!!, 0.0)
        assertNull(AxisReconcile.newCopy(d, "id", "L").internalWeight)
    }
}
