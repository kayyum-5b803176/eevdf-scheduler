package com.eevdf.core.scheduler.timer

/**
 * Pure timer finite-state machine.
 *
 * The reference `TimerEngine` mixed three responsibilities into one class that
 * imported `android.os.CountDownTimer` and `androidx.lifecycle.LiveData`:
 *   1. the tick *source* (CountDownTimer),
 *   2. the observable *outputs* (LiveData), and
 *   3. the *rules* (when a session expires, how remaining time is computed).
 *
 * Only (3) is business logic. Here it is a pure reducer: feed it the current
 * [TimerState] and a [TimerEvent], get back a new [TimerState] plus any
 * [TimerEffect]s the platform driver should perform. The Android tick source
 * and the LiveData/Flow exposure live in platform/, and call this reducer.
 *
 * This makes expiry/restore/quota logic unit-testable with a FixedClock and no
 * Android runtime at all.
 */

data class TimerState(
    val taskId: String? = null,
    val running: Boolean = false,
    val remainingSeconds: Long = 0L,
    val accumulatedMs: Long = 0L,
)

sealed interface TimerEvent {
    data class Start(val taskId: String, val sliceSeconds: Long) : TimerEvent
    data class Tick(val elapsedMs: Long) : TimerEvent
    data object Pause : TimerEvent
    data object Clear : TimerEvent
}

sealed interface TimerEffect {
    data class Expired(val taskId: String, val ranSeconds: Long) : TimerEffect
}

object TimerEngine {
    /** Pure transition. Returns the next state and any effects to perform. */
    fun reduce(state: TimerState, event: TimerEvent): Pair<TimerState, List<TimerEffect>> {
        return when (event) {
            is TimerEvent.Start ->
                TimerState(event.taskId, running = true, remainingSeconds = event.sliceSeconds) to emptyList()

            is TimerEvent.Tick -> {
                if (!state.running) return state to emptyList()
                val acc = state.accumulatedMs + event.elapsedMs
                val ticked = acc / 1_000L
                val remaining = (state.remainingSeconds - ticked).coerceAtLeast(0L)
                val next = state.copy(remainingSeconds = remaining, accumulatedMs = acc % 1_000L)
                if (remaining == 0L && state.taskId != null) {
                    next.copy(running = false) to listOf(TimerEffect.Expired(state.taskId, ranSeconds = 0L))
                } else next to emptyList()
            }

            TimerEvent.Pause -> state.copy(running = false) to emptyList()
            TimerEvent.Clear -> TimerState() to emptyList()
        }
    }
}
