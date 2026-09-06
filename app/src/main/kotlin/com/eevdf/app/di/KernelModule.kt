package com.eevdf.app.di

import android.content.Context
import com.eevdf.capabilities.alarmringer.AlarmCommandHandler
import com.eevdf.capabilities.callautoswitch.OverlayCommandHandler
import com.eevdf.capabilities.taskstorage.logic.BackupCheckpointHandler
import com.eevdf.kernel.clock.Clock
import com.eevdf.kernel.clock.SystemClock
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.supervisor.HealthMonitor
import com.eevdf.kernel.supervisor.Supervisor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the kernel into the running app.
 *
 * This is composition-layer code in the redesign's sense: it knows the full
 * list of kernel singletons and which capabilities subscribe at startup, and
 * it does nothing else. It wires; it never computes.
 *
 * Rule 6 note: adding capability N+1 that participates in the bus means adding
 * one `@Provides` line here for its subscriber — nothing in `kernel/` and
 * nothing in any existing capability changes.
 *
 * This file is the interim home for that wiring. Phase 10 moves it to
 * `composition/capability-bindings.kt`, merging it with the other four
 * existing DI modules (AppCoreModule, DatabaseModule, PlatformModule,
 * RepositoryModule, SchedulerModule) as the Phase -1 audit mapped out.
 */
@Module
@InstallIn(SingletonComponent::class)
object KernelModule {

    @Provides
    @Singleton
    fun provideHealthMonitor(): HealthMonitor = HealthMonitor()

    @Provides
    @Singleton
    fun provideSupervisor(healthMonitor: HealthMonitor): Supervisor = Supervisor(healthMonitor)

    @Provides
    @Singleton
    fun provideEventBus(supervisor: Supervisor): EventBus = EventBus(supervisor)

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock()

    /**
     * task-storage's backup subscriber. Constructing it registers its bus
     * subscriptions; it is requested once at startup from
     * SchedulerApplication.onCreate() so the handler exists before any
     * backup-restore screen can publish to it.
     */
    @Provides
    @Singleton
    fun provideBackupCheckpointHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): BackupCheckpointHandler = BackupCheckpointHandler(context, bus)

    /** Replaces AlarmControlModule's AlarmController binding — see AlarmCommandHandler's KDoc. */
    @Provides
    @Singleton
    fun provideAlarmCommandHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): AlarmCommandHandler = AlarmCommandHandler(context, bus)

    /** Replaces OverlayControlModule's OverlayController binding — see OverlayCommandHandler's KDoc. */
    @Provides
    @Singleton
    fun provideOverlayCommandHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): OverlayCommandHandler = OverlayCommandHandler(context, bus)
}
