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

    @Test fun `hide new - the copy's own default wins, else the entry's, else the global default`() {
        assertEquals(true,  AxisAclRules.effectiveHideNew(copyHideNew = true,  entryHideNew = false, global = false))
        assertEquals(false, AxisAclRules.effectiveHideNew(copyHideNew = false, entryHideNew = true,  global = true))
        assertEquals(true,  AxisAclRules.effectiveHideNew(copyHideNew = null,  entryHideNew = true,  global = false))
        assertEquals(true,  AxisAclRules.effectiveHideNew(copyHideNew = null,  entryHideNew = null,  global = true))    // ON out of the box
        assertEquals(false, AxisAclRules.effectiveHideNew(copyHideNew = null,  entryHideNew = null,  global = false))
    }

    @Test fun `only a switch the door set itself counts as explicit`() {
        assertTrue(AxisAclRules.isExplicitHideNew(true, null))
        assertTrue(AxisAclRules.isExplicitHideNew(null, false))
        assertFalse(AxisAclRules.isExplicitHideNew(null, null))     // it only follows the global default
    }

    @Test fun `an entry that hides new, and a copy that shows only one thing`() {
        val all = listOf(t("a"), t("b"), t("c"))
        val hideNew = AxisAclRules.effectiveHideNew(null, true, global = false)
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

    // ── a routed entry: shown where its route matches this door, hidden everywhere else ──

    private fun included(all: List<Task>, rules: Map<String, Boolean> = emptyMap(), hideNew: Boolean = false,
                         hidden: Set<String> = emptySet(), shown: Set<String> = emptySet()) =
        AxisAclRules.includedEntries(Axes.Y, all, rules, hideNew, hidden, shown).map { it.id }.toSet()

    @Test fun `a routed entry is hidden at a door its route does not match`() {
        assertEquals(setOf("a"), included(listOf(t("a"), t("placed")), hidden = setOf("placed")))
    }

    @Test fun `a routed entry is shown where its route matches, whatever the door default`() {
        val all = listOf(t("placed"))
        assertEquals(setOf("placed"), included(all, shown = setOf("placed")))
        assertEquals(setOf("placed"), included(all, hideNew = true, shown = setOf("placed")))   // hide new does not lift or hide it
    }

    @Test fun `an explicit rule at a door beats the route`() {
        val all = listOf(t("placed"))
        assertEquals(emptySet<String>(), included(all, rules = mapOf("placed" to true), shown = setOf("placed")))    // Hide where it matches
        assertEquals(setOf("placed"), included(all, rules = mapOf("placed" to false), hidden = setOf("placed")))     // Show where it does not
    }

    @Test fun `a routed entry in a shown group is placed on its own`() {
        val all = listOf(t("g", group = true), t("task", parent = "g"))
        assertEquals(setOf("g", "task"), included(all, shown = setOf("task")))
        assertEquals(setOf("g"), included(all, hidden = setOf("task")))
    }

    @Test fun `a routed group locks what is inside it where its route does not match`() {
        val all = listOf(t("g", group = true), t("g1", parent = "g"))
        assertEquals(emptySet<String>(), included(all, hidden = setOf("g")))
        assertEquals(setOf("g", "g1"), included(all, shown = setOf("g")))
    }

    @Test fun `an unrouted entry is unaffected by another entry's route`() {
        assertFalse(AxisAclRules.resolve(emptyList(), "a", emptyMap(), false, setOf("other"), emptySet()).hidden)
    }

    @Test fun `the ACL tab can tell a route from a rule`() {
        val away = AxisAclRules.resolve(emptyList(), "placed", emptyMap(), false, hiddenHere = setOf("placed"))
        assertTrue(away.hidden); assertEquals(AclChoice.INHERIT, away.choice); assertEquals(null, away.lockedByKey)
        val here = AxisAclRules.resolve(emptyList(), "placed", emptyMap(), false, shownHere = setOf("placed"))
        assertFalse(here.hidden); assertEquals(AclChoice.INHERIT, here.choice)
    }

    // ── route patterns ───────────────────────────────────────────────────

    @Test fun `a route matches a door segment by segment, any matches anything`() {
        assertTrue(AxisRoute.matches("type5/*", "type5/user1"))
        assertTrue(AxisRoute.matches("type5/*", "type5/user2"))
        assertFalse(AxisRoute.matches("type5/*", "type6/user1"))
        assertTrue(AxisRoute.matches("*/user1", "type6/user1"))
        assertTrue(AxisRoute.matches("type5/user1", "type5/user1"))
        assertFalse(AxisRoute.matches("type5/user1", "type5/user2"))
    }

    @Test fun `a route never matches a door of a different depth`() {
        assertFalse(AxisRoute.matches("type5", "type5/user1"))
        assertFalse(AxisRoute.matches("type5/*", "type5"))
    }

    @Test fun `a place created later matches the route by itself`() {
        assertTrue(AxisRoute.matches("type5/*", "type5/brand-new-user"))
    }

    @Test fun `a route is built from the picks, a skipped axis is any`() {
        assertEquals("type5/*", AxisRoute.of(listOf("type5", null)))
        assertEquals("*/user1", AxisRoute.of(listOf(null, "user1")))
        assertEquals("type5", AxisRoute.of(listOf("type5")))
    }

    @Test fun `routes split into what a door shows and hides`() {
        val at = AxisRoute.at(mapOf("a" to "type5/*", "b" to "type6/*", "c" to "*/user1"), "type5/user1")
        assertEquals(setOf("a", "c"), at.shown); assertEquals(setOf("b"), at.hidden)
        assertEquals(AxisRoute.AtDoor.NONE, AxisRoute.at(emptyMap(), "type5/user1"))
    }
}
