package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisCopyTest {

    private val places = listOf("type5", "type6", "type7")

    @Test fun `a top-level entry can be copied into every place`() {
        assertEquals(places, AxisCopy.eligibleDoors(places, parentId = null, copiesByDoor = emptyMap()))
    }

    @Test fun `an entry inside a group can only go where a copy of the group is`() {
        val copies = mapOf("type5" to setOf("g"), "type7" to setOf("g", "other"))
        assertEquals(listOf("type5", "type7"), AxisCopy.eligibleDoors(places, parentId = "g", copiesByDoor = copies))
    }

    @Test fun `no place has the group - nothing to offer`() {
        assertTrue(AxisCopy.eligibleDoors(places, parentId = "g", copiesByDoor = mapOf("type5" to setOf("x"))).isEmpty())
    }

    @Test fun `no places at all - nothing to offer`() {
        assertTrue(AxisCopy.eligibleDoors(emptyList(), null, emptyMap()).isEmpty())
    }

    @Test fun `copying an entry is one plus per place, at that place's door`() {
        val rules = AxisCopy.entryRules("hello", listOf("type5", "type6"))
        assertEquals(listOf(AxisAcl("type5", "hello", false), AxisAcl("type6", "hello", false)), rules)
        assertTrue(rules.none { it.hidden })
    }

    @Test fun `copying entries into a new place is one plus per entry at the new place's own door`() {
        val rules = AxisCopy.placeRules("type8", listOf("user1", "user2", "team"))
        assertEquals(setOf("user1", "user2", "team"), rules.map { it.childKey }.toSet())
        assertTrue(rules.all { it.scopeKey == "type8" && !it.hidden })
    }

    @Test fun `an offer counts its rules`() {
        val offer = AxisCopy.Offer(AxisCopy.Kind.ENTRY, "hello", Axes.Y, AxisCopy.entryRules("hello", places))
        assertEquals(3, offer.count)
        assertEquals(0, AxisCopy.Offer(AxisCopy.Kind.PLACE, "p", Axes.Y, emptyList()).count)
    }

    @Test fun `accepting an offer shows the entry only where it was offered`() {
        // after Copy: a '+' at type5 and type6 and nothing at type7; the default (hide new) hides it everywhere else
        val rulesAt = { door: String ->
            AxisCopy.entryRules("hello", listOf("type5", "type6")).filter { it.scopeKey == door }.associate { it.childKey to it.hidden }
        }
        val all = listOf(Task(id = "hello", name = "hello", priority = 1, timeSliceSeconds = 60, axisId = Axes.Y))
        fun shown(door: String) = AxisAclRules.includedEntries(Axes.Y, all, rulesAt(door), hideNew = true).isNotEmpty()
        assertTrue(shown("type5")); assertTrue(shown("type6")); assertFalse(shown("type7"))
    }

    @Test fun `declining copies nothing - hide new keeps a new entry off every door`() {
        val all = listOf(Task(id = "hello", name = "hello", priority = 1, timeSliceSeconds = 60, axisId = Axes.Y))
        for (door in places) assertTrue(AxisAclRules.includedEntries(Axes.Y, all, emptyMap(), hideNew = true).isEmpty())
    }
}
