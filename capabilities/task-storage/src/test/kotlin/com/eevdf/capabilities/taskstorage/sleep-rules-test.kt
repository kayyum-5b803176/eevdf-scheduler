package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules for task sleep / wake — hiding, group subtrees, wake ordering. */
class SleepRulesTest {

    private fun t(id: String, parent: String? = null, group: Boolean = false, until: Long = 0L) =
        Task(id = id, name = id, priority = 4, timeSliceSeconds = 60L,
             parentId = parent, isGroup = group, sleepUntilEpoch = until)

    @Test fun `awake task is visible and own sleep hides it`() {
        val all = listOf(t("a"), t("b", until = 5_000L))
        assertEquals(setOf("b"), SleepRules.asleepIds(all))
        assertEquals(listOf("a"), SleepRules.awake(all).map { it.id })
    }

    @Test fun `sleeping group hides its whole subtree without touching the children`() {
        val all = listOf(
            t("g", group = true, until = 9_000L),
            t("c1", parent = "g"),
            t("sub", parent = "g", group = true),
            t("c2", parent = "sub"),
            t("other"),
        )
        assertEquals(listOf("other"), SleepRules.awake(all).map { it.id })
        // children carry no sleep state of their own
        assertTrue(all.filter { it.parentId != null }.none { it.isAsleep })
    }

    @Test fun `child with its own sleep stays separate from the group's`() {
        val all = listOf(t("g", group = true), t("c", parent = "g", until = 7_000L), t("d", parent = "g"))
        assertEquals(listOf("g", "d"), SleepRules.awake(all).map { it.id })
    }

    @Test fun `nothing asleep returns the same list`() {
        val all = listOf(t("a"), t("b"))
        assertTrue(SleepRules.awake(all) === all)
    }

    @Test fun `dueForWake only returns expired sleepers`() {
        val all = listOf(t("a", until = 1_000L), t("b", until = 9_000L), t("c"))
        assertEquals(listOf("a"), SleepRules.dueForWake(all, nowMs = 5_000L).map { it.id })
        assertTrue(SleepRules.dueForWake(all, nowMs = 500L).isEmpty())
    }

    @Test fun `expired child behind a still-sleeping group is not due`() {
        val all = listOf(t("g", group = true, until = 9_000L), t("c", parent = "g", until = 1_000L))
        assertTrue(SleepRules.dueForWake(all, nowMs = 5_000L).isEmpty())
    }

    @Test fun `parent is woken before its expired child`() {
        val all = listOf(t("c", parent = "g", until = 1_000L), t("g", group = true, until = 2_000L))
        // pass 1: only the group (the child is still behind it)
        assertEquals(listOf("g"), SleepRules.dueForWake(all, nowMs = 5_000L).map { it.id })
        // once the group is awake the child becomes due
        val woken = all.map { if (it.id == "g") it.copy(sleepUntilEpoch = 0L) else it }
        assertEquals(listOf("c"), SleepRules.dueForWake(woken, nowMs = 5_000L).map { it.id })
    }

    @Test fun `sleep roots skip children already covered by a sleeping group`() {
        val all = listOf(
            t("g", group = true, until = 9_000L),
            t("c", parent = "g", until = 4_000L),    // own sleep, but under a sleeping group
            t("x", until = 3_000L),
            t("awake"),
        )
        assertEquals(listOf("x", "g"), SleepRules.sleepRoots(all).map { it.id })   // soonest first
    }

    @Test fun `parent cycle does not hang`() {
        val a = t("a", parent = "b"); val b = t("b", parent = "a", until = 1_000L)
        SleepRules.asleepIds(listOf(a, b))
        SleepRules.dueForWake(listOf(a, b), nowMs = 2_000L)
    }
}
