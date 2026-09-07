package com.eevdf.composition

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Vibrator
import androidx.core.content.getSystemService
import com.eevdf.capabilities.alarmringer.AlarmCommandHandler
import com.eevdf.capabilities.alarmringer.TimerExpiryHandler
import com.eevdf.capabilities.callautoswitch.OverlayCommandHandler
import com.eevdf.capabilities.feedbackcues.AlarmCueHandler
import com.eevdf.capabilities.featuretoggles.FeatureFlags
import com.eevdf.capabilities.featuretoggles.SharedPrefsFeatureFlags
import com.eevdf.capabilities.multidevicesync.logic.TaskSavedSyncHandler
import com.eevdf.capabilities.remindernotifier.AlarmDeliveryHandler
import com.eevdf.capabilities.runhistory.RunLogDao
import com.eevdf.capabilities.runhistory.RunLogRepository
import com.eevdf.capabilities.runhistory.TaskSavedCompactionHandler
import com.eevdf.capabilities.settingsstorage.state.AppPreferences
import com.eevdf.capabilities.taskstorage.InterruptReturnDao
import com.eevdf.capabilities.taskstorage.TaskDao
import com.eevdf.capabilities.taskstorage.TaskDatabase
import com.eevdf.capabilities.taskstorage.TaskLinkDao
import com.eevdf.capabilities.taskstorage.TaskLoadFactorDao
import com.eevdf.capabilities.taskstorage.TaskMembershipDao
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
 * THE composition layer. The only place allowed to know the full list of
 * capabilities that exist (rule 6).
 *
 * It wires; it never computes. Every function here either hands back a kernel
 * singleton, an Android system service, or constructs a capability's bus
 * subscriber. There is no business logic in this file and none should be added
 * — if a binding needs a decision made, that decision belongs in the capability
 * that owns it.
 *
 * ADDING CAPABILITY N+1: add one `@Provides` here for its bus subscriber (if it
 * has one) and one line in `settings.gradle.kts`. Nothing in `kernel/` and
 * nothing in any existing capability changes. That is the property the whole
 * redesign exists to buy.
 *
 * REPLACES five separate DI modules that used to live in `app/di/`:
 *   - AppCoreModule      -> the feature-flag bindings below
 *   - DatabaseModule     -> the Room/DAO bindings below
 *   - PlatformModule     -> the Android system-service bindings below
 *   - KernelModule       -> the kernel + subscriber bindings below
 *   - RepositoryModule   -> deleted, not merged. It contained ZERO bindings:
 *                           every repository uses an `@Inject` constructor, so
 *                           the file was pure documentation of that fact.
 *   - SchedulerModule    -> deleted, not merged. Same: zero bindings, pure
 *                           documentation.
 */
@Module
@InstallIn(SingletonComponent::class)
object CapabilityBindings {

    // ── kernel ───────────────────────────────────────────────────────────────

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

    // ── capability bus subscribers ───────────────────────────────────────────
    // Constructing each of these registers its subscriptions. They are
    // requested once at startup from ApplicationEntry so a publisher can never
    // fire before its subscriber exists.

    /** task-storage: checkpoints/closes Room on backup export/import. */
    @Provides
    @Singleton
    fun provideBackupCheckpointHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): BackupCheckpointHandler = BackupCheckpointHandler(context, bus)

    /** alarm-ringer: the 6 alarm command topics. */
    @Provides
    @Singleton
    fun provideAlarmCommandHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): AlarmCommandHandler = AlarmCommandHandler(context, bus)

    /** call-autoswitch: the 2 overlay command topics. */
    @Provides
    @Singleton
    fun provideOverlayCommandHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): OverlayCommandHandler = OverlayCommandHandler(context, bus)

    /** multi-device-sync: schedules a debounced export on every task.saved. */
    @Provides
    @Singleton
    fun provideTaskSavedSyncHandler(bus: EventBus): TaskSavedSyncHandler =
        TaskSavedSyncHandler(bus)

    /** run-history: opportunistic tier-compaction check on every task.saved. */
    @Provides
    @Singleton
    fun provideTaskSavedCompactionHandler(
        runLog: RunLogRepository,
        bus: EventBus,
    ): TaskSavedCompactionHandler = TaskSavedCompactionHandler(runLog, bus)

    /** feedback-cues: plays/stops alarm sound + vibration on alarm.ringing/stopped. */
    @Provides
    @Singleton
    fun provideAlarmCueHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): AlarmCueHandler = AlarmCueHandler(context, bus)

    /** reminder-notifier: delivery logging + notification cancel on alarm.ringing/stopped. */
    @Provides
    @Singleton
    fun provideAlarmDeliveryHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): AlarmDeliveryHandler = AlarmDeliveryHandler(context, bus)

    /** alarm-ringer: diagnostic-only log on timer.expired / realtime-window.expired. */
    @Provides
    @Singleton
    fun provideTimerExpiryHandler(
        @ApplicationContext context: Context,
        bus: EventBus,
    ): TimerExpiryHandler = TimerExpiryHandler(context, bus)

    // ── task-storage: database + DAOs ────────────────────────────────────────

    @Provides
    @Singleton
    fun provideTaskDatabase(
        @ApplicationContext context: Context,
    ): TaskDatabase = TaskDatabase.getDatabase(context)

    @Provides
    fun provideTaskDao(db: TaskDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideRunLogDao(db: TaskDatabase): RunLogDao = db.runLogDao()

    @Provides
    fun provideInterruptReturnDao(db: TaskDatabase): InterruptReturnDao = db.interruptReturnDao()

    @Provides
    fun provideTaskLoadFactorDao(db: TaskDatabase): TaskLoadFactorDao = db.taskLoadFactorDao()

    @Provides
    fun provideTaskLinkDao(db: TaskDatabase): TaskLinkDao = db.taskLinkDao()

    @Provides
    fun provideTaskMembershipDao(db: TaskDatabase): TaskMembershipDao = db.taskMembershipDao()

    // ── feature-toggles ──────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideFeatureFlagStore(
        @ApplicationContext context: Context,
    ): SharedPrefsFeatureFlags = SharedPrefsFeatureFlags(context)

    @Provides
    @Singleton
    fun provideFeatureFlags(store: SharedPrefsFeatureFlags): FeatureFlags = store

    // ── Android system services ──────────────────────────────────────────────
    // Not owned by any capability: these are the OS's, and several capabilities
    // need them. Providing them here keeps every capability free of
    // `getSystemService` boilerplate and of each other.

    @Provides
    @Singleton
    fun provideAlarmManager(@ApplicationContext context: Context): AlarmManager =
        context.getSystemService()!!

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        context.getSystemService()!!

    @Provides
    @Singleton
    fun provideUsageStatsManager(@ApplicationContext context: Context): UsageStatsManager =
        context.getSystemService()!!

    @Provides
    @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        context.getSystemService()!!

    @Provides
    @Singleton
    @AppPreferences
    fun provideAppPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
}
