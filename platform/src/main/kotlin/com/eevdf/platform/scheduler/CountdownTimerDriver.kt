package com.eevdf.platform.scheduler

import android.os.CountDownTimer
import com.eevdf.core.scheduler.timer.TimerEffect
import com.eevdf.core.scheduler.timer.TimerEngine
import com.eevdf.core.scheduler.timer.TimerEvent
import com.eevdf.core.scheduler.timer.TimerState

/**
 * The Android tick *source*. All timing *rules* live in the pure
 * [TimerEngine] reducer; this class only turns CountDownTimer callbacks into
 * [TimerEvent.Tick]s and forwards reducer [TimerEffect]s to a listener.
 *
 * The reference `TimerEngine` fused the tick source, the LiveData outputs, and
 * the rules into one Android-coupled class. Splitting them makes the rules unit
 * testable and lets the UI choose how to observe (LiveData/Flow) in features/.
 */
class CountdownTimerDriver(
    private val onState: (TimerState) -> Unit,
    private val onEffect: (TimerEffect) -> Unit,
) {
    private var state = TimerState()
    private var ticker: CountDownTimer? = null

    fun start(taskId: String, sliceSeconds: Long) {
        dispatch(TimerEvent.Start(taskId, sliceSeconds))
        ticker?.cancel()
        ticker = object : CountDownTimer(sliceSeconds * 1_000L, 1_000L) {
            override fun onTick(msUntilFinished: Long) = dispatch(TimerEvent.Tick(1_000L))
            override fun onFinish() = dispatch(TimerEvent.Tick(1_000L))
        }.also { it.start() }
    }

    fun pause() { ticker?.cancel(); dispatch(TimerEvent.Pause) }
    fun clear() { ticker?.cancel(); ticker = null; dispatch(TimerEvent.Clear) }

    private fun dispatch(event: TimerEvent) {
        val (next, effects) = TimerEngine.reduce(state, event)
        state = next
        onState(next)
        effects.forEach(onEffect)
    }
}
