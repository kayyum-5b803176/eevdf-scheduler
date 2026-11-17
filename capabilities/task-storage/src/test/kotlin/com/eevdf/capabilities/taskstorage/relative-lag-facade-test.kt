package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Task-level wrapper the list builders call: hardlink placements as pool
 * entries, and the awake pool the builders actually receive.
 */
class RelativeLagFacadeTest {

    private fun t(id: String, parent: String? = null, group: Boolean = false, vr: Double = 0.0, until: Long = 0L) =
        Task(id = id, name = id, priority = 4, timeSliceSeconds = 60L,
             parentId = parent, isGroup = group, vruntime = vr, sleepUntilEpoch = until)

    @Test fun `a hardlink placement competes with its own vruntime under its host group`() {
        // P holds leaf a (0), group x (10) and a hardlink placement of group y (placement vr 20).
        // y's REAL row sits elsewhere at 999 and must not matter.
        val tasks = listOf(
            t("p", group = true), t("a", "p", vr = 0.0), t("x", "p", group = true, vr = 10.0),
            t("y", group = true, vr = 999.0),
        )
        val m = TaskMembership(id = "m1", taskId = "y", groupId = "p", vruntime = 20.0)
        val effective = EEVDFScheduler.withMemberships(tasks, listOf(m))

        val pool = effective.filter { it.parentId == "p" }
        val placement = effective.first { it.id == "membership:m1" }
        // N = 3 (half = 1.5), avg = (0 + 10 + 20)/3 = 10; lag = (10 − 20)·4 = −40 → −40/60 · 1.5 = −1.0
        assertEquals(-1.0, EEVDFScheduler.relativeLag(placement, pool)!!, 1e-9)
        // the leaf is the one that is behind: lag = (10 − 0)·4 = 40 → +1.0
        assertEquals(1.0, EEVDFScheduler.relativeLag(effective.first { it.id == "a" }, pool)!!, 1e-9)
    }

    @Test fun `nothing is written back onto the tasks`() {
        val a = t("a", vr = 0.0); val b = t("b", vr = 10.0)
        val (lagA, lagB, vrA) = Triple(a.lag, b.lag, a.vruntime)
        EEVDFScheduler.relativeLag(a, listOf(a, b))
        assertEquals(lagA, a.lag, 0.0); assertEquals(lagB, b.lag, 0.0); assertEquals(vrA, a.vruntime, 0.0)
    }

    @Test fun `sleeping siblings are not in the pool the builders pass, so N shrinks`() {
        val all = listOf(
            t("a", vr = 0.0), t("b", vr = 10.0),
            t("sleeper", vr = 500.0, until = 9_000L),                 // asleep
            t("sleeping-group", group = true, until = 9_000L),
            t("hidden-by-group", "sleeping-group", vr = 700.0),        // asleep with its group
        )
        val awake = SleepRules.awake(all)                              // exactly what the builders receive
        val pool = awake.filter { it.parentId == null }
        assertEquals(listOf("a", "b"), pool.map { it.id })
        // N = 2 → same as a plain two-sibling level: a is +0.3333
        assertEquals(1.0 / 3.0, EEVDFScheduler.relativeLag(awake.first { it.id == "a" }, pool)!!, 1e-9)
    }

    @Test fun `a group alone at its level has no relative lag`() {
        val g = t("g", group = true)
        assertNull(EEVDFScheduler.relativeLag(g, listOf(g)))
    }
}
