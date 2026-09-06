package com.eevdf.capabilities.alarmringer

import android.content.Context
import com.eevdf.contract.control.AlarmRingingQuery
import com.eevdf.contract.control.RingingAlarm
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin adapter over [AlarmScheduler.currentState] — see [AlarmRingingQuery]'s
 * KDoc for why this stays a direct dependency rather than a bus topic.
 */
@Singleton
internal class AlarmRingingQueryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmRingingQuery {

    override fun ringingAlarm(): RingingAlarm? =
        (AlarmScheduler.currentState(context) as? AlarmState.Ringing)?.let {
            RingingAlarm(taskName = it.taskName, taskType = it.taskType, firedEpoch = it.firedEpoch)
        }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AlarmRingingQueryModule {
    @Binds
    abstract fun bindAlarmRingingQuery(impl: AlarmRingingQueryImpl): AlarmRingingQuery
}
