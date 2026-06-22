package com.eevdf.app.di

import android.content.Context
import com.eevdf.data.runlog.RunLogDao
import com.eevdf.data.task.TaskDao
import com.eevdf.data.task.InterruptReturnDao
import com.eevdf.data.task.TaskDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room [TaskDatabase] and every DAO it exposes.
 *
 * The database is bound `@Singleton`, so the whole object graph shares one Room
 * instance — the same guarantee the old `TaskDatabase.getDatabase()` volatile
 * singleton gave, now expressed through Hilt's application-scoped container.
 *
 * `TaskDatabase.getDatabase()` is intentionally reused here rather than calling
 * `Room.databaseBuilder` directly: it owns the full migration chain (1→20) and
 * the WAL-checkpoint helpers used by the sync/export paths, so funnelling
 * through it keeps a single source of truth for database construction and
 * avoids two independently-built Room handles fighting over the same file.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
}
