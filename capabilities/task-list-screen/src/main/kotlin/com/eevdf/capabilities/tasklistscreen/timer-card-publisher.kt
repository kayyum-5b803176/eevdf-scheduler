package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.countdowntimer.NextButtonState
import com.eevdf.capabilities.countdowntimer.TimerCardAction
import com.eevdf.capabilities.taskstorage.IdentityRules
import com.eevdf.capabilities.timercard.CardClock
import com.eevdf.kernel.eventbus.CardAction
import com.eevdf.kernel.eventbus.CardButton
import com.eevdf.kernel.eventbus.CardKind
import com.eevdf.kernel.eventbus.CardPriority
import com.eevdf.kernel.eventbus.CardSpec
import com.eevdf.kernel.eventbus.CardStrip
import com.eevdf.kernel.eventbus.CardTint
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Describes the card above the list to the `timer-card` capability — and carries out its buttons.
 *
 * The timer / notice / alarm state machine stays exactly where it was ([TaskViewModel]: what the
 * card says, what each button does); this class only turns that state into card specs and
 * publishes them over the bus, and turns a pressed button back into the same ViewModel call the
 * old button listener made. It draws nothing and touches no view.
 *
 *  • The running / paused timer is the TIMER card (`timer`): name, priority, countdown, the 7-segment
 *    status strip, Next · Start/Pause · INT.
 *  • An expired timer is the ALARM card (`alarm`): the expired task, the overrun counter, Stop. It
 *    outranks the timer card, which waits behind it.
 *
 * Nothing is published per second: the countdown and the overrun counter are an END / START instant
 * in the spec, and the card ticks them itself. A spec is only republished when it really changed
 * (a clock instant must move by more than [CLOCK_TOLERANCE_MS] to count), so the once-a-second
 * LiveData ticks that drive [refresh] cost nothing on the bus.
 */
internal class TimerCardPublisher(private val activity: MainActivity) {

    private val vm get() = activity.viewModel

    private var strip = CardStrip()

    /** The cards this publisher has put up, by id — what it last published. */
    private val published = HashMap<String, CardSpec>()

    /** The first sync after a (re)start also takes down cards an earlier screen left behind. */
    private var firstSync = true

    /** Begins observing the state the card describes and listening for its buttons. */
    fun start() {
        activeOwner = this
        // One subscription set for this id however many times the screen is recreated.
        vm.bus.unsubscribeAll(SUBSCRIBER_ID)
        vm.bus.subscribe(Topics.CARD_ACTION, SUBSCRIBER_ID) { action -> withContext(Dispatchers.Main) { onAction(action) } }

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (activeOwner === this@TimerCardPublisher) {
                    vm.bus.unsubscribeAll(SUBSCRIBER_ID)
                    activeOwner = null
                }
            }
        })

        vm.timerCardAction.observe(activity)  { refresh() }
        vm.currentTask.observe(activity)      { refresh() }
        vm.timerSeconds.observe(activity)     { refresh() }
        vm.noticePhase.observe(activity)      { refresh() }
        vm.nextButtonState.observe(activity)  { refresh() }
        vm.intButtonState.observe(activity)   { refresh() }

        publish { vm.bus.publish(Topics.CARD_USER_HIDE, vm.getCardManuallyHidden(), CAPABILITY_ID) }
        refresh()
    }

    /** The 7-segment strip changed (quota exhaustion, notice delay / wait) — see MainActivity.refreshPhaseStatusBar. */
    fun setStrip(newStrip: CardStrip) {
        if (newStrip == strip) return
        strip = newStrip
        refresh()
    }

    /** Re-describes the card from the current state and publishes whatever changed. */
    fun refresh() {
        val action = vm.timerCardAction.value ?: TimerCardAction.Hidden
        val now = vm.clock.nowEpochMillis()
        val timer = if (action is TimerCardAction.Hidden || action is TimerCardAction.Expired) null else buildTimer(action, now)
        val alarm = (action as? TimerCardAction.Expired)?.let { buildAlarm(it, now) }
        sync(TIMER_ID, timer)
        sync(ALARM_ID, alarm)
        firstSync = false
    }

    // ── Describing the card ───────────────────────────────────────────────────

    private fun buildTimer(action: TimerCardAction, now: Long): CardSpec {
        val task = vm.currentTask.value
        val phase = vm.noticePhase.value
        val seconds = vm.timerSeconds.value ?: task?.remainingSeconds ?: 0L

        // A notice's delay / wait counts its own phase down; otherwise the timer's remaining time —
        // running (Pause shown) → a countdown the card ticks itself; paused → fixed text.
        val endEpochMs: Long
        val text: String
        when {
            phase is NoticePhase.Delay      -> { endEpochMs = now + phase.remainingSecs * 1000L; text = "" }
            phase is NoticePhase.Wait       -> { endEpochMs = now + phase.remainingSecs * 1000L; text = "" }
            action is TimerCardAction.Pause -> { endEpochMs = now + seconds * 1000L;             text = "" }
            else                            -> { endEpochMs = 0L;                                text = CardClock.format(seconds) }
        }

        val next = vm.nextButtonState.value ?: NextButtonState.Next
        val int  = vm.intButtonState.value
        return CardSpec(
            id = TIMER_ID, kind = CardKind.TIMER, priority = CardPriority.TIMER,
            title = task?.name.orEmpty(),
            subtitle = task?.let { "Priority ${it.priority} · ${IdentityRules.display(it.identity)}" }.orEmpty(),
            clockEpochMs = endEpochMs, clockText = text,
            strip = strip,
            buttons = listOf(
                CardButton("next", next.label, longPressId = "toggleNext"),
                CardButton("startPause", action.label, enabled = action.enabled, tint = tintOf(action), longPressId = "pauseDeselect"),
                CardButton("int", int?.label ?: "INT-A", textColorHex = int?.textColorHex?.takeIf { it.isNotEmpty() }, longPressId = "toggleInt"),
            ),
        )
    }

    private fun buildAlarm(expired: TimerCardAction.Expired, now: Long) = CardSpec(
        id = ALARM_ID, kind = CardKind.ALARM, priority = CardPriority.ALARM,
        title = expired.taskName,
        subtitle = "Time slice complete · tap Stop to dismiss",
        clockEpochMs = now - expired.elapsedSeconds * 1000L,     // the overrun counter counts up from here
        buttons = listOf(CardButton("stop", "Stop")),
        ignoresUserHide = true,                                   // an expired alarm must be seen, even on a hand-closed card
    )

    private fun tintOf(action: TimerCardAction): CardTint = when (action) {
        is TimerCardAction.Pause, is TimerCardAction.Cancel -> CardTint.YELLOW
        is TimerCardAction.Expired                         -> CardTint.RED
        else                                                -> CardTint.GREEN
    }

    // ── Publishing only what changed ──────────────────────────────────────────

    private fun sync(id: String, spec: CardSpec?) {
        val shown = published[id]
        if (spec == null) {
            if (shown != null || firstSync) {
                published.remove(id)
                publish { vm.bus.publish(Topics.CARD_HIDE, id, CAPABILITY_ID) }
            }
            return
        }
        if (shown != null && sameCard(shown, spec)) return
        published[id] = spec
        publish { vm.bus.publish(Topics.CARD_SHOW, spec, CAPABILITY_ID) }
    }

    /** Equal, allowing the clock instant to differ by a little: the card ticks it itself, so jitter is not news. */
    private fun sameCard(a: CardSpec, b: CardSpec): Boolean {
        if ((a.clockEpochMs == 0L) != (b.clockEpochMs == 0L)) return false
        return a.copy(clockEpochMs = b.clockEpochMs) == b && abs(a.clockEpochMs - b.clockEpochMs) <= CLOCK_TOLERANCE_MS
    }

    private fun publish(block: suspend () -> Unit) {
        activity.lifecycleScope.launch { block() }
    }

    // ── Carrying out the buttons — the same calls the old button listeners made ──

    private fun onAction(action: CardAction) {
        when (action.cardId) {
            TIMER_ID -> { activity.haptic(activity.cardTimer); onTimerAction(action.buttonId) }
            ALARM_ID -> { activity.haptic(activity.cardTimer); if (action.buttonId == "stop") vm.stopAlarmSound() }
            else     -> Unit      // another capability's card (the wake card answers to task-wake)
        }
    }

    private fun onTimerAction(buttonId: String) {
        when (buttonId) {
            // Next / Auto: tap performs whichever action is armed; hold flips which one is armed.
            "next" -> when (vm.nextButtonState.value) {
                is NextButtonState.Auto -> vm.triggerAutoJump(onQueueTab = activity.currentTab == 0)
                else                    -> vm.nextSibling(onQueueTab = activity.currentTab == 0)
            }
            "toggleNext" -> vm.toggleNextButtonMode()

            // Start / Pause / Cancel — dispatched from timerCardAction, the already-settled value, NEVER
            // from timerRunning or noticePhase read at click time (two LiveData read at tap time was the
            // root cause of "button stuck at Start").
            "startPause" -> when (vm.timerCardAction.value) {
                TimerCardAction.Start  -> vm.startTimer()
                TimerCardAction.Pause  -> vm.pauseTimer()
                TimerCardAction.Cancel -> vm.cancelNotice()
                else                   -> Unit
            }
            // Hold Start/Pause → pause the running task, then close the card by DESELECTING it.
            "pauseDeselect" -> if (vm.currentTask.value != null) vm.pauseAndDeselect()

            "int"       -> vm.jumpToInterrupt()
            "toggleInt" -> vm.toggleInterruptSlot()
            else        -> Unit
        }
    }

    companion object {
        const val TIMER_ID = "timer"
        const val ALARM_ID = "alarm"
        private const val CAPABILITY_ID = "task-list-screen"
        private const val SUBSCRIBER_ID = "task-list-screen.card"

        /** A clock instant must move by more than this to be worth republishing. */
        private const val CLOCK_TOLERANCE_MS = 700L

        /** The publisher currently listening, so a recreated screen does not leave two. */
        @Volatile private var activeOwner: TimerCardPublisher? = null
    }
}
