package com.eevdf.capabilities.timercard

import com.eevdf.kernel.eventbus.CardAction
import com.eevdf.kernel.eventbus.CardSpec
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The state behind the card, and this capability's ONLY bus wiring.
 *
 * It lives for the whole process (built at app start, like every bus subscriber), so it hears every
 * card whether or not a screen exists: a card published while no activity is open is waiting when
 * one opens. The [TimerCardView] only observes [visible] and reports presses to [press] — rotating
 * the screen or reopening the app loses nothing.
 *
 * A button marked `closeOnPress` takes its card off the screen HERE, before the press is announced,
 * so a card can never stay up because its owner was slow to answer or never did.
 */
class TimerCardController(private val bus: EventBus) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val queue = CardQueue()
    private var userHidden = false

    private val _visible = MutableStateFlow<CardSpec?>(null)

    /** The card to draw right now, or null for none. */
    val visible: StateFlow<CardSpec?> = _visible

    init {
        bus.getLast(Topics.CARD_USER_HIDE)?.let { userHidden = it }
        bus.subscribe(Topics.CARD_SHOW, CAPABILITY_ID) { spec -> update { queue.put(spec) } }
        bus.subscribe(Topics.CARD_HIDE, CAPABILITY_ID) { id -> update { queue.remove(id) } }
        bus.subscribe(Topics.CARD_USER_HIDE, CAPABILITY_ID) { hidden -> update { userHidden = hidden } }
        update { }
    }

    /**
     * A button of card [cardId] was pressed ([long] = held). Announces it as [Topics.CARD_ACTION] —
     * for a hold, with the button's `longPressId` — and, for a short press of a `closeOnPress`
     * button, removes the card first. Ignored when the card or button is gone or a hold has no id.
     */
    fun press(cardId: String, buttonId: String, long: Boolean = false) {
        val actionId: String
        synchronized(lock) {
            val button = queue.get(cardId)?.buttons?.firstOrNull { it.id == buttonId } ?: return
            actionId = if (long) (button.longPressId ?: return) else button.id
            if (!long && button.closeOnPress) { queue.remove(cardId); _visible.value = queue.visible(userHidden) }
        }
        scope.launch { bus.publish(Topics.CARD_ACTION, CardAction(cardId, actionId, long), CAPABILITY_ID) }
    }

    private inline fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            _visible.value = queue.visible(userHidden)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "timer-card"
    }
}
