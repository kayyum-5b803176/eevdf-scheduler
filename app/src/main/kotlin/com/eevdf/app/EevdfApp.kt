package com.eevdf.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.eevdf.core.scheduler.SchedulerService
import com.eevdf.data.task.TaskDatabase
import com.eevdf.data.task.TaskRepositoryImpl
import com.eevdf.platform.scheduler.SharedPrefsRrStateStore
import com.eevdf.platform.scheduler.SystemClock

/**
 * The composition root — the ONE place concrete implementations are chosen and
 * wired into core ports. Every other module depends on interfaces only, so
 * swapping a backend (or a fake, in tests) is a single edit here.
 *
 * This replaces the reference's scattered construction (ViewModels `new`-ing
 * their own SharedPreferences, repositories importing the scheduler, etc.).
 */
class AppContainer(context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext, TaskDatabase::class.java, "eevdf.db",
    ).build()

    private val prefs = context.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    val clock = SystemClock()
    val rrState = SharedPrefsRrStateStore(prefs)

    val taskQueue = TaskRepositoryImpl(db.taskDao())
    val scheduler = SchedulerService(rrState)
}

class EevdfApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
