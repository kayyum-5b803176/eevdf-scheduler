package com.eevdf.app.feature.alarm

import android.content.Context
import com.eevdf.app.core.control.AlarmController
import com.eevdf.app.core.control.RingingAlarm
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The alarm feature's implementation of [AlarmController].
 *
 * A thin adapter over the existing static API — deliberately thin. All the
 * behaviour stays in [AlarmForegroundService] and [AlarmScheduler]; this class
 * exists purely so that other features can express "pause the alarm" without
 * holding a compile-time reference to a class inside this package.
 *
 * The application context is injected once here instead of being threaded
 * through every call site.
 */
@Singleton
internal class AlarmControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmController {

    override fun timerStart(taskName: String, remainingSecs: Long, taskType: String, alarmSecs: Long) =
        AlarmForegroundService.timerStart(context, taskName, remainingSecs, taskType, alarmSecs)

    override fun timerPause() =
        AlarmForegroundService.timerPause(context)

    override fun timerExpire(taskName: String, taskType: String) =
        AlarmForegroundService.timerExpire(context, taskName, taskType)

    override fun stopAlarm() =
        AlarmForegroundService.stopAlarm(context)

    override fun cancelScheduledAlarm() =
        AlarmForegroundService.cancelScheduledAlarm(context)

    override fun delayStart(taskName: String, delaySecs: Long) =
        AlarmForegroundService.delayStart(context, taskName, delaySecs)

    /**
     * [AlarmState] is a sealed class internal to this feature, so it is mapped
     * to the neutral [RingingAlarm] at the boundary rather than leaked.
     */
    override fun ringingAlarm(): RingingAlarm? =
        (AlarmScheduler.currentState(context) as? AlarmState.Ringing)?.let {
            RingingAlarm(taskName = it.taskName, taskType = it.taskType, firedEpoch = it.firedEpoch)
        }
}

/**
 * Binding provided BY the alarm feature, not by a central DI package.
 *
 * This is what makes the arrangement survive modularization: when
 * `:feature:alarm` becomes its own Gradle module it ships this module with it,
 * and `:app` needs no change.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AlarmControlModule {
    @Binds
    abstract fun bindAlarmController(impl: AlarmControllerImpl): AlarmController
}
