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

    // ── a copy's door is layered over its entry's door ───────────────────

    @Test fun `copy rule wins over the entry's per key, the rest follows the entry`() {
        val merged = AxisAclRules.merge(entryRules = mapOf("a" to true, "b" to true), copyRules = mapOf("a" to false))
        assertEquals(mapOf("a" to false, "b" to true), merged)
    }

    @Test fun `a copy that sets nothing follows the entry entirely`() {
        val all = listOf(t("a"), t("b"), t("c"))
        val merged = AxisAclRules.merge(mapOf("b" to true), emptyMap())
        assertEquals(setOf("a", "c"), ids(all, merged))
    }

    @Test fun `Show on a copy overrides the entry's Hide for that one entry`() {
        val all = listOf(t("a"), t("b"))
        val merged = AxisAclRules.merge(mapOf("a" to true, "b" to true), mapOf("a" to false))
        assertEquals(setOf("a"), ids(all, merged))
    }

    @Test fun `Show on a copy cannot reach inside a group the entry hides - the group locks it`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"))
        val merged = AxisAclRules.merge(mapOf("g" to true), mapOf("g1" to false))
        assertEquals(emptySet<String>(), ids(all, merged))
    }

    @Test fun `hide new - the copy's own default wins, else the entry's, else everything included`() {
        assertEquals(true,  AxisAclRules.effectiveHideNew(copyHideNew = true,  entryHideNew = false))
        assertEquals(false, AxisAclRules.effectiveHideNew(copyHideNew = false, entryHideNew = true))
        assertEquals(true,  AxisAclRules.effectiveHideNew(copyHideNew = null,  entryHideNew = true))
        assertEquals(false, AxisAclRules.effectiveHideNew(copyHideNew = null,  entryHideNew = null))
    }

    @Test fun `an entry that hides new, and a copy that shows only one thing`() {
        val all = listOf(t("a"), t("b"), t("c"))
        val hideNew = AxisAclRules.effectiveHideNew(null, true)
        assertEquals(emptySet<String>(), ids(all, AxisAclRules.merge(emptyMap(), emptyMap()), hideNew))
        assertEquals(setOf("b"), ids(all, AxisAclRules.merge(emptyMap(), mapOf("b" to false)), hideNew))
    }

    // ── door keys ────────────────────────────────────────────────────────

    @Test fun `a copy's door layers over its entry's door`() {
        assertEquals("def:a", Axes.layerDoorOf("L/a"))
        assertEquals("def:b", Axes.layerDoorOf("L/a/b"))
        assertEquals(Axes.definitionDoor("a"), Axes.layerDoorOf("L/a"))
    }

    @Test fun `a first-axis row's door and an entry's own door have nothing to layer over`() {
        assertEquals(null, Axes.layerDoorOf("L"))
        assertEquals(null, Axes.layerDoorOf(Axes.definitionDoor("a")))
    }

    @Test fun `an entry's door can never collide with a row path`() {
        assertTrue(Axes.definitionDoor("a").startsWith(Axes.DEFINITION_DOOR))
        assertFalse("L/a".startsWith(Axes.DEFINITION_DOOR))
    }

    // ── the single '−': hidden everywhere unless a door shows it ─────────

    @Test fun `hidden everywhere - hidden at every door that has no rule for it`() {
        val all = listOf(t("a"), t("placed"))
        assertEquals(setOf("a"), AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), false, setOf("placed")).map { it.id }.toSet())
    }

    @Test fun `hidden everywhere - a Show on one door adds it there only`() {
        val all = listOf(t("a"), t("placed"))
        val here = AxisAclRules.includedEntries(Axes.Y, all, mapOf("placed" to false), false, setOf("placed")).map { it.id }.toSet()
        val elsewhere = AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), false, setOf("placed")).map { it.id }.toSet()
        assertEquals(setOf("a", "placed"), here)
        assertEquals(setOf("a"), elsewhere)
    }

    @Test fun `hidden everywhere - the door default does not matter, only Show lifts it`() {
        val all = listOf(t("placed"))
        assertEquals(emptySet<String>(), AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), false, setOf("placed")).map { it.id }.toSet())
        assertEquals(setOf("placed"), AxisAclRules.includedEntries(Axes.Y, all, mapOf("placed" to false), true, setOf("placed")).map { it.id }.toSet())
    }

    @Test fun `hidden everywhere - a deeper entry in a shown group is placed on its own`() {
        val all = listOf(t("g", group = true), t("task", parent = "g"))
        val shown = AxisAclRules.includedEntries(Axes.Y, all, mapOf("task" to false), false, setOf("task")).map { it.id }.toSet()
        val others = AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), false, setOf("task")).map { it.id }.toSet()
        assertEquals(setOf("g", "task"), shown)
        assertEquals(setOf("g"), others)
    }

    @Test fun `a group hidden everywhere locks what is inside it until a door shows the group`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"))
        assertEquals(emptySet<String>(), AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), false, setOf("g")).map { it.id }.toSet())
        assertEquals(setOf("g", "g1"), AxisAclRules.includedEntries(Axes.Y, all, mapOf("g" to false), false, setOf("g")).map { it.id }.toSet())
    }

    @Test fun `a plain entry is unaffected by another entry being hidden everywhere`() {
        val r = AxisAclRules.resolve(emptyList(), "a", emptyMap(), false, setOf("other"))
        assertFalse(r.hidden)
    }

    @Test fun `the ACL tab can tell inherited-hidden from a rule`() {
        val r = AxisAclRules.resolve(emptyList(), "placed", emptyMap(), false, setOf("placed"))
        assertTrue(r.hidden); assertEquals(AclChoice.INHERIT, r.choice); assertEquals(null, r.lockedByKey)
    }
}
