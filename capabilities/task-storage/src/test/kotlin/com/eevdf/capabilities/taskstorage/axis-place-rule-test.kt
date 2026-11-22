package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisPlaceRuleTest {

    private fun like(stored: Boolean?, container: Boolean = false, untouchedDefIsGroup: Boolean? = null, shown: Boolean = false) =
        AxisPlaceRule.isPlaceLike(stored, container, untouchedDefIsGroup, shown)

    // The case from the report:  x1 > y1 (real)   x2 > y1 (never touched)   x3 > y1 (real)

    @Test fun `x1 and x3 - a stored leaf is a place`() {
        assertTrue(like(stored = false))
    }

    @Test fun `x2 - an UNTOUCHED copy of a leaf definition is a place even though the screen draws it as a group`() {
        assertTrue(like(stored = null, untouchedDefIsGroup = false, shown = true))
    }

    @Test fun `x2 does not change when it is touched - the answer is the same before and after`() {
        val before = like(stored = null, untouchedDefIsGroup = false, shown = true)      // never run
        val after  = like(stored = false, container = false, untouchedDefIsGroup = null, shown = false)  // now a stored leaf
        assertTrue(before); assertTrue(after); assertTrue(before == after)
    }

    // ── what is not a place ──────────────────────────────────────────────

    @Test fun `an untouched copy of a GROUP definition is not a place`() {
        assertFalse(like(stored = null, untouchedDefIsGroup = true, shown = true))
    }

    @Test fun `a stored group is not a place unless it is a container`() {
        assertFalse(like(stored = true))
        assertTrue(like(stored = true, container = true))
    }

    @Test fun `the stored row beats how it is drawn`() {
        assertTrue(like(stored = false, shown = true))      // drawn as a group, stored as a leaf
        assertFalse(like(stored = true, shown = false))     // drawn as a leaf, stored as a group
    }

    @Test fun `with nothing better known, what was drawn decides`() {
        assertTrue(like(stored = null, untouchedDefIsGroup = null, shown = false))
        assertFalse(like(stored = null, untouchedDefIsGroup = null, shown = true))
        assertTrue(like(stored = null, untouchedDefIsGroup = null, shown = true, container = true))
    }
}
