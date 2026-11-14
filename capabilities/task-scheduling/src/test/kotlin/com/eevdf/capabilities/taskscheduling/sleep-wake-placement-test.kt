package com.eevdf.capabilities.taskscheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wake placement for a sleeping task (Linux `place_entity` for a woken
 * entity): the lag a task slept with is restored on wake, clamped to one slice.
 */
class SleepWakePlacementTest {

    private fun task(id: String, priority: Int, vruntime: Double, slice: Long = 60L, parentId: String? = null) =
        SchedTask(
            id = id, parentId = parentId, isGroup = false, isCompleted = false, isRunning = false,
            priority = priority, timeSliceSeconds = slice, vruntime = vruntime,
        )

    @Test fun `sleepLag is avg minus vruntime times weight`() {
        // avg over a(4,vr 98) and b(4,vr 102) = 100 → a lag = (100-98)*4 = +8
        val a = task("a", 4, 98.0)
        val b = task("b", 4, 102.0)
        assertEquals(8.0, EevdfScheduler.sleepLag(a, listOf(a, b)), 1e-9)
        assertEquals(-8.0, EevdfScheduler.sleepLag(b, listOf(a, b)), 1e-9)
    }

    @Test fun `sleepLag works whether or not the task is in the sibling list`() {
        val a = task("a", 4, 98.0)
        val b = task("b", 4, 102.0)
        assertEquals(
            EevdfScheduler.sleepLag(a, listOf(a, b)),
            EevdfScheduler.sleepLag(a, listOf(b, a)), 1e-9,
        )
        assertEquals(EevdfScheduler.sleepLag(a, listOf(a, b)), EevdfScheduler.sleepLag(a, listOf(b)), 1e-9)
    }

    @Test fun `sleepLag is clamped to one time slice`() {
        val a = task("a", 4, 0.0, slice = 10L)
        val b = task("b", 4, 1000.0, slice = 10L)   // huge credit for a
        assertEquals(10.0, EevdfScheduler.sleepLag(a, listOf(a, b)), 1e-9)
        assertEquals(-10.0, EevdfScheduler.sleepLag(b, listOf(a, b)), 1e-9)
    }

    @Test fun `wake restores the saved lag exactly after the task rejoins the pool`() {
        // Others kept running while the task slept: their average moved to ~130.
        val others = listOf(task("b", 4, 128.0), task("c", 2, 134.0))
        val woken = task("a", 4, 98.0)
        for (saved in listOf(8.0, -16.0, 0.0, 30.0)) {
            val vr = EevdfScheduler.wakeVruntime(woken, saved, others)
            val rejoined = EevdfScheduler.recalculate(others + woken.copy(vruntime = vr))
            val lag = rejoined.first { it.id == "a" }.lag
            assertEquals("saved lag $saved must be reproduced", saved, lag, 1e-9)
        }
    }

    @Test fun `a task in debt cannot erase it by sleeping`() {
        val others = listOf(task("b", 4, 130.0))
        val hog = task("a", 4, 134.0)                       // behind-schedule hog
        val saved = EevdfScheduler.sleepLag(hog, listOf(hog, task("b", 4, 130.0)))
        assertTrue("hog starts in debt", saved < 0.0)
        val vr = EevdfScheduler.wakeVruntime(hog, saved, others)
        assertTrue("wakes AFTER the average, still in debt", vr > EevdfScheduler.averageVruntime(others))
    }

    @Test fun `a task owed time wakes ahead of the average`() {
        val others = listOf(task("b", 4, 130.0))
        val owed = task("a", 4, 98.0)
        val saved = EevdfScheduler.sleepLag(owed, listOf(owed, task("b", 4, 102.0)))
        assertTrue(saved > 0.0)
        val vr = EevdfScheduler.wakeVruntime(owed, saved, others)
        assertTrue(vr < EevdfScheduler.averageVruntime(others))
    }

    @Test fun `no awake siblings places like a lone new task`() {
        val a = task("a", 4, 55.0)
        assertEquals(0.0, EevdfScheduler.wakeVruntime(a, 7.0, emptyList()), 0.0)
        assertEquals(0.0, EevdfScheduler.wakeVruntime(a, 7.0, listOf(a)), 0.0)
    }

    @Test fun `zero lag wakes exactly at the sibling average like a new task`() {
        val others = listOf(task("b", 4, 120.0), task("c", 4, 140.0))
        val a = task("a", 4, 10.0)
        assertEquals(
            EevdfScheduler.initialVruntime(a, others),
            EevdfScheduler.wakeVruntime(a, 0.0, others), 1e-9,
        )
    }

    @Test fun `waking does not mutate inputs`() {
        val others = listOf(task("b", 4, 120.0))
        val a = task("a", 4, 10.0)
        val before = others.toList()
        EevdfScheduler.wakeVruntime(a, 5.0, others)
        assertEquals(before, others)
    }
}
