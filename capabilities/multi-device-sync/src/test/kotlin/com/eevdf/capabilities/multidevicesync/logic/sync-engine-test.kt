package com.eevdf.capabilities.multidevicesync.logic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end tests of multi-user sync: several "users", each with a real
 * SQLite database, sharing one real folder on disk — exactly the pieces the
 * app runs, minus Android.
 *
 * Each test is one promise made to the user, named as that promise.
 */
class SyncEngineTest {

    private lateinit var root: File
    private lateinit var shared: File
    private var time = 1_700_000_000_000L
    private val users = ArrayList<User>()

    @Before fun setUp() {
        root = Files.createTempDirectory("eevdf-sync-test").toFile()
        shared = File(root, "sd-card").apply { mkdirs() }
    }

    @After fun tearDown() {
        users.forEach { runCatching { it.db.conn.close() } }
        root.deleteRecursively()
    }

    /** Moves the shared clock forward so every action has its own timestamp. */
    private fun tick(ms: Long = 2_000L) { time += ms }

    private fun user(name: String, ddl: List<String> = SCHEMA): User =
        User(name, root, shared, { time }, ddl).also { users.add(it) }

    private inner class User(val name: String, dir: File, folder: File, clock: () -> Long, ddl: List<String>) {
        val db = JdbcSyncDb(File(dir, "$name.db").absolutePath)
        val engine: SyncEngine
        val session: SyncSession

        init {
            ddl.forEach { db.exec(it) }
            engine = SyncEngine(db, name, clock)
            engine.init()
            session = SyncSession(engine, SyncFolder(folder, name), clock, "test", "user-$name")
        }

        fun join(choice: SyncSession.JoinChoice = SyncSession.JoinChoice.KEEP_MINE): SyncSession.CycleResult {
            tick(); engine.bootstrap(); return session.cycle(choice)
        }

        fun sync(): SyncSession.CycleResult { tick(); return session.cycle() }

        fun task(id: String, name: String) = db.exec(
            "INSERT OR REPLACE INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId) VALUES(?,?,0,0,0,1.5,NULL)",
            listOf(id, name)
        )

        fun log(taskId: String, start: Long, secs: Long) = db.exec(
            "INSERT INTO run_log(taskId,startEpoch,durationSecs,prevTaskId) VALUES(?,?,?,NULL)", listOf(taskId, start, secs)
        )

        fun name(id: String): Any? = db.queryRows("SELECT name FROM tasks WHERE id = ?", listOf(id)).firstOrNull()?.get("name")
        fun field(id: String, column: String): Any? =
            db.queryRows("SELECT $column AS v FROM tasks WHERE id = ?", listOf(id)).firstOrNull()?.get("v")
        fun count(table: String): Long = db.queryLong("SELECT COUNT(*) FROM $table")!!
        fun tasksDump(): String = db.queryRows("SELECT id,name,isRunning,startTimeEpoch,accumulatedMs FROM tasks ORDER BY id").toString()
        fun logDump(): String = db.queryRows("SELECT taskId,startEpoch,durationSecs FROM run_log ORDER BY taskId,startEpoch,durationSecs").toString()
    }

    // ── Joining ───────────────────────────────────────────────────────────────

    @Test fun `an empty new user receives everything and erases nothing`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t2", "Beta"); a.log("t1", 1000, 60)
        a.join()
        val b = user("B")
        val joined = b.join()
        a.sync()
        assertEquals(0, joined.announced)
        assertEquals(2L, a.count("tasks")); assertEquals(2L, b.count("tasks"))
        assertEquals(1L, a.count("run_log")); assertEquals(1L, b.count("run_log"))
    }

    @Test fun `a joining user keeps its own tasks and adopts the group's version of shared ones`() {
        val a = user("A"); a.task("t1", "Alpha"); a.log("t1", 1000, 60); a.join()
        val c = user("C"); c.task("t1", "Alpha-stale"); c.task("t9", "OnlyOnC"); c.log("t1", 1000, 60); c.log("t9", 5000, 10)
        c.join(); a.sync()
        assertEquals("Alpha", c.name("t1"))
        assertEquals("OnlyOnC", a.name("t9"))
        // The run both users already had is one run, not two.
        assertEquals(2L, a.count("run_log")); assertEquals(2L, c.count("run_log"))
    }

    @Test fun `joining with group-only drops a stale user's leftovers instead of spreading them`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val e = user("E"); e.task("old", "DeletedLongAgo"); e.task("t1", "stale")
        val joined = e.join(SyncSession.JoinChoice.GROUP_ONLY); a.sync()
        assertEquals(1, joined.discarded)
        assertNull(e.name("old")); assertNull(a.name("old"))
        assertEquals(a.tasksDump(), e.tasksDump())
    }

    // ── Everyday use ──────────────────────────────────────────────────────────

    @Test fun `a timer started by one user can be stopped by another`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        tick(); a.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = ? WHERE id = 't1'", listOf(time)); a.session.pushOnly()
        b.sync()
        assertEquals(1L, b.field("t1", "isRunning"))
        tick(); b.db.exec("UPDATE tasks SET isRunning = 0, startTimeEpoch = 0, accumulatedMs = 4000 WHERE id = 't1'"); b.log("t1", time, 4); b.session.pushOnly()
        val received = a.sync()
        assertEquals(0L, a.field("t1", "isRunning")); assertEquals(4000L, a.field("t1", "accumulatedMs"))
        assertEquals(1L, a.count("run_log"))
        assertTrue("tasks" in received.applied.tables)
    }

    @Test fun `nothing is written to the card when nothing changed`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join(); a.sync()
        assertEquals(0L, a.sync().bytesWritten); assertEquals(0L, b.sync().bytesWritten)
        a.task("t1", "Alpha") // saved again with identical values
        assertEquals(0L, a.sync().bytesWritten)
    }

    @Test fun `edits to different fields of one task by two users both survive`() {
        val a = user("A"); a.task("t2", "Beta"); a.join()
        val b = user("B"); b.join()
        tick(); a.db.exec("UPDATE tasks SET name = 'Renamed' WHERE id = 't2'")
        tick(); b.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = 777 WHERE id = 't2'")
        a.sync(); b.sync(); a.sync()
        for (u in listOf(a, b)) {
            assertEquals("Renamed", u.name("t2")); assertEquals(1L, u.field("t2", "isRunning"))
        }
    }

    @Test fun `the same field edited by two users settles on the later edit everywhere`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        tick(); a.db.exec("UPDATE tasks SET name = 'from A' WHERE id = 't1'")
        tick(); b.db.exec("UPDATE tasks SET name = 'from B, later' WHERE id = 't1'")
        b.sync(); a.sync(); b.sync()
        assertEquals("from B, later", a.name("t1")); assertEquals("from B, later", b.name("t1"))
    }

    // ── How Room writes ───────────────────────────────────────────────────────

    @Test fun `writes made with an explicit conflict clause, as Room does, are captured and never fail`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        // Room's @Update/@Delete/@Insert spell these out; SQLite then applies the
        // outer clause to the trigger's own statements.
        a.db.exec("INSERT OR ABORT INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId) VALUES('t2','Beta',0,0,0,1.0,NULL)")
        a.db.exec("UPDATE OR ABORT tasks SET name = 'Beta 2' WHERE id = 't2'")
        a.db.exec("UPDATE OR ABORT tasks SET name = 'Beta 3' WHERE id = 't2'")   // same row marked again
        a.db.exec("UPDATE OR ABORT tasks SET id = 't2b' WHERE id = 't2'")        // primary key changes
        a.db.exec("INSERT OR REPLACE INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId) VALUES('t1','Alpha 2',0,0,0,1.0,NULL)")
        a.db.exec("DELETE FROM tasks WHERE id = 't1'")
        a.sync(); b.sync()
        assertEquals("Beta 3", b.name("t2b")); assertNull(b.name("t2")); assertNull(b.name("t1"))
        assertEquals(a.tasksDump(), b.tasksDump())
    }

    // ── Deleting ──────────────────────────────────────────────────────────────

    @Test fun `a delete reaches every user`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t9", "Gone"); a.join()
        val b = user("B"); b.join()
        tick(); a.db.exec("DELETE FROM tasks WHERE id = 't9'"); a.sync(); b.sync()
        assertNull(b.name("t9")); assertEquals("Alpha", b.name("t1"))
    }

    @Test fun `a task edited after someone else deleted it comes back instead of losing the edit`() {
        val a = user("A"); a.task("d1", "Doomed"); a.join()
        val b = user("B"); b.join(); val c = user("C"); c.join()
        tick(); a.db.exec("DELETE FROM tasks WHERE id = 'd1'"); a.session.pushOnly()
        tick(); b.db.exec("UPDATE tasks SET name = 'Rescued' WHERE id = 'd1'") // B has not seen the delete yet
        b.sync(); a.sync(); c.sync(); b.sync()
        for (u in listOf(a, b, c)) assertEquals("Rescued", u.name("d1"))
    }

    // ── The card misbehaving ──────────────────────────────────────────────────

    @Test fun `a write cut short on the card is skipped and later changes still arrive`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        val journal = File(shared, "eevdf-sync/journal").listFiles()!!.first { it.name.startsWith("j-A-") }
        journal.appendText("deadbeef\t{\"o\":\"p\",\"t\":\"tasks\",\"k\":\"[\\\"zz\\\"]\",\"s\":1,\"c\":{\"na") // no newline: torn
        assertEquals(0, b.sync().damagedLines) // unfinished line: wait, do not guess
        a.task("t7", "AfterTear"); a.sync()
        val after = b.sync()
        assertEquals("AfterTear", b.name("t7"))
        assertEquals(1, after.damagedLines)
        assertNull(b.name("zz"))
    }

    @Test fun `changes made while the card is missing are kept and written when it returns`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        // "Pull the card": the sync folder is gone and cannot be recreated
        // (a plain file sits where the directory was).
        val folder = File(shared, "eevdf-sync")
        val away = File(root, "card-pulled")
        assertTrue(folder.renameTo(away))
        assertTrue(folder.createNewFile())
        try {
            a.task("t5", "MadeOffline")
            val offline = runCatching { a.sync() }
            assertTrue(offline.isFailure || offline.getOrThrow().writeError != null)
            assertTrue(a.engine.outboxSize() > 0L) // stamped and waiting, not lost
        } finally {
            folder.delete()
            away.renameTo(folder)
        }
        a.sync(); b.sync()
        assertEquals("MadeOffline", b.name("t5"))
        assertEquals(0L, a.engine.outboxSize())
    }

    @Test fun `after the card is wiped everyone carries on and a newcomer still gets everything`() {
        val a = user("A"); a.task("t1", "Alpha"); a.log("t1", 1000, 60); a.join()
        val b = user("B"); b.join()
        File(shared, "eevdf-sync").deleteRecursively()
        a.task("w1", "AfterWipe")
        assertTrue(a.sync().snapshotWritten)
        b.sync()
        assertEquals("AfterWipe", b.name("w1"))
        val f = user("F"); f.join()
        assertEquals(a.tasksDump(), f.tasksDump()); assertEquals(a.logDump(), f.logDump())
    }

    // ── Large data ────────────────────────────────────────────────────────────

    @Test fun `a long journal is folded into a snapshot and a late joiner catches up from it`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        a.db.transaction { for (i in 0 until 16_000) a.log("t1", 10_000_000L + i, 5) }
        assertTrue(a.sync().snapshotWritten)
        // A user that syncs regularly catches up from the journal, not the snapshot.
        val regular = b.sync()
        assertEquals(false, regular.snapshotLoaded); assertEquals(16_000L, b.count("run_log"))
        // Past the grace period the covered journal files are removed…
        tick(SyncSession.PRUNE_DELAY_MS + 1); a.task("t10", "PostSnapshot"); a.sync()
        val left = File(shared, "eevdf-sync/journal").listFiles()!!.count { it.name.startsWith("j-A-") }
        assertTrue("covered journal files should be pruned, found $left", left <= 2)
        // …and someone joining now gets everything from the snapshot plus what came after.
        val d = user("D")
        assertTrue(d.join().snapshotLoaded)
        assertEquals(a.tasksDump(), d.tasksDump()); assertEquals(16_000L, d.count("run_log"))
    }

    @Test fun `a user in heavy daily use keeps the card tidy and an idle user still catches up from the snapshot`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()                       // joins, then never opens the app again
        // ~10 days: one sync every 30 minutes, each carrying ~40 KB of change.
        for (i in 0 until 500) {
            tick(30L * 60_000L)
            a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("n$i-" + "x".repeat(40_000)))
            a.session.cycle()
        }
        val files = File(shared, "eevdf-sync/journal").listFiles()!!.count { it.name.startsWith("j-A-") }
        val snaps = File(shared, "eevdf-sync/snap").listFiles()!!.count { it.name.endsWith(".snap.gz") }
        // 12 h of journal is ~1 MB, i.e. a handful of files — not the ~40 that 10 days would leave.
        assertTrue("journal files should stay bounded, found $files", files <= 8)
        assertTrue("snapshots should stay at most 2, found $snaps", snaps <= 2)
        tick(60L * 60_000L)
        val caught = b.sync()
        assertTrue("an idle user whose journal files were pruned must load the snapshot", caught.snapshotLoaded)
        assertEquals(0, caught.gaps)
        assertEquals(a.name("t1"), b.name("t1"))
    }

    // ── Different app versions ────────────────────────────────────────────────

    @Test fun `data for a table or column an older build lacks is kept and applied after it updates`() {
        val newer = SCHEMA + listOf(
            "ALTER TABLE tasks ADD COLUMN extra TEXT",
            "CREATE TABLE notes(id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)",
        )
        val a = user("A", newer); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join() // older build: no `extra`, no `notes`
        tick(); a.db.exec("UPDATE tasks SET extra = 'x1' WHERE id = 't1'"); a.db.exec("INSERT INTO notes VALUES('n1', 'hello')")
        a.sync()
        assertTrue(b.sync().applied.parked > 0)
        // B "updates the app": the schema gains what was missing, and the engine starts again.
        b.db.exec("ALTER TABLE tasks ADD COLUMN extra TEXT")
        b.db.exec("CREATE TABLE notes(id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)")
        b.engine.init()
        assertEquals("x1", b.field("t1", "extra"))
        assertEquals("hello", b.db.queryString("SELECT body FROM notes WHERE id = 'n1'"))
        tick(); b.db.exec("UPDATE notes SET body = 'edited on B' WHERE id = 'n1'"); b.sync(); a.sync()
        assertEquals("edited on B", a.db.queryString("SELECT body FROM notes WHERE id = 'n1'"))
    }

    @Test fun `an older build's new task arrives on a newer build with the extra column filled in`() {
        val newer = SCHEMA + listOf("ALTER TABLE tasks ADD COLUMN weight INTEGER NOT NULL DEFAULT 7")
        val a = user("A", newer); a.join()
        val b = user("B"); b.join()
        b.task("old1", "FromOlderBuild"); b.sync(); a.sync()
        assertEquals("FromOlderBuild", a.name("old1"))
        assertEquals(7L, a.field("old1", "weight"))
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    private fun backup(): JdbcSyncDb {
        val bk = JdbcSyncDb(File(root, "backup-${System.nanoTime()}.db").absolutePath)
        // A backup from a NEWER build: an extra column and an extra table.
        bk.exec("CREATE TABLE tasks(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isRunning INTEGER NOT NULL, startTimeEpoch INTEGER NOT NULL, accumulatedMs INTEGER NOT NULL, vruntime REAL NOT NULL, parentId TEXT, futureCol TEXT)")
        bk.exec("CREATE TABLE run_log(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL, startEpoch INTEGER NOT NULL, durationSecs INTEGER NOT NULL, prevTaskId TEXT)")
        bk.exec("CREATE TABLE future_table(id TEXT PRIMARY KEY, v TEXT)")
        bk.exec("INSERT INTO tasks VALUES('t1', 'Alpha-from-backup', 1, 123, 0, 2.5, NULL, 'f')")
        bk.exec("INSERT INTO tasks VALUES('bk1', 'OnlyInBackup', 1, 999, 5000, 0.0, NULL, 'f')")
        bk.exec("INSERT INTO run_log(taskId, startEpoch, durationSecs) VALUES('t1', 1000, 60)")
        bk.exec("INSERT INTO run_log(taskId, startEpoch, durationSecs) VALUES('bk1', 42, 7)")
        return bk
    }

    @Test fun `restore add-missing brings back what is absent and changes nothing else`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t2", "Beta"); a.log("t1", 1000, 60); a.join()
        val b = user("B"); b.join()
        val r = a.engine.restoreFrom(backup(), SyncEngine.RestoreMode.MERGE, TaskRunInvariants::neutraliseRestoredRow)
        assertEquals(2, r.added); assertEquals(0, r.updated); assertEquals(0, r.removed)
        assertEquals("Alpha", a.name("t1")); assertEquals("Beta", a.name("t2"))
        assertEquals(2L, a.count("run_log"))
        assertTrue("future_table" in r.tablesSkipped)
        // A restore never brings back a running timer.
        assertEquals(0L, a.field("bk1", "isRunning")); assertEquals(0L, a.field("bk1", "startTimeEpoch"))
        assertEquals(5000L, a.field("bk1", "accumulatedMs"))
        // Restored rows are ordinary changes: they reach the other user.
        a.sync(); b.sync()
        assertEquals("OnlyInBackup", b.name("bk1"))
    }

    @Test fun `restore replace makes the data equal to the backup for every user`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t2", "Beta"); a.log("t2", 9000, 5); a.join()
        val b = user("B"); b.join()
        a.engine.restoreFrom(backup(), SyncEngine.RestoreMode.REPLACE, TaskRunInvariants::neutraliseRestoredRow)
        assertEquals(2L, a.count("tasks")); assertEquals(2L, a.count("run_log"))
        assertEquals("Alpha-from-backup", a.name("t1")); assertNull(a.name("t2"))
        a.sync(); b.sync()
        assertEquals(a.tasksDump(), b.tasksDump()); assertEquals(a.logDump(), b.logDump())
    }

    @Test fun `restore works the same when sync has never been switched on`() {
        val a = user("A"); a.task("t1", "Alpha")
        val r = a.engine.restoreFrom(backup(), SyncEngine.RestoreMode.MERGE)
        assertEquals(3, r.added) // one task and two runs the local data did not have
        assertEquals(2L, a.count("tasks")); assertEquals(2L, a.count("run_log"))
        assertEquals("Alpha", a.name("t1"))
    }

    // ── Meaning ───────────────────────────────────────────────────────────────

    @Test fun `two timers running after a merge are reduced to the most recently started`() {
        val a = user("A"); a.db.exec("ALTER TABLE tasks ADD COLUMN timeSliceSeconds INTEGER NOT NULL DEFAULT 600")
        a.db.exec("ALTER TABLE tasks ADD COLUMN remainingSeconds INTEGER NOT NULL DEFAULT 600")
        a.task("x", "First"); a.task("y", "Second")
        a.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = 1000000 WHERE id = 'x'")
        a.db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = 1060000 WHERE id = 'y'")
        assertEquals(1, TaskRunInvariants.enforceSingleRunningTask(a.db))
        assertEquals(1L, a.field("y", "isRunning"))
        assertEquals(0L, a.field("x", "isRunning"))
        assertEquals(60_000L, a.field("x", "accumulatedMs")) // ran until the other one started
        assertEquals(540L, a.field("x", "remainingSeconds"))
        assertEquals(0, TaskRunInvariants.enforceSingleRunningTask(a.db))
    }

    // ── Everything together ───────────────────────────────────────────────────

    @Test fun `five users doing different things end up with identical data`() {
        val all = listOf("A", "B", "C", "D", "E").map { user(it) }
        all[0].task("t1", "Alpha"); all[0].task("t2", "Beta")
        all.forEach { it.join() }
        all[1].task("b1", "FromB"); all[2].log("t1", 500, 30)
        tick(); all[3].db.exec("UPDATE tasks SET name = 'Beta v2' WHERE id = 't2'")
        tick(); all[4].db.exec("UPDATE tasks SET isRunning = 1, startTimeEpoch = ? WHERE id = 't1'", listOf(time))
        all[1].sync(); tick(); all[1].db.exec("DELETE FROM tasks WHERE id = 'b1'")
        repeat(3) { all.forEach { it.sync() } }
        for (u in all.drop(1)) {
            assertEquals("${u.name} tasks", all[0].tasksDump(), u.tasksDump())
            assertEquals("${u.name} history", all[0].logDump(), u.logDump())
        }
        assertEquals("Beta v2", all[0].name("t2")); assertNull(all[0].name("b1"))
    }

    private companion object {
        /** A cut-down copy of the app's real tables: one per kind of primary key the engine treats differently. */
        val SCHEMA = listOf(
            // text key, merged column by column
            """CREATE TABLE tasks(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isRunning INTEGER NOT NULL,
               startTimeEpoch INTEGER NOT NULL, accumulatedMs INTEGER NOT NULL, vruntime REAL NOT NULL, parentId TEXT)""",
            // machine-local auto-increment id, matched by content
            """CREATE TABLE run_log(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL,
               startEpoch INTEGER NOT NULL, durationSecs INTEGER NOT NULL, prevTaskId TEXT)""",
            // composite key, merged row by row
            """CREATE TABLE run_daily(taskId TEXT NOT NULL, dayEpoch INTEGER NOT NULL, totalSecs INTEGER NOT NULL,
               PRIMARY KEY(taskId, dayEpoch))""",
        )
    }
}
