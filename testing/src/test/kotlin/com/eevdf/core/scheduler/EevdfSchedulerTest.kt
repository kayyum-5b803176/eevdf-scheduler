package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import com.eevdf.core.scheduler.model.SchedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are only possible because the rewritten core is pure: no Android,
 * no `System.currentTimeMillis()`, no in-place mutation. The reference design
 * could not be exercised like this without an emulator and a frozen clock.
 */
class EevdfSchedulerTest {

    private fun task(id: String, priority: Int, vruntime: Double = 0.0) = SchedTask(
        id = id, parentId = null, isGroup = false, isCompleted = false, isRunning = false,
        priority = priority, timeSliceSeconds = 60, vruntime = vruntime,
    )

    @Test fun `recalculate does not mutate its inputs`() {
        val input = listOf(task("a", 4, vruntime = 10.0))
        val before = input.first().copy()
        EevdfScheduler.recalculate(input)
        assertEquals("inputs must be immutable", before, input.first())
    }

    @Test fun `eligible task with earliest deadline is selected`() {
        val tasks = EevdfScheduler.recalculate(
            listOf(task("low", 1, vruntime = 0.0), task("high", 8, vruntime = 0.0)),
        )
        // Both eligible (lag >= 0); higher weight → smaller slice/weight → earlier deadline.
        assertEquals("high", EevdfScheduler.selectNext(tasks)?.id)
    }

    @Test fun `new task is placed at sibling average, not zero`() {
        val existing = listOf(task("a", 4, vruntime = 100.0), task("b", 4, vruntime = 120.0))
        val fresh = task("c", 4, vruntime = 0.0)
        val placed = EevdfScheduler.initialVruntime(fresh, existing)
        assertTrue("new task must not start starving the queue at vruntime 0", placed in 100.0..120.0)
    }
}
