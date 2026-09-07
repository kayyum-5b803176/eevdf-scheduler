package com.eevdf.capabilities.countdowntimer

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object CountdownTimerManifest : CapabilityManifest {
    override val capabilityId = "countdown-timer"
    override val publishes: Set<Topic<*>> = setOf(Topics.TIMER_EXPIRED)
    override val subscribes: Set<Topic<*>> = setOf(Topics.BACKUP_EXPORT_REQUESTED, Topics.BACKUP_IMPORT_REQUESTED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // A missed timer.expired means alarm-ringer's diagnostic-only
        // TimerExpiryLog (see TimerExpiryHandler) doesn't record this
        // expiry — nothing user-facing. The actual in-app transition
        // (updating the task card, crediting vruntime, deciding whether to
        // ring) is task-list-screen's own direct LiveData observation of
        // this same TimerEngine instance, not a bus subscription — so it is
        // unaffected by this capability being unavailable. alarm-ringer's
        // AlarmManager-backed path, scheduled at timer-start time, is also
        // unaffected — it's the doze-immune path and does not depend on any
        // in-process bus delivery at all.
    }
}

/**
 * RESOLVED (was fully dead — no `publish()` call anywhere for
 * `Topics.TIMER_EXPIRED` despite this manifest declaring it since Phase 1):
 * [TimerEngine] now publishes it for real, from both `onFinish()` (live
 * expiry) and `restoreFromDb()` (expired while the app was dead).
 *
 * WHY THE PUBLISH SITE LIVES IN A CLASS, NOT A STANDALONE HANDLER LIKE THIS
 * CAPABILITY'S SIBLINGS
 * ---------------------------------------------------------------------------
 * Every other capability's bus wiring in this codebase is a small
 * `XHandler(context, bus)` class, constructed once at app startup (see
 * `BackupCheckpointHandler`, `AlarmCommandHandler`, etc.). `TimerEngine`
 * can't follow that shape: it isn't a singleton — it's instantiated directly
 * inside `task-list-screen`'s `TaskViewModel` (`internal val timerEngine =
 * TimerEngine(bus)`), which is itself a pre-existing architectural reality
 * this fix doesn't attempt to unwind. Passing `bus` into the constructor
 * (optional, defaulting to null, so `TimerEngine()` still works anywhere
 * else it might be constructed, e.g. tests) keeps the publish call inside
 * the class that actually owns the "the countdown hit zero" fact, which is
 * what matters for `capabilityId` attribution — regardless of which
 * capability happens to construct the instance.
 */
