package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.eevdf.CpuShares
import com.eevdf.core.scheduler.model.SchedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERIZATION tests for CPU-share allocation and fairness.
 *
 * Shares are what the user actually sees on every task card, so a silent change
 * here is a visible regression across the whole app.
 */
class CpuSharesCharacterizationTest {

    private fun t(
        id: String,
        priority: Int = 4,
        pinned: Double? = null,
        weight: Double? = null,
        parent: String? = null,
        group: Boolean = false,
        done: Boolean = false,
        vruntime: Double = 0.0,
    ) = SchedTask(
        id = id, parentId = parent, isGroup = group, isCompleted = done, isRunning = false,
        priority = priority, internalWeight = weight, pinnedShare = pinned,
        timeSliceSeconds = 60L, vruntime = vruntime,
    )

    @Test fun `equal priority tasks split evenly`() {
        val s = CpuShares.computeShares(listOf(t("a"), t("b"), t("c")))
        s.values.forEach { assertEquals(100.0 / 3, it, 1e-9) }
    }

    @Test fun `shares are proportional to priority`() {
        val s = CpuShares.computeShares(listOf(t("a", priority = 1), t("b", priority = 3)))
        assertEquals(25.0, s["a"]!!, 1e-9)
        assertEquals(75.0, s["b"]!!, 1e-9)
    }

    @Test fun `completed tasks are excluded and do not dilute the pool`() {
        val s = CpuShares.computeShares(listOf(t("a"), t("b"), t("dead", done = true)))
        assertEquals(50.0, s["a"]!!, 1e-9)
        assertEquals(50.0, s["b"]!!, 1e-9)
        assertTrue("completed task must not receive a share", s["dead"] == null)
    }

    @Test fun `a pinned task gets exactly its share and floaters split the rest`() {
        val s = CpuShares.computeShares(listOf(t("pin", pinned = 40.0), t("a"), t("b")))
        assertEquals(40.0, s["pin"]!!, 1e-9)
        assertEquals(30.0, s["a"]!!, 1e-9)
        assertEquals(30.0, s["b"]!!, 1e-9)
    }

    @Test fun `over-pinned level clamps the float pool at zero rather than going negative`() {
        val s = CpuShares.computeShares(listOf(t("p1", pinned = 70.0), t("p2", pinned = 60.0), t("f")))
        assertEquals(0.0, s["f"]!!, 1e-9)
    }

    @Test fun `single task takes the whole cpu`() {
        assertEquals(100.0, CpuShares.computeShares(listOf(t("solo")))["solo"]!!, 1e-9)
    }

    @Test fun `empty input yields empty shares`() {
        assertTrue(CpuShares.computeShares(emptyList()).isEmpty())
    }

    @Test fun `grouped shares are scoped per level not globally`() {
        val tasks = listOf(
            t("g", group = true),
            t("child1", parent = "g"),
            t("child2", parent = "g"),
        )
        val s = CpuShares.computeShares(tasks, groupsEnabled = true)
        assertEquals("group owns the whole top level", 100.0, s["g"]!!, 1e-9)
        assertEquals("children split their own level, not the parent's slice", 50.0, s["child1"]!!, 1e-9)
        assertEquals(50.0, s["child2"]!!, 1e-9)
    }

    @Test fun `pinnedWeight yields the requested share when re-computed`() {
        val others = listOf(t("a"), t("b"))
        val w = CpuShares.pinnedWeight(
            targetShare = 25.0, parentId = null, excludeId = "pin",
            allTasks = others, fallbackWeight = 4.0,
        )
        val s = CpuShares.computeShares(others + t("pin", weight = w))
        // "pin" floats on its derived weight and should land on ~25%.
        assertEquals(25.0, s["pin"]!!, 1e-6)
    }

    @Test fun `pinnedWeight saturates when the target exceeds the available pool`() {
        val w = CpuShares.pinnedWeight(
            targetShare = 100.0, parentId = null, excludeId = null,
            allTasks = listOf(t("a")), fallbackWeight = 4.0,
        )
        assertEquals(9_999.0, w, 1e-9)
    }

    @Test fun `syncPinnedWeights returns only the tasks whose weight actually changed`() {
        val stable = t("pin", pinned = 25.0, weight = null)
        val changed = CpuShares.syncPinnedWeights(listOf(stable, t("a"), t("b")))
        assertEquals(1, changed.size)
        assertEquals("pin", changed.single().id)
        // Re-running with the corrected weight must now be a no-op.
        val second = CpuShares.syncPinnedWeights(listOf(changed.single(), t("a"), t("b")))
        assertTrue("weight sync must converge, not oscillate", second.isEmpty())
    }

    @Test fun `fairness is one for identical vruntimes`() {
        assertEquals(1.0, CpuShares.fairness(listOf(t("a", vruntime = 50.0), t("b", vruntime = 50.0))), 1e-9)
    }

    @Test fun `fairness is one for fewer than two tasks`() {
        assertEquals(1.0, CpuShares.fairness(listOf(t("a", vruntime = 7.0))), 1e-9)
        assertEquals(1.0, CpuShares.fairness(emptyList()), 1e-9)
    }

    @Test fun `fairness degrades as vruntimes diverge`() {
        val even = CpuShares.fairness(listOf(t("a", vruntime = 50.0), t("b", vruntime = 50.0)))
        val skewed = CpuShares.fairness(listOf(t("a", vruntime = 1.0), t("b", vruntime = 99.0)))
        assertTrue("skewed queue must score lower than a balanced one", skewed < even)
    }
}
