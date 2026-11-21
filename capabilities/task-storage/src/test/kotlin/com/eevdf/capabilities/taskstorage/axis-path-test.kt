package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisPathTest {

    private val axes = Axes.ALL     // x, y, z

    private fun t(id: String, axis: String, group: Boolean = false, parent: String? = null) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, isGroup = group, parentId = parent, axisId = axis)

    // x: types (leaves hold users), plus a group.   y: users (leaves hold activities), plus a group.
    // z: activities are groups; hello is a task inside act1.
    private val type5 = t("type5", Axes.X); private val type6 = t("type6", Axes.X)
    private val typesGroup = t("types", Axes.X, group = true)
    private val user1 = t("user1", Axes.Y); private val user2 = t("user2", Axes.Y)
    private val team = t("team", Axes.Y, group = true)
    private val act1 = t("act1", Axes.Z, group = true); private val act2 = t("act2", Axes.Z, group = true)
    private val hello = t("hello", Axes.Z, parent = "act1")

    private fun snap(
        acl: List<AxisAcl> = emptyList(), doors: List<AxisDoor> = emptyList(),
        hiddenEverywhere: Set<String> = emptySet(), containers: Set<String> = emptySet(),
        xRows: List<Task> = listOf(type5, type6, typesGroup),
    ) = AxisPath.Snapshot(xRows, listOf(user1, user2, team, act1, act2, hello), acl, doors, hiddenEverywhere, containers)

    private fun choice(task: Task, holder: Boolean) = AxisPath.Choice(task, holder)
    private val pType5 = choice(type5, true); private val pUser1 = choice(user1, true)
    private val pAct1 = choice(act1, false); private val pTeam = choice(team, false)

    private fun ids(list: List<AxisPath.Choice>) = list.map { it.task.id }.toSet()

    // ── what each axis lists ─────────────────────────────────────────────

    @Test fun `the first axis lists its rows - leaves and containers hold, groups do not`() {
        val container = t("cont", Axes.X, group = true)
        val list = AxisPath.choices(axes, 0, listOf(null, null, null), snap(xRows = listOf(type5, typesGroup, container), containers = setOf("cont")))
        assertEquals(setOf("type5", "types", "cont"), ids(list))
        assertTrue(list.first { it.task.id == "type5" }.holder)
        assertFalse(list.first { it.task.id == "types" }.holder)
        assertTrue(list.first { it.task.id == "cont" }.holder)
    }

    @Test fun `a later axis lists nothing until the axes before it are picked`() {
        assertTrue(AxisPath.choices(axes, 1, listOf(null, null, null), snap()).isEmpty())
        assertTrue(AxisPath.choices(axes, 2, listOf(pType5, null, null), snap()).isEmpty())
    }

    @Test fun `the second axis lists only what exists under the picked row`() {
        val acl = listOf(AxisAcl("type5", "user2", hidden = true))
        assertEquals(setOf("user1", "team"), ids(AxisPath.choices(axes, 1, listOf(pType5, null, null), snap(acl = acl))))
        assertEquals(setOf("user1", "user2", "team"), ids(AxisPath.choices(axes, 1, listOf(choice(type6, true), null, null), snap(acl = acl))))
    }

    @Test fun `the last axis offers groups only`() {
        val list = AxisPath.choices(axes, 2, listOf(pType5, pUser1, null), snap())
        assertEquals(setOf("act1", "act2"), ids(list))          // hello (a leaf) cannot hold anything
        assertTrue(list.none { it.holder })
    }

    @Test fun `the third axis follows the entry's door layered with the copy's own`() {
        // every copy of user1 hides act2; under type5 that copy shows it again
        val acl = listOf(AxisAcl("def:user1", "act2", true), AxisAcl("type5/user1", "act2", false))
        assertEquals(setOf("act1"), ids(AxisPath.choices(axes, 2, listOf(choice(type6, true), pUser1, null), snap(acl = acl))))
        assertEquals(setOf("act1", "act2"), ids(AxisPath.choices(axes, 2, listOf(pType5, pUser1, null), snap(acl = acl))))
    }

    @Test fun `an entry placed elsewhere is not offered here unless this door shows it`() {
        val s = snap(hiddenEverywhere = setOf("user2"), acl = listOf(AxisAcl("type5", "user2", false)))
        assertEquals(setOf("user1", "team", "user2"), ids(AxisPath.choices(axes, 1, listOf(pType5, null, null), s)))
        assertEquals(setOf("user1", "team"), ids(AxisPath.choices(axes, 1, listOf(choice(type6, true), null, null), s)))
    }

    @Test fun `the row being edited and everything inside it are never offered`() {
        val list = AxisPath.choices(axes, 2, listOf(pType5, pUser1, null), snap(), exclude = "act1")
        assertEquals(setOf("act2"), ids(list))
    }

    // ── picks → placement ────────────────────────────────────────────────

    private fun ok(r: AxisPath.Result) = (r as AxisPath.Result.Ok).placement

    @Test fun `type5, user1 and a group on z - the task goes in that group and exists only at type5 then user1`() {
        val p = ok(AxisPath.resolve(axes, listOf(pType5, pUser1, pAct1)))
        assertEquals(2, p.axisIndex); assertEquals("act1", p.parentId); assertEquals("type5/user1", p.door)
    }

    @Test fun `type5 and user1 with z empty - the task goes directly under user1`() {
        val p = ok(AxisPath.resolve(axes, listOf(pType5, pUser1, null)))
        assertEquals(2, p.axisIndex); assertEquals(null, p.parentId); assertEquals("type5/user1", p.door)
    }

    @Test fun `only type5 - the task goes directly under type5, on the second axis`() {
        val p = ok(AxisPath.resolve(axes, listOf(pType5, null, null)))
        assertEquals(1, p.axisIndex); assertEquals(null, p.parentId); assertEquals("type5", p.door)
    }

    @Test fun `a group on y with type5 - parent group on y, existing only under type5`() {
        val p = ok(AxisPath.resolve(axes, listOf(pType5, pTeam, null)))
        assertEquals(1, p.axisIndex); assertEquals("team", p.parentId); assertEquals("type5", p.door)
    }

    @Test fun `only a group on x is an ordinary placement with nothing narrowed`() {
        val p = ok(AxisPath.resolve(axes, listOf(choice(typesGroup, false), null, null)))
        assertEquals(0, p.axisIndex); assertEquals("types", p.parentId); assertEquals(null, p.door)
    }

    @Test fun `labels are the names of the picks, in order`() {
        assertEquals(listOf("type5", "user1", "act1"), ok(AxisPath.resolve(axes, listOf(pType5, pUser1, pAct1))).labels)
    }

    // ── what is not allowed ──────────────────────────────────────────────

    private fun reason(r: AxisPath.Result) = (r as AxisPath.Result.Invalid).reason

    @Test fun `nothing picked`() {
        assertTrue(AxisPath.resolve(axes, listOf(null, null, null)) is AxisPath.Result.Invalid)
    }

    @Test fun `picks must start at the first axis`() {
        assertEquals("Pick X first.", reason(AxisPath.resolve(axes, listOf(null, pUser1, null))))
        assertEquals("Pick Y first.", reason(AxisPath.resolve(axes, listOf(pType5, null, pAct1))))
    }

    @Test fun `a group can only be the last pick`() {
        val r = AxisPath.resolve(axes, listOf(choice(typesGroup, false), pUser1, null))
        assertTrue(reason(r).contains("group"))
    }

    @Test fun `a leaf on the last axis holds nothing`() {
        val r = AxisPath.resolve(axes, listOf(pType5, pUser1, choice(hello, true)))
        assertEquals("Pick a group on Z.", reason(r))
    }
}
