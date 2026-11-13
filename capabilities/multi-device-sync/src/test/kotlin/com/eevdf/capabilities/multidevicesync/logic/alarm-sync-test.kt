package com.eevdf.capabilities.multidevicesync.logic

import com.eevdf.kernel.eventbus.AlarmRingingEvent
import com.eevdf.kernel.eventbus.AlarmTimerStartRequest
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One alarm across synced users and phones: the shared stop, the ring modes,
 * the duplicate-credit guard, and alarms kept in step while the app is closed.
 * Real event bus, real sync engine, real folders; only Android is absent.
 */
class AlarmSyncTest {

    private lateinit var root: File
    private var time = 1_700_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val members = ArrayList<Member>()

    @Before fun setUp() { root = Files.createTempDirectory("eevdf-alarm-test").toFile() }

    @After fun tearDown() {
        scope.cancel()
        members.forEach { runCatching { it.db.conn.close() } }
        root.deleteRecursively()
    }

    private fun tick(ms: Long = 2_000L) { time += ms }

    private inner class Member(
        val name: String,
        folder: File,
        val phone: String,
        var onScreen: Boolean = true,
        var linkedTo: String? = null,
    ) {
        val db = JdbcSyncDb(File(root, "$name.db").absolutePath)
        val bus = EventBus(Supervisor())
        val lock = Mutex()
        val engine: SyncEngine
        val session: SyncSession
        val alarms: AlarmSync
        val stopRequests = CopyOnWriteArrayList<Unit>()
        val starts = CopyOnWriteArrayList<AlarmTimerStartRequest>()
        val pauses = CopyOnWriteArrayList<Unit>()

        init {
            SCHEMA.forEach { db.exec(it) }
            SharedState(db).ensure()                      // as the coordinator does, before the engine looks
            engine = SyncEngine(db, name, { time }); engine.init()
            session = SyncSession(engine, SyncFolder(folder, name), { time }, "test", "user-$name")
            alarms = AlarmSync(
                bus = bus, clock = { time }, scope = scope, lock = lock,
                syncEnabled = { true }, database = { db },
                phoneId = { phone }, phoneName = { "phone $phone" },
                linkedPhone = { linkedTo }, appOnScreen = { onScreen },
                sendSoon = { }, report = { },
            )
            alarms.start()
            bus.subscribe(Topics.ALARM_STOP_REQUESTED, "test") { stopRequests.add(Unit) }
            bus.subscribe(Topics.ALARM_TIMER_START_REQUESTED, "test") { starts.add(it) }
            bus.subscribe(Topics.ALARM_TIMER_PAUSE_REQUESTED, "test") { pauses.add(Unit) }
        }

        // Under the member's sync lock, exactly as the app's coordinator runs them.
        fun join() { tick(); runBlocking { lock.withLock { engine.bootstrap(); session.cycle() } } }
        fun sync() { tick(); runBlocking { lock.withLock { session.cycle() } } }
        fun merged() = runBlocking { alarms.afterMerge() }
        fun refresh() = runBlocking { alarms.refresh() }
        fun ring(task: String) = runBlocking { bus.publish(Topics.ALARM_RINGING, AlarmRingingEvent(task), "alarm-ringer") }
        fun stop(task: String) = runBlocking { bus.publish(Topics.ALARM_STOPPED, task, "alarm-ringer") }
        fun shared() = SharedState(db)
    }

    private fun member(name: String, folder: File, phone: String) = Member(name, folder, phone).also { members.add(it) }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    /** Starts a run and returns its alarm id (task + start time), which every phone derives the same way. */
    private fun startRun(m: Member, taskId: String): String {
        tick()
        m.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = ?, accumulatedMs = 0 WHERE id = ?", listOf(time, taskId))
        return SharedState.alarmIdOf(taskId, time)
    }

    private fun exchange(x: File, y: File) {
        val xOut = PipedOutputStream(); val yIn = PipedInputStream(xOut, 1 shl 16)
        val yOut = PipedOutputStream(); val xIn = PipedInputStream(yOut, 1 shl 16)
        var err: Throwable? = null
        val t = Thread {
            try { DeviceExchange(SyncFolder(y, "radio"), "Y", { time }).run(false, yIn, yOut) } catch (e: Throwable) { err = e }
            finally { runCatching { yOut.close() } }
        }.apply { start() }
        DeviceExchange(SyncFolder(x, "radio"), "X", { time }).run(true, xIn, xOut)
        xOut.close(); t.join(10_000); err?.let { throw it }
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `the ring rule rings unless it is sure another phone rings instead`() {
        fun ring(mode: RingMode, owner: String?, reachable: Boolean, stopped: Boolean = false) =
            RingPolicy.shouldRing(mode, "me", owner, reachable, stopped)
        assertTrue(ring(RingMode.SWARM, "other", reachable = true))
        assertTrue(ring(RingMode.HANDOVER, "me", reachable = false))
        assertTrue(ring(RingMode.HANDOVER, null, reachable = false))
        assertEquals(false, ring(RingMode.HANDOVER, "other", reachable = true))
        assertTrue("cannot reach the ringing phone: ring anyway", ring(RingMode.HANDOVER, "other", reachable = false))
        assertEquals(false, ring(RingMode.SWARM, "other", reachable = true, stopped = true))
    }

    @Test fun `a stopped alarm can never be turned back into a ringing one`() {
        val a = member("A", File(root, "sd").apply { mkdirs() }, "p1")
        val s = a.shared()
        s.raise("t1@5", "t1", "Alpha", 5)
        assertTrue(s.stop("t1@5", "t1", "Alpha", 9, "p1"))
        s.raise("t1@5", "t1", "Alpha", 10)
        assertTrue(s.alarm("t1@5")!!.stopped)
        assertEquals("second stop writes nothing", false, s.stop("t1@5", "t1", "Alpha", 11, "p1"))
    }

    @Test fun `stopping the alarm in one user stops it in the other user of the same phone`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "phone1"); a.db.exec(TASK); a.join()
        val b = member("B", sd, "phone1"); b.join()
        val id = startRun(a, "t1"); a.sync(); b.sync()
        a.refresh(); b.refresh()
        a.ring("Alpha"); b.ring("Alpha")
        eventually("both recorded the alarm") { a.shared().alarm(id) != null && b.shared().alarm(id) != null }
        a.stop("Alpha")
        eventually("A wrote stopped") { a.shared().alarm(id)?.stopped == true }
        a.sync(); b.sync(); b.merged()
        eventually("B was told to stop") { b.stopRequests.isNotEmpty() }
        assertEquals("A is not told to stop its own alarm again", 0, a.stopRequests.size)
    }

    @Test fun `stopping on one phone stops the other phone over Bluetooth`() {
        val phoneX = File(root, "X").apply { mkdirs() }; val phoneY = File(root, "Y").apply { mkdirs() }
        val a = member("A", phoneX, "X"); a.db.exec(TASK); a.join()
        val c = member("C", phoneY, "Y"); c.join()
        exchange(phoneX, phoneY); c.sync()
        val id = startRun(a, "t1"); a.sync(); exchange(phoneX, phoneY); c.sync()
        a.refresh(); c.refresh()
        a.ring("Alpha"); c.ring("Alpha")
        eventually("both recorded the alarm") { a.shared().alarm(id) != null && c.shared().alarm(id) != null }
        c.stop("Alpha")                                   // stopped on phone Y this time
        eventually("C wrote stopped") { c.shared().alarm(id)?.stopped == true }
        c.sync(); exchange(phoneX, phoneY); a.sync(); a.merged()
        eventually("phone X was told to stop") { a.stopRequests.isNotEmpty() }
    }

    @Test fun `an alarm already stopped elsewhere does not ring at all`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "p1"); a.db.exec(TASK); a.join()
        val b = member("B", sd, "p2"); b.join()
        val id = startRun(a, "t1"); a.sync(); b.sync(); a.refresh(); b.refresh()
        a.ring("Alpha")
        eventually("A recorded the alarm") { a.shared().alarm(id) != null }
        a.stop("Alpha")
        eventually("stopped written") { a.shared().alarm(id)?.stopped == true }
        a.sync(); b.sync(); b.refresh()
        assertEquals("B should stay silent", false, b.alarms.shouldRing("Alpha"))
    }

    @Test fun `handover rings only on the phone used last, and rings anyway when that phone is out of reach`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "phoneA"); a.db.exec(TASK); a.join()
        val b = member("B", sd, "phoneB"); b.join()
        runBlocking { a.alarms.setRingMode(RingMode.HANDOVER) }
        a.alarms.onLocalAction()                          // A started the timer
        eventually("owner recorded") { a.shared().ringOwner().first == "phoneA" }
        a.sync(); b.sync(); b.refresh(); a.refresh()
        assertEquals(RingMode.HANDOVER, b.alarms.mode)
        assertTrue("A was used last: A rings", a.alarms.shouldRing("Alpha"))
        b.linkedTo = "phoneA"
        assertEquals("B can reach A: B stays silent", false, b.alarms.shouldRing("Alpha"))
        b.linkedTo = null
        assertTrue("B cannot reach A: B rings anyway", b.alarms.shouldRing("Alpha"))
        // B takes over.
        b.alarms.onLocalAction()
        eventually("owner moved") { b.shared().ringOwner().first == "phoneB" }
        b.sync(); a.sync(); a.refresh(); a.linkedTo = "phoneB"
        assertEquals("now A stays silent", false, a.alarms.shouldRing("Alpha"))
    }

    @Test fun `a run credited on both phones leaves one history entry, the same on both`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "p1"); a.db.exec(TASK); a.join()
        val b = member("B", sd, "p2"); b.join()
        val start = time - 60_000L
        a.db.exec("INSERT INTO run_log(taskId, startEpoch, durationSecs, prevTaskId) VALUES('t1', ?, 1500, NULL)", listOf(start))
        b.db.exec("INSERT INTO run_log(taskId, startEpoch, durationSecs, prevTaskId) VALUES('t1', ?, 1499, 'x')", listOf(start))
        a.sync(); b.sync(); a.sync()
        assertEquals(2L, a.db.queryLong("SELECT COUNT(*) FROM run_log"))
        TaskRunInvariants.dedupeRecentRuns(a.db, start - 1)
        TaskRunInvariants.dedupeRecentRuns(b.db, start - 1)
        a.sync(); b.sync(); a.sync(); b.sync()
        for (m in listOf(a, b)) {
            assertEquals(1L, m.db.queryLong("SELECT COUNT(*) FROM run_log"))
            assertEquals(1500L, m.db.queryLong("SELECT durationSecs FROM run_log"))
        }
    }

    @Test fun `with the app closed, a timer started or paused on the other phone sets or cancels this phone's alarm`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "p1"); a.db.exec(TASK); a.join()
        val b = member("B", sd, "p2"); b.join()
        b.onScreen = false
        startRun(a, "t1"); a.sync(); b.sync(); b.merged()
        eventually("B scheduled its alarm") { b.starts.isNotEmpty() }
        assertEquals("Alpha", b.starts.first().taskName)
        tick(); a.db.exec("UPDATE tasks SET isRunning = 0, startTimeEpoch = 0 WHERE id = 't1'"); a.sync(); b.sync(); b.merged()
        eventually("B cancelled its alarm") { b.pauses.isNotEmpty() }
    }

    @Test fun `the ring mode is one setting for every phone`() {
        val sd = File(root, "sd").apply { mkdirs() }
        val a = member("A", sd, "p1"); a.join()
        val b = member("B", sd, "p2"); b.join()
        runBlocking { b.alarms.setRingMode(RingMode.HANDOVER) }
        b.sync(); a.sync(); a.refresh()
        assertEquals(RingMode.HANDOVER, a.alarms.mode)
    }

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE tasks(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isRunning INTEGER NOT NULL,
               startTimeEpoch INTEGER NOT NULL, accumulatedMs INTEGER NOT NULL, timeSliceSeconds INTEGER NOT NULL,
               taskType TEXT NOT NULL)""",
            """CREATE TABLE run_log(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL,
               startEpoch INTEGER NOT NULL, durationSecs INTEGER NOT NULL, prevTaskId TEXT)""",
        )
        const val TASK = "INSERT INTO tasks VALUES('t1', 'Alpha', 0, 0, 0, 1500, 'DEFAULT')"
    }
}
