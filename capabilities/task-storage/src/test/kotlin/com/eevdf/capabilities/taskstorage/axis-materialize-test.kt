package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which entries an explicit '+' makes real — the pure half of "a '+' turns the virtual card into a row". */
class AxisMaterializeTest {

    private fun t(id: String, group: Boolean = false, parent: String? = null) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, isGroup = group, parentId = parent, axisId = Axes.Y)

    // grp ─ g1, sub ─ s1, s2        a (alone)
    private val grp = t("grp", group = true)
    private val g1  = t("g1", parent = "grp")
    private val sub = t("sub", group = true, parent = "grp")
    private val s1  = t("s1", parent = "sub")
    private val s2  = t("s2", parent = "sub")
    private val a   = t("a")
    private val defs = listOf(a, grp, g1, sub, s1, s2)

    private fun ids(list: List<Task>) = list.map { it.id }

    @Test fun `a plus on a leaf makes just that card`() {
        assertEquals(listOf("a"), ids(AxisMaterialize.shownTree("a", defs, mapOf("a" to false), hideNew = true)))
    }

    @Test fun `a plus on a group makes the whole tree inside it`() {
        val tree = ids(AxisMaterialize.shownTree("grp", defs, mapOf("grp" to false), hideNew = true))
        assertEquals(setOf("grp", "g1", "sub", "s1", "s2"), tree.toSet())
        assertEquals(5, tree.size)
    }

    @Test fun `a parent always comes before its children`() {
        val tree = ids(AxisMaterialize.shownTree("grp", defs, mapOf("grp" to false), hideNew = true))
        assertTrue(tree.indexOf("grp") < tree.indexOf("sub"))
        assertTrue(tree.indexOf("sub") < tree.indexOf("s1"))
        assertTrue(tree.indexOf("sub") < tree.indexOf("s2"))
    }

    @Test fun `a hidden entry inside the tree is left out with everything in it`() {
        val tree = ids(AxisMaterialize.shownTree("grp", defs, mapOf("grp" to false, "sub" to true), hideNew = true))
        assertEquals(setOf("grp", "g1"), tree.toSet())
    }

    @Test fun `a deeper plus does not bring back what a hidden parent hides`() {
        // grp is hidden: nothing under it can be shown, so a plus on s1 makes nothing.
        assertTrue(AxisMaterialize.shownTree("s1", defs, mapOf("grp" to true, "s1" to false), hideNew = true).isEmpty())
    }

    @Test fun `a plus on an entry inside a shown group makes only that branch`() {
        // hide new is off, so the group above is shown by default and the plus on "sub" is free to act.
        val tree = ids(AxisMaterialize.shownTree("sub", defs, mapOf("sub" to false), hideNew = false))
        assertEquals(setOf("sub", "s1", "s2"), tree.toSet())
    }

    @Test fun `with hide new on a plus inside a group that was never shown makes nothing`() {
        // The group above has no rule and the door hides new entries: it is hidden, which locks everything inside it.
        assertTrue(AxisMaterialize.shownTree("sub", defs, mapOf("sub" to false), hideNew = true).isEmpty())
        // Show the group too and the branch is made.
        val tree = ids(AxisMaterialize.shownTree("sub", defs, mapOf("grp" to false, "sub" to false), hideNew = true))
        assertEquals(setOf("sub", "s1", "s2"), tree.toSet())
    }

    @Test fun `the limit stops the tree and keeps parents first`() {
        val tree = ids(AxisMaterialize.shownTree("grp", defs, mapOf("grp" to false), hideNew = true, limit = 2))
        assertEquals(2, tree.size)
        assertEquals("grp", tree.first())
    }

    @Test fun `an entry that arrives later inside a plus group is part of its tree`() {
        // Walking up from the arrival finds the group with the plus; its tree now includes the newcomer.
        val late = t("late", parent = "sub")
        val tree = ids(AxisMaterialize.shownTree("grp", defs + late, mapOf("grp" to false), hideNew = true))
        assertTrue("late" in tree)
        assertEquals(6, tree.size)
    }

    @Test fun `a newcomer the door hides is not part of the tree`() {
        val late = t("late", parent = "sub")
        val tree = ids(AxisMaterialize.shownTree("grp", defs + late, mapOf("grp" to false, "late" to true), hideNew = true))
        assertFalse("late" in tree)
    }

    @Test fun `an unknown entry makes nothing`() {
        assertTrue(AxisMaterialize.shownTree("nope", defs, emptyMap(), hideNew = false).isEmpty())
    }

    @Test fun `a parent cycle cannot loop`() {
        val x = t("x", group = true, parent = "y"); val y = t("y", group = true, parent = "x")
        val tree = AxisMaterialize.shownTree("x", listOf(x, y), mapOf("x" to false), hideNew = true)
        assertTrue(tree.size <= 2)
    }

    @Test fun `only a rule on a copy's or place's own door makes cards real`() {
        assertTrue(AxisMaterialize.makesCardsReal(AxisAcl("L1", "a", hidden = false)))
        assertTrue(AxisMaterialize.makesCardsReal(AxisAcl("L1/a", "z1", hidden = false)))
        assertFalse(AxisMaterialize.makesCardsReal(AxisAcl("L1", "a", hidden = true)))
        assertFalse(AxisMaterialize.makesCardsReal(AxisAcl(Axes.definitionDoor("a"), "z1", hidden = false)))
    }
}
