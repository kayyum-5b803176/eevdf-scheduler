package com.eevdf.feature.shared

import javax.inject.Qualifier

/**
 * Qualifies the app's primary `SharedPreferences` file ("eevdf_prefs"),
 * distinguishing it from feature-specific preference files (run-log, sync,
 * hardware-key, etc.) so multiple `SharedPreferences` bindings can coexist.
 *
 * Moved here (from app.di.PlatformModule) in the :feature module extraction:
 * TaskViewModel, now in :feature, has this annotation on an injected
 * constructor parameter, and :feature cannot depend on :app to resolve it.
 * The actual @Provides binding stays in app/di/PlatformModule.kt — Hilt
 * aggregates @InstallIn(SingletonComponent::class) modules into one graph
 * regardless of which module declares them; only the annotation *type* needs
 * to be on the classpath wherever it's referenced.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPreferences
