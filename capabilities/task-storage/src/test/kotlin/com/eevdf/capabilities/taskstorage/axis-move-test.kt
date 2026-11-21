package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisMoveTest {

    private fun t(id: String, parent: String? = null, group: Boolean = false, axis: String = Axes.X) =
        Task(id = id, name = id, priority = 1, timeSliceSeconds = 60, parentId = parent, isGroup = group, axisId = axis)

    // ── template reset ───────────────────────────────────────────────────

    @Test fun `a template has no running history but keeps its configuration`() {
        val worked = t("a").copy(
            name = "Write", priority = 7, timeSliceSeconds = 90, quotaSeconds = 600, category = "Work",
            vruntime = 42.0, eligibleTime = 40.0, virtualDeadline = 50.0, lag = 1.5, remainingSeconds = 12,
            isRunning = true, isCompleted = true, totalRunTime = 900, runCount = 7, accumulatedMs = 5, startTimeEpoch = 9,
            quotaUsedSeconds = 55, quotaPeriodStartEpoch = 8, dlRuntimeUsedSeconds = 3, dlPeriodStartEpoch = 4,
            loadAverage = 2.0, loadLastUpdateEpoch = 6, sleepUntilEpoch = 123, sleepLag = 0.5,
            semaphoreHeld = true, semaphoreQueuedAt = 77,
        )
        val tpl = worked.asTemplate()
        assertEquals(0.0, tpl.vruntime, 0.0); assertEquals(0.0, tpl.lag, 0.0)
        assertEquals(0L, tpl.totalRunTime); assertEquals(0, tpl.runCount)
        assertFalse(tpl.isRunning); assertFalse(tpl.isCompleted)
        assertEquals(0L, tpl.quotaUsedSeconds); assertEquals(0L, tpl.sleepUntilEpoch)
        assertFalse(tpl.semaphoreHeld); assertEquals(0L, tpl.semaphoreQueuedAt)
        assertEquals(90L, tpl.remainingSeconds)                       // a full slice
        assertEquals("Write", tpl.name); assertEquals(7, tpl.priority)
        assertEquals(600L, tpl.quotaSeconds); assertEquals("Work", tpl.category)
        assertEquals("a", tpl.id)
    }

    // ── what moves with a row ────────────────────────────────────────────

    @Test fun `a group moves with everything real inside it, completed rows included`() {
        val all = listOf(t("g", group = true), t("g1", "g"), t("g2", "g", group = true), t("g21", "g2"),
            t("done", "g").copy(isCompleted = true), t("elsewhere"))
        val c = AxisMove.collect(all[0], all, emptySet())
        assertEquals(setOf("g", "g1", "g2", "g21", "done"), c.real.map { it.id }.toSet())
        assertTrue(c.copies.isEmpty())
    }

    @Test fun `copies inside a row are not moved - they are listed to be removed`() {
        val all = listOf(t("L", group = true), t("copyA", "L"), t("copyB", "copyA"), t("real", "L"))
        val c = AxisMove.collect(all[0], all, copyIds = setOf("copyA", "copyB"))
        assertEquals(setOf("L", "real"), c.real.map { it.id }.toSet())
        assertEquals(setOf("copyA", "copyB"), c.copies.map { it.id }.toSet())
    }

    @Test fun `a lone leaf moves alone`() {
        val c = AxisMove.collect(t("a"), listOf(t("a"), t("b")), emptySet())
        assertEquals(listOf("a"), c.real.map { it.id })
    }

    @Test fun `collecting is cycle-safe`() {
        val a = t("a", "b", group = true); val b = t("b", "a", group = true)
        assertEquals(setOf("a", "b"), AxisMove.collect(a, listOf(a, b), emptySet()).real.map { it.id }.toSet())
    }

    @Test fun `axes are mixed freely - the rows carry their own axis`() {
        val all = listOf(t("g", group = true, axis = Axes.Y), t("g1", "g", axis = Axes.Y))
        assertEquals(setOf("g", "g1"), AxisMove.collect(all[0], all, emptySet()).real.map { it.id }.toSet())
    }

    // ── where it may NOT go ──────────────────────────────────────────────

    @Test fun `a row cannot be moved under itself or anything inside it`() {
        val groups = listOf(t("g", group = true), t("sub", "g", group = true), t("deep", "sub", group = true), t("other", group = true))
        assertEquals(listOf("other"), AxisMove.withoutSubtree(groups, "g").map { it.id })
        assertEquals(listOf("g", "other"), AxisMove.withoutSubtree(groups, "sub").map { it.id }.filter { it == "g" || it == "other" })
    }

    @Test fun `a new row has nothing to leave out`() {
        val groups = listOf(t("g", group = true))
        assertEquals(groups, AxisMove.withoutSubtree(groups, null))
    }

    // ── the confirmation ─────────────────────────────────────────────────

    @Test fun `only losses ask for confirmation`() {
        assertFalse(AxisMovePreview(1, 0, 0, resetsRuntime = false, blockedByRunning = false).hasLoss)
        assertTrue(AxisMovePreview(1, 2, 0, false, false).hasLoss)
        assertTrue(AxisMovePreview(1, 0, 1, false, false).hasLoss)
        assertTrue(AxisMovePreview(1, 0, 0, true, false).hasLoss)
        assertFalse(AxisMovePreview.NONE.hasLoss)
    }
}
