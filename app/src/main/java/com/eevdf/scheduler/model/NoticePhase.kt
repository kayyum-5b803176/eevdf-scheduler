package com.eevdf.scheduler.model

/**
 * Models every legal state of a NOTIFICATION-type task's state machine.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *
 *   Idle ──start()──▶ Delay ──onDelayFinish()──▶ Execute ──onExecuteFinish()──▶ Wait
 *    ▲                  │                            │                           │
 *    │              cancel()                     pause()                     cancel()
 *    │                  │                            │                           │
 *    └──────────────────┘                            └───────── Idle             │
 *                                                                                │
 *                                              ◀──onWaitFinish() if repeat───────┘
 *                                              ──onWaitFinish() if done──▶ Expired
 *
 * ── Why sealed over boolean flags ─────────────────────────────────────────────
 *
 *  The UI previously checked _delayRunning + _waitRunning to decide which label
 *  to show.  Impossible states (both true) were representable, and every new
 *  feature had to know the combination rules.
 *
 *  A sealed class makes impossible states unrepresentable and exhaustive when()
 *  ensures every UI consumer handles every phase.
 *
 * ── Button label contract ─────────────────────────────────────────────────────
 *
 *  Idle    → show "Start"   (fresh run from delay)
 *  Delay   → show "Cancel"  (abort delay, return to Idle)
 *  Execute → show "Pause"   (pause execute, return to Idle with slice preserved)
 *  Wait    → show "Cancel"  (abort wait cycle, return to Idle)
 *  Expired → show nothing   (alarm banner takes over)
 */
sealed class NoticePhase {

    /** Not started, or after Cancel/Pause. "Start" button shown. */
    object Idle : NoticePhase()

    /**
     * Delay countdown before the first execute cycle.
     * [remainingSecs] drives the delay countdown label.
     * "Cancel" button shown.
     */
    data class Delay(val remainingSecs: Long) : NoticePhase()

    /**
     * Execute countdown — the actual task timer.
     * [iteration] = 0-based repeat index (0 = first run).
     * "Pause" button shown.
     */
    data class Execute(val iteration: Int) : NoticePhase()

    /**
     * Rest/wait countdown between execute cycles.
     * [remainingSecs] drives the wait countdown label.
     * [iteration] = which repeat cycle just finished.
     * "Cancel" button shown.
     */
    data class Wait(val remainingSecs: Long, val iteration: Int) : NoticePhase()

    /**
     * All cycles complete. Alarm is ringing.
     * No timer button shown — alarm banner takes over.
     */
    object Expired : NoticePhase()
}
