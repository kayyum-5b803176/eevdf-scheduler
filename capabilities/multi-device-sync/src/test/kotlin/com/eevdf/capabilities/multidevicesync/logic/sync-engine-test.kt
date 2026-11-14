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
            "INSERT OR REPLACE INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId,lag,eligibleTime,virtualDeadline) VALUES(?,?,0,0,0,1.5,NULL,0,0,0)",
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
        a.db.exec("INSERT OR ABORT INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId,lag,eligibleTime,virtualDeadline) VALUES('t2','Beta',0,0,0,1.0,NULL,0,0,0)")
        a.db.exec("UPDATE OR ABORT tasks SET name = 'Beta 2' WHERE id = 't2'")
        a.db.exec("UPDATE OR ABORT tasks SET name = 'Beta 3' WHERE id = 't2'")   // same row marked again
        a.db.exec("UPDATE OR ABORT tasks SET id = 't2b' WHERE id = 't2'")        // primary key changes
        a.db.exec("INSERT OR REPLACE INTO tasks(id,name,isRunning,startTimeEpoch,accumulatedMs,vruntime,parentId,lag,eligibleTime,virtualDeadline) VALUES('t1','Alpha 2',0,0,0,1.0,NULL,0,0,0)")
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

    /** Text that does not compress, so journal sizes in tests mean what they say. */
    private fun noise(chars: Int, seed: Long = chars.toLong()): String {
        val bytes = ByteArray(chars * 3 / 4); java.util.Random(seed).nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun journalFiles(owner: String) =
        File(shared, "eevdf-sync/journal").listFiles().orEmpty().filter { it.name.startsWith("j-$owner-") }

    @Test fun `a long journal is folded into a snapshot, confirmed files are deleted, and a late joiner catches up from the snapshot`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        var snapshots = 0
        for (i in 0 until 14) {
            a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("v$i-" + noise(200_000, i.toLong())))
            if (a.sync().snapshotWritten) snapshots++
        }
        assertTrue("a snapshot should have been written once enough journal existed", snapshots >= 1)
        val before = journalFiles("A").size
        // B reads everything (and says so); A may now delete what B confirmed and the snapshot covers.
        b.sync(); a.sync()
        val after = journalFiles("A").size
        assertTrue("confirmed, covered journal files should go: $before -> $after", after < before && after <= 3)
        assertEquals(a.name("t1"), b.name("t1"))
        // Someone joining now gets everything from the snapshot plus what came after.
        val d = user("D")
        assertTrue(d.join().snapshotLoaded)
        assertEquals(a.tasksDump(), d.tasksDump())
    }

    @Test fun `a snapshot is only written once the journal since the last one is at least four times its size`() {
        val a = user("A"); a.task("t1", "Alpha")
        a.db.transaction { for (i in 0 until 6000) a.task("big$i", noise(600, i.toLong())) }   // state worth a snapshot of ~2.7 MB
        a.join()                                                                  // the first snapshot is written here
        fun newestSnapshot() = File(shared, "eevdf-sync/snap").listFiles().orEmpty().maxByOrNull { it.name }?.length() ?: 0L
        var size = newestSnapshot()
        assertTrue("a first snapshot should exist after the join (size $size)", size > 0L)
        var journalSince = 0L
        var snapshotsAfter = 0
        for (i in 0 until 80) {
            a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("v$i-" + noise(250_000, i.toLong())))
            val r = a.sync()
            journalSince += r.bytesWritten      // this cycle's own lines are part of what the snapshot replaces
            if (r.snapshotWritten) {
                snapshotsAfter++
                // The old fixed 2 MB trigger would have fired after ~8 cycles; this one must have waited for 4 x S.
                assertTrue("snapshot of $size B written after only $journalSince B of journal", journalSince >= SyncSession.SNAPSHOT_AMORTISE * size)
                size = newestSnapshot(); journalSince = 0L
                break
            }
        }
        assertEquals("a later snapshot should still happen once the journal justified it", 1, snapshotsAfter)
    }

    @Test fun `a user in heavy daily use keeps the card bounded and an absent user still catches up from the snapshot`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()                       // joins, then never opens the app again
        // ~10 days: one sync every 30 minutes, each carrying ~40 KB of change.
        for (i in 0 until 500) {
            tick(30L * 60_000L)
            a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("n$i-" + noise(40_000, i.toLong())))
            a.session.cycle()
        }
        val retained = journalFiles("A").sumOf { it.length() }
        val perDay = 48L * 40_000L
        // Waiting for B is bounded by the absence cut-off (3 days), plus one snapshot interval and slack.
        val bound = 3L * perDay + SyncSession.SNAPSHOT_MIN_BYTES + 1_000_000L
        assertTrue("retained journal $retained B should stay under $bound B (10 days would be ${10 * perDay} B)", retained <= bound)
        tick(60L * 60_000L)
        val caught = b.sync()
        assertTrue("an absent user whose journal files were deleted must load the snapshot", caught.snapshotLoaded)
        assertEquals(0, caught.gaps)
        assertEquals(a.name("t1"), b.name("t1"))
    }

    @Test fun `a member that is silent is waited for only until the absence cut-off`() {
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()
        for (i in 0 until 14) { a.db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("v$i-" + noise(200_000, i.toLong()))); a.sync() }
        val waiting = journalFiles("A").size
        assertTrue("B has not confirmed, so A keeps the covered files", waiting > 6)
        tick(SyncSession.ABSENT_MS + 1); a.sync()
        assertTrue("after the cut-off they go: $waiting -> ${journalFiles("A").size}", journalFiles("A").size <= 3)
    }

    // ── Cost ──────────────────────────────────────────────────────────────────

    @Test fun `values the scheduler recalculates are never written to the card`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t2", "Beta"); a.join()
        val b = user("B"); b.join()
        // The scheduler's cache refresh, written the way Room does it: every column of every row.
        for (round in 1..3) {
            tick()
            for (id in listOf("t1", "t2")) a.db.exec(
                "UPDATE OR ABORT tasks SET id=id, name=name, isRunning=isRunning, startTimeEpoch=startTimeEpoch, accumulatedMs=accumulatedMs, " +
                    "vruntime=vruntime, parentId=parentId, lag=?, eligibleTime=?, virtualDeadline=? WHERE id=?",
                listOf(round * 1.5, round * 2.5, round * 3.5, id)
            )
        }
        assertEquals(0L, a.count("_sync_dirty"))                          // not even marked as changed
        assertEquals(0L, a.sync().bytesWritten)
        // ...while a real change in the same rows still travels.
        a.db.exec("UPDATE tasks SET vruntime = 9.5, lag = 77 WHERE id = 't1'")
        a.sync(); b.sync()
        assertEquals(9.5, b.field("t1", "vruntime"))
        assertEquals(0.0, b.field("t1", "lag"))                         // B works its own lag out
    }

    @Test fun `a task created elsewhere arrives with the local-only columns defaulted, not failing the insert`() {
        val a = user("A"); a.join()
        val b = user("B"); b.join()
        a.task("n1", "NewTask"); a.sync(); b.sync()
        assertEquals("NewTask", b.name("n1"))
        assertEquals(0.0, b.field("n1", "virtualDeadline"))
    }

    @Test fun `the first sync of a large history is compressed and arrives complete`() {
        val a = user("A"); a.task("t1", "Alpha")
        a.db.transaction { for (i in 0 until 20_000) a.log("t1", 5_000_000L + i * 7L, 30L + i % 50) }
        val raw = 20_000L * 120L
        val joined = a.join()
        val journal = journalFiles("A").sumOf { it.length() }
        assertTrue("journal ($journal B) should be well under the raw size ($raw B)", journal < raw / 2)
        assertEquals(0L, a.engine.outboxSize())
        val b = user("B"); b.join()
        assertEquals(20_000L, b.count("run_log"))
        assertTrue(joined.announced >= 20_000)
    }

    // ── Self-check ────────────────────────────────────────────────────────────

    @Test fun `two members that disagree at the same point are detected and repaired from a snapshot`() {
        val a = user("A"); a.task("t1", "Alpha"); a.task("t2", "Beta"); a.task("t3", "Gamma"); a.join()
        val b = user("B"); b.join(); a.sync(); b.sync()
        assertEquals(a.tasksDump(), b.tasksDump())
        // B silently loses a task AND its bookkeeping (something went wrong that no journal line describes).
        b.db.exec("UPDATE _sync_meta SET v = '1' WHERE k = 'applying'")
        b.db.exec("DELETE FROM tasks WHERE id = 't2'"); b.db.exec("DELETE FROM _sync_row WHERE gk = '[\"t2\"]'")
        b.db.exec("UPDATE _sync_meta SET v = '0' WHERE k = 'applying'")
        var detected = false
        repeat(6) {
            tick(7L * 60L * 60L * 1000L)           // acks are refreshed at most every few hours
            if (a.session.cycle().drift.isNotEmpty()) detected = true
            tick(60_000L)
            if (b.session.cycle().drift.isNotEmpty()) detected = true
        }
        assertTrue("the disagreement should have been noticed", detected)
        assertEquals("Beta", b.name("t2"))
        assertEquals(a.tasksDump(), b.tasksDump())
    }

    @Test fun `members that agree are never flagged`() {
        val all = listOf("A", "B", "C").map { user(it) }
        all[0].task("t1", "Alpha"); all.forEach { it.join() }
        var flagged = 0
        repeat(8) { round ->
            tick(7L * 60L * 60L * 1000L)
            all[round % 3].db.exec("UPDATE tasks SET name = ? WHERE id = 't1'", listOf("r$round"))
            all.forEach { flagged += it.session.cycle().drift.size; tick(60_000L) }
            all.forEach { flagged += it.session.cycle().drift.size; tick(60_000L) }
        }
        assertEquals(0, flagged)
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

    // ── Task sleep / wake (schema 29 → 30) ────────────────────────────────────

    /** Exactly what the app's MIGRATION_29_30 adds to `tasks`. */
    private val SLEEP_MIGRATION = listOf(
        "ALTER TABLE tasks ADD COLUMN sleepDurationSeconds INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE tasks ADD COLUMN sleepUntilEpoch INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE tasks ADD COLUMN sleepLag REAL NOT NULL DEFAULT 0.0",
    )

    @Test fun `a task put to sleep reaches a user that has not updated yet, and applies once it does`() {
        val a = user("A", SCHEMA + SLEEP_MIGRATION); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join()                       // still on schema 29: no sleep columns
        tick(); a.db.exec("UPDATE tasks SET sleepDurationSeconds = 3600, sleepUntilEpoch = 1800000000000, sleepLag = -2.5 WHERE id = 't1'")
        a.sync(); b.sync()
        assertEquals("the old build keeps working", "Alpha", b.name("t1"))
        SLEEP_MIGRATION.forEach { b.db.exec(it) }         // B's app updates and migrates
        b.engine.init()
        assertEquals(1_800_000_000_000L, b.field("t1", "sleepUntilEpoch"))
        assertEquals(-2.5, b.field("t1", "sleepLag"))
        assertEquals(3600L, b.field("t1", "sleepDurationSeconds"))
        // …and waking it travels back.
        tick(); b.db.exec("UPDATE tasks SET sleepUntilEpoch = 0, sleepLag = 0.0 WHERE id = 't1'"); b.sync(); a.sync()
        assertEquals(0L, a.field("t1", "sleepUntilEpoch"))
    }

    @Test fun `updating the phones one after the other never lets a default overwrite a sleep`() {
        // Both users start on schema 29 and share a task…
        val a = user("A"); a.task("t1", "Alpha"); a.join()
        val b = user("B"); b.join(); a.sync(); b.sync()
        // …A updates the app first and puts the task to sleep…
        SLEEP_MIGRATION.forEach { a.db.exec(it) }; a.engine.init()
        tick(); a.db.exec("UPDATE tasks SET sleepDurationSeconds = 3600, sleepUntilEpoch = 1800000000000 WHERE id = 't1'")
        a.sync()
        // …then B updates (without having synced) and saves the same task before anything else.
        SLEEP_MIGRATION.forEach { b.db.exec(it) }; b.engine.init()
        tick(); b.db.exec("UPDATE tasks SET name = 'Alpha 2' WHERE id = 't1'")
        b.sync(); a.sync(); b.sync()
        for (u in listOf(a, b)) {
            assertEquals("${u.name}: the sleep must survive", 1_800_000_000_000L, u.field("t1", "sleepUntilEpoch"))
            assertEquals("${u.name}: B's rename must arrive", "Alpha 2", u.name("t1"))
        }
    }

    @Test fun `a task created before the update arrives on an updated user with the sleep columns at their defaults`() {
        val a = user("A", SCHEMA + SLEEP_MIGRATION); a.join()
        val c = user("C"); c.join()                       // not updated
        c.task("c1", "FromOldBuild"); c.sync(); a.sync()
        assertEquals("FromOldBuild", a.name("c1"))
        assertEquals(0L, a.field("c1", "sleepUntilEpoch"))
        assertEquals(0L, a.field("c1", "sleepDurationSeconds"))
    }

    @Test fun `every user waking the same task on its own settles on one value and then goes quiet`() {
        val a = user("A", SCHEMA + SLEEP_MIGRATION); a.task("t1", "Alpha"); a.join()
        val b = user("B", SCHEMA + SLEEP_MIGRATION); b.join(); a.sync(); b.sync()
        tick(); a.db.exec("UPDATE tasks SET sleepUntilEpoch = 1800000000000, sleepLag = -1.0 WHERE id = 't1'"); a.sync(); b.sync()
        // The wake time passes: each user's wake pass writes the task back on its own.
        tick(); a.db.exec("UPDATE tasks SET sleepUntilEpoch = 0, sleepLag = 0.0, vruntime = 7.5 WHERE id = 't1'")
        tick(); b.db.exec("UPDATE tasks SET sleepUntilEpoch = 0, sleepLag = 0.0, vruntime = 7.6 WHERE id = 't1'")
        repeat(2) { a.sync(); b.sync() }
        assertEquals(a.field("t1", "vruntime"), b.field("t1", "vruntime"))
        assertEquals(0L, a.field("t1", "sleepUntilEpoch")); assertEquals(0L, b.field("t1", "sleepUntilEpoch"))
        assertEquals("nothing left to say", 0L, a.sync().bytesWritten + b.sync().bytesWritten)
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
               startTimeEpoch INTEGER NOT NULL, accumulatedMs INTEGER NOT NULL, vruntime REAL NOT NULL, parentId TEXT,
               lag REAL NOT NULL, eligibleTime REAL NOT NULL, virtualDeadline REAL NOT NULL)""",
            // machine-local auto-increment id, matched by content
            """CREATE TABLE run_log(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL,
               startEpoch INTEGER NOT NULL, durationSecs INTEGER NOT NULL, prevTaskId TEXT)""",
            // composite key, merged row by row
            """CREATE TABLE run_daily(taskId TEXT NOT NULL, dayEpoch INTEGER NOT NULL, totalSecs INTEGER NOT NULL,
               PRIMARY KEY(taskId, dayEpoch))""",
        )
    }
}
