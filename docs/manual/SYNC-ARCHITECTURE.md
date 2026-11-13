# Sync & Restore Architecture (v6.39.0)

**Read this before touching `capabilities/multi-device-sync` or the restore
path in `capabilities/backup-restore`.** It describes what the code does now,
verified by the tests named at the end — not a plan.

---

## 1. What changed, in one paragraph

Until v6.37 "sync" copied the whole database file to the shared folder; the
other user swapped that file in underneath Room and the app killed and
relaunched itself. Last writer won, for everything, so an empty or stale user
could overwrite everyone; a write cut short left a torn file; a newer build's
file crashed an older build; and a restore (which used the same swap-and-kill
trick) could collide with a sync. Now **every user keeps their own database
and the shared folder holds only a journal of changes.** Changes are merged
into the live database with ordinary SQL inside ordinary transactions. Nothing
is closed, replaced or restarted.

## 2. The pieces

| File (`multidevicesync/logic/`) | Job | Android? |
|---|---|---|
| `sync-engine.kt` | The merge. Local changes → stamped lines; remote lines → rows. Restore. | no |
| `sync-schema.kt` | Bookkeeping tables + change-capture triggers; table discovery | no |
| `sync-ops.kt` / `mini-json.kt` | Line format, checksums, fingerprints | no |
| `sync-folder.kt` | The only code that touches the card | no (java.io) |
| `sync-session.kt` | One full cycle: send, read, join, send, tidy | no |
| `task-run-invariants.kt` | "Only one task runs" repair after a merge | no |
| `android-sync-db.kt` | `SyncDb` over live Room, and over a backup file | yes |
| `sync-coordinator.kt` | When to sync; bus in/out; safety copies; UI state | yes |

Everything marked "no" runs in JVM unit tests against a real SQLite file
(`sqlite-jdbc`, test-only dependency).

## 3. How a change travels

1. Any write to any table fires a trigger that notes *which row* changed in
   `_sync_dirty` — in the same transaction as the write.
2. `capture()` compares the row with a stored fingerprint and writes a line to
   `_sync_outbox` **only for what actually differs** (per column for `tasks`,
   per row elsewhere), stamped with time + member id.
3. `SyncFolder.append()` appends outbox lines to this member's own journal
   file and fsyncs. Only then are they removed from the outbox.
4. Another member reads the new bytes of that file, merges them
   (`SyncEngine.apply`) and saves its read position in the same transaction.
5. `sync.remote-applied` is published; task-list-screen re-reads its timer
   (`SyncReconcileDelegate`). Lists refresh by themselves (Room LiveData).

## 4. The merge rules

- **Newest stamp wins** — per column for `tasks`, per row for other tables.
  Ties break on member id, so every member picks the same winner.
- **Baseline** (`Stamp(0,"")`): a row that was here before sync was switched
  on. It loses to anything the group has. After the first complete read,
  rows still at baseline are the ones only this user has: they are announced
  (`KEEP_MINE`, default) or dropped (`GROUP_ONLY`).
- **Delete** leaves a tombstone. A delete older than a change to the same row
  does *not* delete it; the member that made the later change re-sends the
  whole row.
- **Auto-increment tables** (`run_log`): ids are machine-local, so rows are
  identified by a content fingerprint computed once by whoever first sees the
  row. Identical rows that two users both already had are one row.
- **Local-only columns** (`SyncSchema.LOCAL_ONLY_COLUMNS`): `tasks.lag`,
  `eligibleTime`, `virtualDeadline` are a cache the scheduler recomputes, so
  they are never written to the journal, never marked dirty (the update trigger
  only fires when a *shared* column changed) and never sent. After a merge the
  task list screen calls `TaskRepository.recalculateScheduleCache()`, because
  the lists sort by the stored copy. Add a column to this map to make any
  other derived column local.
- **Unknown table / column** (other member runs a newer build): parked in
  `_sync_foreign`, included in snapshots, replayed by `SyncEngine.init()` the
  first time the schema has a place for it.
- **Missing column** (other member runs an older build): SQL default if the
  column has one, else an empty value of the column's type.

## 5. The shared folder

```
<chosen folder>/eevdf-sync/
  journal/j-<member>-000001.log      "<crc32>\t<json>\n" per line, append-only, ~128 KB per file
  snap/s-<epochMs>-<member>.snap.gz  full merged state, written tmp + rename
  ack/a-<member>-<epochMs>.json      how far this member has read in every journal (~300 B)
```

Rules: **one writer per file**, **append only**, **whole files appear
atomically**, an ack is always a NEW file (never an overwrite).

Large batches (the first sync of a big history) are packed into compressed
frames (`{"o":"z",…}`), 5–10x smaller. A build that predates frames skips the
line kind, so update every user before the first big sync.

## 5a. Cost model (why the constants are what they are)

Notation: `W` journal bytes/day, `S` snapshot size, `U` journal written since
the last snapshot, `f` tolerated snapshot overhead.

- **Snapshot trigger `U >= max(2 MB, 4·S)`** (`SNAPSHOT_AMORTISE = 4`). A
  snapshot costs `S` over `U` of journal, so overhead = `S/U <= 1/4`: total
  writes are at most `1.25 ×` the real changes, whatever the database size.
  (A fixed 2 MB trigger is 12.5× for a 25 MB snapshot.) Worst-case card use per
  member is about `4S + 2S`.
- **Pushes are batched** (idle 5 min, at most every 15 min, always on leaving
  the app). A push costs a few file-system blocks whatever its size, so the
  number of pushes matters more than their bytes. A row saved ten times in the
  window is still one line.
- **Delete rule** for one of my journal files: covered by a snapshot AND every
  other member's ack has moved past it, OR that member has been silent for
  `ABSENT_MS` (3 days; the heartbeat is 12 h, so six missed beats). A member that
  never writes acks (older build) gets 12 h after the file was closed.
- **Ack cost**: at most every 6 h, immediately only when a read position moves
  into a later journal file, plus a 12 h heartbeat: about 50 KB/day/member.
- **File size 128 KB** only sets deletion granularity (overshoot ≤ one file).

## 5b. Self-check

Each ack carries `own` (end of that member's journal), `read` (positions in
every other journal) and, when the member has nothing unsent, a fingerprint of
"who last wrote what" over all shared rows and tombstones. If my positions and
another member's describe the same point in every journal and the
fingerprints differ (same table layout), one of us lost or gained something.
Response (never removes data): write a fresh snapshot, and for 24 h merge the
other members' newest snapshots through the normal newest-wins merge. At most
once an hour.

## 6. Bus topics

| Topic | Publisher | Subscriber |
|---|---|---|
| `task.saved` | task-storage | multi-device-sync (debounced push) |
| `app.foreground-changed` | app-foreground | multi-device-sync (true: full sync; false: push now) |
| `sync.now-requested` | task-list-screen | multi-device-sync |
| `sync.status-changed` (retained) | multi-device-sync | task-list-screen (toolbar dot) |
| `sync.remote-applied` | multi-device-sync | task-list-screen (`SyncReconcileDelegate`) |
| `sync.local-published` / `sync.failed` / `sync.maintenance` | multi-device-sync | event log |
| `backup.restore-requested` | backup-restore | multi-device-sync |
| `backup.restore-finished` | multi-device-sync | backup-restore |
| `backup.safety-copies-requested` (request) | backup-restore | multi-device-sync |

Retired: `backup.import-requested`, `task.conflict-detected`.

## 7. Restore

backup-restore unpacks the backup's database to a private temp file and
publishes `backup.restore-requested(path, MERGE | REPLACE)`.
`SyncEngine.restoreFrom` reads it with the plain SQLite API (no Room, so no
version check) and writes rows by table and column name, in one transaction.
The same mutex guards sync and restore. A safety copy (`VACUUM INTO`, last 5
kept in `filesDir/safety-copies/`) is taken before every restore and before a
first join.

## 8. Known limits (deliberate, documented)

- **Sync runs when the app is on screen.** Android gives the SD card to the
  foreground user. A change is written the moment the app leaves the screen
  (`app.foreground-changed = false`), and read when it returns.
- **A user that has not opened the app does not know about a timer started
  elsewhere**, so it cannot ring for it. Addressed by the planned "keep
  listening" service (build 3).
- **An alarm already scheduled in a background user is not cancelled** until
  that user's app next syncs. Also build 3 (ring modes).
- **The self-check only fires when two members are quiet at the same point**
  (they alternate use, so mostly yes) and can only repair what a snapshot
  contains. It reports through `sync.maintenance`.
- **Settings are per user and not synced.** `eevdf_sync` (member id, folder)
  is deliberately outside `SettingsBackup`.
- **Content fingerprints assume the same columns** on the members that first
  index a row. Join all users on the same build.
- `run_daily` / `run_monthly` compaction is additive; two members compacting
  different inputs for the same day before syncing settle on the later write.
- Android Auto Backup (`allowBackup=true`) could clone a member id to another
  phone. Irrelevant on one device; revisit before Bluetooth sync (build 2).

## 9. Planned next (not in this build)

- **Build 2 — Bluetooth:** a second transport for the same journal lines
  (`SyncOp`s through `SyncEngine.apply`). Needs clock-offset correction.
- **Build 3 — Ring modes:** handover / swarm, plus the optional keep-listening
  service.

## 9a. Measured (simulation on a PC, real schema, not a phone)

200 tasks, 30,000 history rows, 50 start/stop cycles: card writes per cycle
118 KB (old whole-file) → 40 KB (6.38.x) → 1 KB (this build). One simulated
week, 1,500 cycles, second user absent: 1.3 MB on the card in 10 small files;
the absent user catches up in one read. Internal database: +102% (was +295%).

## 10. Tests

`capabilities/multi-device-sync/src/test/.../sync-engine-test.kt` — 29 tests,
each named as the promise it checks (empty joiner erases nothing; timer
started by one user stopped by another; torn write skipped; card missing;
card wiped; snapshot + late joiner; older/newer builds; restore merge/replace;
five users converge). `mini-json-test.kt` covers the line format.

**Not covered by tests, verify on a device:** `SyncCoordinator`,
`RoomSyncDb`/`FileSyncDb`, `SyncReconcileDelegate`, and both activities.
