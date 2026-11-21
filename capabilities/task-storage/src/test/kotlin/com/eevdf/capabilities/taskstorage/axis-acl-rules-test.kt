package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Test

/** The axis ACL and the hardlink ACL must be the same rules — this fails the moment they drift apart. */
class AxisAclRulesTest {

    private val scenarios: List<Pair<List<String>, String>> = listOf(
        emptyList<String>() to "a",
        listOf("g") to "g1",
        listOf("g", "h") to "h1",
        listOf("g", "h", "i") to "i1",
    )
    private val ruleSets: List<Map<String, Boolean>> = listOf(
        emptyMap(),
        mapOf("a" to true), mapOf("a" to false),
        mapOf("g" to true), mapOf("g" to false),
        mapOf("g" to true, "g1" to false),
        mapOf("g" to false, "g1" to true),
        mapOf("h" to true), mapOf("g" to false, "h" to true, "h1" to false),
        mapOf("i" to true, "i1" to false), mapOf("g" to true, "h" to false, "i" to false),
    )

    @Test fun `axis rules resolve exactly like the hardlink ACL`() {
        for (hideNew in listOf(false, true)) {
            val door = TaskMembership(id = "door", taskId = "group", groupId = "host", newChildrenHidden = hideNew)
            for (rules in ruleSets) {
                val rows = rules.map { (k, h) -> MembershipAcl("door", k, h) }
                for ((ancestors, key) in scenarios) {
                    val expected = MembershipAclRules.resolve(door, ancestors, key, rows)
                    val actual   = AxisAclRules.resolve(ancestors, key, rules, hideNew)
                    assertEquals("hideNew=$hideNew rules=$rules ancestors=$ancestors key=$key", expected, actual)
                }
            }
        }
    }

    @Test fun `a rule at another door never applies`() {
        // The rules map is per door by construction: the caller filters by scope, so a stranger's map is just empty.
        assertEquals(AclChoice.INHERIT, AxisAclRules.resolve(emptyList(), "a", emptyMap(), false).choice)
    }
}
