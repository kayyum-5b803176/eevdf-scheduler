package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.model.DlBudget
import com.eevdf.core.scheduler.model.QuotaBudget
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERIZATION tests for quota / deadline budgets and for the EEVDF edge
 * cases that the existing EevdfSchedulerTest does not cover.
 *
 * "now" is an explicit parameter throughout, which is exactly why these can be
 * tested at all - keep it that way.
 */
class BudgetCharacterizationTest {

    private fun t(id: String, priority: Int = 4, vruntime: Double = 0.0, running: Boolean = false) =
        SchedTask(
            id = id, parentId = null, isGroup = false, isCompleted = false, isRunning = running,
            priority = priority, timeSliceSeconds = 60L, vruntime = vruntime,
        )

    // ── QuotaBudget ──────────────────────────────────────────────────────────

    @Test fun `quota disabled when zero`() {
        assertFalse(QuotaBudget(quotaSeconds = 0L).isEnabled)
        assertEquals(-1L, QuotaBudget(quotaSeconds = 0L).remainingAt(1_000L))
    }

    @Test fun `unstarted quota period reports raw usage`() {
        val q = QuotaBudget(quotaSeconds = 3600L, periodSeconds = 86_400L, periodStartEpochSeconds = 0L, usedSeconds = 600L)
        assertEquals(600L, q.usedAt(999_999L))
    }

    @Test fun `quota leaks back over the period`() {
        // 3600s quota over an 86400s period; half a period elapsed replenishes 1800s.
        val q = QuotaBudget(quotaSeconds = 3600L, periodSeconds = 86_400L, periodStartEpochSeconds = 0L, usedSeconds = 3600L)
        assertEquals(1800L, q.usedAt(43_200L))
        assertEquals(1800L, q.remainingAt(43_200L))
    }

    @Test fun `quota usage floors at zero after a full period`() {
        val q = QuotaBudget(quotaSeconds = 3600L, periodSeconds = 86_400L, periodStartEpochSeconds = 0L, usedSeconds = 3600L)
        assertEquals(0L, q.usedAt(200_000L))
        assertEquals(3600L, q.remainingAt(200_000L))
    }

    @Test fun `quota exceeded exactly at the limit`() {
        val q = QuotaBudget(quotaSeconds = 100L, periodSeconds = 86_400L, periodStartEpochSeconds = 0L, usedSeconds = 100L)
        assertTrue(q.isExceededAt(0L))
    }

    // ── DlBudget ─────────────────────────────────────────────────────────────

    @Test fun `dl unconfigured without runtime and deadline`() {
        assertFalse(DlBudget(runtimeSeconds = 0L, deadlineSeconds = 100L).isConfigured)
        assertFalse(DlBudget(runtimeSeconds = 100L, deadlineSeconds = 0L).isConfigured)
        assertTrue(DlBudget(runtimeSeconds = 100L, deadlineSeconds = 200L).isConfigured)
    }

    @Test fun `dl period falls back to deadline when unset`() {
        assertEquals(200L, DlBudget(runtimeSeconds = 100L, deadlineSeconds = 200L).effectivePeriodSeconds)
        assertEquals(500L, DlBudget(runtimeSeconds = 100L, deadlineSeconds = 200L, periodSeconds = 500L).effectivePeriodSeconds)
    }

    @Test fun `dl budget replenishes once the period elapses`() {
        val dl = DlBudget(
            runtimeSeconds = 100L, deadlineSeconds = 200L, periodSeconds = 200L,
            periodStartEpochSeconds = 1_000L, runtimeUsedSeconds = 100L,
        )
        assertFalse("exhausted inside the period", dl.isBudgetActiveAt(1_100L))
        assertTrue("replenished after the period", dl.isBudgetActiveAt(1_200L))
    }

    // ── EEVDF edge cases ─────────────────────────────────────────────────────

    @Test fun `averageVruntime of an empty list is zero`() {
        assertEquals(0.0, EevdfScheduler.averageVruntime(emptyList()), 1e-9)
    }

    @Test fun `selectNext excludes the running task`() {
        val tasks = EevdfScheduler.recalculate(listOf(t("running", running = true), t("idle")))
        assertEquals("idle", EevdfScheduler.selectNext(tasks)?.id)
    }

    @Test fun `selectNext returns null when nothing is runnable`() {
        assertEquals(null, EevdfScheduler.selectNext(listOf(t("only", running = true))))
    }

    @Test fun `when no task is eligible the most-behind task runs`() {
        // Force every candidate ineligible by hand-setting negative lag.
        val ahead = t("ahead", vruntime = 500.0).copy(lag = -1.0)
        val behind = t("behind", vruntime = 100.0).copy(lag = -1.0)
        assertEquals("behind", EevdfScheduler.selectNext(listOf(ahead, behind))?.id)
    }

    @Test fun `advanceVruntime scales inversely with weight`() {
        assertEquals(15.0, EevdfScheduler.advanceVruntime(t("a", priority = 4), 60L), 1e-9)
        assertEquals(7.5, EevdfScheduler.advanceVruntime(t("a", priority = 8), 60L), 1e-9)
    }

    @Test fun `scheduleOrder puts eligible tasks ahead of ineligible ones`() {
        val order = EevdfScheduler.scheduleOrder(
            listOf(t("behind", vruntime = 0.0), t("ahead", vruntime = 1_000.0)),
        )
        assertEquals(listOf("behind", "ahead"), order.map { it.id })
    }

    @Test fun `scheduleOrder drops completed tasks`() {
        val done = t("done").copy(isCompleted = true)
        assertFalse(EevdfScheduler.scheduleOrder(listOf(t("live"), done)).any { it.id == "done" })
    }

    /**
     * KNOWN BUG (locked in deliberately): SchedTask.weight is
     * `internalWeight ?: priority.toDouble()`, with no floor. A task saved with
     * priority 0 divides by zero in recalculate(), producing an infinite
     * virtualDeadline, and advanceVruntime silently refuses to advance it - the
     * task can then never fall behind and can starve the queue.
     *
     * Fix: clamp weight to at least 1.0 in SchedTask, then update this test.
     */
    @Test fun `KNOWN BUG zero priority produces an infinite virtual deadline`() {
        val zero = t("zero", priority = 0)
        val out = EevdfScheduler.recalculate(listOf(zero)).single()
        assertTrue("weight 0 divides by zero", out.virtualDeadline.isInfinite() || out.virtualDeadline.isNaN())
        assertEquals("and run time is never credited", 0.0, EevdfScheduler.advanceVruntime(zero, 60L), 1e-9)
    }
}
