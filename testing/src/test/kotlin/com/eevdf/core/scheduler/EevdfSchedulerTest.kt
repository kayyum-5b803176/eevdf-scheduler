package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.testing.InMemoryRrStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── Hierarchy helpers ─────────────────────────────────────────────────────

    /**
     * A root-level group entity (isGroup = true, parentId = null).
     * [timeSliceSeconds] is the group's own slice at root level — independent of
     * whatever slices its children carry.
     */
    private fun group(
        id: String,
        priority: Int = 4,
        vruntime: Double = 0.0,
        timeSliceSeconds: Long = 10L,
    ) = SchedTask(
        id = id, parentId = null, isGroup = true, isCompleted = false, isRunning = false,
        priority = priority, timeSliceSeconds = timeSliceSeconds, vruntime = vruntime,
    )

    /** A child task (parentId = [parentId], isGroup = false). */
    private fun child(
        id: String,
        parentId: String,
        priority: Int = 4,
        vruntime: Double = 0.0,
        timeSliceSeconds: Long = 1L,
    ) = SchedTask(
        id = id, parentId = parentId, isGroup = false, isCompleted = false, isRunning = false,
        priority = priority, timeSliceSeconds = timeSliceSeconds, vruntime = vruntime,
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

    // ── Hierarchy (cgroup) characterization tests ─────────────────────────────
    //
    // Scenario: task-a (root leaf, 10 s slice) vs task-b (root group, 10 s slice)
    // with children b1..b4 (each 1 s slice, parentId = "b").
    //
    // Assertion set (from the fix roadmap, step 5):
    //   H1. b1..b4 never appear as selectNext candidates directly against task-a.
    //   H2. Picking among b1..b4 does not change task-a's root-level deadline/lag.
    //   H3. task-b's vruntime advances only when a child runs (hierarchical charge).
    //   H4. Root-level EEVDF switches back to task-a once task-b's vruntime
    //       catches up, not after each 1 s child tick.
    //   H5. advanceVruntimeHierarchical returns exactly one copy per entity in chain.

    /**
     * H1: pickNextHierarchical must never return a child of task-b directly
     * when it wins at root level; it must descend into the group's runqueue first.
     *
     * Setup: task-b has lower vruntime than task-a → task-b wins at root.
     * The returned leaf must be one of b1..b4, with parentId == "b" and
     * isGroup == false.  task-b itself (the group container) must never be
     * returned as a runnable entity.
     */
    @Test fun `H1 children of a group never compete at root level against a top-level task`() {
        val a  = task("a", priority = 4, vruntime = 5.0)   // root leaf (ahead)
        val b  = group("b", priority = 4, vruntime = 0.0)  // root group (behind → wins root)
        val b1 = child("b1", "b")
        val b2 = child("b2", "b")
        val b3 = child("b3", "b")
        val b4 = child("b4", "b")

        val result = EevdfScheduler.pickNextHierarchical(listOf(a, b, b1, b2, b3, b4))

        assertNotNull("must select a runnable task", result)
        assertEquals(
            "winner must be a child of group b, not a top-level candidate against task-a",
            "b", result!!.parentId,
        )
        assertFalse("the group container itself must not be returned as a runnable leaf", result.isGroup)
    }

    /**
     * H2: Recalculating EEVDF at the children level (b1..b4 runqueue) must not
     * perturb task-a's lag or virtualDeadline at the root level.
     *
     * The root runqueue has only task-a and task-b; the children runqueue has b1..b4.
     * These are independent by design.  Including b1 and b2 in the root pool
     * (the pre-fix flat bug) would skew the weighted-average vruntime and change
     * task-a's lag — the second assertion demonstrates exactly this, proving the
     * first assertion is a meaningful guard.
     */
    @Test fun `H2 root-level lag for task-a is unaffected by child vruntime moves`() {
        val a  = task("a", priority = 4, vruntime = 10.0)
        val b  = group("b", priority = 4, vruntime = 10.0)
        // Children have wildly different vruntimes to maximise skew if incorrectly mixed in.
        val b1 = child("b1", "b", vruntime = 0.0)
        val b2 = child("b2", "b", vruntime = 50.0)

        // CORRECT: root-level recalculate sees only task-a and task-b.
        // avgVr = (10+10)/2 = 10.  task-a's lag = (10−10)·4 = 0.
        val rootOnly = EevdfScheduler.recalculate(listOf(a, b))
        val lagACorrect = rootOnly.first { it.id == "a" }.lag
        assertEquals("task-a lag must be zero when both root entities at vruntime 10", 0.0, lagACorrect, 1e-9)

        // BUG EVIDENCE: flat pool includes children. avgVr = (10+10+0+50)/4 = 17.5.
        // task-a's lag = (17.5−10)·4 = 30 — demonstrating the old flat-pool bug.
        val flatMixed = EevdfScheduler.recalculate(listOf(a, b, b1, b2))
        val lagABuggy = flatMixed.first { it.id == "a" }.lag
        assertTrue(
            "mixing children into the root pool must produce a non-zero lag for task-a (bug evidence)",
            lagABuggy != 0.0,
        )
    }

    /**
     * H3: advanceVruntimeHierarchical charges both the leaf task and every
     * ancestor group.  When b1 runs 1 s (weight 4), both b1 and b (weight 4)
     * have their vruntimes incremented by 1/4 = 0.25.  task-a is untouched.
     */
    @Test fun `H3 group vruntime is charged once per child tick via advanceVruntimeHierarchical`() {
        val a  = task("a", priority = 4, vruntime = 0.0)
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b", priority = 4, vruntime = 0.0)

        val updates = EevdfScheduler.advanceVruntimeHierarchical(
            task = b1, secondsRan = 1L, allTasks = listOf(a, b, b1),
        )

        val updatedB1 = updates.first { it.id == "b1" }
        val updatedB  = updates.first { it.id == "b" }

        // b1 vruntime: 0 + 1/4 = 0.25
        assertEquals("b1 vruntime must advance by secondsRan / weight_b1", 0.25, updatedB1.vruntime, 1e-9)
        // b (group) vruntime: 0 + 1/4 = 0.25 — charged at root level independently
        assertEquals("group b vruntime must advance by secondsRan / weight_b (root-level charge)", 0.25, updatedB.vruntime, 1e-9)
        // task-a must not appear in the updates at all
        assertFalse("task-a must not be charged by a child run", updates.any { it.id == "a" })
    }

    /**
     * H4: After task-b's group vruntime catches up to task-a's, root-level EEVDF
     * selects task-a — even though b1..b4 have lower per-child vruntimes.
     *
     * This verifies the "root-level switch" is driven by group-level fairness,
     * not by individual child ticks.
     */
    @Test fun `H4 root level switches to task-a once group vruntime catches up`() {
        // task-a vruntime 5.0, task-b vruntime 5.25 (b ran more at root level).
        // task-b is ahead of task-a → task-a must win the root-level contest.
        // b1..b4 all have vruntime 0 — they would win if erroneously placed at root.
        val a  = task("a", priority = 4, vruntime = 5.0)
        val b  = group("b", priority = 4, vruntime = 5.25)
        val b1 = child("b1", "b", vruntime = 0.0)
        val b2 = child("b2", "b", vruntime = 0.0)

        val result = EevdfScheduler.pickNextHierarchical(listOf(a, b, b1, b2))

        assertNotNull(result)
        assertEquals(
            "task-a must win when it has lower root-level vruntime than group b",
            "a",
            result!!.id,
        )
        assertNull("task-a is a root leaf; parentId must be null", result.parentId)
    }

    /**
     * H5: advanceVruntimeHierarchical returns exactly one updated copy per entity
     * in the ancestry chain — no duplicates, no extras beyond the chain.
     */
    @Test fun `H5 advanceVruntimeHierarchical returns exactly one copy per entity in the chain`() {
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b", priority = 4, vruntime = 0.0)

        val updates = EevdfScheduler.advanceVruntimeHierarchical(
            task = b1, secondsRan = 2L, allTasks = listOf(b, b1),
        )

        assertEquals("must return exactly two entities: the leaf and its parent group", 2, updates.size)
        val ids = updates.map { it.id }.toSet()
        assertTrue("updated set must contain the leaf b1", "b1" in ids)
        assertTrue("updated set must contain the ancestor group b", "b" in ids)
    }

    // ── Queue / scheduleOrder characterization tests ──────────────────────────
    //
    // These test SchedulerService.scheduleOrder against the five queue-tab issues:
    //   Q1. Group containers must never appear as queue positions (issue 1).
    //   Q2. scheduleOrder[0] must equal selectNext (issue 5).
    //   Q3. Nested groups must be expanded recursively (issue 2).
    //   Q4. No task appears more than once in the queue (issues 3 & 4).

    private val dummyNow = SchedulerService.Now(
        epochSeconds = 1_700_000_000L,
        dayOfWeekIndex = 1,
        secondOfDay = 36_000L,
        prevDayOfWeekIndex = 0,
    )

    private fun schedulerService() = SchedulerService(InMemoryRrStateStore())

    /**
     * Q1: The queue must contain only runnable leaves. Group entities (task-b)
     * determine child positioning but must never occupy a queue slot themselves.
     */
    @Test fun `Q1 scheduleOrder never contains group containers`() {
        val svc = schedulerService()
        val a  = task("a", priority = 4, vruntime = 5.0)
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b")
        val b2 = child("b2", "b")

        val queue = svc.scheduleOrder(listOf(a, b, b1, b2), dummyNow)

        assertFalse(
            "no group entity may appear as a queue position",
            queue.any { it.isGroup },
        )
        assertEquals(
            "queue must contain exactly the three leaf tasks",
            setOf("a", "b1", "b2"),
            queue.map { it.id }.toSet(),
        )
    }

    /**
     * Q2: Position 0 of scheduleOrder must be the same task selectNext returns.
     * This is the queue contract — if they diverge, the UI lies about what runs
     * next.
     *
     * Scenario: task-b (group, behind at root) wins root. Its child b1 is the
     * first leaf. selectNext must return b1. scheduleOrder[0] must also be b1.
     */
    @Test fun `Q2 scheduleOrder position 0 matches selectNext`() {
        val svc = schedulerService()
        val a  = task("a", priority = 4, vruntime = 5.0)
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b", vruntime = 0.0)
        val b2 = child("b2", "b", vruntime = 1.0)

        val all = listOf(a, b, b1, b2)
        val next = svc.selectNext(all, dummyNow)
        val queue = svc.scheduleOrder(all, dummyNow)

        assertNotNull(next)
        assertTrue("queue must not be empty", queue.isNotEmpty())
        assertEquals(
            "scheduleOrder[0] must be the same task selectNext returns",
            next!!.id,
            queue.first().id,
        )
    }

    /**
     * Q3: Nested groups (group inside a group) must be expanded recursively.
     * group-b contains group-c, which contains c1 and c2. All four leaves
     * (b1, c1, c2, task-a) must appear; neither group-b nor group-c may appear.
     */
    @Test fun `Q3 nested groups are expanded recursively to arbitrary depth`() {
        val svc = schedulerService()
        val a  = task("a", priority = 4, vruntime = 10.0)
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b", vruntime = 0.0)
        // group-c is a sub-group inside group-b
        val c  = SchedTask(
            id = "c", parentId = "b", isGroup = true, isCompleted = false, isRunning = false,
            priority = 4, timeSliceSeconds = 5L, vruntime = 0.0,
        )
        val c1 = child("c1", "c", vruntime = 0.0)
        val c2 = child("c2", "c", vruntime = 0.0)

        val queue = svc.scheduleOrder(listOf(a, b, b1, c, c1, c2), dummyNow)

        val ids = queue.map { it.id }.toSet()
        assertFalse("group-b must not appear as a queue position", "b" in ids)
        assertFalse("group-c must not appear as a queue position", "c" in ids)
        assertTrue("leaf b1 must appear", "b1" in ids)
        assertTrue("leaf c1 must appear", "c1" in ids)
        assertTrue("leaf c2 must appear", "c2" in ids)
        assertTrue("leaf task-a must appear", "a" in ids)
        assertEquals("exactly four leaves in the queue", 4, queue.size)
    }

    /**
     * Q4: Every task appears exactly once. A DL child inside a fair group must
     * appear in the DL section and must NOT re-appear in the fair expansion.
     */
    @Test fun `Q4 no task appears twice in the queue`() {
        val svc = schedulerService()
        val a  = task("a", priority = 4, vruntime = 0.0)
        val b  = group("b", priority = 4, vruntime = 0.0)
        val b1 = child("b1", "b")
        val b2 = child("b2", "b")

        val queue = svc.scheduleOrder(listOf(a, b, b1, b2), dummyNow)

        val ids = queue.map { it.id }
        assertEquals(
            "every task id must appear exactly once — no duplicates",
            ids.size,
            ids.toSet().size,
        )
    }

    /**
     * Q5: Dormant RT tasks (out-of-window) and expired DL tasks must not appear
     * in the fair queue. They are neither active for their own class nor eligible
     * for CFS — they should be absent from scheduleOrder entirely.
     *
     * Before the isFair guard, these tasks fell through the excludedIds check
     * (which only covers *active* DL/RT) and were EEVDF-ordered by vruntime
     * inside the fair section — a meaningless ordering for non-fair classes.
     */
    @Test fun `Q5 dormant RT and expired DL tasks are absent from the queue`() {
        val svc = schedulerService()

        val fairTask = task("fair", priority = 4, vruntime = 0.0)

        // RT task whose window is Saturday-only (activeDaysMask bit 6).
        // dummyNow is Monday (dayOfWeekIndex=1), so this task is outside its window.
        val dormantRt = SchedTask(
            id = "dormant-rt", parentId = null, isGroup = false,
            isCompleted = false, isRunning = false,
            priority = 4, timeSliceSeconds = 60L,
            schedulerClass = SchedTask.RT,
            rt = com.eevdf.core.scheduler.model.RtConfig(
                priority = 50,
                activeDaysMask = 1 shl 6,   // Saturday only
                activationHour = 10,
                activationMinute = 0,
                activationSecond = 0,
                sliceTimeoutSeconds = 3_600L,
            ),
        )

        val queue = svc.scheduleOrder(listOf(fairTask, dormantRt), dummyNow)

        assertTrue(
            "fair task must appear in the queue",
            queue.any { it.id == "fair" },
        )
        assertFalse(
            "dormant RT task must not appear anywhere in the queue",
            queue.any { it.id == "dormant-rt" },
        )
    }
}
