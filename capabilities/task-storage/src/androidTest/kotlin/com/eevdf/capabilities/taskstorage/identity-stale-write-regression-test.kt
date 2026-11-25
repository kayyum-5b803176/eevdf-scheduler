package com.eevdf.capabilities.taskstorage

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eevdf.capabilities.runhistory.RunLogRepository
import com.eevdf.kernel.clock.SystemClock
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guard against a real-world-reported bug: "some tasks and groups take the identity I give them, others
 * never do, however many times I try."
 *
 * Root cause: the repository (and the run timer, the interrupt assignment, the schedule refresh, ...)
 * writes WHOLE task rows from in-memory copies that can be old. Any copy taken before the identity was
 * changed still carries the previous identity, so writing it back put the previous identity back —
 * every time, for any task such a copy was written for (a running task, an interrupt task, a group
 * being accounted).
 *
 * The fix is in [TaskDao.update] / [TaskDao.updateAll]: a full-row write keeps the stored identity.
 * Only [TaskDao.setIdentity] (and an insert) changes it. This test locks that in.
 *
 * Run with: ./gradlew :task-storage:connectedDebugAndroidTest (needs a device/emulator)
 */
@RunWith(AndroidJUnit4::class)
class IdentityStaleWriteRegressionTest {

    private lateinit var db: TaskDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(
            dao = db.taskDao(),
            runLog = RunLogRepository(db.runLogDao(), context, SystemClock()),
            interruptReturnDao = db.interruptReturnDao(),
            loadFactorDao = db.taskLoadFactorDao(),
            taskLinkDao = db.taskLinkDao(),
            taskMembershipDao = db.taskMembershipDao(),
            membershipAclDao = db.membershipAclDao(),
            bus = EventBus(Supervisor()),
            clock = SystemClock(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun staleFullRowUpdate_doesNotRevertAChangedIdentity() = runBlocking {
        val task = Task(name = "Backup job", priority = 4, timeSliceSeconds = 60L, identity = "undefined:")
        repository.insert(task)
        assertEquals("undefined:101", db.taskDao().getTaskById(task.id)!!.identity)

        // A copy taken NOW — what a running timer, an interrupt assignment or a schedule refresh holds.
        val staleCopy = db.taskDao().getTaskById(task.id)!!

        // The person changes the kind.
        repository.changeIdentityKind(task.id, IdentityKind.PROCESS)
        assertEquals("process:101", db.taskDao().getTaskById(task.id)!!.identity)

        // The stale copy is written back, whole row, as those paths do.
        db.taskDao().update(staleCopy.copy(isRunning = false, remainingSeconds = 5))

        assertEquals("process:101", db.taskDao().getTaskById(task.id)!!.identity)
        // ...and the rest of that stale write still went through — only the identity is protected.
        assertEquals(5L, db.taskDao().getTaskById(task.id)!!.remainingSeconds)
    }

    @Test
    fun staleBatchUpdate_doesNotRevertAChangedIdentity() = runBlocking {
        val a = Task(name = "A", priority = 4, timeSliceSeconds = 60L, identity = "undefined:")
        val b = Task(name = "B", priority = 4, timeSliceSeconds = 60L, identity = "undefined:")
        repository.insert(a)
        repository.insert(b)
        val stale = listOf(db.taskDao().getTaskById(a.id)!!, db.taskDao().getTaskById(b.id)!!)

        repository.changeIdentityKind(a.id, IdentityKind.USER)
        repository.changeIdentityKind(b.id, IdentityKind.GROUP)

        db.taskDao().updateAll(stale)

        assertEquals("user:101",  db.taskDao().getTaskById(a.id)!!.identity)
        assertEquals("group:101", db.taskDao().getTaskById(b.id)!!.identity)
    }

    @Test
    fun updateStampsTheCurrentIdentityOntoTheCallersObject() = runBlocking {
        val task = Task(name = "Report", priority = 4, timeSliceSeconds = 60L, identity = "undefined:")
        repository.insert(task)
        val stale = db.taskDao().getTaskById(task.id)!!
        repository.changeIdentityKind(task.id, IdentityKind.THREAD)

        db.taskDao().update(stale)

        // The caller's own object is current afterwards, so it cannot re-introduce the old value later.
        assertEquals("thread:101", stale.identity)
    }

    @Test
    fun changeKind_thenSaveAgain_keepsTheNewKindAndNumber() = runBlocking {
        val task = Task(name = "Daemon", priority = 4, timeSliceSeconds = 60L, identity = "undefined:")
        repository.insert(task)
        repository.changeIdentityKind(task.id, IdentityKind.SESSION)
        // An ordinary edit-save (repository.update) with a copy that still has the old identity.
        repository.update(task.copy(description = "edited"))
        assertEquals("session:101", db.taskDao().getTaskById(task.id)!!.identity)
    }
}
