package com.eevdf.capabilities.timercard

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * The card above the task list — the running timer, an expired alarm, a wake asking to preempt —
 * drawn by ONE capability and described by everyone else over the bus.
 *
 * It owns the card's view ([TimerCardView]), the stack of cards waiting to be shown
 * ([CardQueue]), the countdown / count-up that ticks on its own (so the bus carries no
 * once-a-second traffic) and the 7-segment status strip. It knows nothing about tasks, timers,
 * alarms or wakes: a publisher says "show this card" ([Topics.CARD_SHOW]), a button press comes
 * back as [Topics.CARD_ACTION]. See [TimerCardController].
 */
object TimerCardManifest : CapabilityManifest {
    override val capabilityId = "timer-card"
    override val publishes: Set<Topic<*>> = setOf(Topics.CARD_ACTION)
    override val subscribes: Set<Topic<*>> = setOf(Topics.CARD_SHOW, Topics.CARD_HIDE, Topics.CARD_USER_HIDE)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // Unavailable → no card is drawn and presses are not delivered. Timers still run, alarms still
        // ring (their sound, vibration and notification do not go through this card) and a wake still
        // waits; the person just has no card to see or press until it is attached again.
    }
}
