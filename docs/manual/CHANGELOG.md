# Changelog

Covers the 6.0.0 line. Newest first. Each entry corresponds to one exported
zip; the zip filename is that change's diff, this file is the summary.

---

## 6.47.0 — Group semaphore: a container admits only N children at a time

Includes everything in 6.46.1. **Database change: version 31 → 32** (four columns on `tasks`,
migration `MIGRATION_31_32`). `versionCode` untouched.

### Added
- **Semaphore card** on the Edit screen, in the same style as Quota / Sleep: a toggle and a
  slot count N (1–64, default 4). The card is visible only while "Assign as group
  (container)" is on. Turning the container off only hides it — the switch, N and every
  child's slot are kept, so turning it back on needs no reconfiguring. The queue is released
  only by switching the semaphore itself off.
- **Semantics follow a kernel counting semaphore.** Only N *direct* children (leaf or group;
  a child group costs one slot however much it contains) are visible and scheduled. The rest
  WAIT and are hidden exactly like sleeping tasks: no list row, no scheduling, no share of the
  sibling average, no effect on load / quota / stats. A waiting group hides its subtree.
  - A holder keeps its slot until its state changes: slept, marked done, deleted, or moved to
    another group. Pause, a finished timer, class or interrupt changes release nothing.
  - A freed slot goes to the oldest waiter (FIFO by arrival).
  - A child *arrives* — back of the queue — when created in the group, moved into it, woken
    from sleep, or un-done. A woken child does not get its old slot back.
  - Lowering N never evicts; raising N admits waiters at once.
  - Realtime / deadline children get no special treatment, as in a kernel semaphore.
  - A waiting task cannot be put to sleep. There is no override or "move to front".
  - Switching the semaphore on admits the oldest N existing children (by creation time);
    switching it off shows everyone and forgets the slots (N is kept).
- **`W: n` on the group card**, after `G` and `T` (`G: 1 | T: 2 | W: 4`). Shown only when the
  semaphore is on and n > 0.
- **Wait mode on the third tab** (tap the tab again, next to Completed / Sleep): waiting tasks
  in queue order with `Waiting · #n`. Mark done, plus long-press Edit / Delete (moving a task
  to another group sends it to the back of that group's queue). Clear Completed is hidden in
  this mode.
- **"Queued: waiting for a slot"** toast after saving a task into a full semaphore group.
- If saving an Edit would hide the current task (semaphore switched on over it, or moving it
  into a full group) its timer is paused and it is deselected first, as sleep does.

### Storage
- `tasks` gains `semaphoreEnabled`, `semaphoreLimit` (group) and `semaphoreHeld`,
  `semaphoreQueuedAt` (child). Added to `FROZEN_FIELDS` as the same narrow exception sleep
  used (read on every list refresh; sync carries the row as-is) — the justification is written
  next to the entry. Backup JSON carries all four (older backups load as "no semaphore").
- The holder is *stored*, not derived as "the first N by priority", so a newly arrived child
  with a better deadline can never bump a running one.
- Slot writes are column-targeted (`setSemaphoreSlot`, `admitFromQueue`,
  `resetSemaphoreChildren`); `TaskRepository.update` keeps the child fields from the database
  so a stale copy of a task cannot overwrite them. An admitted waiter is placed like a new
  arrival (vruntime = sibling average).
- `SleepRules.awake` / new `hiddenIds` now hide waiting tasks as well as sleeping ones, which
  is what makes every list, scheduler pass and share computation treat them as absent.
- Symlinks and hardlink placements hosted in a semaphore group are not governed; only real
  children take slots.

### Tests
New `semaphore-rules-test.kt` (22 tests): hiding, waiting-group subtrees, class-blind
queueing, first-enable admission by creation time, oldest-waiter-first, no eviction on a
lowered limit, raising N, child group = one slot, arrival at the back / no queue-jumping /
woken child gets no old slot, stale-state cleanup, wait-list order and positions, limit
clamp. `task-schema-freeze-test.kt` updated.

### Verified / not verified
The pure rules (`semaphore-rules.kt`, `sleep-rules.kt`, `Task`) were compiled with a
standalone Kotlin 2.0.21 compiler and all tests in `semaphore-rules-test.kt` and
`sleep-rules-test.kt` (31) pass. **Not built and not run** (no Android SDK / Gradle here):
Room schema generation (`schemas/…/32.json` will be written by the first build — commit it),
the migration, `TaskRepository`, the layout and all UI code, and no on-device check.

## 6.46.1 — Relative Lag becomes a ranking: small centred integers, one per group

Includes everything in 6.46.0. Display only; no database or sync change.

### Why
6.46.0 scaled each group's lag by its own slice, so any gap beyond about one slice hit the
clamp and groups showed `+2.5 +2.5 +2.5 −2.5 −2.5` — no way to tell them apart. RL is for a
person picking, by eye, which group needs attention, so it is now a ranking.

### Changed
- **RL is the group's rank among the groups of its sibling level**, as an integer centred
  on 0. The most-behind group gets the highest positive number; every group gets its own.
  The range adapts to the number of groups: 5 groups → `+2 +1 0 −1 −2`; 4 → `+2 +1 −1 −2`
  (even counts skip 0 so the halves stay symmetric); 2 → `+1 −1`; 17 → `+8 … −8`.
- **Order** = the scheduler's own lag, `(avgVruntime − vruntime) × weight`, measured against
  the whole fair-class sibling pool (leaf tasks and groups together). Only groups get a
  number; leaves shape the average but are not counted in N. Gap sizes don't matter, only
  the order.
- **Ties** (equal lag) are still given different numbers: the earlier virtual deadline (what
  the scheduler would pick first) gets the higher number, then the id, so the result is
  deterministic and independent of input order.
- Because it is relative, the sign means "more / less behind than its siblings", not
  "owed / in debt".
- Shown as a signed integer (`+2`, `0`, `-1`); `RL: —` for the only group at its level, a
  non-fair group, or a symlinked group. Colours are unchanged (positive / negative / zero).
- API: `EevdfScheduler.relativeLagRanks(siblingPool): Map<id, Int>` replaces `relativeLag`;
  `TaskDisplayItem.relativeLag` is now `Int?`. Builders compute the map once per level (the
  class-filtered builder caches it per level) from the visible siblings, so items hidden
  through a hardlink ACL are not ranked.

### Tests
`relative-lag-test.kt` rewritten: centred numbers for odd / even counts, 17 groups, gap size
irrelevant, no saturation, ties distinct and order-independent, deadline tie-break, weighted
lag decides, leaves not counted, lone group, completed / non-fair ignored, relative sign.
`relative-lag-facade-test.kt`: hardlink placement ranked under its host group, sleeping
groups excluded via `SleepRules.awake`.

### Verified / not verified
Not built and not run (no Android SDK here): no Gradle tests, no on-device rendering check.
Test expectations were worked out by hand.

## 6.46.0 — Relative Lag (RL) on group cards

Includes everything in 6.45.2. No database change (still version 31), no sync change.

### Added
- **Group cards show `RL: +1.5`** in the time-info row, in place of `VRT: …`
  (`TRT: …` on the left is unchanged; the metrics row below still shows the raw VRT).
  RL says how far ahead or behind the group is among its siblings:
  `RL = clamp(lag / group.timeSliceSeconds × (N / 2), −N/2, +N/2)`, with
  `lag = (avgVruntime − vruntime) × weight`, `avgVruntime` the weight-weighted mean of
  the sibling pool, and `N` the pool size including the group itself (6 siblings give
  −3…+3, 5 give ±2.5). Positive = behind the siblings (owed time, eligible); negative =
  ahead (ineligible); zero = at the average.
- **Pool** = one level: same parent, awake, not completed, fair class; leaf tasks and
  groups together. It is computed fresh per level at list-build time (never from the
  stored `Task.lag`, which is measured against a flat pool of every active task). A
  hardlink placement competes with its own vruntime among the siblings under its host
  group; items hidden through a hardlink ACL are not in the pool. The running entity is
  included, so N doesn't jump while something runs.
- **Placeholder `RL: —`** for a group alone at its level, a group outside the fair class,
  a symlinked group row, and any group row where RL isn't computed.
- **Colour:** behind (+) `relativeLagBehind`, ahead (−) `relativeLagAhead`; zero and `—`
  use the normal label colour. New tokens in `values/` and `values-night/` (the night
  values are the lightened green/amber already used for `rankRt` / `quotaTextWarning`).
  The colour is reset on every bind (views are recycled).
- Plain one-decimal formatting with an explicit sign; an exact (displayed) zero is `0.0`.
  RL ignores the unit-format setting.
- API: `EevdfScheduler.relativeLag(entity, siblingPool)` (pure, `null` when there is no
  pool of 2+), `EEVDFScheduler.relativeLag(entry, pool)` (Task wrapper),
  `TaskDisplayItem.relativeLag` (display-only, like `displayVruntime`).

### Refresh behaviour
RL refreshes exactly when a group's VRT does: group vruntime is written when a run
stops, pauses or expires (not on every tick), the list rebuilds, and every sibling card
rebinds because the field is part of the item. No new timers.

### Tests
`relative-lag-test.kt` (task-scheduling): balanced, hand-computed values, clamp, N = 1,
completed/non-fair exclusion, zero weight/slice, mixed pool and own-slice, weighted
average, sign vs. eligibility. `relative-lag-facade-test.kt` (task-storage): hardlink
placement as a pool entry, sleeping siblings via `SleepRules.awake`.

### Verified / not verified
Not built and not run where this was written: no Android SDK, so no Gradle tests and no
on-device rendering check. `check_architecture.sh` shows the same 33 existing import hits
and nothing new (plus the missing `31.json`, generated by your build);
`check_ui_geometry.sh` passes. Test expectations were worked out by hand.

## 6.45.2 — ACL page: = / - / + header symbols, tighter circle columns

Includes everything in 6.45.0. Layout only: same rules, data, builders and code.

### Changed
- The Inherit / Hide / Show column header is now `=`, `-`, `+` (the circles keep
  their word descriptions for screen readers; the intro text uses the same symbols).
- The three circle columns are 40dp wide instead of 56dp (`acl_radio_column_width`),
  so the circles sit closer together. Header and rows read the same value, so they
  stay aligned.

### Verified / not verified
Not built and not run. XML well-formedness checked.

## 6.45.0 — Hardlink ACL goes multi-level: rules at any depth, Inherit / Hide / Show

Includes everything in 6.44.0. No database change (still version 31).

### Changed
- **A rule can now sit on any item inside a hardlinked group, at any depth, and
  is inherited downward** (Nextcloud-style). Per item: no rule = Inherit, a Hide
  rule, or a Show rule; the deepest rule wins. 6.44.0 only covered the group's
  direct children. Existing 6.44.0 rows keep working as Hide / Show rules on
  direct children.
- **"Hide new children by default"** now applies to the group's DIRECT children
  that have no rule; deeper items follow their parent. It no longer needs rows
  written for every child: opening the ACL page writes nothing, and flipping the
  default affects every direct child that is still on Inherit.
- **Nothing inside a hidden group can be shown.** The page disables the circles
  under a hidden group ("Hidden by <group>"); a stored rule there stays but has
  no effect until the group is visible.
- **Builders** (Queue, Schedule, class-filtered Schedule, both drill-down
  levels) apply the door at every level below the placement, not just the first.
  Child counts and `childTotalRuntime` leave out hidden items at every depth, for
  the placement row and for plain group rows listed under it. Shares are
  recomputed for each level that has a hidden item, as that one pool.
- Deleting or promoting a task now drops every rule keyed by it, at any depth.

### Added (ACL page)
- One level at a time: tap a group's name to open it; the breadcrumb or the back
  arrow goes up. Each row has three circles (Inherit / Hide / Show) and a caption
  ("Shown (inherited)", "Hidden · set here", "Hidden by <group>"); collapsed
  groups say "N rules inside". Every tap saves immediately.
- A hosted hardlink placement is not opened: its inside is governed by its own
  ACL.

### Removed
- `TaskRepository.setMembershipChildHidden` / `ensureMembershipAclRows` (replaced
  by `setMembershipChildChoice(membershipId, childKey, AclChoice)`); the DAO
  queries `insertMissing` and `deleteChildUnderGroupDoors`.

### Verified / not verified
Not built and not run where this was written: only brace/paren balance and XML
well-formedness were checked. `membership-acl-test.kt` was rewritten for the new
semantics (rules at depth, default for direct children only, per-level shares,
`resolve`); the list builders themselves still have no tests (they need a
ViewModel).

### Known, not handled
The list builder stops at depth 64 (`MAX_TREE_DEPTH`), so rules deeper than that
cannot show; nested doors (a ref carries one door); stale rules after moving a
task to another parent; an open drill frame inside an item that then becomes
hidden; memberships and rules are not in backup.

## 6.44.0 — Hardlink ACL: hide chosen children of a hardlinked group, per placement

Includes everything in 6.43.1. Database 30 → 31.

### Added
- **Hide direct children through one hardlink placement.** A hardlink of a group
  used to show all of its real children. Each placement can now hide chosen
  DIRECT children (real tasks/groups, plus hardlink placements and symlinks
  hosted in the group). A hidden child is not deleted or changed; it still shows
  in its real location and in other placements. Only that placement's view changes.
- **Through the door, a hidden child behaves as if it never existed** for its
  siblings: not listed, not reachable by Next/rotate/jump/auto, not counted in
  child counts or `childTotalRuntime`, and sibling CPU shares are recomputed
  without it and its subtree (`DoorAcl.sharesFor`). Its own pinned/realtime
  share, quota and schedule class are untouched. Applies to Queue, Schedule,
  class-filtered Schedule and both drill-down builders.
- **Default for new children.** Each placement has `newChildrenHidden`
  (default visible). A child with no explicit row follows it; the ACL page writes
  explicit rows for every listed child, so changing the default later only
  affects children added afterwards.
- **Edit screen:** opened by long-pressing a hardlink row (`membership_id` extra),
  it shows Config / ACL tabs (ACL only when the item is a group). Config is the
  unchanged form. The ACL tab has the "Hide new children by default" switch and
  one "Visible" switch per child; switches save immediately, no Save button.
- **Links screen:** "Hide new children by default" switch when Hardlink is selected.

### Changed
- `TaskRepository.createHardlink(taskId, hostGroupId, newChildrenHidden = false)`.
- If the selected/running task becomes hidden through its door, the selection
  falls back to its real placement (`door-ref-guard.kt`), and run accounting
  credits the real chain instead of the hiding door.

### Database
- `MIGRATION_30_31`: new table `membership_acl(membershipId, childKey, hidden)`,
  composite primary key; `task_memberships.newChildrenHidden INTEGER NOT NULL DEFAULT 0`.
  `schemas/.../31.json` is generated by the first build — not included here.
- ACL rows are deleted with the placement, the group or the child
  (`deleteSymlink`, `deleteHardlink`, `forceDelete`, `deleteOrPromote`).

### Build
- `:capabilities:task-storage` gets `testImplementation(libs.junit)` and
  `kotlin("reflect")`. Its existing unit tests could not compile without them.

### Verified / not verified
NOT built and NOT run: no Android SDK where this was written. Only brace/paren
balance and XML well-formedness were checked. New tests (`membership-acl-test.kt`)
cover `isHidden` and the `DoorAcl` logic the builders call, not the builders.
`scripts/check_architecture.sh` reports the missing `31.json` until the first build.

### Known, not handled
Nested doors (a ref carries one door); stale ACL rows after moving a task to
another parent; an open drill frame inside a child that then becomes hidden;
flat mode (groups disabled) has no hardlinks so ACL does not apply; memberships
and ACL rows are not in backup.

## 6.43.1 — Sleep/wake and sync: two fixes found while testing them together

Includes everything in 6.43.0.

### Fixed
- **A sleeping task could wake up when the phones were updated one after the
  other.** The first time an updated phone saved a row, sync sent the new
  columns' default values stamped "now", which beat a real value (here
  `sleepUntilEpoch`) another phone had written just before. Sync now starts a
  new column as "no claim, current value" and sends it only when it truly
  changes (`SyncEngine.seedNewColumnClocks`). This applies to every column a
  future update adds, not just sleep. Regression test reproduces the rollout
  and failed before the fix.
- **A task put to sleep on another phone stayed on this phone's timer card.**
  A local sleep deselects the task; a sleep arriving by sync now does the same
  (`SyncReconcileDelegate.reseat` checks the awake list).

### Checked and unchanged
The three sleep columns sync with no sync-code change; a user not yet updated
parks them and applies them after updating; every phone running its own wake
pass settles on one value and goes quiet.

### Verified / not verified
61 sync tests, 9 wake-placement and 9 sleep-rule tests pass off-device. The
Android code (screens, Room migration) is not built.

## 6.43.0 — Task sleep / wake (moon button), merged onto the sync line

This is the sleep/wake feature (delivered separately as "6.38.0 task-sleep-wake"
and "6.38.1 sleep-card-toggle-switch", both built on 6.37.1) merged onto 6.42.0
by a three-way merge against 6.37.1. Those two version numbers collided with the
sync releases, so the feature is renumbered here as one release.

### Added
A moon button at the far-left edge of every task and group card. Tapping it
hides the item for the length set in the new **Sleep** field on the Edit screen
(same `NdNhNmNs` text format, 1s–365d and live preview as Quota Limit). The
Sleep card has an on/off switch like Quota Limit: OFF (default) = no sleep time
and the field is hidden; ON reveals it, and an empty or invalid time is rejected
on save. Sleeping a running task stops its timer first.

A sleeping task behaves as if it did not exist: no list row, no scheduling, no
share of the sibling average, CPU shares, or DL/RT/quota maths; symlinks and
hardlink placements vanish with it. Sleeping a group hides its whole subtree
without writing to the children.

**Wake placement** follows Linux `place_entity()` for a woken task: the lag it
slept with (clamped to ±one time slice) is restored with the `(W+w)/W`
compensation, so sleeping can neither erase debt nor discard credit. A child
whose own timer ran out while its group slept is placed right after the group
wakes. There is no alarm: a task is hidden while its stored wake time is ahead,
and the foreground 1 s tick (and resume) runs the wake pass.

The third tab gains a pop-up (tap it while selected): **Completed** or **Sleep**;
Sleep lists sleeping items soonest-first with a "Wakes in …" countdown, and the
moon there wakes the item now.

### Storage
Schema 29 → 30: `sleepDurationSeconds`, `sleepUntilEpoch`, `sleepLag` on
`tasks` (a justified exception to the freeze, recorded in TaskSchemaFreezeTest).
Backup JSON carries them. **Room writes `schemas/…/30.json` on the first build —
commit it.**

### Merge notes (what changed relative to the standalone feature)
- Dropped the feature's edit to `task-field-classification.kt`: that file was
  removed in 6.38.0 (sync now discovers columns itself; the three new columns
  sync like any other, nothing to register).
- `sleep-delegate.kt` called `triggerSyncExport()`, which no longer exists. The
  call was removed: `sleepTask` / `wakeExpired` already publish `task.saved`.
- `TaskRepository.recalculateScheduleCache` (6.39.0) now uses the awake-only
  task list, exactly like `refreshAllAndReturn`, so a sleeping task cannot skew
  the order other users compute after a sync.
- Freeze-test help text no longer names the removed `SyncFieldGuard`.
- 3 new sync tests: sleep columns reaching a user that has not updated yet; a
  task from an old build arriving with the sleep columns defaulted; every user
  waking the same task settling on one value and then going quiet.

### Known, not caused by this merge
`TaskSchemaFreezeTest` fails on the original 6.37.1 as well: its list has 53
frozen columns, the entity has 59 (six load-average columns were never added to
the list). With sleep it is 56 against 62. Left for you to decide.

### Verified / not verified
Ran off-device: the feature's own tests (62 scheduler incl. wake placement, 9
sleep rules), 60 sync tests, and a parse check of all 25 changed Kotlin files.
Not built: the Android code (Room migration, screens, layouts).

## 6.42.0 — One alarm across phones: stop everywhere, Swarm / Handover, background sync (build 3 of 3)

### Why
Timer state synced, but an alarm is local: stopping it on one phone wrote
nothing shared, so the other phone kept ringing. (Sync also deliberately left
a ringing alarm alone.)

### Added
- **Stop on one, stop on all.** A shared alarm record (task + run start)
  carries "stopped" to every phone (within a second or two over the live
  Bluetooth link), where it stops exactly like the Stop button. Works for every
  stop path: in-app, notification, full-screen alarm, the 1-hour limit, and a
  stop in another Android user of the same phone.
- **Ring modes** ("When a timer ends" on the sync screen; one setting for all
  phones): *Swarm* (default), every phone rings; *Handover*, only the phone
  that last started, paused or changed a timer rings, the others show the
  alarm silently and stop with it. A phone that cannot reach the ringing phone
  rings anyway. An alarm already stopped elsewhere does not ring.
- **Keep syncing when the app is closed** (Bluetooth section, off by default):
  a background service with a permanent silent notification keeps the link up,
  merges what arrives, and schedules or cancels this phone's alarm to match a
  timer started or paused on the other phone.
- **Duplicate history guard:** a run credited on both phones leaves one entry,
  the same one on every phone.
- Topic `alarm.should-ring-requested`; `AlarmRingingEvent.silent`.
  Permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- 9 tests with the real event bus and sync engine (57 total).

### Changed outside sync (small)
- alarm-ringer asks the ring question at expiry (1 s timeout means ring).
- sound and vibration skip an alarm marked silent.

### Fixed
- SYNC-ARCHITECTURE.md had lost its "6. Bus topics" heading in 6.40.0.

### Not verified by a build
The background service, alarm-ringer change, coordinator wiring and screen
were type-checked (alarm-ringer: reviewed by reading) only. Background sync
between Android users of one phone depends on Android and is untested.

## 6.41.0 — Bluetooth: live link, changes cross in about a second

### Why
A timer start took up to a minute or more to reach the other phone: exchanges
ran on a once-a-minute timer, only one phone was allowed to dial, and every
exchange opened a new Bluetooth connection.

### Changed
- **One live connection** while the app is open on both phones. A save (timer
  start, pause, any task change) is written to the sync folder 0.4 s later and
  sent across at once; either phone can start it. Requests during a transfer
  are folded into one more. Target: 1–2 s from tap to the other phone. On the
  test link a round took 2 ms; the radio adds its own time.
- **Battery:** the link is silent between changes except a 1-byte keep-alive
  every 25 s, and closes when the app leaves the screen. A phone dials as soon
  as its app opens, so retries are rare: 30 s, doubling to 5 min (was 1 min
  doubling to 10). Either phone may dial; if both do at once, both keep the
  same link.
- **Card writes while connected:** each save is written to the card within
  0.4 s while a link is live (the 5-minute batching still applies without one).
- **Protocol version 2.** Update BOTH phones: a phone on 6.40.0 is told
  "different sync version".

### Added
- `DeviceSession` (live link) and 5 tests: a change on either phone crossing
  without reconnecting, both phones asking at the same moment, a burst of 50
  requests costing at most 2 rounds, an idle link surviving on keep-alives, and
  a link ending cleanly when the other phone leaves.

### Not verified by a build
The link and coordinator changes were type-checked against stubs; the
protocol is tested over in-memory pipes, not over a real Bluetooth socket.

## 6.40.0 — Sync between two phones over Bluetooth (build 2 of 3)

### Added
- **"Another phone (Bluetooth)"** on the Multiuser Sync screen: choose a phone
  already paired in Android's Bluetooth settings, on both phones. While the app
  is open on both, they exchange changes about once a minute and on Sync Now.
  Every Android user on each phone takes part through the shared sync folder.
- **How it works:** Bluetooth copies the sync folder's journal, snapshot and
  ack files between the phones (`DeviceExchange`); the normal sync reads them.
  No change to how data is merged. Each phone is the authority for its own
  users' files; copies on the other phone are only deleted after the originals
  were. An interrupted exchange continues where it stopped.
- **Clock check:** the phones measure their clock difference on every exchange;
  above 2 s the sync screen and event log say so (timers would look off).
- Topic `sync.device-exchanged` for the event log. Permission
  `BLUETOOTH_CONNECT` ("Nearby devices"), asked when you switch it on — once in
  every Android user that should take part. Bluetooth is optional
  (`required="false"`).
- 10 tests: two simulated phones over a pipe — changes both ways, a timer
  started on one phone stopped on the other, nothing sent when up to date, a
  link cut mid-transfer, deletions following the home phone only, a phone that
  lost files gets them back without the home phone deleting anything, a late
  phone catching up from a copied snapshot, clock measurement, a slow clock
  losing nothing, and unsafe file names rejected.

### Limits
- Both phones need the app on screen; no background listening yet (with build 3).
- Exactly two phones.
- The radio code (`bluetooth-link.kt`), the coordinator wiring and the screen
  were type-checked against stubs only — never run. The exchange protocol is
  tested; the Bluetooth socket under it is not.

## 6.39.0 — Sync writes only what changed: local scheduler values, confirm-then-delete, batched pushes

Includes everything in 6.38.2 (journal cleanup fix). Named 6.39.0 rather than
6.38.3 because it adds behaviour, not only fixes.

### Why
Measured against the real schema: every pause rewrote `lag`, `eligibleTime`
and `virtualDeadline` of ALL active tasks, and those travelled through sync
(~40 KB per start/stop with 200 tasks); snapshots were rewritten every 2 MB of
journal regardless of database size; every save was pushed to the card.

### Changed
- **`tasks.lag / eligibleTime / virtualDeadline` are no longer synced.** Each
  user recomputes them after a merge (`TaskRepository.recalculateScheduleCache`,
  one transaction, only changed rows). The update trigger now fires only when a
  shared column really changed, so Room's all-column `@Update` no longer marks
  rows dirty. Card writes per start/stop: 40 KB → ~1 KB (simulation).
- **Confirm-then-delete.** Every member publishes a tiny ack (how far it has
  read in each journal). A journal file is deleted once a snapshot covers it
  and every member has confirmed it; a member silent for 3 days is no longer
  waited for and catches up from the snapshot.
- **Snapshots only when they pay for themselves:** after the journal written
  since the last one reaches 4× its size (min 2 MB). Total writes ≤ 1.25× the
  real changes for any database size.
- **Pushes are batched:** idle 5 min / at most every 15 min / immediately on
  leaving the app (was: 0.8 s after every save).
- **Journal files are 128 KB** (was 512 KB): finer deletion.
- **Compact bookkeeping:** partial index for baseline rows, compact per-column
  clocks for `tasks`.
- **Chunked, compressed first sync:** history is handed to the card batch by
  batch (the outbox no longer holds it all, which left ~10 MB of unused space in
  the database file) and large batches are written as compressed frames.
  Update every user before the first big sync: builds before this one skip
  compressed frames.

### Added
- **Self-check:** members at the same point in the journals compare a
  fingerprint; a difference triggers a fresh snapshot and a 24 h merge of the
  others' snapshots (never removes data). Reported in the event log.
- `ScheduleCache` partial entity + `TaskDao.updateScheduleCache`.
- Tests: local-only columns, Room-style full-row updates, compressed first
  sync, snapshot amortisation, ack-based deletion, absence cut-off, drift
  detection and repair, no false alarms (33 total).

### Measured (simulation, not a phone)
Per start/stop: 118 KB (old) → 1 KB. One week of 1,500 cycles with a second
user absent: 1.3 MB in 10 files; the absent user catches up in one read.
Database growth from sync bookkeeping: +295% → +102%; local page writes: ~10× → 1.7×.

### Not verified by a build
New Room partial-entity `@Update` and the coordinator/UI were type-checked
against stubs only. Expect small compile errors on the first Gradle build.

## 6.38.2 — Journal files were never deleted while the app was in daily use

### Fixed
Old journal files piled up on the SD card without limit for any user who used
the app regularly (measured: 65 files / 32 MB after a simulated week of heavy
use). Cleanup waited until the newest snapshot was 48 h old, but a busy user
writes a new snapshot every ~2 MB, so that never happened. Each journal file
now has its own clock, started when the file is closed, and is deleted once it
is covered by a snapshot AND 12 h old. Measured after the fix: a flat ~3 MB on
the card (5 journal files + 2 snapshots) across the same week. Files from an
earlier build start their clock on first run, so the existing backlog clears
about 12 h after the update.

A user who does not open the app for longer than that catches up from the
snapshot instead of the journal (verified: week-long absence, all data equal,
2 s instead of 18 s on the test machine). The grace period was shortened from
48 h to 12 h.

### Known, not fixed here
Every task pause rewrites the three derived scheduling values of ALL active
tasks, and those travel through sync (about 40 KB per start/stop with 200
tasks). Keeping them local to each user would cut that to ~1 KB; pending
approval. Also pending: compact the per-row bookkeeping that grows each user's
own database.

## 6.38.1 — Fix crash on every task save when sync is installed

### Fixed
`android.database.sqlite.SQLiteConstraintException: UNIQUE constraint failed:
_sync_dirty…` killed the app on the first task write. The change-capture
triggers used `INSERT OR REPLACE`, but SQLite lets the statement that fires a
trigger override the conflict clause of statements inside it, and Room's
generated `UPDATE OR ABORT` / `DELETE` / `INSERT OR ABORT` all specify one. An
UPDATE marks the row twice (old and new key), so the second mark became a
constraint violation. The triggers now use an update-then-insert-if-absent
pair that cannot conflict whatever the outer statement says. The trigger text
is rewritten automatically the next time the app starts (`SyncEngine.init`).
The unit tests missed it because they wrote with plain SQL; a regression test
now writes the way Room does and was confirmed to fail on the old triggers.

## 6.38.0 — Multi-user sync and restore rebuilt (build 1 of 3)

### Why
The whole-database-file sync could erase everyone's data from an empty or
stale user, crashed/restarted the app on every received change, could be torn
by a write cut short, broke between app versions, and could collide with a
restore. Full description: `docs/manual/SYNC-ARCHITECTURE.md`.

### Changed
- **Sync is now a change journal.** Each user keeps their own database; only
  small stamped lines describing changes are appended to the shared folder
  (one file per user, append-only, checksummed). Merged into the live
  database — no file swap, no restart. Newest change wins, per column for
  `tasks`. An empty user can only add.
- **Restore no longer replaces the database file or restarts the app.** It
  merges the backup's rows through the same engine, with two modes: *Add
  missing* (changes nothing you have) and *Replace*. Works with a backup from
  any app version. Sync and restore share one lock.
- **Automatic safety copies** before every restore and before first joining;
  the last five can be restored from the Data & Backup screen.
- **task-list-screen no longer imports multi-device-sync** (flagged debt since
  v6.10.8). Toolbar dot, sync tap and "data changed elsewhere" are bus topics.
  The "Sync received — reloading…" restart is replaced by
  `SyncReconcileDelegate`, which re-reads the timer in place.
- **Settings are per user**; they are no longer copied between users by sync.

### Added
- Topics: `sync.status-changed`, `sync.now-requested`, `sync.local-published`,
  `sync.remote-applied`, `sync.failed`, `sync.maintenance`,
  `backup.restore-requested`, `backup.restore-finished`, and the request topic
  `backup.safety-copies-requested`. Every sync step shows in the event log.
- `SyncCoordinator` (injected singleton) replaces the `MultiUserSyncManager`
  global object and `TaskSavedSyncHandler`.
- 24 JVM unit tests running the real merge SQL against SQLite
  (`sqlite-jdbc`, test-only).

### Removed
- Topics `backup.import-requested` and `task.conflict-detected`; the conflict
  dialog; `SyncFieldGuard`, `TaskFieldClassification` (+ test), `SyncConflict`,
  `SyncState`, `SyncWriteStats`. Run `scripts/apply-6.38.0-deletions.sh`.

### First run (important)
Update the app in **every** Android user first. Then switch sync on first in
the user whose data is most up to date; then in the others. A user whose copy
is old should choose *Shared only* when asked.

### Not verified by a build
This release was written without access to an Android build. The merge engine
is unit-tested; the Android-facing files were type-checked against stubs only.
Expect to fix small compile errors on the first `./gradlew assembleDebug`.

## 6.37.1 — Quota chain now collapse-independent (real fix, not display-based)

### Fixed
6.37.0's fix read the ancestor chain off the currently-rendered display
list — correct at any hardlink depth, but a collapsed ancestor anywhere
above the selection removes it (and everything below it) from that list
entirely, so the chain came back empty and the bar fell back to neutral —
for real tasks and hardlinks alike, at whatever depth the first collapsed
group happened to sit. Quota state doesn't stop existing just because a
folder is collapsed; reading from something collapse-PRUNED was the wrong
source, not a small bug in how it was read.

Replaced with `findAncestorChain`: a direct depth-first search over the
FULL real + membership tree from root (the same `EEVDFScheduler.withMemberships`
data and door-threading the list builders use), with NO collapse-based
pruning at all. A hardlink or symlink nested inside another hardlink's
position is resolved by finding the actual path through the tree that
reaches it — not by re-deriving door logic independently a third time.

## 6.37.0 — Quota ancestor chain now read from the display list (Option B)

### Fixed
`TaskInstanceRef.ancestorChain` only resolved ONE hop of placement-aware
traversal correctly (a hardlink/symlink's own host), then fell back to
walking real `parentId`s above that. Correct for a single hardlink; wrong
the moment two or more were stacked (a host group only reachable via
another hardlink) — the walk silently left the path actually on screen and
reported an unrelated branch's quota state, which is why severity appeared
to behave arbitrarily at multi-hop depth (landing on segment 1 or slamming
to segment 7 fast with nothing in between).

Replaced with `ancestorChainFromDisplayLists`: reads the chain directly off
whichever on-screen list (Schedule or Queue tab) currently renders the
selected instance, instead of re-deriving it from the task graph a second
time. The list builders already thread the correct door through arbitrary
depth — any mix of real/hardlink/symlink hops — because that's what they
already have to do to draw a row in the right place at all; depth 3 and
depth 100 now cost the walk nothing extra, since there's only one
implementation of "find the ancestors" left, not two that can disagree.

Removed the now-unused graph-walking `TaskInstanceRef.ancestorChain` —
deliberately not kept around as a fallback, since leaving the disproven
approach in place only invites it to be reached for again later.

## 6.36.5 — Corrected fast-blink threshold to 128x (doc fix, code was already right)

### Fixed
The fast-blink threshold constant was already correctly set to 128x, but
its surrounding doc comments still described 64x as the fast-blink point —
a real inconsistency between what the code does and what it claims to do.
Corrected: 64x–127x blinks segment 7 at the normal rate (same as segments
1–6); 128x and above switches it to the fast rate.

## 6.36.4 — Fixed compile error: unsafe call on nullable BlinkHandle

### Fixed
`currentHandle.intervalMs` in one `when` branch didn't compile — Kotlin's
smart-cast from the earlier `currentHandle == null` branch doesn't carry
across to a separate branch condition. Changed to a safe call
(`currentHandle?.intervalMs`); no behavior change, since that branch is
only reached once the prior one has already ruled out `currentHandle` being
null.

## 6.36.3 — Fast blink past 64x overage

### Added
Once the worst overage anywhere in the ancestor chain reaches 64x, the
blinking segment switches from the normal 700ms toggle to a faster 250ms
one — "off the end of the scale" now reads as faster, not as stuck at the
same rate no matter how bad it gets. This can only ever affect the last
(7th) segment: reaching 64x overage always computes segment index 6 on its
own, so there's no code path where the fast blink shows on an earlier
segment.

## 6.36.2 — Blink changed from fade to hard on/off (red / neutral)

### Changed
Replaced the alpha-fade animation with a discrete two-state toggle: "on" is
the quota-red color, "off" is the same neutral track color the bar already
uses for "nothing active" — not a fade to transparent/black. Implemented as
a small self-rescheduling toggle (700ms per half-cycle) rather than
Android's `Animation` system, since that only interpolates continuously and
can't express a hard cut between two fixed colors. Added `ids.xml` for the
tag key used to track which segment is currently toggling.

## 6.36.1 — Fixed blink never appearing: segments rebuilt every second

### Fixed
`buildPhaseStatusSegments` tore down and recreated all 7 segment views on
every call — and this bar refreshes every second, on every quota tick. A
blinking segment's animation restarted from full opacity every single time
its View was recreated, so it could never actually complete a visible fade.
Segments are now created once and updated in place on every later call;
the blink animation is only (re)started when the blink target actually
changes, not on every refresh. This also removes the "tear down all 7,
then rebuild" window entirely, which was a plausible source of the
inconsistent/partially-stale colors also reported.

## 6.36.0 — Quota-overage blink on the phase-status bar

### Added
When the phase-status bar has nothing else to show (exactly `[QUOTA]`
active — no delay/wait mixed in), one segment now slowly pulses to indicate
overage severity: the root-to-current ancestor chain (placement-aware, same
as the existing quota-exhaustion check) is walked, the WORST overage ratio
found anywhere in it is taken (not just the selected task's own — a distant
ancestor at 8x correctly outweighs the task's own 2x), and each doubling of
that ratio advances the blinking segment one further: 1x → segment 1, 2x →
segment 2, 4x → segment 3, capped at segment 7. Any other active state
(delay/wait) disables blinking entirely and reverts to the existing static
bar — the gate is checked before the severity calculation, not after.

## 6.35.2 — Centralized the door: TaskInstanceRef.of() ignored inherited membership

### Fixed
`TaskInstanceRef.of(item)` — the factory every call site uses to build a
ref from a row — only ever read a row's OWN direct `membershipId`, never
falling back to `entryMembershipId` (the door a leaf inherits from an
ancestor hardlink placement it's merely nested inside). `matchesInstance`
had the identical gap on the comparison side. Only one call site
(`onRunClick`) had ever manually combined the two fields correctly — every
other consumer of `.of()`, and the comparison function itself, disagreed
with it. A ref could be built correctly at the moment of selection and then
fail every subsequent "is this the one" check, because the type's own
factory and its own comparison function used different rules than the one
place that built it right.

Fixed centrally, once, in the type itself — `.of()` and `matchesInstance`
now both use the same combined-door logic — instead of requiring every
caller to remember to combine `membershipId ?: entryMembershipId`
correctly on its own, which is exactly how this was missed for as long as
it was. Simplified `onRunClick` to just call `TaskInstanceRef.of(item)`
directly now that the type itself gets this right.

## 6.35.1 — The actual dominant bug: field-refresh calls silently reset placement

### Fixed
Every place that updates a field on the ALREADY-selected task (tick, pause,
reset, expiry, notice reset, post-run vruntime credit) calls
`currentTaskOwner.set/setAsync` again — and the default parameter recomputes
"real" every time, since it has no way to know these are refreshes rather
than a real switch. Two of these fire constantly:

- **The per-second tick observer** — every single tick while any timer
  runs was silently resetting the placement back to real. This is almost
  certainly why a hardlink selection appeared to "become real" the moment
  Start was pressed — the tick fires within the same second.
- **The expiry observer** — reset to real at the exact moment accounting
  matters most, explaining "when it expires it credits the real task."

Also fixed the identical pattern in `pauseTimer`, `resetTimer`,
`NoticeStateMachine`'s pause-phase reset, and the post-run vruntime-credit
refresh — all now explicitly preserve `currentInstanceRef.value` instead of
letting the default silently overwrite it.

**A third, entirely separate mechanism** with the same bug: phone-call
interrupt handling (`CallSwitchDelegate`) has its own `savedTaskBeforeCall`,
never touched by the earlier interrupt-delegate fix — same fix applied,
`savedRefBeforeCall` added alongside it.

**`jumpToFirst`** was discarding the found row's placement by grabbing
`.task` directly instead of building a ref from the whole row.

**Cold-start / cross-device recovery** (`StartupRecoveryDelegate`,
`TaskViewModel`'s dead-Activity recovery): a bare DB query has no placement
info of its own — now falls back to whatever was persisted for that same
task, instead of defaulting to real. One of these had a compounding bug:
it defaulted to real AND then immediately re-saved that wrong "real" back
into the persisted preference on the very next line — permanently
destroying a correctly-saved placement on every cold start.

## 6.35.0 — No bare task id may mean "the selected/running one," anywhere

### Fixed
Found via a screenshot showing a real task and its hardlink both displaying
a live "00:02" countdown simultaneously: `TaskAdapter.runningTaskId` was a
plain `String?`, matched by bare task id (`task.id == runningTaskId`) —
completely separate from all the `TaskInstanceRef` work in 6.34.0, and never
touched by it. Every row sharing that task id — real, hardlink, or symlink —
lit up as "running" together, because the one signal deciding that was never
placement-aware to begin with. This was the actual visible bug the whole
`TaskInstanceRef` effort had been chasing.

### Changed
Applied the same rule everywhere this pattern was found in the adapter
layer, not just the one spot: `runningTaskId` → `runningInstance`,
`selectedTaskId` → `selectedInstance` (tap-highlight, same bug shape), both
now `TaskInstanceRef?`, matched via `TaskDisplayItem.matchesInstance` (exact
placement) instead of bare id. `positionOf` gained a placement-aware
overload alongside its existing bare-id one (kept separate — the bare-id
version is still legitimately used by the unrelated notice-phase mechanism).

**Persisted selection** (survives app restart) had the identical gap in
storage form: only the task id was ever saved, so a hardlink/symlink
selection reverted to the real placement after every reboot — including the
*read-back* path on cold start, which is worth calling out on its own,
since it would have silently undone the fix even after fixing the save
side. Added two more optional preference keys (membership id, symlink id)
alongside the existing one; old saved prefs with only a task id simply mean
"no membership/symlink" — safe, no migration needed.

### Explicitly not touched
Vruntime/quota per-hardlink accounting — confirmed working correctly and
intentionally left exactly as it is; this pass was scoped only to
selection/running-state representation.

## 6.34.3 — Fixed missing door propagation in the class-tab builder

### Fixed
`buildFilteredScheduleList` (the Deadline/Realtime/Fair tab builder) never
threaded the "door" (which hardlink placement, if any, a row was reached
through) down its recursive walk at all — a leaf merely nested inside a
hardlinked group's subtree had no way to record that, and silently fell
back to the real placement the moment it was selected. This was a separate,
concrete gap from the current-task/ref race fixed in 6.34.2 — likely the
actual remaining cause of "hardlink with a clean quota chain still shows
red." Fixed by threading the door through `render`/`renderOwnedSubtree` the
same way the older, pre-existing list builders already do it correctly.

### Known related gap (pre-existing, not introduced here)
There is no equivalent mechanism anywhere in the app for a leaf nested
inside a *symlinked* group — only hardlink placements have a "door" concept
today. A symlink pointing at a group and expanded to show its real children
has no way to record that inheritance either.

## 6.34.2 — Fixed a race between current-task and its placement ref

### Fixed
`CurrentTaskOwner.set`/`setAsync` updated `_current` before `_instanceRef`.
Since a `MutableLiveData` notifies its observers the instant its value is
set — synchronously for `.value =`, and in call-order for `postValue` on the
same thread — anything observing `currentTask` (like the quota-exhaustion
refresh) could fire and read `currentInstanceRef.value` one step too early,
seeing the PREVIOUS placement instead of the one just selected. This is what
caused both regressions reported after 6.34.0: a hardlink with a clean
quota chain still showing red (stale ref from before), and a real task's
exhausted-parent indicator only appearing after the task actually ran
(waiting for a later, coincidentally-correct refresh). Fixed by setting the
ref first, the task second, in both functions.

## 6.34.1 — Fixed TaskInstanceRef visibility compile error

### Fixed
`TaskInstanceRef` and its extension functions were `internal`, but
`TaskViewModel` (a public class) exposes them through public members
(`currentInstanceRef`, `setCurrentTask`) — Kotlin doesn't allow a public
declaration to expose an internal type. Made `TaskInstanceRef` and its
extensions public to match the visibility of what already exposes them.

## 6.34.0 — TaskInstanceRef: placement-aware selection (permanent link fix)

### Added
`TaskInstanceRef` (task id + symlinkId + membershipId) — reuses the same
identity triple `TaskDisplayItem`/the RecyclerView diff callback already use
for row identity, promoted to a first-class concept. `CurrentTaskOwner` now
tracks this alongside the bare `Task` (`vm.currentInstanceRef`), so "what's
selected" can finally answer not just *which task* but *which placement of
it* — a real task, or a specific hardlink/symlink.

This closes one root cause behind four independent-looking bugs that were
all the same underlying gap:

### Fixed
- **Quota-exhaustion indicator** walked the wrong ancestor chain for a
  linked selection (used the real task's own parent instead of the actual
  placement's host).
- **Tap-to-run** discarded placement context immediately for symlinks (never
  handled at all before this) and only partially for hardlinks.
- **Interrupt jump/return** (`InterruptDelegate`) saved a bare task id as
  "what to return to," so returning from an interrupt always landed on the
  real placement, never the hardlink/symlink actually running before the
  interrupt (in-memory only — a return-to saved right before a reboot still
  falls back to the real placement, since there's no DB column for the
  placement yet).
- **Alarm/expiry re-seat** (`stopAlarmSound`) had the same loss — now
  captures and restores the placement active at the moment of expiry.
- **"Next"/"Global Rotate"/"Auto"** (`rotateSiblings`, `rotateGlobal`,
  `selectAutoNextTask`) matched "current" by bare task id, which could
  anchor on the wrong occurrence when a real task and a link to it were both
  on screen, and could land on/return the wrong placement too. All three now
  match and land by exact placement.

### Known remaining gap
The DB has no column yet for persisting *which placement* an interrupt
return-to was saved through — only the in-memory case is fully fixed. A
schema change would be needed to close this the rest of the way.

## 6.33.4 — Fixed timer-card flicker on alarm stop

### Fixed
`stopAlarmSound()` cleared the "alarm ringing" flag before re-seating the
task that triggers the card's normal (Start/Pause) content. That left a
brief window where neither "alarm ringing" nor "task selected" was true —
read as Hidden, closing the card — until the very next line re-seated the
task and reopened it. That close-then-reopen was the flicker. Re-seating
the task first, then clearing the alarm flag, means a task is already
selected the moment the flag clears, so the card goes directly from
expired to its normal content in one step. No button or functional
behavior changed — this is a statement-order fix only.

## 6.33.3 — Phase-status bar always drawn, neutral when idle

### Changed
The strip no longer goes `INVISIBLE` when nothing is active — it now always
draws all 7 segments, same size either way, using the neutral `divider`
track color at rest (same tone the card's other progress bars already use
for "nothing here yet") instead of disappearing. Same principle as a plain
`ProgressBar` always showing its empty track at 0%.

## 6.33.2 — Matching reserved space on the expired/alarm card

### Fixed
Added an inert spacer of the same height/margin as `viewPhaseStatus` to the
expired/alarm block, so the timer card doesn't change overall height when
it swaps between the countdown and expired/alarm states. Purely a layout
matcher — this block doesn't run notice/quota state, so nothing is wired to
the spacer.

## 6.33.1 — Phase-status bar reserves its space when empty

### Fixed
Switched from `GONE` to `INVISIBLE` when no state is active, so the strip's
space stays reserved on the card instead of collapsing and shifting the
button row up/down as states come and go.

## 6.33.0 — Generalized phase-status bar; quota-exhaustion indicator

### Added
`viewPhaseStatus` (the timer card's status strip, previously hardcoded to
NOTIFICATION delay/wait only) is now a general-purpose indicator any feature
can report through. It's built as 7 fixed segments; when more than one
state is active at once, colors ping-pong across the 7 segments in a fixed
priority order (quota outermost, wait innermost) rather than picking just
one to show.

New: quota-exhaustion detection, checked from the root of the task tree down
to the currently-selected task — the first exhausted ancestor found (own
task included) lights the strip red; nothing below that ancestor needs
checking once one is found.

### Changed
Moved the strip from the bottom edge of the Next/Start/Int button row to
above it. Colors now come from real design-system tokens
(`quotaBarExceeded`, `timerYellow`, `timerGreen`) instead of hardcoded hex.

## 6.32.2 — Fair tab no longer "owns" a subtree

### Fixed
The "owned subtree — show everything inside unconditionally" rule (built for
Deadline/Realtime-owned groups, e.g. a group whose own class is Deadline
showing its entire contents regardless of each child's own class) was
accidentally also firing for plain Fair-class groups, since Fair is every
ordinary group's default class. On the Fair tab this dragged in Deadline/RT
descendants unfiltered. Only Deadline/Realtime can trigger unconditional
full-subtree inclusion now — a Fair-class group always recurses through
normal relevance filtering, same as any other plain container.

## 6.32.1 — Realtime same-level sort uses window proximity, not priority

### Changed
Sibling Realtime groups/tasks on a class-filtered tab now sort by which one
is closest to its next window transition — time left before it closes if
currently active, time left until it opens if not — instead of raw
`rtPriority`. Matches the Deadline tab's "nearest in time" logic.

## 6.32.0 — Same-level urgency sort on class-filtered tabs

### Added
At every depth, independently, a group's children are ordered by the most
urgent Deadline/Realtime/Fair match reachable inside each child —
recomputed live as task state changes. This is same-level ordering only: it
never changes which parent shows, or how a parent ranks among *its own*
siblings (that would be hoisting, deliberately excluded — see 6.30.0).

### Fixed (self-caught, pre-export)
Mid-edit, an accidental deletion of a function's own signature line left its
body orphaned under the wrong function name. Caught via a brace-balance
check before shipping.

## 6.31.3 — Auto-dive no longer blocked by an empty/collapsed sibling

### Fixed
A collapsed or empty group was still being counted as a real "candidate at
this level" when deciding whether to auto-dive, so closing an empty Group B
stopped "Next" from diving into Group A even though A had real children to
rotate through. A group now only counts toward that decision if it can
actually produce something to land on.

## 6.31.2 — Next/rotate navigate by screen position, not `task.parentId`

### Fixed
Rewrote the structural lookups behind "Next" to use on-screen depth and list
order instead of `task.parentId`. This is what makes hardlinks and symlinks
fully navigable: a linked row's underlying task still carries its original
real parent, so parentId-based lookups silently failed to treat it as a
child of wherever it's actually displayed. Depth/order-based lookups don't
need to know or care whether a row is a real task, a hardlink placement, or
a symlink — only where it's drawn on screen right now.

## 6.31.1 — Restored real depth-resolution mechanism, drill-down aware

### Fixed
Reverted an earlier from-scratch reinvention of "Global Rotate" (which
anchored depth resolution from the root and had a real bug) back to the
original correct mechanism — re-pointed at the screen-bound display lists,
with one necessary addition: depth resolution now starts from whatever
parent id is actually common to the visible list's own top level, instead
of a hardcoded `null` root. That's what makes it correctly stay within a
drill-down frame instead of trying to escalate past what that frame can
show.

## 6.31.0 — "Global Rotate" restored (screen-aware rebuild)

### Added
Brought back the "Global Rotate" menu toggle (removed in 6.30.2) and its
auto-depth-diving behavior — rebuilt to read only from the screen-bound
task lists, so it can no longer drift out of sync with whatever tab, class
filter, or collapse state is currently showing.

## 6.30.2 — "Global Rotate" removed; "Next" fully screen-aware

### Changed
Every button in `SchedulerDelegate` (Next, rotate, jump-to-first) now reads
directly from the same `LiveData` the screen's adapter is bound to, instead
of separately re-querying/re-deriving "roughly the same list" — the root
cause of Next silently doing nothing once class-filtered tabs existed.

### Removed
"Global Rotate" and its raw-tree-walking implementation, as part of the same
pass (later restored correctly in 6.31.0 / 6.31.1).

## 6.30.1 — Hardlink/symlink class-tab fix

### Fixed
Hardlink placements (`TaskMembership`) weren't visible on class-filtered
tabs at all — the tree walk never called `EEVDFScheduler.withMemberships(...)`,
so a hardlink placement (which only exists as a synthetic entry that
function creates) was invisible. Each hardlink placement is now evaluated
independently along its own path, matching how the app already tracks
per-placement vruntime.

## 6.30.0 — Descendant-hoisting removed from the Schedule tab; link propagation added

### Fixed
The default Schedule tab's own row-ordering still had the "promote a
fair-class group because a descendant is Deadline/RT" behavior
(`hasActiveDlDescendant` / `hasActiveRtDescendant`) baked into its bucket
sort — a leftover predating this line's scheduler-fundamentals work. A
group's bucket is decided entirely by its own class now, never a
descendant's.

### Added
Symlinks/hardlinks gained correct "upward propagation" (a linked row shows
its real target's live class/state) on the class-filtered tabs. Every
button in `SchedulerDelegate` was moved onto the real screen-bound lists as
part of the same pass.

## 6.29.3 — Schedule/Fair menu rows get the same blank dot as Deadline/Realtime

### Fixed
All four popup-menu rows now carry the same blank/white status dot by
default, instead of only Deadline/Realtime having one — keeps every row's
label aligned regardless of which tab is selected.

## 6.29.2 — Popup-menu dot colors moved to design-system tokens

### Changed
Replaced hardcoded hex colors for the class-filter popup's status dots with
real `colors.xml` tokens (`classFilterDotBlank` / `OneActive` / `TwoActive` /
`ThreeOrMoreActive`), matching the existing `syncDotOK` / `syncDotError`
naming convention already used elsewhere in the app.

## 6.29.1 — Popup menu status dots

### Added
Deadline/Realtime rows in the class-filter popup carry a plain colored dot
reporting how many of that class are active right now: blank at 0, blue at
1, green at 2, red at 3+.

## 6.29.0 — Schedule tab class filter (Deadline / Realtime / Fair / Schedule)

### Added
Re-tapping the Schedule tab opens a popup letting the person narrow it to
one scheduler class. The tab's own label mirrors whichever class is
selected. Badge shows the count of classes more urgent than the current
selection that aren't visible in it.

### Fixed
Popup now centers on the actual tapped tab — was anchoring flush-left on
the whole tab bar regardless of which tab was tapped.

### Changed
"RT" relabeled "Realtime" in the popup menu (the on-card pill stays "RT").
"All" renamed to "Schedule" as the default/unfiltered state.

## 6.28.0 – 6.28.3 — Class-filtered tab tree correctness

### Fixed
Early iterations of the class-tab feature and its bug fixes: parent
structure preserved as a full real ancestor chain to root rather than
truncated to one level; collapse/expand made to work correctly inside a
filtered tab (a collapsed group used to disappear entirely instead of
staying as a re-openable row); "Next" made aware of the active filter so it
stopped jumping to tasks not actually visible on the current tab.

## 6.27.1 – 6.27.3 — Single clock source; current-task dispatch via bus

### Changed
Every capability now reads "now" through the kernel's one injected `Clock`
instead of calling `System.currentTimeMillis()` independently (`object`
singletons that Hilt can't field-inject use a swappable `var clock` test
seam instead). "Which task is currently running" is now owned by one
`CurrentTaskOwner`, announced over the event bus (`Topics.CURRENT_TASK_CHANGED`)
instead of ~30 scattered direct writes to a shared LiveData field across the
app.
