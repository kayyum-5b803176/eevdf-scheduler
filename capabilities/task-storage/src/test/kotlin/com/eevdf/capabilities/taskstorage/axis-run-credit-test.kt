package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisRunCreditTest {

    private fun link(row: String, def: String? = null) = AxisRunCredit.Link(row, def)

    // ── the rule ─────────────────────────────────────────────────────────

    @Test fun `every copy in the chain credits the entry it came from, a real row credits none`() {
        val chain = listOf(link("x1.z1", "z1"), link("x1.y1", "y1"), link("x1"))
        assertEquals(listOf("z1", "y1"), AxisRunCredit.definitionsToCredit(chain))
    }

    @Test fun `a run with no copy in the chain credits no entry`() {
        assertTrue(AxisRunCredit.definitionsToCredit(listOf(link("task"), link("group"))).isEmpty())
    }

    @Test fun `an entry is credited once per run even if it appears twice`() {
        assertEquals(listOf("g"), AxisRunCredit.definitionsToCredit(listOf(link("a", "g"), link("b", "g"))))
    }

    // ── your example: tracked in real time as runs happen ────────────────

    /** The totals a run leaves behind, exactly as the repository adds them: the row, every parent, and the entry of every copy. */
    private class Totals {
        val sec = HashMap<String, Long>(); val runs = HashMap<String, Int>()
        fun add(id: String, seconds: Long) { sec[id] = (sec[id] ?: 0L) + seconds; runs[id] = (runs[id] ?: 0) + 1 }
        fun run(chain: List<AxisRunCredit.Link>, seconds: Long) {
            chain.forEach { add(it.rowId, seconds) }                                  // the row and every parent above it
            AxisRunCredit.definitionsToCredit(chain).forEach { add(it, seconds) }     // and the entry of each copy
        }
        fun min(id: String) = (sec[id] ?: 0L) / 60
        fun runCount(id: String) = runs[id] ?: 0
    }

    private fun scenario(): Totals {
        val t = Totals()
        fun y(x: String) = listOf(link("$x.y1", "y1"), link(x))
        fun z(x: String) = listOf(link("$x.z1", "z1"), link("$x.y1", "y1"), link(x))
        // x1: y1 = 10 min 3 runs, of which z1 = 5 min 2 runs
        t.run(y("x1"), 300)                      // y1 itself: 5 min, 1 run
        t.run(z("x1"), 150); t.run(z("x1"), 150) // z1: 2 runs, 5 min
        // x2: y1 = 10 min 1 run, z1 = 0
        t.run(y("x2"), 600)
        // x3: y1 = 12 min 4 runs, of which z1 = 2 min 2 runs
        t.run(y("x3"), 300); t.run(y("x3"), 300)         // y1 itself: 2 runs, 10 min
        t.run(z("x3"), 60); t.run(z("x3"), 60)           // z1: 2 runs, 2 min
        return t
    }

    @Test fun `each copy shows its own numbers - isolated`() {
        val t = scenario()
        assertEquals(10L, t.min("x1.y1")); assertEquals(3, t.runCount("x1.y1"))
        assertEquals(5L,  t.min("x1.z1")); assertEquals(2, t.runCount("x1.z1"))
        assertEquals(10L, t.min("x2.y1")); assertEquals(1, t.runCount("x2.y1"))
        assertEquals(0L,  t.min("x2.z1")); assertEquals(0, t.runCount("x2.z1"))
        assertEquals(12L, t.min("x3.y1")); assertEquals(4, t.runCount("x3.y1"))
        assertEquals(2L,  t.min("x3.z1")); assertEquals(2, t.runCount("x3.z1"))
    }

    @Test fun `the Y entry totals all its copies - 32 minutes, 8 runs`() {
        val t = scenario()
        assertEquals(32L, t.min("y1")); assertEquals(8, t.runCount("y1"))
    }

    @Test fun `the Z entry totals all its copies - 7 minutes, 4 runs`() {
        val t = scenario()
        assertEquals(7L, t.min("z1")); assertEquals(4, t.runCount("z1"))
    }

    @Test fun `a parent holds what ran inside it`() {
        val t = scenario()
        assertEquals(10L, t.min("x1")); assertEquals(3, t.runCount("x1"))
        assertEquals(12L, t.min("x3")); assertEquals(4, t.runCount("x3"))
    }

    // ── nothing a copy goes through can lower an entry ───────────────────

    @Test fun `hiding, deleting or moving a copy changes no entry total`() {
        val t = scenario()
        val before = listOf(t.min("y1"), t.min("z1"), t.runCount("y1").toLong(), t.runCount("z1").toLong())
        t.sec.remove("x1.z1"); t.runs.remove("x1.z1")        // the copy is gone — its row, not what the entries recorded
        val after = listOf(t.min("y1"), t.min("z1"), t.runCount("y1").toLong(), t.runCount("z1").toLong())
        assertEquals(before, after)
    }

    @Test fun `nothing is added when a copy is removed - entries only grow when something runs`() {
        val t = scenario()
        val y = t.sec["y1"]
        t.sec.remove("x2.y1"); t.runs.remove("x2.y1")
        assertEquals(y, t.sec["y1"])
    }

    @Test fun `a new run keeps adding to both the copy and the entry`() {
        val t = scenario()
        t.run(listOf(link("x2.z1", "z1"), link("x2.y1", "y1"), link("x2")), 180)    // 3 min, 1 run
        assertEquals(3L, t.min("x2.z1")); assertEquals(1, t.runCount("x2.z1"))
        assertEquals(13L, t.min("x2.y1")); assertEquals(2, t.runCount("x2.y1"))
        assertEquals(35L, t.min("y1")); assertEquals(9, t.runCount("y1"))
        assertEquals(10L, t.min("z1")); assertEquals(5, t.runCount("z1"))
        assertFalse(t.min("x1") != 10L)                                              // other places unchanged
    }
}
