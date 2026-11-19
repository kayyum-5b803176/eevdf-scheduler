package com.eevdf.capabilities.timercard

import com.eevdf.kernel.eventbus.CardKind
import com.eevdf.kernel.eventbus.CardSpec

/**
 * The cards waiting to be shown, and which one is on screen. Pure — no Android, no bus.
 *
 *  • Publishing a card whose id is already queued REPLACES it, keeping its place in line.
 *  • The card on screen is the one with the highest priority; of equal priority, the one that
 *    arrived first.
 *  • While the person has hidden the timer card by hand, a TIMER card is skipped (unless it says
 *    [CardSpec.ignoresUserHide]); alarm and wake cards are never skipped.
 */
class CardQueue {

    // A LinkedHashMap keeps arrival order, and re-putting an existing key does not move it.
    private val cards = LinkedHashMap<String, CardSpec>()

    fun put(spec: CardSpec) { cards[spec.id] = spec }

    /** @return true when a card with that id was there. */
    fun remove(id: String): Boolean = cards.remove(id) != null

    fun get(id: String): CardSpec? = cards[id]

    val size: Int get() = cards.size

    /** The card to draw, or null when there is none (or only a hand-hidden timer card). */
    fun visible(userHidden: Boolean): CardSpec? {
        var best: CardSpec? = null
        for (card in cards.values) {
            if (userHidden && card.kind == CardKind.TIMER && !card.ignoresUserHide) continue
            if (best == null || card.priority > best.priority) best = card
        }
        return best
    }
}
