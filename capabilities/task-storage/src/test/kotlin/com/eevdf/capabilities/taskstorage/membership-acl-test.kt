package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rule: an explicit row wins, otherwise the placement's default. */
class MembershipAclRulesTest {

    private fun door(id: String = "m1", newChildrenHidden: Boolean = false) =
        TaskMembership(id = id, taskId = "g", groupId = "host", newChildrenHidden = newChildrenHidden)

    @Test fun `no row follows the default - visible`() {
        assertFalse(MembershipAclRules.isHidden(door(), "c", emptyList()))
    }

    @Test fun `no row follows the default - hidden`() {
        assertTrue(MembershipAclRules.isHidden(door(newChildrenHidden = true), "c", emptyList()))
    }

    @Test fun `explicit hidden row beats a visible default`() {
        val rows = listOf(MembershipAcl("m1", "c", hidden = true))
        assertTrue(MembershipAclRules.isHidden(door(), "c", rows))
    }

    @Test fun `explicit visible row beats a hidden default`() {
        val rows = listOf(MembershipAcl("m1", "c", hidden = false))
        assertFalse(MembershipAclRules.isHidden(door(newChildrenHidden = true), "c", rows))
    }

    @Test fun `a row of another placement or another child is ignored`() {
        val rows = listOf(MembershipAcl("m2", "c", hidden = true), MembershipAcl("m1", "other", hidden = true))
        assertFalse(MembershipAclRules.isHidden(door(), "c", rows))
    }

    @Test fun `key prefixes match the literal used in the DAO cleanup query`() {
        // MembershipAclDao.deleteChildKeysOfMembershipsOfTask hardcodes 'm:'.
        assertEquals("m:", MembershipAclKeys.MEMBERSHIP_PREFIX)
        assertEquals("m:x", MembershipAclKeys.membership("x"))
        assertEquals("l:x", MembershipAclKeys.symlink("x"))
        assertEquals("x", MembershipAclKeys.real("x"))
    }

    @Test fun `a task is hidden through a door when its direct-child ancestor is hidden`() {
        val parents = mapOf("grand" to "child", "child" to "g", "g" to null)
        val rows = listOf(MembershipAcl("m1", "child", hidden = true))
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("grand", door(), rows) { parents[it] })
        assertTrue(MembershipAclRules.isTaskHiddenThroughDoor("child", door(), rows) { parents[it] })
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
 * ViewModel, so these tests pin the three decisions they delegate to [DoorAcl]:
 * which children a placement lists, what the sibling shares are, and what a
 * newly added child does.
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

    @Test fun `hidden child is absent through the door but present in the real placement`() {
        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "b", hidden = true)))
        val children = listOf(a, b, c)

        assertEquals(listOf("a", "c"), ids(acl.visibleChildren(children, doorId = "m1", groupRealId = "g")))
        // The real placement carries no door: nothing is filtered.
        assertEquals(listOf("a", "b", "c"), ids(acl.visibleChildren(children, doorId = null, groupRealId = "g")))
        // Another placement of the same group is unaffected.
        val m2 = TaskMembership(id = "m2", taskId = "g", groupId = "other")
        val both = DoorAcl(listOf(m1, m2), listOf(MembershipAcl("m1", "b", hidden = true)))
        assertEquals(listOf("a", "b", "c"), ids(both.visibleChildren(children, doorId = "m2", groupRealId = "g")))
    }

    @Test fun `a door filters only the direct children of its own group`() {
        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "b", hidden = true)))
        val sub = t("sub", "a", group = true)
        // Listing a's children while still inside door m1: m1 is a placement of g, not a.
        assertEquals(listOf("sub"), ids(acl.visibleChildren(listOf(sub), doorId = "m1", groupRealId = "a")))
        assertEquals(emptySet<String>(), acl.hiddenRealChildIds(null, listOf(a, b, c)))
    }

    @Test fun `hidden hosted hardlink and symlink are filtered by prefixed keys`() {
        val hosted = TaskMembership(id = "m9", taskId = "x", groupId = "g")
        val link   = TaskLink(id = "l9", targetTaskId = "x", hostGroupId = "g")
        val synthetic = EEVDFScheduler.withMemberships(listOf(g, t("x"), a), listOf(hosted)).first { it.id.startsWith("membership:") }
        val acl = DoorAcl(
            listOf(m1, hosted),
            listOf(MembershipAcl("m1", "m:m9", true), MembershipAcl("m1", "l:l9", true)),
        )
        assertEquals(listOf("a"), ids(acl.visibleChildren(listOf(a, synthetic), "m1", "g")))
        assertTrue(acl.visibleLinks(listOf(link), "m1", "g").isEmpty())
        assertEquals(1, acl.visibleLinks(listOf(link), null, "g").size)
    }

    @Test fun `sibling shares are recomputed without the hidden child and its subtree`() {
        val sub  = t("sub", "c", group = true)      // c is a group in this scenario
        val deep = t("deep", "sub")
        val cGroup = t("c", "g", group = true)
        val all = listOf(g, a, b, cGroup, sub, deep)
        val global = EEVDFScheduler.computeShares(all, groupsEnabled = true)
        assertEquals(100.0 / 3, global.getValue("a"), 1e-9)

        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "c", hidden = true)))
        val shares = acl.sharesFor("m1", "g", all, true, global)

        assertEquals(50.0, shares.getValue("a"), 1e-9)
        assertEquals(50.0, shares.getValue("b"), 1e-9)
        // Without a hidden child at the door the global map is returned as-is.
        assertSame(global, acl.sharesFor(null, "g", all, true, global))
    }

    @Test fun `pinned share of a visible sibling stays and the float pool shrinks`() {
        val pinned = t("a", "g", pinned = 40.0)
        val all = listOf(g, pinned, b, c)
        val global = EEVDFScheduler.computeShares(all, true)
        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "c", hidden = true)))
        val shares = acl.sharesFor("m1", "g", all, true, global)
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
        val acl = DoorAcl(listOf(m1, d), listOf(MembershipAcl("m1", "b", hidden = true)))
        val shares = acl.sharesFor("m1", "g", all, true, global)
        assertEquals(50.0, shares.getValue("x1"), 1e-9)   // still reachable and still its own pool
        assertEquals(50.0, shares.getValue("a"), 1e-9)    // a and the membership entry d split g's pool
    }

    @Test fun `counts and rollup exclude hidden real children only for that placement`() {
        val acl = DoorAcl(listOf(m1), listOf(MembershipAcl("m1", "b", hidden = true)))
        assertEquals(setOf("b"), acl.hiddenRealChildIds(m1, listOf(a, b, c)))
    }

    @Test fun `a new child follows the default, an old child keeps its explicit choice`() {
        val rows = listOf(MembershipAcl("m1", "a", hidden = false), MembershipAcl("m1", "b", hidden = false))
        val newChild = t("n", "g")

        // Default visible: the new child shows.
        val visibleDefault = DoorAcl(listOf(m1), rows)
        assertTrue(visibleDefault.isInert)
        assertEquals(listOf("a", "b", "n"), ids(visibleDefault.visibleChildren(listOf(a, b, newChild), "m1", "g")))

        // Flip the default to hidden later: a and b stay (explicit rows), n is hidden.
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
