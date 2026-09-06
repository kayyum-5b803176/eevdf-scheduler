package com.eevdf.app.di

import android.content.Context
import com.eevdf.capabilities.runhistory.RunLogDao
import com.eevdf.capabilities.taskstorage.TaskDao
import com.eevdf.capabilities.taskstorage.InterruptReturnDao
import com.eevdf.capabilities.taskstorage.TaskLoadFactorDao
import com.eevdf.capabilities.taskstorage.TaskLinkDao
import com.eevdf.capabilities.taskstorage.TaskMembershipDao
import com.eevdf.capabilities.taskstorage.TaskDatabase
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

    @Provides
    fun provideTaskLinkDao(db: TaskDatabase): TaskLinkDao = db.taskLinkDao()

    @Provides
    fun provideTaskMembershipDao(db: TaskDatabase): TaskMembershipDao = db.taskMembershipDao()
}
