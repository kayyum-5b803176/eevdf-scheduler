package com.eevdf.capabilities.timercard

import com.eevdf.kernel.eventbus.CardButton
import com.eevdf.kernel.eventbus.CardKind
import com.eevdf.kernel.eventbus.CardPriority
import com.eevdf.kernel.eventbus.CardSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card stack, the card's clock and the status strip's arithmetic — all pure. */
class TimerCardTest {

    private fun card(id: String, kind: CardKind, priority: Int, ignoresUserHide: Boolean = false, title: String = id) =
        CardSpec(id = id, kind = kind, priority = priority, title = title, ignoresUserHide = ignoresUserHide)

    private val timer = card("timer", CardKind.TIMER, CardPriority.TIMER)
    private val wake  = card("wake", CardKind.WAKE, CardPriority.WAKE, ignoresUserHide = true)
    private val alarm = card("alarm", CardKind.ALARM, CardPriority.ALARM, ignoresUserHide = true)

    // ── CardQueue ─────────────────────────────────────────────────────────────

    @Test fun `an empty queue shows nothing`() {
        assertEquals(null, CardQueue().visible(userHidden = false))
    }

    @Test fun `the highest priority is on screen - alarm over wake over timer`() {
        val q = CardQueue().apply { put(timer); put(wake); put(alarm) }
        assertEquals("alarm", q.visible(false)?.id)
        q.remove("alarm")
        assertEquals("wake", q.visible(false)?.id)
        q.remove("wake")
        assertEquals("timer", q.visible(false)?.id)
    }

    @Test fun `arrival order does not matter for priority`() {
        val q = CardQueue().apply { put(alarm); put(timer); put(wake) }
        assertEquals("alarm", q.visible(false)?.id)
    }

    @Test fun `a lower card waits behind a higher one and comes back when it is hidden`() {
        val q = CardQueue().apply { put(timer); put(wake) }
        assertEquals("wake", q.visible(false)?.id)
        assertEquals(true, q.remove("wake"))
        assertEquals("timer", q.visible(false)?.id)
    }

    @Test fun `publishing the same id replaces the card`() {
        val q = CardQueue().apply { put(card("timer", CardKind.TIMER, 100, title = "old")); put(card("timer", CardKind.TIMER, 100, title = "new")) }
        assertEquals(1, q.size)
        assertEquals("new", q.visible(false)?.title)
    }

    @Test fun `equal priority goes to the card that arrived first, and replacing keeps its place`() {
        val a = card("a", CardKind.WAKE, 200)
        val b = card("b", CardKind.WAKE, 200)
        val q = CardQueue().apply { put(a); put(b) }
        assertEquals("a", q.visible(false)?.id)
        q.put(a.copy(title = "a again"))
        assertEquals("a", q.visible(false)?.id)
    }

    @Test fun `removing a card that is not there is harmless`() {
        val q = CardQueue().apply { put(timer) }
        assertEquals(false, q.remove("nope"))
        assertEquals("timer", q.visible(false)?.id)
    }

    @Test fun `a timer card the person hid by hand is not shown`() {
        val q = CardQueue().apply { put(timer) }
        assertEquals(null, q.visible(userHidden = true))
        assertEquals("timer", q.visible(userHidden = false)?.id)
    }

    @Test fun `alarm and wake cards ignore the hand-hide`() {
        val q = CardQueue().apply { put(timer); put(wake) }
        assertEquals("wake", q.visible(userHidden = true)?.id)
        q.put(alarm)
        assertEquals("alarm", q.visible(userHidden = true)?.id)
    }

    @Test fun `a timer card may opt out of the hand-hide`() {
        val q = CardQueue().apply { put(timer.copy(ignoresUserHide = true)) }
        assertEquals("timer", q.visible(userHidden = true)?.id)
    }

    @Test fun `buttons travel with the card`() {
        val b = CardButton("stop", "Stop", closeOnPress = true)
        val q = CardQueue().apply { put(alarm.copy(buttons = listOf(b))) }
        assertEquals(listOf(b), q.get("alarm")?.buttons)
    }

    // ── CardClock ─────────────────────────────────────────────────────────────

    @Test fun `a countdown reads mm ss under an hour and h mm ss from an hour`() {
        assertEquals("00:00", CardClock.format(0))
        assertEquals("00:09", CardClock.format(9))
        assertEquals("25:00", CardClock.format(25 * 60L))
        assertEquals("59:59", CardClock.format(3599))
        assertEquals("1:00:00", CardClock.format(3600))
        assertEquals("2:03:04", CardClock.format(2 * 3600L + 3 * 60 + 4))
        assertEquals("00:00", CardClock.format(-5))
    }

    @Test fun `the overrun counter reads like the Clock app`() {
        assertEquals("0:00", CardClock.formatElapsed(0))
        assertEquals("0:05", CardClock.formatElapsed(5))
        assertEquals("1:23", CardClock.formatElapsed(83))
        assertEquals("59:59", CardClock.formatElapsed(3599))
        assertEquals("1:02:34", CardClock.formatElapsed(3600L + 120 + 34))
    }

    @Test fun `remaining time rounds up and never goes negative`() {
        assertEquals(10L, CardClock.remainingSeconds(10_000L, 0L))
        assertEquals(10L, CardClock.remainingSeconds(10_000L, 1L))      // 9.999 s left still reads 10
        assertEquals(1L, CardClock.remainingSeconds(10_000L, 9_000L))
        assertEquals(1L, CardClock.remainingSeconds(10_000L, 9_999L))
        assertEquals(0L, CardClock.remainingSeconds(10_000L, 10_000L))
        assertEquals(0L, CardClock.remainingSeconds(10_000L, 50_000L))
    }

    @Test fun `elapsed time counts whole seconds and never goes negative`() {
        assertEquals(0L, CardClock.elapsedSeconds(5_000L, 5_000L))
        assertEquals(0L, CardClock.elapsedSeconds(5_000L, 5_999L))
        assertEquals(3L, CardClock.elapsedSeconds(5_000L, 8_001L))
        assertEquals(0L, CardClock.elapsedSeconds(9_000L, 5_000L))
    }

    // ── Status strip ──────────────────────────────────────────────────────────

    @Test fun `the strip always has seven segments`() {
        assertEquals(7, StripMath.SEGMENT_COUNT)
    }

    @Test fun `one state colours every segment the same`() {
        assertTrue((0 until 7).all { StripMath.pingPongIndex(it, 1) == 0 })
        assertTrue((0 until 7).all { StripMath.pingPongIndex(it, 0) == 0 })
    }

    @Test fun `two states alternate`() {
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), (0 until 7).map { StripMath.pingPongIndex(it, 2) })
    }

    @Test fun `three states bounce outermost to innermost and back`() {
        assertEquals(listOf(0, 1, 2, 1, 0, 1, 2), (0 until 7).map { StripMath.pingPongIndex(it, 3) })
    }

    @Test fun `the ping-pong index never leaves its range`() {
        for (n in 1..5) assertTrue((0 until 7).all { StripMath.pingPongIndex(it, n) in 0 until n })
        assertFalse(StripMath.pingPongIndex(3, 3) == 0)
    }
}
