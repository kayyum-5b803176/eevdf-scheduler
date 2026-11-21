package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisChecklistTest {

    private fun t(id: String, name: String = id, parent: String? = null, group: Boolean = false) =
        Task(id = id, name = name, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = Axes.Y)

    private val tree = listOf(
        t("g", "Group", group = true), t("g2", "b-child", parent = "g"), t("g1", "a-child", parent = "g"),
        t("solo", "Solo"),
    )

    @Test fun `flatten is depth first, sorted by name, with depth`() {
        val rows = AxisChecklist.flatten(tree)
        assertEquals(listOf("g", "g1", "g2", "solo"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
    }

    @Test fun `orphan whose parent is not in the list is treated as a root`() {
        val rows = AxisChecklist.flatten(listOf(t("x", parent = "missing")))
        assertEquals(listOf(0), rows.map { it.depth })
    }

    @Test fun `unchecked group stores only the group`() {
        val state = mapOf("g" to false, "g1" to false, "g2" to false, "solo" to true)
        assertEquals(setOf("g"), AxisChecklist.topmostExcluded(tree, state))
    }

    @Test fun `unchecked child of a checked group is stored on its own`() {
        val state = mapOf("g" to true, "g1" to false, "g2" to true, "solo" to true)
        assertEquals(setOf("g1"), AxisChecklist.topmostExcluded(tree, state))
    }

    @Test fun `everything checked stores nothing`() {
        assertEquals(emptySet<String>(), AxisChecklist.topmostExcluded(tree, tree.associate { it.id to true }))
    }
}
