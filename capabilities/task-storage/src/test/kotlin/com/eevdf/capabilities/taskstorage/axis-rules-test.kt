package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisRulesTest {

    private fun t(id: String, axis: String = Axes.Y, parent: String? = null, group: Boolean = false) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = axis)

    @Test fun `existing rows default to the first axis`() {
        assertEquals(Axes.X, Task(name = "n", priority = 1, timeSliceSeconds = 1).axisId)
    }

    @Test fun `axis order and next`() {
        assertEquals(Axes.Y, Axes.next(Axes.X)); assertEquals(Axes.Z, Axes.next(Axes.Y))
        assertEquals(null, Axes.next(Axes.Z))
    }

    @Test fun `scope key joins picked leaves outermost first`() {
        assertEquals("", Axes.scopeKey(emptyList()))
        assertEquals("L", Axes.scopeKey(listOf("L")))
        assertEquals("L/M", Axes.scopeKey(listOf("L", "M")))
    }

    // user-1 only a ; user-2 b, c, a  (the example from the design discussion)
    @Test fun `each outer leaf includes only what it wants`() {
        val all = listOf(t("a"), t("b"), t("c"))
        val user1 = AxisRules.includedEntries(Axes.Y, all, excludedIds = setOf("b", "c")).map { it.id }.toSet()
        val user2 = AxisRules.includedEntries(Axes.Y, all, excludedIds = emptySet()).map { it.id }.toSet()
        assertEquals(setOf("a"), user1)
        assertEquals(setOf("a", "b", "c"), user2)
    }

    @Test fun `excluding a group excludes its whole subtree`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"), t("g2", parent = "g"), t("solo"))
        val ids = AxisRules.includedEntries(Axes.Y, all, setOf("g")).map { it.id }.toSet()
        assertEquals(setOf("solo"), ids)
    }

    @Test fun `only the requested axis is considered`() {
        val all = listOf(t("a", Axes.X), t("b", Axes.Y), t("c", Axes.Z))
        assertEquals(listOf("b"), AxisRules.includedEntries(Axes.Y, all, emptySet()).map { it.id })
    }

    @Test fun `parent cycle cannot hang`() {
        val a = t("a", parent = "b"); val b = t("b", parent = "a")
        assertTrue(AxisRules.isIncluded(a, mapOf("a" to a, "b" to b), emptySet()))
        assertFalse(AxisRules.isIncluded(a, mapOf("a" to a, "b" to b), setOf("b")))
    }
}
