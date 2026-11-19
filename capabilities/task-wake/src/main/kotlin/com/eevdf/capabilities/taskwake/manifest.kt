package com.eevdf.capabilities.taskwake

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * Scheduled wake: a task can be set to "wake" at a date/time (alarm) or after a duration
 * (timer); when it comes due it asks to preempt the current task, by scheduler class.
 *
 * Everything this capability says to the rest of the app goes through the bus — see
 * [WakeCoordinator]. No other capability imports anything from here, and this one imports
 * none of theirs: it listens to which task is current, ASKS task-list-screen to select a task
 * (`scheduler.task-select-requested`), publishes the preempt card for timer-card to show in
 * the timer card's place (`card.show`, answered by `card.action`), and asks sound and vibration to ring a profile
 * (`wake.alert-requested`). Its two dialogs (prompt, state popup) live in its own
 * [WakeHostActivity], so no screen needs them.
 */
object TaskWakeManifest : CapabilityManifest {
    override val capabilityId = "task-wake"
    override val publishes: Set<Topic<*>> = setOf(
        Topics.WAKE_ARMED_CHANGED,
        Topics.CARD_SHOW,
        Topics.CARD_HIDE,
        Topics.TASK_SELECT_REQUESTED,
        Topics.WAKE_ALERT_REQUESTED,
        Topics.WAKE_ALERT_STOP_REQUESTED,
    )
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.CURRENT_TASK_CHANGED,
        Topics.APP_FOREGROUND_CHANGED,
        Topics.WAKE_EDIT_REQUESTED,
        Topics.WAKE_STATE_REQUESTED,
        Topics.CARD_ACTION,
        Topics.WAKE_PROFILE_SET,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // Unavailable → the wake icon's taps and the profile choice are dropped, and an armed
        // wake does nothing when it comes due. The AlarmManager entry belongs to the OS and
        // survives; the wake is picked up again the next time this capability is attached.
    }
}
