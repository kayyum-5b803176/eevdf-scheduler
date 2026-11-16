package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules: explicit rule wins, otherwise inherit; Show/Hide/Inherit resolution at any depth. */
class MembershipAclRulesTest {

    private fun door(id: String = "m1", newChildrenHidden: Boolean = false) =
        TaskMembership(id = id, taskId = "g", groupId = "host", newChildrenHidden = newChildrenHidden)

    private fun rule(key: String, hidden: Boolean, door: String = "m1") = MembershipAcl(door, key, hidden)

    // ── isHidden: a DIRECT child ─────────────────────────────────────────────

    @Test fun `no row follows the default - visible`() {
        assertFalse(MembershipAclRules.isHidden(door(), "c", emptyList()))
    }

    @Test fun `no row follows the default - hidden`() {
        assertTrue(MembershipAclRules.isHidden(door(newChildrenHidden = true), "c", emptyList()))
    }

    @Test fun `explicit hidden row beats a visible default`() {
        assertTrue(MembershipAclRules.isHidden(door(), "c", listOf(rule("c", true))))
    }

    @Test fun `explicit visible row beats a hidden default`() {
        assertFalse(MembershipAclRules.isHidden(door(newChildrenHidden = true), "c", listOf(rule("c", false))))
    }

    @Test fun `a row of another placement or another child is ignored`() {
        val rows = listOf(rule("c", true, door = "m2"), rule("other", true))
        assertFalse(MembershipAclRules.isHidden(door(), "c", rows))
    }

    @Test fun `key prefixes match the literal used in the DAO cleanup query`() {
        // MembershipAclDao.deleteChildKeysOfMembershipsOfTask hardcodes 'm:'.
        assertEquals("m:", MembershipAclKeys.MEMBERSHIP_PREFIX)
        assertEquals("m:x", MembershipAclKeys.membership("x"))
        assertEquals("l:x", MembershipAclKeys.symlink("x"))
        assertEquals("x", MembershipAclKeys.real("x"))
    }

    // ── resolve: Inherit / Hide / Show at any depth ──────────────────────────

    @Test fun `no rule at depth 1 inherits the door default`() {
        val shown = MembershipAclRules.resolve(door(), emptyList(), "a", emptyList())
        assertEquals(AclChoice.INHERIT, shown.choice); assertFalse(shown.hidden); assertNull(shown.lockedByKey)

        val hidden = MembershipAclRules.resolve(door(newChildrenHidden = true), emptyList(), "a", emptyList())
        assertEquals(AclChoice.INHERIT, hidden.choice); assertTrue(hidden.hidden); assertNull(hidden.lockedByKey)
    }

    @Test fun `Show at depth 1 beats a hidden default and Hide beats a visible one`() {
        val show = MembershipAclRules.resolve(door(newChildrenHidden = true), emptyList(), "a", listOf(rule("a", false)))
        assertEquals(AclChoice.SHOW, show.choice); assertFalse(show.hidden)

        val hide = MembershipAclRules.resolve(door(), emptyList(), "a", listOf(rule("a", true)))
        assertEquals(AclChoice.HIDE, hide.choice); assertTrue(hide.hidden)
    }

    @Test fun `deeper items with no rule follow their parent, not the door default`() {
        // default hidden, but "web" was shown: its child inherits "shown".
        val rows = listOf(rule("web", false))
        val r = MembershipAclRules.resolve(door(newChildrenHidden = true), listOf("web"), "nginx", rows)
        assertEquals(AclChoice.INHERIT, r.choice); assertFalse(r.hidden); assertNull(r.lockedByKey)
    }

    @Test fun `an item under a hidden ancestor is locked and hidden whatever its own rule says`() {
        val rows = listOf(rule("batch", true), rule("job", false))
        val r = MembershipAclRules.resolve(door(), listOf("batch"), "job", rows)
        assertEquals("batch", r.lockedByKey)
        assertTrue(r.hidden)
        assertEquals(AclChoice.SHOW, r.choice)   // the stored rule is still shown to the user, just inert
    }

    @Test fun `a group hidden only by the default locks its children`() {
        val r = MembershipAclRules.resolve(door(newChildrenHidden = true), listOf("web"), "nginx", emptyList())
        assertEquals("web", r.lockedByKey)
        assertTrue(r.hidden)
    }

    @Test fun `the outermost hidden ancestor is the one reported`() {
        val rows = listOf(rule("a", true), rule("b", true))
        assertEquals("a", MembershipAclRules.resolve(door(), listOf("a", "b"), "c", rows).lockedByKey)
    }

    // ── isTaskHiddenThroughDoor ──────────────────────────────────────────────

    @Test fun `a task is hidden through a door when any ancestor below the group is hidden`() {
        val parents = mapOf("grand" to "child", "child" to "g", "g" to null)
        val rows = listOf(rule("child", true))
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("grand", door(), rows) { parents[it] })
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("child", door(), rows) { parents[it] })
    }

    @Test fun `a rule deep in the chain hides only that branch`() {
        val parents = mapOf("leaf" to "mid", "mid" to "top", "top" to "g", "other" to "top", "g" to null)
        val rows = listOf(rule("mid", true))
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("leaf", door(), rows) { parents[it] })
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("other", door(), rows) { parents[it] })
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("top", door(), rows) { parents[it] })
    }

    @Test fun `the default applies to the direct child only`() {
        val parents = mapOf("leaf" to "top", "top" to "g", "g" to null)
        val d = door(newChildrenHidden = true)
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("top", d, emptyList()) { parents[it] })
        // shown by a rule on "top": its leaf follows it, not the default
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("leaf", d, listOf(rule("top", false))) { parents[it] })
    }

    @Test fun `the door's own group and tasks outside it are never hidden by it`() {
        val parents = mapOf("g" to "host", "host" to null, "elsewhere" to null)
        val door = door(newChildrenHidden = true)
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("g", door, emptyList()) { parents[it] })
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("elsewhere", door, emptyList()) { parents[it] })
    }

    @Test fun `a cyclic parent chain terminates`() {
        val parents = mapOf("a" to "b", "b" to "a")
        assertFalse(MembershipAclRules.isTaskHiddenThroughDoor("a", door(newChildrenHidden = true), emptyList()) { parents[it] })
    }
}

/**
 * What the list builders do with a door. The builders themselves need a
 * ViewModel, so these tests pin the decisions they delegate to [DoorAcl]:
 * which items a placement lists at any depth, what the sibling shares are,
 * what counts leave out, and how a new child behaves.
 */
class DoorAclTest {

    private fun t(id: String, parent: String? = null, group: Boolean = false, pinned: Double? = null, priority: Int = 4) =
        Task(id = id, name = id, priority = priority, timeSliceSeconds = 60L,
             parentId = parent, isGroup = group, pinnedShare = pinned)

    // host ⊃ (real group g ⊃ a, b, c) ; m1 = a hardlink of g hosted in "host"
    private val g  = t("g", group = true)
    private val a  = t("a", "g")
    private val b  = t("b", "g")
    private val c  = t("c", "g")
    private val m1 = TaskMembership(id = "m1", taskId = "g", groupId = "host")

    private fun ids(list: List<Task>) = list.map { it.id }
    private fun hide(key: String, door: String = "m1") = MembershipAcl(door, key, true)

    @Test fun `hidden child is absent through the door but present in the real placement`() {
        val acl = DoorAcl(listOf(m1), listOf(hide("b")))
        val children = listOf(a, b, c)

        assertEquals(listOf("a", "c"), ids(acl.visibleChildren(children, doorId = "m1", groupRealId = "g")))
        // The real placement carries no door: nothing is filtered.
        assertEquals(listOf("a", "b", "c"), ids(acl.visibleChildren(children, doorId = null, groupRealId = "g")))
        // Another placement of the same group is unaffected.
        val m2 = TaskMembership(id = "m2", taskId = "g", groupId = "other")
        val both = DoorAcl(listOf(m1, m2), listOf(hide("b")))
        assertEquals(listOf("a", "b", "c"), ids(both.visibleChildren(children, doorId = "m2", groupRealId = "g")))
    }

    @Test fun `a rule on a deeper item hides it at that level`() {
        val sub  = t("sub", "a", group = true)      // a is a group here
        val deep = t("deep", "sub")
        val keep = t("keep", "sub")
        val acl = DoorAcl(listOf(m1), listOf(hide("deep")))
        // Listing sub's children while still inside door m1 (m1 is a placement of g, not sub).
        assertEquals(listOf("keep"), ids(acl.visibleChildren(listOf(deep, keep), "m1", "sub")))
        // Without a door the same level is untouched.
        assertEquals(listOf("deep", "keep"), ids(acl.visibleChildren(listOf(deep, keep), null, "sub")))
    }

    @Test fun `the door default applies to direct children only`() {
        val hiddenByDefault = TaskMembership(id = "m1", taskId = "g", groupId = "host", newChildrenHidden = true)
        val acl = DoorAcl(listOf(hiddenByDefault), listOf(MembershipAcl("m1", "a", hidden = false)))
        val inner = t("inner", "a")
        // direct level: only the shown one
        assertEquals(listOf("a"), ids(acl.visibleChildren(listOf(a, b, c), "m1", "g")))
        // one level down: no rule on inner, default does NOT apply -> visible
        assertEquals(listOf("inner"), ids(acl.visibleChildren(listOf(inner), "m1", "a")))
    }

    @Test fun `hidden hosted hardlink and symlink are filtered by prefixed keys`() {
        val hosted = TaskMembership(id = "m9", taskId = "x", groupId = "g")
        val link   = TaskLink(id = "l9", targetTaskId = "x", hostGroupId = "g")
        val synthetic = EEVDFScheduler.withMemberships(listOf(g, t("x"), a), listOf(hosted)).first { it.id.startsWith("membership:") }
        val acl = DoorAcl(listOf(m1, hosted), listOf(hide("m:m9"), hide("l:l9")))
        assertEquals(listOf("a"), ids(acl.visibleChildren(listOf(a, synthetic), "m1", "g")))
        assertTrue(acl.visibleLinks(listOf(link), "m1", "g").isEmpty())
        assertEquals(1, acl.visibleLinks(listOf(link), null, "g").size)
    }

    @Test fun `sibling shares are recomputed without the hidden child`() {
        val cGroup = t("c", "g", group = true)
        val sub  = t("sub", "c", group = true)
        val deep = t("deep", "sub")
        val all = listOf(g, a, b, cGroup, sub, deep)
        val global = EEVDFScheduler.computeShares(all, groupsEnabled = true)
        assertEquals(100.0 / 3, global.getValue("a"), 1e-9)

        val acl = DoorAcl(listOf(m1), listOf(hide("c")))
        val shares = acl.sharesFor("m1", "g", all, global)

        assertEquals(50.0, shares.getValue("a"), 1e-9)
        assertEquals(50.0, shares.getValue("b"), 1e-9)
        // No door, or nothing hidden at that level: the global map is returned as-is.
        assertSame(global, acl.sharesFor(null, "g", all, global))
        assertSame(global, acl.sharesFor("m1", "sub", all, global))
    }

    @Test fun `shares are recomputed at a deeper level too`() {
        val sub = t("sub", "a", group = true)
        val s1 = t("s1", "sub"); val s2 = t("s2", "sub"); val s3 = t("s3", "sub")
        val aGroup = t("a", "g", group = true)
        val all = listOf(g, aGroup, b, sub, s1, s2, s3)
        val global = EEVDFScheduler.computeShares(all, groupsEnabled = true)
        assertEquals(100.0 / 3, global.getValue("s1"), 1e-9)

        val acl = DoorAcl(listOf(m1), listOf(hide("s3")))
        val shares = acl.sharesFor("m1", "sub", all, global)
        assertEquals(50.0, shares.getValue("s1"), 1e-9)
        assertEquals(50.0, shares.getValue("s2"), 1e-9)
        // the level above is untouched
        assertEquals(global.getValue("b"), shares.getValue("b"), 1e-9)
    }

    @Test fun `pinned share of a visible sibling stays and the float pool shrinks`() {
        val pinned = t("a", "g", pinned = 40.0)
        val all = listOf(g, pinned, b, c)
        val global = EEVDFScheduler.computeShares(all, true)
        val acl = DoorAcl(listOf(m1), listOf(hide("c")))
        val shares = acl.sharesFor("m1", "g", all, global)
        assertEquals(40.0, shares.getValue("a"), 1e-9)
        assertEquals(60.0, shares.getValue("b"), 1e-9)   // the only floater takes the whole pool
        // The hidden child's own data is untouched.
        assertTrue(c.pinnedShare == null)
    }

    @Test fun `visible hardlink into a hidden sibling's subtree keeps its children's shares`() {
        // b is hidden; d (visible) is a placement of x, a group that lives inside b.
        val bGroup = t("b", "g", group = true)
        val x  = t("x", "b", group = true)
        val x1 = t("x1", "x"); val x2 = t("x2", "x")
        val d  = TaskMembership(id = "d", taskId = "x", groupId = "g")
        val all = EEVDFScheduler.withMemberships(listOf(g, a, bGroup, x, x1, x2), listOf(d))
        val global = EEVDFScheduler.computeShares(all, true)
        val acl = DoorAcl(listOf(m1, d), listOf(hide("b")))
        val shares = acl.sharesFor("m1", "g", all, global)
        assertEquals(50.0, shares.getValue("x1"), 1e-9)   // still its own pool
        assertEquals(50.0, shares.getValue("a"), 1e-9)    // a and the placement entry d split g's pool
    }

    @Test fun `hidden real ids cover every depth and skip a hidden group's inside`() {
        val sub  = t("sub", "a", group = true)
        val deep = t("deep", "sub")
        val aGroup = t("a", "g", group = true)
        val all = listOf(g, aGroup, b, c, sub, deep)
        val acl = DoorAcl(listOf(m1), listOf(hide("b"), hide("deep")))
        assertEquals(setOf("b", "deep"), acl.hiddenRealIds("m1", all))

        // Hiding a group hides it once; its descendants are never reached.
        val acl2 = DoorAcl(listOf(m1), listOf(hide("a"), hide("deep")))
        assertEquals(setOf("a"), acl2.hiddenRealIds("m1", all))

        assertEquals(emptySet<String>(), acl.hiddenRealIds(null, all))
        assertEquals(emptySet<String>(), acl.hiddenRealIds("unknown", all))
    }

    @Test fun `the door default hides direct children without a rule from counts`() {
        val hiddenByDefault = TaskMembership(id = "m1", taskId = "g", groupId = "host", newChildrenHidden = true)
        val acl = DoorAcl(listOf(hiddenByDefault), listOf(MembershipAcl("m1", "a", hidden = false)))
        assertEquals(setOf("b", "c"), acl.hiddenRealIds("m1", listOf(g, a, b, c)))
    }

    @Test fun `a new child follows the default until it has a rule`() {
        val newChild = t("n", "g")
        val rows = listOf(MembershipAcl("m1", "a", hidden = false), MembershipAcl("m1", "b", hidden = false))

        // Default visible: the new child shows.
        val visibleDefault = DoorAcl(listOf(m1), rows)
        assertTrue(visibleDefault.isInert)
        assertEquals(listOf("a", "b", "n"), ids(visibleDefault.visibleChildren(listOf(a, b, newChild), "m1", "g")))

        // Default hidden: items shown by a rule stay, the new child (no rule) is hidden.
        val hiddenDefault = DoorAcl(listOf(m1.copy(newChildrenHidden = true)), rows)
        assertEquals(listOf("a", "b"), ids(hiddenDefault.visibleChildren(listOf(a, b, newChild), "m1", "g")))
    }

    @Test fun `nothing hidden anywhere makes the helper inert and returns inputs unchanged`() {
        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "a", hidden = false)))
        val children = listOf(a, b)
        assertTrue(acl.isInert)
        assertSame(children, acl.visibleChildren(children, "m1", "g"))
    }
}
