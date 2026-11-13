package com.eevdf.capabilities.multidevicesync.logic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files

/**
 * Two phones, each with its own sync folder, connected by a pipe instead of
 * Bluetooth. Phone X has users A and B, phone Y has user C. Everything except
 * the radio is the code the app runs.
 */
class DeviceExchangeTest {

    private lateinit var root: File
    private lateinit var phoneX: File
    private lateinit var phoneY: File
    private var time = 1_700_000_000_000L
    private val members = ArrayList<Member>()

    @Before fun setUp() {
        root = Files.createTempDirectory("eevdf-bt-test").toFile()
        phoneX = File(root, "phoneX").apply { mkdirs() }
        phoneY = File(root, "phoneY").apply { mkdirs() }
    }

    @After fun tearDown() {
        members.forEach { runCatching { it.db.conn.close() } }
        root.deleteRecursively()
    }

    private fun tick(ms: Long = 2_000L) { time += ms }

    private inner class Member(val name: String, folder: File, skewMs: Long = 0L) {
        val db = JdbcSyncDb(File(root, "$name.db").absolutePath)
        val engine: SyncEngine
        val session: SyncSession
        init {
            SCHEMA.forEach { db.exec(it) }
            engine = SyncEngine(db, name, { time + skewMs }); engine.init()
            session = SyncSession(engine, SyncFolder(folder, name), { time + skewMs }, "test", "user-$name")
        }
        fun join() { tick(); engine.bootstrap(); session.cycle() }
        fun sync(): SyncSession.CycleResult { tick(); return session.cycle() }
        fun task(id: String, name: String) = db.exec(
            "INSERT OR REPLACE INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs) VALUES(?,?,0,0,0)", listOf(id, name))
        fun name(id: String) = db.queryString("SELECT name FROM tasks WHERE id = ?", listOf(id))
        fun field(id: String, c: String) = db.queryRows("SELECT $c AS v FROM tasks WHERE id = ?", listOf(id)).firstOrNull()?.get("v")
        fun dump() = db.queryRows("SELECT id,name,isRunning,startTimeEpoch,accumulatedMs FROM tasks ORDER BY id").toString()
    }

    private fun member(name: String, folder: File, skewMs: Long = 0L) = Member(name, folder, skewMs).also { members.add(it) }

    /** Runs one exchange between the two folders: X starts it, Y answers. */
    private fun exchange(cutAfterBytes: Long = Long.MAX_VALUE, yClockSkew: Long = 0L): Pair<DeviceExchange.Result, DeviceExchange.Result> {
        tick()
        val xOut = PipedOutputStream(); val yIn = PipedInputStream(xOut, 1 shl 16)
        val yOut = PipedOutputStream(); val xIn = PipedInputStream(yOut, 1 shl 16)
        var yResult: DeviceExchange.Result? = null
        var yError: Throwable? = null
        val yThread = Thread {
            try {
                yResult = DeviceExchange(SyncFolder(phoneY, "radio"), "phone Y", { time + yClockSkew })
                    .run(false, Cut(yIn, cutAfterBytes), yOut)
            } catch (e: Throwable) { yError = e } finally { runCatching { yOut.close() } }
        }
        yThread.start()
        try {
            val xResult = DeviceExchange(SyncFolder(phoneX, "radio"), "phone X", { time }).run(true, xIn, xOut)
            yThread.join(30_000)
            yError?.let { throw it }
            return xResult to yResult!!
        } finally {
            runCatching { xOut.close() }
            yThread.join(30_000)
        }
    }

    /** An input that breaks after a number of bytes — a radio link dropping mid-transfer. */
    private class Cut(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) throw IOException("link lost"); left--; return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw IOException("link lost")
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun noise(chars: Int, seed: Long): String {
        val bytes = ByteArray(chars * 3 / 4); java.util.Random(seed).nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun journals(phone: File, owner: String) =
        File(phone, "eevdf-sync/journal").listFiles().orEmpty().filter { it.name.startsWith("j-$owner-") }.map { it.name }.sorted()

    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `a change on one phone reaches every user on the other phone and back`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val b = member("B", phoneX); b.join()
        val c = member("C", phoneY); c.join()
        exchange(); c.sync()
        assertEquals("Alpha", c.name("t1"))
        tick(); c.db.exec("UPDATE tasks SET name = 'edited on phone Y' WHERE id = 't1'"); c.sync()
        exchange(); a.sync(); b.sync()
        assertEquals("edited on phone Y", a.name("t1"))
        assertEquals("edited on phone Y", b.name("t1"))
    }

    @Test fun `a timer started on one phone can be stopped on the other`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val c = member("C", phoneY); c.join(); exchange(); c.sync()
        tick(); a.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = ? WHERE id = 't1'", listOf(time)); a.sync()
        exchange(); c.sync()
        assertEquals(1L, c.field("t1", "isRunning"))
        tick(); c.db.exec("UPDATE tasks SET isRunning = 0, startTimeEpoch = 0, accumulatedMs = 5000 WHERE id = 't1'"); c.sync()
        exchange(); a.sync()
        assertEquals(0L, a.field("t1", "isRunning")); assertEquals(5000L, a.field("t1", "accumulatedMs"))
    }

    @Test fun `nothing is sent when both phones are already up to date`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val c = member("C", phoneY); c.join()
        exchange(); c.sync(); exchange(); a.sync(); exchange()
        val (x, y) = exchange()
        assertEquals(0L, x.bytesSent); assertEquals(0L, y.bytesSent)
    }

    @Test fun `an exchange cut off half-way leaves both folders usable and the next one finishes`() {
        val a = member("A", phoneX); a.task("t1", "Alpha")
        a.db.transaction { for (i in 0 until 40) a.task("big$i", noise(20_000, i.toLong())) }
        a.join()
        val c = member("C", phoneY); c.join()
        val broken = runCatching { exchange(cutAfterBytes = 300_000L) }
        assertTrue("the cut link should fail the exchange", broken.isFailure)
        assertTrue("whatever arrived is still readable", runCatching { c.sync() }.isSuccess)
        exchange(); c.sync()
        assertEquals(a.dump(), c.dump())
        val leftovers = File(phoneY, "eevdf-sync").walkTopDown().count { it.name.startsWith(".incoming-") }
        assertEquals(0, leftovers)
    }

    @Test fun `copies on the other phone are deleted only after the home phone deleted the originals`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val c = member("C", phoneY); c.join(); exchange(); c.sync(); exchange()
        for (i in 0 until 14) {
            tick(); a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("v$i-" + noise(200_000, i.toLong()))); a.sync()
        }
        exchange(); c.sync()                 // C reads everything and confirms
        val before = journals(phoneY, "A").size
        exchange()                           // C's confirmation reaches phone X
        a.sync()                             // A deletes what everyone confirmed
        val home = journals(phoneX, "A")
        assertTrue("home phone deleted confirmed files", home.size < before)
        exchange()                           // phone Y follows
        assertEquals(home, journals(phoneY, "A"))
        assertEquals(a.name("t1"), c.name("t1"))
    }

    @Test fun `a phone never deletes its own users' files because the other phone lacks them`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val c = member("C", phoneY); c.join(); exchange(); c.sync(); exchange()
        tick(); a.task("t2", "Beta"); a.sync()
        exchange()
        // Phone Y loses its copy of A's journal (say, a card error).
        File(phoneY, "eevdf-sync/journal").listFiles()!!.filter { it.name.startsWith("j-A-") }.forEach { it.delete() }
        val homeBefore = journals(phoneX, "A")
        exchange()
        assertEquals("phone X keeps its originals", homeBefore, journals(phoneX, "A"))
        assertEquals("phone Y gets them back", homeBefore, journals(phoneY, "A"))
        val d = member("D", phoneY); d.join()
        assertEquals("Beta", d.name("t2"))
    }

    @Test fun `a phone that joins late catches up from the copied snapshot`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val b = member("B", phoneX); b.join()
        for (i in 0 until 14) {
            tick(); a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("v$i-" + noise(200_000, i.toLong()))); a.sync(); b.sync()
        }
        tick(SyncSession.LEGACY_GRACE_MS + 1); a.sync()  // only B to wait for, and B confirmed
        val c = member("C", phoneY); c.join()
        exchange(); val r = c.sync()
        assertEquals(a.name("t1"), c.name("t1"))
        assertEquals(0, r.gaps)
    }

    @Test fun `the clock difference between the phones is measured on both sides`() {
        val a = member("A", phoneX); a.join()
        val c = member("C", phoneY); c.join()
        val (x, y) = exchange(yClockSkew = 90_000L)
        assertTrue("X sees Y ~90 s ahead: ${x.clockOffsetMs}", kotlin.math.abs(x.clockOffsetMs - 90_000L) < 2_000L)
        assertTrue("Y sees X ~90 s behind: ${y.clockOffsetMs}", kotlin.math.abs(y.clockOffsetMs + 90_000L) < 2_000L)
    }

    @Test fun `an edit made after seeing the other phone's change wins even if this phone's clock is behind`() {
        val a = member("A", phoneX); a.task("t1", "Alpha"); a.join()
        val c = member("C", phoneY, skewMs = -10L * 60_000L); c.join()     // C's clock is 10 minutes slow
        exchange(); c.sync()
        tick(); a.db.exec("UPDATE tasks SET name = 'from A' WHERE id = 't1'"); a.sync()
        exchange(); c.sync()
        tick(); c.db.exec("UPDATE tasks SET name = 'from C, after seeing A' WHERE id = 't1'"); c.sync()
        exchange(); a.sync()
        assertEquals("from C, after seeing A", a.name("t1"))
        assertEquals("from C, after seeing A", c.name("t1"))
    }

    @Test fun `a name the other phone sends that this folder would never hold is ignored`() {
        val f = SyncFolder(phoneX, "radio")
        assertNull(f.fileFor('j', "../../etc/passwd"))
        assertNull(f.fileFor('s', "s-1-x.snap.gz/../../x"))
        assertNull(f.fileFor('a', "device.json"))
    }

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE tasks(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isRunning INTEGER NOT NULL,
               startTimeEpoch INTEGER NOT NULL, accumulatedMs INTEGER NOT NULL)""",
        )
    }
}
