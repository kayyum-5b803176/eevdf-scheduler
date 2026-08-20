package com.eevdf.app.di

import android.content.Context
import com.eevdf.data.runlog.RunLogDao
import com.eevdf.data.task.TaskDao
import com.eevdf.data.task.InterruptReturnDao
import com.eevdf.data.task.TaskLoadFactorDao
import com.eevdf.data.task.TaskDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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

    @Provides
    fun provideTaskLoadFactorDao(db: TaskDatabase): TaskLoadFactorDao = db.taskLoadFactorDao()
}
