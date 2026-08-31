package com.eevdf.data.task

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eevdf.data.runlog.RunLogRepository
import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.data.task.timer.timerState
import com.eevdf.data.task.timer.withTimerState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Permanent guard against a critical, real-world-reported bug:
 *
 *   vruntime=10, virtualDeadline=10 -> run 5s -> pause -> vruntime=15,
 *   virtualDeadline=15 (correct, briefly, in the DB) -> start again ->
 *   vruntime=15 (still correct) but virtualDeadline REVERTS to 10 (stale).
 *
 * Root cause: [TaskRepository.updateVruntimeAfterRun] mutates `vruntime` in
 * place on the SAME object its caller holds (so that field stays correct on
 * that reference), but recalculates `virtualDeadline`/`eligibleTime`/`lag`
 * on a SEPARATELY-QUERIED list of fresh Task objects, several lines later.
 * A caller that keeps using its own, now-stale-in-virtualDeadline reference
 * for anything persisted afterward (the exact shape of "start the task
 * again") silently reverts the DB's correct value back to stale. This
 * predates this session's refactor — it was found via a real user report,
 * not introduced by any single phase of it.
 *
 * The fix: [TaskRepository.updateVruntimeAfterRun] now returns the
 * authoritative post-recalculation Task, and every caller in the app was
 * updated to use that returned value instead of its own input parameter for
 * anything written afterward. This test locks that contract in — if any
 * future change reverts to using the stale input instead of the return
 * value ANYWHERE in the call chain this test exercises, or if
 * updateVruntimeAfterRun itself stops returning the correct object, this
 * test goes red immediately, the same way BackupRoundTripCoverageTest
 * guards against a silently-dropped backup field.
 *
 * Run with: ./gradlew :data:connectedDebugAndroidTest (needs a device/emulator)
 */
@RunWith(AndroidJUnit4::class)
class VruntimeStalenessRegressionTest {

    private lateinit var db: TaskDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runLogRepository = RunLogRepository(db.runLogDao(), context)
        repository = TaskRepository(
            dao = db.taskDao(),
            runLog = runLogRepository,
            interruptReturnDao = db.interruptReturnDao(),
            loadFactorDao = db.taskLoadFactorDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pauseThenStart_virtualDeadlineDoesNotRevertToStaleValue() = runBlocking {
        // ── Arrange: a task at vruntime=10, virtualDeadline=10, matching the
        // exact numbers from the original bug report. ──────────────────────
        val original = Task(
            name = "regression-test-task",
            priority = 3,
            timeSliceSeconds = 60L,
            vruntime = 10.0,
            virtualDeadline = 10.0,
        )
        db.taskDao().insert(original)

        // A sibling task is required for EEVDFScheduler.recalculate to have
        // something to compute eligibility/deadline relative to — a lone
        // task's virtualDeadline calculation would trivially not exercise
        // the batch-recalculation code path this test targets.
        db.taskDao().insert(Task(name = "sibling", priority = 3, timeSliceSeconds = 60L))

        // ── Act 1: run for 5 seconds, then pause. ───────────────────────────
        val nowMs = System.currentTimeMillis()
        val session = RunSession.Paused(
            taskId = original.id,
            startEpochMs = nowMs - 5_000L,
            endEpochMs = nowMs,
        )
        val afterPause = repository.updateVruntimeAfterRun(original, session)

        // ── Assert 1: both fields updated on the RETURNED object — this is
        // the "vrt 15 vdl 15" state the user confirmed happens correctly. ──
        assertEquals(
            "vruntime should advance by roughly the 5s run",
            15.0, afterPause.vruntime, 0.01,
        )
        assertNotEquals(
            "virtualDeadline must actually change from its pre-run value — " +
                "if this fails, the recalculation itself silently stopped running",
            10.0, afterPause.virtualDeadline, 0.0001,
        )

        // ── Act 2: simulate "start again", using the RETURNED (fresh) task —
        // exactly what every real caller in the app now does. ───────────────
        val started = afterPause.withTimerState(TaskTimerState.resume(afterPause.timerState, nowMs))
        db.taskDao().update(started)

        // ── Assert 2: re-read from the DB — a completely fresh query, not
        // any in-memory reference — and confirm virtualDeadline is STILL the
        // correct, post-pause value, not reverted to the original 10.0. This
        // is the exact assertion that would have caught the original bug:
        // before the fix, this line would see 10.0 here, not afterPause's
        // recalculated value. ────────────────────────────────────────────────
        val reReadFromDb = db.taskDao().getTaskById(original.id)!!
        assertEquals(
            "virtualDeadline must NOT revert to the stale pre-pause value " +
                "after starting — this is the exact bug from the user report",
            afterPause.virtualDeadline, reReadFromDb.virtualDeadline, 0.0001,
        )
        assertEquals(
            "vruntime must also survive the start-again round trip",
            afterPause.vruntime, reReadFromDb.vruntime, 0.01,
        )
    }
}
