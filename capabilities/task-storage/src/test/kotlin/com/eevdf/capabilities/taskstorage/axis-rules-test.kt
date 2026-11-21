package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisRulesTest {

    private fun t(id: String, axis: String = Axes.Y, parent: String? = null, group: Boolean = false) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = axis)

    private fun ids(all: List<Task>, rules: Map<String, Boolean> = emptyMap(), hideNew: Boolean = false, axis: String = Axes.Y) =
        AxisAclRules.includedEntries(axis, all, rules, hideNew).map { it.id }.toSet()

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

    @Test fun `no rules and no default - everything is included`() {
        assertEquals(setOf("a", "b", "c"), ids(listOf(t("a"), t("b"), t("c"))))
    }

    // user-1 only a ; user-2 b, c, a  (the example from the design discussion)
    @Test fun `each door includes only what it wants - by Hide`() {
        val all = listOf(t("a"), t("b"), t("c"))
        assertEquals(setOf("a"), ids(all, mapOf("b" to true, "c" to true)))
        assertEquals(setOf("a", "b", "c"), ids(all))
    }

    @Test fun `each door includes only what it wants - by hide new and Show`() {
        val all = listOf(t("a"), t("b"), t("c"))
        assertEquals(setOf("a"), ids(all, mapOf("a" to false), hideNew = true))
        assertEquals(emptySet<String>(), ids(all, emptyMap(), hideNew = true))
    }

    @Test fun `Show beats the door default for that entry only`() {
        val all = listOf(t("a"), t("b"))
        assertEquals(setOf("b"), ids(all, mapOf("b" to false), hideNew = true))
    }

    @Test fun `hiding a group hides its whole subtree and Show inside it cannot override`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"), t("g2", parent = "g"), t("solo"))
        assertEquals(setOf("solo"), ids(all, mapOf("g" to true)))
        assertEquals(setOf("solo"), ids(all, mapOf("g" to true, "g1" to false)))
    }

    @Test fun `a deeper rule wins inside a shown group`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"), t("g2", parent = "g"))
        assertEquals(setOf("g", "g1"), ids(all, mapOf("g2" to true)))
    }

    @Test fun `hide new applies to direct entries only - inside a Shown group the default is shown`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"), t("other"))
        assertEquals(setOf("g", "g1"), ids(all, mapOf("g" to false), hideNew = true))
    }

    @Test fun `only the requested axis is considered`() {
        val all = listOf(t("a", Axes.X), t("b", Axes.Y), t("c", Axes.Z))
        assertEquals(setOf("b"), ids(all))
        assertEquals(setOf("c"), ids(all, axis = Axes.Z))
    }

    @Test fun `parent cycle cannot hang`() {
        val a = t("a", parent = "b"); val b = t("b", parent = "a")
        assertFalse(AxisAclRules.isHidden(a, mapOf("a" to a, "b" to b), emptyMap(), false))
        assertTrue(AxisAclRules.isHidden(a, mapOf("a" to a, "b" to b), mapOf("b" to true), false))
    }
}
