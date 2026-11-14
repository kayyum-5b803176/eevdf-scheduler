# Sync & Restore Architecture (v6.43.1)

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
- **Columns added by an app update** (`seedNewColumnClocks`, run from `init()`
  when the table layout changed): every existing row gets a baseline clock
  holding the column's current value. A new column is therefore sent only once
  it really changes, never as a "default, stamped now" that would beat a real
  value another phone wrote earlier but this phone has not read yet.
- **Task sleep columns** (`sleepDurationSeconds`, `sleepUntilEpoch`,
  `sleepLag`, schema 30) are ordinary shared `tasks` columns: nothing to
  register. Every phone runs its own wake pass at the wake time, so each writes
  the woken task back; the writes settle on one value and then stop (tested).
  A user still on schema 29 parks them and applies them after it updates.
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

## 5c. Two phones (Bluetooth)

`device-exchange.kt` (pure, tested over a pipe) + `bluetooth-link.kt` (radio).
Bluetooth **copies the sync folder's files** between two phones; the normal
sync then reads them, so the merge engine does not know a second phone exists.

- **Phone identity:** `eevdf-sync/device.json` (never copied) holds the phone
  id and the phone-wide choice of partner. Acks carry `dev`, the phone a member
  lives on.
- **Authority:** a member's journal files are originals on its home phone and
  exact appended copies on the other. Copies are deleted only after the home
  phone deleted the originals; a home phone never deletes because the other
  side lacks something. Members with no ack yet: files are only added.
  Snapshots: newest two across both phones. Acks: newest per member.
- **Wire order:** HELLO both ways (clock measured), INVENTORY both ways,
  initiator's files + END, responder's files + END, then deletions. One
  direction at a time; an interrupted exchange resumes (a shorter journal copy
  is simply continued; half-received whole files are discarded).
- **Live link** (`device-session.kt`, protocol version 2): one secure RFCOMM
  connection to an already-paired phone stays open while the app is on screen
  on both. A save is written to the folder 0.4 s later and a *round*
  (inventory + missing bytes) runs at once, started by either phone; requests
  made during a round fold into one more round. Target: 1–2 s from tap to the
  other phone. Both phones may start a round at the same instant: roles are
  fixed per link (the dialler is the initiator) and each start byte lands
  where the other expected the round's first message, where it is skipped.
- **Battery:** silent between changes except a 1-byte keep-alive every 25 s;
  nothing while the app is in the background; a phone dials the moment its app
  opens, so the other retries only every 30 s doubling to 5 min. A round with no
  bytes moving for 20 s is cut off. If both phones dial at once, both keep the
  link dialled by the phone with the smaller id. No scanning; permission
  BLUETOOTH_CONNECT only.
- **Card writes while linked:** with a live link every save is written to the
  card within 0.4 s (that is what makes it fast); without one the 5-minute
  batching applies.
- **Clocks:** stamps already follow cause and effect (an edit made after
  seeing the other phone's change always wins, whatever the clocks say). Timer
  start times are wall-clock data, so a difference over 2 s is reported on the
  sync screen and in the event log.
- **Two phones.** The deletion rule assumes exactly two phones in the group.

## 5d. One alarm across phones (6.42.0)

`alarm-sync.kt` + `shared-state.kt`, tested in `alarm-sync-test.kt`.

- **Shared record:** two tables owned by this capability, created before the
  engine discovers tables (so they sync like any other; Room ignores them):
  `shared_alarm(id = taskId@runStart, state ringing|stopped, …)` and
  `shared_setting(ring_mode, ring_owner, ring_owner_name)`. "ringing" is
  INSERT OR IGNORE and "stopped" is never undone.
- **Stop everywhere:** `alarm.stopped` here (any stop path) → write
  "stopped" → sent at once. A merge bringing "stopped" for the alarm ringing
  here → `alarm.stop-requested` (the same full stop as the Stop button). This
  includes a stop by another Android user of the same phone.
- **Ring modes:** `alarm.should-ring-requested` (asked by alarm-ringer at
  expiry, answered from memory). Swarm: ring. Handover: only the phone that
  last saved a task (`ring_owner`, updated on `task.saved`) rings; others stay
  silent only while a live link to that phone exists. A silent alarm is a
  normal alarm with `AlarmRingingEvent.silent = true` (sound/vibration skip
  it; no banner, no full-screen screen). An alarm already stopped elsewhere
  does not ring at all.
- **Duplicate credit:** both phones may credit the same expired run;
  `TaskRunInvariants.dedupeRecentRuns` keeps one row per (task, start) in the
  last 48 h, chosen identically on every phone.
- **Background sync:** `SyncLinkService` (foreground service, type
  connectedDevice, permanent silent notification) keeps the process — and so
  the live link — alive while the app is closed. Started only while on screen.
  With the app closed, a merged timer start/pause schedules/cancels this
  phone's alarm (`ALARM_TIMER_START/PAUSE_REQUESTED`), which the task list
  screen would otherwise do.

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
| `sync.device-exchanged` | multi-device-sync | event log |
| `alarm.should-ring-requested` (request) | alarm-ringer | multi-device-sync |
| `alarm.ringing` / `alarm.stopped` | alarm-ringer | multi-device-sync (+ sound, vibration, task list) |
| `alarm.stop-requested` / `alarm.timer-start-requested` / `alarm.timer-pause-requested` | multi-device-sync (also task-list-screen) | alarm-ringer |

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
- **Bluetooth needs the app open on both phones** unless background sync is
  switched on. Two phones only. Background sync between Android users of the
  same phone depends on Android keeping the switched-away user running.
- **Settings are per user and not synced.** `eevdf_sync` (member id, folder)
  is deliberately outside `SettingsBackup`.
- **Content fingerprints assume the same columns** on the members that first
  index a row. Join all users on the same build.
- `run_daily` / `run_monthly` compaction is additive; two members compacting
  different inputs for the same day before syncing settle on the later write.
- Android Auto Backup (`allowBackup=true`) could clone a member id to another
  phone. Irrelevant on one device; revisit before Bluetooth sync (build 2).

## 9. Planned next

All three planned builds are in (SD card sync and backup; Bluetooth; alarms
and background sync). Open items: the scheduler still saves lag /
eligibleTime / virtualDeadline for every task on each pause (internal-storage
writes); per-row sync bookkeeping for large history could be smaller.

## 9a. Measured (simulation on a PC, real schema, not a phone)

200 tasks, 30,000 history rows, 50 start/stop cycles: card writes per cycle
118 KB (old whole-file) → 40 KB (6.38.x) → 1 KB (this build). One simulated
week, 1,500 cycles, second user absent: 1.3 MB on the card in 10 small files;
the absent user catches up in one read. Internal database: +102% (was +295%).

## 10. Tests

`capabilities/multi-device-sync/src/test/.../sync-engine-test.kt` — 33 tests, `device-exchange-test.kt` — 15 tests (two phones over a pipe, incl. the live link), `alarm-sync-test.kt` — 9 tests (stop everywhere, ring modes, duplicate credit, alarms kept in step while closed),
each named as the promise it checks (empty joiner erases nothing; timer
started by one user stopped by another; torn write skipped; card missing;
card wiped; snapshot + late joiner; older/newer builds; restore merge/replace;
five users converge). `mini-json-test.kt` covers the line format.

**Not covered by tests, verify on a device:** `SyncCoordinator`,
`RoomSyncDb`/`FileSyncDb`, `SyncReconcileDelegate`, and both activities.
