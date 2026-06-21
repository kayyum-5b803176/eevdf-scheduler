package com.eevdf.app.di

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Vibrator
import androidx.core.content.getSystemService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the app's primary `SharedPreferences` file ("eevdf_prefs"),
 * distinguishing it from feature-specific preference files (run-log, sync,
 * hardware-key, etc.) so multiple `SharedPreferences` bindings can coexist.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPreferences

/**
 * Provides Android framework services as injectable dependencies.
 *
 * These are fetched from the application `Context` and bound `@Singleton`,
 * matching how the framework itself hands back process-wide service instances.
 * Injecting them removes scattered `context.getSystemService(...)` lookups and
 * makes the services trivially fakeable in tests.
 *
 * Note: this module *provides* the framework handles. Behavioral helpers like
 * `AlarmScheduler` and `NotificationHelper` remain stateless `object`s that take
 * a `Context` per call — converting those was out of scope (no DI benefit, and
 * the task forbids behavioral change). They can be migrated to inject these
 * handles incrementally.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

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
