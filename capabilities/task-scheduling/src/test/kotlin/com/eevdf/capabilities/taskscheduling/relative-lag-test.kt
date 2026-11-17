package com.eevdf.capabilities.taskscheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relative lag (RL) is a RANKING of the groups of one sibling level, as small
 * integers centred on 0: the most-behind group gets the highest positive
 * number, every group its own, and the range follows the number of groups.
 * Expected values are worked out by hand in the comments.
 */
class RelativeLagRanksTest {

    private fun grp(
        id: String, vr: Double = 0.0, priority: Int = 4, slice: Long = 60L,
        completed: Boolean = false, cls: String = SchedTask.FAIR,
    ) = SchedTask(
        id = id, parentId = "p", isGroup = true, isCompleted = completed, isRunning = false,
        priority = priority, timeSliceSeconds = slice, vruntime = vr, schedulerClass = cls,
    )

    private fun leaf(id: String, vr: Double = 0.0) = SchedTask(
        id = id, parentId = "p", isGroup = false, isCompleted = false, isRunning = false,
        priority = 4, timeSliceSeconds = 60L, vruntime = vr,
    )

    /** Groups g1..gN with vruntime = index, so g1 is the most behind and gN the most ahead. */
    private fun ladder(n: Int) = (1..n).map { grp("g$it", vr = (it - 1).toDouble()) }

    private fun ranks(pool: List<SchedTask>) = EevdfScheduler.relativeLagRanks(pool)

    // ── The shape of the numbers ──────────────────────────────────────────────

    @Test fun `five groups are centred on zero and all different`() {
        // equal weights, vruntime 0..4 → g1 most behind
        assertEquals(mapOf("g1" to 2, "g2" to 1, "g3" to 0, "g4" to -1, "g5" to -2), ranks(ladder(5)))
    }

    @Test fun `odd counts keep a zero, even counts skip it so the halves stay symmetric`() {
        assertEquals(mapOf("g1" to 1, "g2" to 0, "g3" to -1), ranks(ladder(3)))
        assertEquals(mapOf("g1" to 2, "g2" to 1, "g3" to -1, "g4" to -2), ranks(ladder(4)))
        assertEquals(mapOf("g1" to 1, "g2" to -1), ranks(ladder(2)))
    }

    @Test fun `the range adapts to the number of groups - seventeen groups use minus 8 to plus 8`() {
        val r = ranks(ladder(17))
        assertEquals(17, r.size)
        assertEquals((-8..8).toSet(), r.values.toSet())      // every number exactly once
        assertEquals(8, r.getValue("g1"))                    // most behind
        assertEquals(0, r.getValue("g9"))                    // the middle one
        assertEquals(-8, r.getValue("g17"))                  // most ahead
    }

    @Test fun `it is a ranking - the size of the gaps does not matter`() {
        val tiny = listOf(grp("a", vr = 0.0), grp("b", vr = 1.0), grp("c", vr = 2.0))
        val huge = listOf(grp("a", vr = 0.0), grp("b", vr = 1_000.0), grp("c", vr = 100_000.0))
        assertEquals(mapOf("a" to 1, "b" to 0, "c" to -1), ranks(tiny))
        assertEquals(ranks(tiny), ranks(huge))
    }

    @Test fun `large lags no longer saturate into identical numbers`() {
        // The reported problem: five groups showed +2.5 +2.5 +2.5 -2.5 -2.5. With 10 s slices and
        // hours of lag the old mapping clamped them all; ranks stay distinct.
        val pool = listOf(0.0, 5_000.0, 10_000.0, 20_000.0, 40_000.0).mapIndexed { i, v -> grp("g$i", vr = v, slice = 10L) }
        assertEquals(mapOf("g0" to 2, "g1" to 1, "g2" to 0, "g3" to -1, "g4" to -2), ranks(pool))
    }

    // ── Ties ──────────────────────────────────────────────────────────────────

    @Test fun `groups with equal lag still get different numbers, decided by id`() {
        val pool = listOf("a", "b", "c", "d").map { grp(it, vr = 10.0) }
        assertEquals(mapOf("a" to 2, "b" to 1, "c" to -1, "d" to -2), ranks(pool))
    }

    @Test fun `the result does not depend on the order of the input`() {
        val pool = ladder(17) + listOf("x", "y", "z").map { grp(it, vr = 5.0) }
        val expected = ranks(pool)
        assertEquals(expected, ranks(pool.reversed()))
        assertEquals(expected, ranks(pool.shuffled(java.util.Random(7))))
        assertEquals(expected, ranks(pool.shuffled(java.util.Random(99))))
    }

    @Test fun `equal lag is broken in favour of the earlier virtual deadline`() {
        // same vruntime and weight → same lag. z's deadline = 0 + 10/4, a's = 0 + 100/4: the scheduler
        // would pick z first, so z is the one more worth attention → higher number (ids would say the opposite).
        val pool = listOf(grp("a", slice = 100L), grp("z", slice = 10L))
        assertEquals(mapOf("z" to 1, "a" to -1), ranks(pool))
    }

    // ── What is ranked ────────────────────────────────────────────────────────

    @Test fun `weighted lag decides the order, not plain vruntime`() {
        // A: weight 1, vr 12   B: weight 4, vr 11   C: weight 4, vr 1.   avg = (12 + 44 + 4)/9 = 6.667
        // lag_A = (6.667 − 12)·1 = −5.33    lag_B = (6.667 − 11)·4 = −17.33    lag_C = (6.667 − 1)·4 = +22.67
        // → C, A, B.  (Ordering by vruntime alone would put B above A.)
        val pool = listOf(grp("A", vr = 12.0, priority = 1), grp("B", vr = 11.0, priority = 4), grp("C", vr = 1.0, priority = 4))
        assertEquals(mapOf("C" to 1, "A" to 0, "B" to -1), ranks(pool))
    }

    @Test fun `leaf tasks shape the average but get no number and do not count in N`() {
        // avg over all four = (0 + 10 + 20 + 30)/4 = 15 → lags g1 +20, g2 −20, g3 −60 (weight 4)
        val pool = listOf(leaf("L", vr = 0.0), grp("g1", vr = 10.0), grp("g2", vr = 20.0), grp("g3", vr = 30.0))
        val r = ranks(pool)
        assertEquals(mapOf("g1" to 1, "g2" to 0, "g3" to -1), r)
        assertFalse("L" in r)
        assertTrue(ranks(listOf(leaf("a"), leaf("b"), leaf("c"))).isEmpty())
    }

    @Test fun `a lone group has nothing to be ranked against`() {
        assertTrue(ranks(listOf(grp("only"))).isEmpty())
        assertTrue(ranks(listOf(grp("only"), leaf("a"), leaf("b"))).isEmpty())   // leaves don't count
        assertTrue(ranks(emptyList()).isEmpty())
    }

    @Test fun `completed and non-fair groups are ignored and N shrinks`() {
        val pool = listOf(
            grp("g1", vr = 0.0), grp("g2", vr = 10.0),
            grp("done", vr = 5.0, completed = true), grp("rt", vr = 7.0, cls = SchedTask.RT),
        )
        assertEquals(mapOf("g1" to 1, "g2" to -1), ranks(pool))
    }

    @Test fun `the sign is relative to the siblings, not owed versus in debt`() {
        // A leaf far below drags the average under both groups, so both are truly ahead (lag < 0) —
        // yet the less-ahead one still reads positive. RL is a ranking.
        val pool = listOf(leaf("L", vr = -1000.0), grp("g1", vr = 10.0), grp("g2", vr = 20.0))
        assertEquals(mapOf("g1" to 1, "g2" to -1), ranks(pool))
    }

    @Test fun `a zero weight counts as zero lag and never breaks the ranking`() {
        val r = ranks(listOf(grp("g1", vr = 5.0, priority = 0), grp("g2", vr = 10.0)))
        assertEquals(setOf(1, -1), r.values.toSet())
    }

    @Test fun `inputs are never mutated`() {
        val pool = ladder(6)
        val before = pool.map { it.copy() }
        ranks(pool)
        assertEquals(before, pool)
    }
}
