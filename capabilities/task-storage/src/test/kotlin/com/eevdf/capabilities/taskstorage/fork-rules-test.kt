package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkRulesTest {

    /** A task that has been used hard: every setting is non-default and every piece of run state is dirty. */
    private fun busySource() = Task(
        id = "src-1", name = "backup", description = "nightly", priority = 5, timeSliceSeconds = 1500L,
        identity = "process:142", color = 7, parentId = "group-alice",
        vruntime = 12.0, eligibleTime = 3.0, virtualDeadline = 9.0, lag = 1.5,
        remainingSeconds = 200L, isRunning = true, totalRunTime = 999L, runCount = 7,
        isInterrupt = true, interruptSlot = "B", taskType = "ALARM",
        accumulatedMs = 5_000L, startTimeEpoch = 123L,
        pinnedShare = 40.0, internalWeight = 3.3,
        quotaSeconds = 3600L, quotaPeriodSeconds = 7200L, quotaPeriodStartEpoch = 5L, quotaUsedSeconds = 100L,
        schedulerClass = "deadline_sched_class",
        dlRuntimeSeconds = 10L, dlDeadlineSeconds = 20L, dlPeriodSeconds = 30L,
        dlPeriodStartEpoch = 6L, dlRuntimeUsedSeconds = 4L,
        rtPriority = 60, rtPolicy = "FIFO",
        loadFactor = 2.0, loadAverage = 0.5, loadLastUpdateEpoch = 9L,
        loadAvgCognitive = 1.0, loadAvgPhysical = 2.0, loadAvgEmotional = 3.0,
        sleepDurationSeconds = 60L, sleepUntilEpoch = 77L, sleepLag = 2.0,
        semaphoreEnabled = true, semaphoreLimit = 3, semaphoreHeld = true, semaphoreQueuedAt = 11L,
    )

    // ── what a fork keeps ────────────────────────────────────────────────────

    @Test fun `the fork keeps what the original is`() {
        val s = busySource()
        val c = ForkRules.child(s, "backup (fork)", nowMs = 1_000L, id = "child-1")
        assertEquals("backup (fork)", c.name)
        assertEquals(s.description, c.description)
        assertEquals(s.priority, c.priority)
        assertEquals(s.timeSliceSeconds, c.timeSliceSeconds)
        assertEquals(s.color, c.color)
        assertEquals(s.taskType, c.taskType)
        assertEquals(s.schedulerClass, c.schedulerClass)
        assertEquals(s.dlRuntimeSeconds, c.dlRuntimeSeconds)
        assertEquals(s.dlDeadlineSeconds, c.dlDeadlineSeconds)
        assertEquals(s.dlPeriodSeconds, c.dlPeriodSeconds)
        assertEquals(s.rtPriority, c.rtPriority)
        assertEquals(s.rtPolicy, c.rtPolicy)
        assertEquals(s.quotaSeconds, c.quotaSeconds)
        assertEquals(s.quotaPeriodSeconds, c.quotaPeriodSeconds)
        assertEquals(s.loadFactor, c.loadFactor, 0.0)
        assertEquals(s.sleepDurationSeconds, c.sleepDurationSeconds)
        assertEquals(s.semaphoreEnabled, c.semaphoreEnabled)
        assertEquals(s.semaphoreLimit, c.semaphoreLimit)
    }

    @Test fun `the fork always lands in the same group as the original`() {
        assertEquals("group-alice", ForkRules.child(busySource(), "x", 0L).parentId)
        assertNull(ForkRules.child(busySource().copy(parentId = null), "x", 0L).parentId)
    }

    @Test fun `forking a group makes a group`() {
        val group = busySource().copy(isGroup = true)
        assertTrue(ForkRules.child(group, "g (fork)", 0L).isGroup)
        assertFalse(ForkRules.child(busySource(), "x", 0L).isGroup)
    }

    // ── what a fork starts without ───────────────────────────────────────────

    @Test fun `the fork gets a new id and the clock time`() {
        val c = ForkRules.child(busySource(), "x", nowMs = 4_242L, id = "child-9")
        assertEquals("child-9", c.id)
        assertEquals(4_242L, c.createdAt)
        assertNotEquals("src-1", ForkRules.child(busySource(), "x", 0L).id)   // a generated id is never the original's
    }

    @Test fun `the fork has done nothing yet`() {
        val c = ForkRules.child(busySource(), "x", 0L)
        assertEquals(0L, c.totalRunTime)
        assertEquals(0, c.runCount)
        assertFalse(c.isRunning)
        assertFalse(c.isCompleted)
        assertEquals(0L, c.accumulatedMs)
        assertEquals(0L, c.startTimeEpoch)
        assertEquals(c.timeSliceSeconds, c.remainingSeconds)          // a full slice again
    }

    @Test fun `scheduling position and budgets are reset`() {
        val c = ForkRules.child(busySource(), "x", 0L)
        assertEquals(0.0, c.vruntime, 0.0); assertEquals(0.0, c.eligibleTime, 0.0)
        assertEquals(0.0, c.virtualDeadline, 0.0); assertEquals(0.0, c.lag, 0.0)
        assertEquals(0L, c.quotaPeriodStartEpoch); assertEquals(0L, c.quotaUsedSeconds)
        assertEquals(0L, c.dlPeriodStartEpoch); assertEquals(0L, c.dlRuntimeUsedSeconds)
        assertEquals(0.0, c.loadAverage, 0.0); assertEquals(0L, c.loadLastUpdateEpoch)
        assertEquals(0.0, c.loadAvgCognitive, 0.0); assertEquals(0.0, c.loadAvgPhysical, 0.0)
        assertEquals(0.0, c.loadAvgEmotional, 0.0)
    }

    @Test fun `sleep and the semaphore slot are reset`() {
        val c = ForkRules.child(busySource(), "x", 0L)
        assertEquals(0L, c.sleepUntilEpoch); assertEquals(0.0, c.sleepLag, 0.0)
        assertFalse(c.semaphoreHeld); assertEquals(0L, c.semaphoreQueuedAt)
    }

    // ── what a fork drops ────────────────────────────────────────────────────

    @Test fun `the interrupt slot is never copied`() {
        val c = ForkRules.child(busySource(), "x", 0L)
        assertFalse(c.isInterrupt)
        assertEquals("A", c.interruptSlot)
    }

    @Test fun `a pinned share is not copied`() {
        val c = ForkRules.child(busySource(), "x", 0L)
        assertNull(c.pinnedShare)
        assertNull(c.internalWeight)
        assertEquals(5, c.priority)                                   // the priority stays, so it floats at the same weight
    }

    // ── identity ─────────────────────────────────────────────────────────────

    @Test fun `the fork asks for its kind and leaves the number to insert`() {
        assertEquals("process:", ForkRules.child(busySource(), "x", 0L).identity)
        assertEquals("user:", ForkRules.child(busySource().copy(identity = "user:300"), "x", 0L).identity)
        assertEquals("thread:", ForkRules.child(busySource().copy(identity = "thread:101"), "x", 0L).identity)
    }

    @Test fun `an undefined or unreadable original becomes a process`() {
        assertEquals(IdentityKind.PROCESS, ForkRules.childKind("undefined:104"))
        assertEquals(IdentityKind.PROCESS, ForkRules.childKind(""))
        assertEquals(IdentityKind.PROCESS, ForkRules.childKind("nonsense"))
        assertEquals(IdentityKind.GROUP,   ForkRules.childKind("group:200"))
    }

    @Test fun `a requested kind is numbered with the smallest free id of that kind`() {
        // ForkRules asks for "process:"; IdentityRules (used by TaskRepository.insert) numbers it.
        assertEquals("process:101", IdentityRules.resolve(ForkRules.child(busySource(), "x", 0L).identity, emptyList()))
        assertEquals("process:102", IdentityRules.resolve(ForkRules.child(busySource(), "x", 0L).identity, listOf("process:101")))
    }

    @Test fun `the picker offers every unfinished task and group, in name order`() {
        val a = Task(name = "10 b", priority = 3, timeSliceSeconds = 60L)
        val b = Task(name = "2 a",  priority = 3, timeSliceSeconds = 60L, isGroup = true)
        val done = Task(name = "1 done", priority = 3, timeSliceSeconds = 60L, isCompleted = true)
        val offered = ForkRules.forkable(listOf(a, done, b))
        assertEquals(listOf(b.id, a.id), offered.map { it.id })   // finished one left out; "2 a" before "10 b"
    }

    @Test fun `the default name is the original's with a suffix`() {
        assertEquals("backup (fork)", ForkRules.defaultName("backup"))
    }

    @Test fun `the original is never changed`() {
        val s = busySource()
        val before = s.copy()
        ForkRules.child(s, "x", 0L)
        assertEquals(before, s)
    }
}
