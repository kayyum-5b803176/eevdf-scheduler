package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisPathTest {

    private val axes = Axes.ALL     // x, y, z

    private fun t(id: String, axis: String, group: Boolean = false, parent: String? = null) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, isGroup = group, parentId = parent, axisId = axis)

    // x: types (tasks) plus a group, and a container.   y: users (tasks) plus a group.   z: activities (groups) plus a task.
    private val type5 = t("type5", Axes.X); private val type6 = t("type6", Axes.X)
    private val typesGroup = t("types", Axes.X, group = true)
    private val cont = t("cont", Axes.X, group = true)
    private val user1 = t("user1", Axes.Y); private val user2 = t("user2", Axes.Y)
    private val team = t("team", Axes.Y, group = true)
    private val act1 = t("act1", Axes.Z, group = true); private val act2 = t("act2", Axes.Z, group = true)
    private val hello = t("hello", Axes.Z, parent = "act1")

    private fun snap(xRows: List<Task> = listOf(type5, type6, typesGroup, cont)) =
        AxisPath.Snapshot(xRows, listOf(user1, user2, team, act1, act2, hello), containers = setOf("cont"))

    private fun group(task: Task) = AxisPath.Choice(task, group = true)
    private fun leaf(task: Task) = AxisPath.Choice(task, group = false)
    private val none: List<AxisPath.Choice?> = listOf(null, null, null)
    private fun ids(list: List<AxisPath.Choice>) = list.map { it.task.id }.toSet()
    private fun ok(r: AxisPath.Result) = (r as AxisPath.Result.Ok).placement
    private fun reason(r: AxisPath.Result) = (r as AxisPath.Result.Invalid).reason

    // ── what an axis lists: groups until a deeper axis has a pick, then leaves ─────

    @Test fun `tap any axis first and it lists its groups, whatever the other axes hold`() {
        assertEquals(setOf("types"), ids(AxisPath.choices(axes, 0, none, snap())))
        assertEquals(setOf("team"), ids(AxisPath.choices(axes, 1, none, snap())))
        assertEquals(setOf("act1", "act2"), ids(AxisPath.choices(axes, 2, none, snap())))
    }

    @Test fun `a list is never a mix of groups and leaves`() {
        for (i in 0..2) {
            val groups = AxisPath.choices(axes, i, none, snap())
            assertTrue(groups.all { it.group })
        }
        val picks = listOf(null, null, group(act1))
        for (i in 0..1) assertTrue(AxisPath.choices(axes, i, picks, snap()).none { it.group })
    }

    @Test fun `once a deeper axis has a pick, the axes above list leaf nodes - the route`() {
        val picks = listOf(null, null, group(act1))
        assertEquals(setOf("type5", "type6", "cont"), ids(AxisPath.choices(axes, 0, picks, snap())))   // a container is a leaf
        assertEquals(setOf("user1", "user2"), ids(AxisPath.choices(axes, 1, picks, snap())))
    }

    @Test fun `an axis switches by itself - clearing the deeper pick brings its groups back`() {
        assertTrue(AxisPath.wantsGroups(1, none))
        assertFalse(AxisPath.wantsGroups(1, listOf(null, null, group(act1))))
        assertTrue(AxisPath.wantsGroups(1, listOf(leaf(type5), null, null)))        // x is above y: it does not count
    }

    @Test fun `the last axis always lists groups, the first axis lists leaves as soon as anything below is picked`() {
        assertTrue(AxisPath.wantsGroups(2, listOf(leaf(type5), leaf(user1), null)))
        assertFalse(AxisPath.wantsGroups(0, listOf(null, group(team), null)))
    }

    @Test fun `lists are not narrowed by the other picks - a route axis can be picked first or skipped`() {
        val a = AxisPath.choices(axes, 0, listOf(null, group(team), null), snap())
        val b = AxisPath.choices(axes, 0, listOf(null, null, group(act1)), snap())
        assertEquals(ids(a), ids(b))
    }

    @Test fun `the row being edited and everything inside it are never offered`() {
        assertEquals(setOf("act2"), ids(AxisPath.choices(axes, 2, none, snap(), exclude = "act1")))
    }

    @Test fun `interrupt and completed rows are not offered on the first axis`() {
        val interrupt = type6.copy(isInterrupt = true); val done = type5.copy(isCompleted = true)
        val list = AxisPath.choices(axes, 0, listOf(null, null, group(act1)), snap(xRows = listOf(done, interrupt, cont)))
        assertEquals(setOf("cont"), ids(list))
    }

    // ── picks -> placement: your examples ───────────────────────────────

    @Test fun `tap Y first, pick y1, route x - the task is in y1 and exists only under type5`() {
        val p = ok(AxisPath.resolve(axes, listOf(leaf(type5), group(t("y1", Axes.Y, group = true)), null)))
        assertEquals(1, p.axisIndex); assertEquals("y1", p.parentId); assertEquals("type5", p.route)
        assertEquals(listOf("type5", "y1"), p.labels)
    }

    @Test fun `tap Z, pick the group, skip Y, route x - any user under type5`() {
        val p = ok(AxisPath.resolve(axes, listOf(leaf(type5), null, group(act1))))
        assertEquals(2, p.axisIndex); assertEquals("act1", p.parentId); assertEquals("type5/*", p.route)
        assertEquals(listOf("type5", "any", "act1"), p.labels)
    }

    @Test fun `route x and y, group on z - exactly that place`() {
        val p = ok(AxisPath.resolve(axes, listOf(leaf(type5), leaf(user1), group(act1))))
        assertEquals("type5/user1", p.route)
    }

    @Test fun `skipping x but picking y - any type, that user`() {
        val p = ok(AxisPath.resolve(axes, listOf(null, leaf(user1), group(act1))))
        assertEquals("*/user1", p.route)
    }

    @Test fun `only a group - an ordinary placement with nothing narrowed`() {
        assertEquals(null, ok(AxisPath.resolve(axes, listOf(null, null, group(act1)))).route)
        assertEquals(null, ok(AxisPath.resolve(axes, listOf(null, group(team), null))).route)
    }

    @Test fun `a group on the first axis is an ordinary placement`() {
        val p = ok(AxisPath.resolve(axes, listOf(group(typesGroup), null, null)))
        assertEquals(0, p.axisIndex); assertEquals("types", p.parentId); assertEquals(null, p.route)
    }

    // ── what is not allowed ──────────────────────────────────────────────

    @Test fun `nothing picked`() {
        assertEquals("Pick a group.", reason(AxisPath.resolve(axes, none)))
    }

    @Test fun `the last pick must be a group`() {
        assertEquals("Pick a group on Y.", reason(AxisPath.resolve(axes, listOf(null, leaf(user1), null))))
    }

    @Test fun `a route entry must be a task`() {
        assertTrue(reason(AxisPath.resolve(axes, listOf(group(typesGroup), null, group(act1)))).contains("group"))
    }

    // ── keeping the picks consistent ─────────────────────────────────────

    @Test fun `a group above the anchor is dropped - a group cannot be a route entry`() {
        val n = AxisPath.normalize(axes, listOf(group(typesGroup), group(team), null))
        assertEquals(listOf(null, group(team), null), n.picks)
        assertEquals(listOf("x"), n.cleared)
    }

    @Test fun `a leaf as the deepest pick is dropped - it cannot be the group`() {
        val n = AxisPath.normalize(axes, listOf(leaf(type5), null, null))
        assertEquals(none, n.picks); assertEquals(listOf("x"), n.cleared)
    }

    @Test fun `clearing the anchor drops the route that hung from it`() {
        val n = AxisPath.normalize(axes, listOf(leaf(type5), leaf(user1), null))
        assertEquals(none, n.picks); assertEquals(listOf("y", "x"), n.cleared)
    }

    @Test fun `a consistent set of picks is left alone`() {
        val picks = listOf(leaf(type5), leaf(user1), group(act1))
        val n = AxisPath.normalize(axes, picks)
        assertEquals(picks, n.picks); assertTrue(n.cleared.isEmpty())
    }

    // ── your example, end to end through the lists ───────────────────────

    @Test fun `z first, then y and x as the route, through the lists`() {
        val s = snap()
        var picks: List<AxisPath.Choice?> = none
        val a = AxisPath.choices(axes, 2, picks, s).first { it.task.id == "act1" }              // tap Z: its groups
        picks = listOf(null, null, a)
        val y = AxisPath.choices(axes, 1, picks, s).first { it.task.id == "user1" }              // then Y: leaves
        picks = listOf(null, y, a)
        val x = AxisPath.choices(axes, 0, picks, s).first { it.task.id == "type5" }              // then X: leaves
        val p = ok(AxisPath.resolve(axes, listOf(x, y, a)))
        assertEquals("type5/user1", p.route); assertEquals("act1", p.parentId); assertEquals(2, p.axisIndex)
    }
}
