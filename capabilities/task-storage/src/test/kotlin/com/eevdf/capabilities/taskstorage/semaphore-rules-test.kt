package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules for the group semaphore — hiding, FIFO admission, arrival, no eviction. */
class SemaphoreRulesTest {

    private fun t(
        id: String,
        parent: String? = null,
        group: Boolean = false,
        sem: Boolean = false,
        limit: Int = 2,
        held: Boolean = false,
        queuedAt: Long = 0L,
        created: Long = 0L,
        asleepUntil: Long = 0L,
        schedulerClass: String = "fair_sched_class",
    ) = Task(
        id = id, name = id, priority = 4, timeSliceSeconds = 60L,
        parentId = parent, isGroup = group, createdAt = created,
        sleepUntilEpoch = asleepUntil, schedulerClass = schedulerClass,
        semaphoreEnabled = sem, semaphoreLimit = limit,
        semaphoreHeld = held, semaphoreQueuedAt = queuedAt,
    )

    // ── Hiding ────────────────────────────────────────────────────────────────

    @Test fun `no semaphore means nothing is hidden and the same list comes back`() {
        val all = listOf(t("g", group = true), t("a", parent = "g"), t("b", parent = "g"))
        assertTrue(SleepRules.awake(all) === all)
    }

    @Test fun `a child without a slot is hidden, holders stay visible`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("a", parent = "g", held = true), t("b", parent = "g", held = true),
            t("c", parent = "g", queuedAt = 50L),
        )
        assertEquals(listOf("g", "a", "b"), SleepRules.awake(all).map { it.id })
        assertEquals(mapOf("g" to 1), SemaphoreRules.waitingCounts(all))
    }

    @Test fun `a waiting group hides its whole subtree`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("holder", parent = "g", held = true),
            t("sub", parent = "g", group = true, queuedAt = 9L),
            t("inner", parent = "sub"),
        )
        assertEquals(listOf("g", "holder"), SleepRules.awake(all).map { it.id })
        // only the direct waiter counts towards W, not what it contains
        assertEquals(mapOf("g" to 1), SemaphoreRules.waitingCounts(all))
    }

    @Test fun `realtime and deadline children queue like everyone else`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("a", parent = "g", held = true),
            t("rt", parent = "g", queuedAt = 5L, schedulerClass = "rt_sched_class"),
            t("dl", parent = "g", queuedAt = 6L, schedulerClass = "dl_sched_class"),
        )
        assertEquals(listOf("g", "a"), SleepRules.awake(all).map { it.id })
    }

    @Test fun `a sleeping child is hidden by sleep and is not counted as waiting`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("a", parent = "g", held = true),
            t("s", parent = "g", asleepUntil = 9_000L),
        )
        assertEquals(listOf("g", "a"), SleepRules.awake(all).map { it.id })
        assertTrue(SemaphoreRules.waitingCounts(all).isEmpty())
    }

    @Test fun `semaphore state is kept but inactive when the switch is off`() {
        val all = listOf(
            t("g", group = true, sem = false),
            t("a", parent = "g", held = false, queuedAt = 7L),
        )
        assertTrue(SleepRules.awake(all) === all)
    }

    // ── Admission ─────────────────────────────────────────────────────────────

    @Test fun `switching on admits the oldest N by creation time`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("c", parent = "g", created = 30L),
            t("a", parent = "g", created = 10L),
            t("b", parent = "g", created = 20L),
        )
        val admitted = SemaphoreRules.plan(all, setOf("g"))
        assertEquals(listOf("a", "b"), admitted.map { it.id })
        // these were already there: they keep their place, no re-placement
        assertTrue(admitted.none { it.fromQueue })
        assertTrue(admitted.all { it.held })
    }

    @Test fun `a freed slot goes to the oldest waiter`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("gone", parent = "g", held = true, queuedAt = 1L, asleepUntil = 5_000L),   // just slept
            t("late", parent = "g", queuedAt = 30L),
            t("early", parent = "g", queuedAt = 20L),
        )
        val plan = SemaphoreRules.plan(all, setOf("g"))
        // the sleeper loses its slot and stamp…
        assertTrue(plan.any { it.id == "gone" && !it.held && it.queuedAt == 0L })
        // …and the OLDEST waiter is admitted from the queue
        val admitted = plan.filter { it.held }
        assertEquals(listOf("early"), admitted.map { it.id })
        assertTrue(admitted.single().fromQueue)
    }

    @Test fun `lowering the limit never evicts a holder`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("a", parent = "g", held = true), t("b", parent = "g", held = true),
            t("c", parent = "g", queuedAt = 10L),
        )
        assertTrue(SemaphoreRules.plan(all).isEmpty())
    }

    @Test fun `holders still fill after a lowered limit once enough have left`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("a", parent = "g", held = true),
            t("c", parent = "g", queuedAt = 10L), t("d", parent = "g", queuedAt = 11L),
        )
        assertEquals(listOf("c"), SemaphoreRules.plan(all).filter { it.held }.map { it.id })
    }

    @Test fun `raising the limit admits waiters at once and in order`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 3),
            t("a", parent = "g", held = true),
            t("c", parent = "g", queuedAt = 20L), t("b", parent = "g", queuedAt = 10L),
        )
        assertEquals(listOf("b", "c"), SemaphoreRules.plan(all).filter { it.held }.map { it.id })
    }

    @Test fun `a child group costs exactly one slot whatever it contains`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("big", parent = "g", group = true, held = true),
            t("x1", parent = "big"), t("x2", parent = "big"), t("x3", parent = "big"),
            t("leaf", parent = "g", created = 5L),
        )
        assertEquals(listOf("leaf"), SemaphoreRules.plan(all, setOf("g")).filter { it.held }.map { it.id })
    }

    @Test fun `only the named groups are touched`() {
        val all = listOf(
            t("g1", group = true, sem = true, limit = 1), t("a", parent = "g1"),
            t("g2", group = true, sem = true, limit = 1), t("b", parent = "g2"),
        )
        assertEquals(listOf("a"), SemaphoreRules.plan(all, setOf("g1")).map { it.id })
    }

    @Test fun `stale slot state under a group without a semaphore is cleaned up`() {
        val all = listOf(
            t("g", group = true, sem = false),
            t("a", parent = "g", held = true, queuedAt = 3L),
        )
        val plan = SemaphoreRules.plan(all)
        assertEquals(listOf(SemaphoreRules.SlotChange("a", false, 0L, false)), plan)
    }

    // ── Arrival ───────────────────────────────────────────────────────────────

    @Test fun `arriving with a free slot and an empty queue takes the slot`() {
        val pool = listOf(t("g", group = true, sem = true, limit = 2), t("a", parent = "g", held = true))
        val n = t("n", parent = "g")
        SemaphoreRules.stamp(n, pool, nowMs = 1_000L)
        assertTrue(n.semaphoreHeld)
        assertEquals(1_000L, n.semaphoreQueuedAt)
    }

    @Test fun `arriving at a full group queues at the back`() {
        val pool = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("a", parent = "g", held = true, queuedAt = 400L),
            t("w", parent = "g", queuedAt = 5_000L),
        )
        val n = t("n", parent = "g")
        SemaphoreRules.stamp(n, pool, nowMs = 1_000L)
        assertFalse(n.semaphoreHeld)
        // strictly after every stamp already in the queue, even if the clock is behind
        assertEquals(5_001L, n.semaphoreQueuedAt)
    }

    @Test fun `a newcomer never jumps ahead of a waiter even if a slot looks free`() {
        val pool = listOf(
            t("g", group = true, sem = true, limit = 2),
            t("a", parent = "g", held = true),
            t("w", parent = "g", queuedAt = 10L),
        )
        val n = t("n", parent = "g")
        SemaphoreRules.stamp(n, pool, nowMs = 99L)
        assertFalse(n.semaphoreHeld)
    }

    @Test fun `a woken child queues like a new arrival and does not get its old slot back`() {
        val pool = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("other", parent = "g", held = true, queuedAt = 50L),
        )
        // it held a slot before it slept; sleeping cleared that, so it arrives with nothing
        val woken = t("woken", parent = "g", held = false, queuedAt = 0L)
        SemaphoreRules.stamp(woken, pool, nowMs = 100L)
        assertFalse(woken.semaphoreHeld)
        assertTrue(woken.semaphoreQueuedAt > 50L)
    }

    @Test fun `arriving under a group without a semaphore clears the slot fields`() {
        val pool = listOf(t("g", group = true, sem = false))
        val n = t("n", parent = "g", held = true, queuedAt = 8L)
        SemaphoreRules.stamp(n, pool, nowMs = 100L)
        assertFalse(n.semaphoreHeld)
        assertEquals(0L, n.semaphoreQueuedAt)
        SemaphoreRules.stamp(n.copy(parentId = null), pool, nowMs = 100L)   // root: no parent at all
    }

    @Test fun `admitsImmediately answers for the creation toast`() {
        val full = listOf(t("g", group = true, sem = true, limit = 1), t("a", parent = "g", held = true))
        val open = listOf(t("g", group = true, sem = true, limit = 2), t("a", parent = "g", held = true))
        assertFalse(SemaphoreRules.admitsImmediately("g", full))
        assertTrue(SemaphoreRules.admitsImmediately("g", open))
        assertTrue(SemaphoreRules.admitsImmediately(null, full))
        assertTrue(SemaphoreRules.admitsImmediately("g", listOf(t("g", group = true, sem = false))))
    }

    // ── Wait list ─────────────────────────────────────────────────────────────

    @Test fun `wait list roots are in arrival order and nested contents are reached through their group`() {
        val all = listOf(
            t("g", group = true, sem = true, limit = 1),
            t("a", parent = "g", held = true),
            t("w2", parent = "g", queuedAt = 20L),
            t("w1", parent = "g", queuedAt = 10L, group = true),
            t("inside", parent = "w1"),
        )
        assertEquals(listOf("w1", "w2"), SemaphoreRules.waitingRoots(all).map { it.id })
        assertEquals(mapOf("w1" to 1, "w2" to 2), SemaphoreRules.queuePositions(all))
    }

    @Test fun `limit is clamped to 1 to 64`() {
        assertEquals(1, SemaphoreRules.clampLimit(0))
        assertEquals(64, SemaphoreRules.clampLimit(500))
        assertEquals(4, SemaphoreRules.DEFAULT_LIMIT)
    }
}
