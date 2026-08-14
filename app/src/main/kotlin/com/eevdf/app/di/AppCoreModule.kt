package com.eevdf.app.di

import android.content.Context
import com.eevdf.app.core.SharedPrefsFeatureFlags
import com.eevdf.shared.FeatureFlags
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-wide cross-cutting services that are not database, repository,
 * scheduler or platform concerns (those have their own modules).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppCoreModule {

    @Provides
    @Singleton
    fun provideFeatureFlagStore(
        @ApplicationContext context: Context,
    ): SharedPrefsFeatureFlags = SharedPrefsFeatureFlags(context)

    @Provides
    @Singleton
    fun provideFeatureFlags(store: SharedPrefsFeatureFlags): FeatureFlags = store
}
