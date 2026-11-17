package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Task-level wrapper the list builders call: hardlink placements as pool
 * entries, and the awake pool the builders actually receive.
 */
class RelativeLagFacadeTest {

    private fun t(id: String, parent: String? = null, group: Boolean = false, vr: Double = 0.0, until: Long = 0L) =
        Task(id = id, name = id, priority = 4, timeSliceSeconds = 60L,
             parentId = parent, isGroup = group, vruntime = vr, sleepUntilEpoch = until)

    @Test fun `a hardlink placement is ranked with its own vruntime under its host group`() {
        // P holds leaf a (0), group x (10) and a hardlink placement of group y (placement vr 20).
        // y's REAL row sits elsewhere at 999 and must not matter.
        val tasks = listOf(
            t("p", group = true), t("a", "p", vr = 0.0), t("x", "p", group = true, vr = 10.0),
            t("y", group = true, vr = 999.0),
        )
        val m = TaskMembership(id = "m1", taskId = "y", groupId = "p", vruntime = 20.0)
        val effective = EEVDFScheduler.withMemberships(tasks, listOf(m))

        val pool = effective.filter { it.parentId == "p" }
        // avg over a, x, placement = (0 + 10 + 20)/3 = 10 → lag_x = 0, lag_placement = (10 − 20)·4 = −40
        // → x is the more-behind group (+1), the placement the more-ahead one (−1); the leaf gets no number.
        assertEquals(mapOf("x" to 1, "membership:m1" to -1), EEVDFScheduler.relativeLagRanks(pool))
    }

    @Test fun `nothing is written back onto the tasks`() {
        val a = t("a", group = true, vr = 0.0); val b = t("b", group = true, vr = 10.0)
        val (lagA, lagB, vrA) = Triple(a.lag, b.lag, a.vruntime)
        EEVDFScheduler.relativeLagRanks(listOf(a, b))
        assertEquals(lagA, a.lag, 0.0); assertEquals(lagB, b.lag, 0.0); assertEquals(vrA, a.vruntime, 0.0)
    }

    @Test fun `sleeping groups are not in the pool the builders pass, so N shrinks`() {
        val all = listOf(
            t("g1", group = true, vr = 0.0), t("g2", group = true, vr = 10.0),
            t("sg", group = true, vr = 500.0, until = 9_000L),         // asleep
            t("kid", "sg", vr = 700.0),                                 // asleep with its group
        )
        val awake = SleepRules.awake(all)                               // exactly what the builders receive
        val ranks = EEVDFScheduler.relativeLagRanks(awake.filter { it.parentId == null })
        // two awake groups → +1 / −1; had the sleeping one been counted there would be three numbers
        assertEquals(mapOf("g1" to 1, "g2" to -1), ranks)
        assertFalse("sg" in ranks)
    }

    @Test fun `a group alone at its level has no number`() {
        val g = t("g", group = true)
        assertTrue(EEVDFScheduler.relativeLagRanks(listOf(g)).isEmpty())
        assertTrue(EEVDFScheduler.relativeLagRanks(listOf(g, t("leaf"))).isEmpty())
    }
}
