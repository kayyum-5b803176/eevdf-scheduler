package com.eevdf.capabilities.taskscheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relative lag (RL): `clamp(lag / slice · N/2, −N/2, +N/2)` where
 * `lag = (avgVruntime − vruntime) · weight` over ONE sibling pool that includes
 * the entity itself. Expected values below are worked out by hand in the
 * comments, not copied from the implementation.
 */
class RelativeLagTest {

    private fun ent(
        id: String,
        parent: String? = "p",
        group: Boolean = false,
        priority: Int = 4,
        slice: Long = 60L,
        vr: Double = 0.0,
        completed: Boolean = false,
        cls: String = SchedTask.FAIR,
    ) = SchedTask(
        id = id, parentId = parent, isGroup = group, isCompleted = completed, isRunning = false,
        priority = priority, timeSliceSeconds = slice, vruntime = vr, schedulerClass = cls,
    )

    private fun rl(entity: SchedTask, pool: List<SchedTask>): Double =
        EevdfScheduler.relativeLag(entity, pool) ?: error("expected a value for ${entity.id}, got null")

    /** Six weight-4, 60 s siblings; "x" sits [behindBy] below the other five (at 100). */
    private fun sixWith(xVruntime: Double): List<SchedTask> =
        listOf(ent("x", vr = xVruntime)) + (1..5).map { ent("s$it", vr = 100.0) }

    // ── Balanced ──────────────────────────────────────────────────────────────

    @Test fun `six equal-weight siblings at the same vruntime are all zero`() {
        val pool = (1..6).map { ent("s$it", vr = 10.0) }
        pool.forEach { assertEquals(0.0, rl(it, pool), 1e-9) }
    }

    // ── Hand-computed values (N = 6, weight 4, slice 60 → half = 3) ───────────

    @Test fun `sibling whose lag is exactly one slice sits at the plus-N-over-2 limit`() {
        // others at 100, x at 82:  avg = (5·100 + 82)/6 = 97
        // lag_x = (97 − 82)·4 = 60 = one slice  →  RL = 60/60 · 3 = +3.0
        // lag_others = (97 − 100)·4 = −12       →  RL = −12/60 · 3 = −0.6
        val pool = sixWith(82.0)
        assertEquals(3.0, rl(pool[0], pool), 1e-9)
        pool.drop(1).forEach { assertEquals(-0.6, rl(it, pool), 1e-9) }
    }

    @Test fun `half a slice of lag reads as half the limit`() {
        // x at 91: avg = (500 + 91)/6 = 98.5; lag_x = 7.5·4 = 30 → RL = 30/60 · 3 = +1.5
        // others: (98.5 − 100)·4 = −6 → RL = −6/60 · 3 = −0.3
        val pool = sixWith(91.0)
        assertEquals(1.5, rl(pool[0], pool), 1e-9)
        assertEquals(-0.3, rl(pool[1], pool), 1e-9)
    }

    @Test fun `two siblings give a range of plus or minus one`() {
        // a=0, b=10 → avg 5; lag_a = 5·4 = 20 → 20/60 · 1 = +0.3333; b mirrors it.
        val a = ent("a", vr = 0.0); val b = ent("b", vr = 10.0)
        assertEquals(1.0 / 3.0, rl(a, listOf(a, b)), 1e-9)
        assertEquals(-1.0 / 3.0, rl(b, listOf(a, b)), 1e-9)
    }

    @Test fun `odd N gives half steps`() {
        // 5 siblings (half = 2.5), x's lag = exactly one slice → +2.5.   others at 100, x at V:
        // avg = (400 + V)/5 ; lag_x = (avg − V)·4 = (400 − 4V)/5 · 4 = 60  →  V = 81.25, avg = 96.25
        val pool = listOf(ent("x", vr = 81.25)) + (1..4).map { ent("s$it", vr = 100.0) }
        assertEquals(2.5, rl(pool[0], pool), 1e-9)
    }

    // ── Clamp ─────────────────────────────────────────────────────────────────

    @Test fun `far behind stays at the positive limit and far ahead at the negative limit`() {
        // x = −80: avg = (500 − 80)/6 = 70; lag_x = 150·4 = 600 → unclamped 30 → +3.0
        assertEquals(3.0, rl(sixWith(-80.0)[0], sixWith(-80.0)), 1e-9)
        // x = 280: avg = (500 + 280)/6 = 130; lag_x = −150·4 = −600 → unclamped −30 → −3.0
        assertEquals(-3.0, rl(sixWith(280.0)[0], sixWith(280.0)), 1e-9)
    }

    // ── No pool / bad inputs ──────────────────────────────────────────────────

    @Test fun `a lone entity has no relative lag`() {
        val a = ent("a", vr = 5.0)
        assertNull(EevdfScheduler.relativeLag(a, listOf(a)))
        assertNull(EevdfScheduler.relativeLag(a, emptyList()))
    }

    @Test fun `an entity missing from its pool is added`() {
        val a = ent("a", vr = 0.0); val b = ent("b", vr = 10.0)
        assertEquals(rl(a, listOf(a, b)), rl(a, listOf(b)), 1e-9)
    }

    @Test fun `completed siblings are excluded and N shrinks`() {
        val a = ent("a", vr = 0.0); val b = ent("b", vr = 10.0)
        val done = ent("done", vr = 1000.0, completed = true)
        // Same as the two-sibling case: the completed one neither moves the average nor counts in N.
        assertEquals(1.0 / 3.0, rl(a, listOf(a, b, done)), 1e-9)
        // Left alone with only a completed sibling → nothing to compare against.
        assertNull(EevdfScheduler.relativeLag(a, listOf(a, done)))
    }

    @Test fun `a completed or non-fair entity has no relative lag, and non-fair siblings are ignored`() {
        val a = ent("a", vr = 0.0); val b = ent("b", vr = 10.0)
        assertNull(EevdfScheduler.relativeLag(ent("c", completed = true), listOf(a, b)))
        assertNull(EevdfScheduler.relativeLag(ent("r", cls = SchedTask.RT), listOf(a, b)))
        val rt = ent("rt", vr = 500.0, cls = SchedTask.RT)
        assertEquals(1.0 / 3.0, rl(a, listOf(a, b, rt)), 1e-9)       // the RT sibling is not in the fair pool
        assertNull(EevdfScheduler.relativeLag(a, listOf(a, rt)))     // …so a is alone
    }

    @Test fun `non-positive weight or slice gives zero, never NaN or a crash`() {
        val b = ent("b", vr = 10.0)
        assertEquals(0.0, rl(ent("zero-weight", priority = 0, vr = 0.0), listOf(b)), 0.0)
        assertEquals(0.0, rl(ent("zero-slice", slice = 0L, vr = 0.0), listOf(b)), 0.0)
        assertEquals(0.0, rl(ent("neg-weight", vr = 0.0).copy(internalWeight = -1.0), listOf(b)), 0.0)
        // every sibling at weight 0: total weight 0 must not divide by zero
        val z1 = ent("z1", priority = 0, vr = 1.0); val z2 = ent("z2", priority = 0, vr = 2.0)
        assertEquals(0.0, rl(z1, listOf(z1, z2)), 0.0)
    }

    @Test fun `inputs are never mutated`() {
        val pool = sixWith(82.0)
        val before = pool.map { it.copy() }
        val outsider = ent("outsider", vr = 3.0)
        val outsiderBefore = outsider.copy()
        EevdfScheduler.relativeLag(pool[0], pool)
        EevdfScheduler.relativeLag(outsider, pool)
        assertEquals(before, pool)
        assertEquals(outsiderBefore, outsider)
    }

    // ── Mixed pool, own level only ────────────────────────────────────────────

    @Test fun `leaf tasks and groups share one pool and a group uses its own slice`() {
        // leaf a (slice 60, vr 0) vs group g (slice 120, vr 10): avg = 5, N = 2 → half = 1
        // lag_g = (5 − 10)·4 = −20 → −20/120 · 1 = −0.1667  (its own 120 s slice, not the leaf's 60)
        // lag_a = (5 − 0)·4  = +20 →  20/60  · 1 = +0.3333
        val a = ent("a", vr = 0.0)
        val g = ent("g", group = true, slice = 120L, vr = 10.0)
        assertEquals(-1.0 / 6.0, rl(g, listOf(a, g)), 1e-9)
        assertEquals(1.0 / 3.0, rl(a, listOf(a, g)), 1e-9)
    }

    @Test fun `a group's relative lag ignores its children's level entirely`() {
        // Root pool is [a, g]. Wildly skewed children of g live one level down and are
        // simply not part of the pool the caller passes (test H2 documents the flat-pool bug).
        val a = ent("a", parent = null, vr = 10.0)
        val g = ent("g", parent = null, group = true, vr = 10.0)
        val kids = listOf(ent("k1", parent = "g", vr = 1000.0), ent("k2", parent = "g", vr = -1000.0))
        assertEquals(0.0, rl(g, listOf(a, g)), 1e-9)
        // The children's own level is independent: avg = 0, k1 is far ahead (clamped to −N/2 = −1), k2 far behind (+1).
        assertEquals(-1.0, rl(kids[0], kids), 1e-9)
        assertEquals(1.0, rl(kids[1], kids), 1e-9)
        assertTrue(rl(g, listOf(a, g)) == 0.0)   // …and still zero after looking at them
    }

    // ── Weighted average ──────────────────────────────────────────────────────

    @Test fun `the average is weighted, not a plain mean`() {
        // a: weight 4, vr 10; b: weight 1, vr 20. Weighted avg = (40 + 20)/5 = 12 (a plain mean would be 15).
        // lag_a = (12 − 10)·4 = +8  → 8/60 · 1 = +0.1333
        // lag_b = (12 − 20)·1 = −8  → −8/60 · 1 = −0.1333
        val a = ent("a", priority = 4, vr = 10.0); val b = ent("b", priority = 1, vr = 20.0)
        assertEquals(8.0 / 60.0, rl(a, listOf(a, b)), 1e-9)
        assertEquals(-8.0 / 60.0, rl(b, listOf(a, b)), 1e-9)
    }

    // ── Agreement with the real pick ──────────────────────────────────────────

    @Test fun `RL is non-negative exactly when the scheduler considers the entity eligible`() {
        val pools = listOf(
            // exact zero: the middle one sits on the average (avg of 0, 10, 20 = 10)
            listOf(ent("a", vr = 0.0), ent("b", vr = 10.0), ent("c", vr = 20.0)),
            // unequal weights and vruntimes
            listOf(ent("w1", priority = 1, vr = 3.0), ent("w2", priority = 2, vr = 17.0), ent("w3", priority = 3, vr = 9.0),
                   ent("w4", priority = 4, vr = 25.0), ent("w5", priority = 5, vr = 12.0)),
        )
        for (pool in pools) {
            val recalced = EevdfScheduler.recalculate(pool).associateBy { it.id }
            for (e in pool) {
                val lag = recalced.getValue(e.id).lag          // selectNext's eligibility test is lag >= 0
                assertEquals("${e.id}: RL sign must match eligibility", lag >= 0, rl(e, pool) >= 0.0)
            }
        }
    }
}
