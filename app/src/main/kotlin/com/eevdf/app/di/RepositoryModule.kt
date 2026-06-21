package com.eevdf.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Repository bindings.
 *
 * Both repositories in the project are bound by Hilt **automatically** because
 * they declare `@Inject` constructors and carry `@Singleton`:
 *
 *   • [com.eevdf.data.task.TaskRepository]
 *         @Inject constructor(dao: TaskDao, runLog: RunLogRepository)
 *   • [com.eevdf.data.runlog.RunLogRepository]
 *         @Inject constructor(dao: RunLogDao, @ApplicationContext context: Context)
 *
 * Their transitive dependencies (TaskDao, RunLogDao, the Room database) come
 * from [DatabaseModule]. There is therefore no manual `@Provides` to write —
 * adding one would only duplicate what the constructor already declares.
 *
 * This module is kept (empty of providers) as the documented, discoverable home
 * for repository wiring. When a future repository needs an *interface → impl*
 * binding (e.g. `SettingsRepository` backed by a `SharedPreferences` impl),
 * convert it to an `abstract class` module with an `@Binds` method here. The
 * project's examples — `StatsRepository`, `SettingsRepository`,
 * `BackupRepository` — do not currently exist as classes: stats screens read
 * DAOs directly, settings use `SharedPreferences`-backed `object` prefs, and
 * backup is a stateless `BackupManager` object. They are intentionally not
 * fabricated here; doing so would change behavior, which the task forbids.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
