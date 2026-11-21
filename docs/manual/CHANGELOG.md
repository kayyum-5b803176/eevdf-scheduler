# Changelog

Covers the 6.0.0 line. Newest first. Each entry corresponds to one exported
zip; the zip filename is that change's diff, this file is the summary.

---

## 6.62.0 — Parent group picker: path mode (place an entry only where you picked)

Includes everything in 6.61.0. **Database 36 → 37.** `versionCode` untouched. The picker keeps doing exactly what it
did; path mode is a switch you turn on.

### The problem it solves
A new later-axis entry is copied into every place that includes it, so `hello world` added in `activity1` appeared
under every user of every type. Path mode says where it should exist instead, so nothing has to be deleted afterwards.

### Added
- **Path switch in the picker's title row.** On: tapping an entry ticks it (tap again, or None, to clear) and keeps the
  dialog open; one pick per axis; each chip shows its pick (`X · type5`, `Y · user1`, `Z · —`); after a pick it moves on to
  the next axis; a footer says `Exists only in: type5 › user1` (and `In group: activity1`) and **Done** places the row.
  Off — the default — nothing changes.
- **What the picks mean.** Picks are contiguous from the first axis. The deepest pick decides the task:
  - a **group** is the task's parent group, on that group's axis;
  - a **holder** (a leaf with the next axis's entries hanging under it, e.g. `type5`, `user1`) means *inside it*: the task
    becomes an entry of the **next** axis, at its top level;
  - every shallower pick must be a holder — it names the place the task exists in.
  `type5 + user1 + activity1` → in group activity1, only at type5 › user1. `type5 + user1` (z empty) → directly under user1,
  only there. `type5` alone → directly under type5, only there.
- **Only where you picked: one '−', one '+'.** The entry gets a single "hidden everywhere" mark (`axis_entry_hidden`) and a
  '+' on the one door you picked. It is therefore never copied elsewhere — including places created later — and there is
  nothing to delete or edit. In any other place's ACL tab it shows as "Hidden · all other places (tap + to add it here)";
  tapping + adds it there.
- **Each list only offers what exists.** The second axis lists only the entries included under the row you picked, the third
  only those included under that copy (layered with the entry's ACL, as before), the last axis groups only. A path can never
  name a place that is not there. The row being edited and everything inside it are never offered.
- **Back to the default.** A plain (non-path) pick on a placed entry saves it as "copied wherever included" again.
- **Confirmation** when re-placing an existing entry would remove its copies from other places (same dialog as a move).
- `AxisPath` (pure): what each axis lists and how picks become a placement. `AxisAclRules` gained the single '−'; the
  planner and reconcile honour it. Placement is written BEFORE the entry is saved, so no copy is ever made in a wrong place.

### Know this
- The picker does not remember a path when reopened; it starts empty.
- Picks must start at the first axis (no gaps): to put an entry "everywhere", use the plain picker as before.
- A group on the first axis can only be the last pick (an ordinary placement); it cannot hold later-axis copies.

### Verified / not verified
`axis-path-test.kt`: every list (holders vs groups, nothing until earlier axes are picked, only what exists under the row,
groups only on the last axis, layered ACL, placed-elsewhere entries, exclusion of itself) and every placement from your
examples plus the invalid ones. `axis-rules-test.kt` / `axis-reconcile-test.kt`: the single '−' (hidden everywhere, shown by
a door, group locks, later places do not pick it up, an existing entry loses its other copies). **Not built, not run.** On the
phone: add a task → Parent group → Path on → X type5, Y user1, Z activity1 → Done → Save: only type5 › user1 › activity1
has it; open type6 › user1's ACL tab and confirm it reads "Hidden · all other places", tap + and it appears.

---

## 6.61.0 — Move a task or group to another axis, from the parent group picker

Includes everything in 6.60.0. No database change, `versionCode` untouched. Nothing changes until you choose a
group on another axis.

### Added
- **Axis chips in the parent group picker.** `[ X ] [ Y ] [ Z ]` under the search field (from the axis list, so a
  fourth axis is a fourth chip). The chips choose WHICH axis's groups are listed; the list header says which
  (`All Groups · Y`) and the first row reads `None (root level of Y)`. The tick shows the current choice only while
  you are looking at its axis. Picking an item **on another axis moves the row there**, applied on Save.
- **The parent field says where it goes**: `Work` on X as before; `Y · Project B`, `Y · None (root level)` elsewhere.
- **Groups move whole**: the group and every real row inside it (completed ones too).
- **What a move does**
  - first axis → later axis: the row becomes a **template** (timer, vruntime, run counts, completion, sleep and
    semaphore state reset; configuration kept); copies hanging under it and hardlinks/symlinks to or inside it are
    removed; copies appear under every outer row that includes it.
  - later axis → first axis: every copy of it is removed and it becomes a normal task.
  - later axis → later axis: its copies are removed; copies on the new axis appear.
  - The id stays. ACL rules that named the moved rows (they listed the old axis) are dropped. Pinned weights are
    normalised again on both axes.
- **Save asks first, only when something is lost** — "Move "Write" to Y?" listing the reset, the copies and the
  links, and how many items move together. Nothing lost → no dialog.
- **Nothing silently unsafe**: a row that is running (or contains one) cannot be moved — the other chips are greyed
  with "Stop it to move it to another axis."; interrupt tasks stay on the first axis; a row cannot be moved under
  itself or anything inside it.
- **Add screen**: the picker opens on the axis of the Queue tab you pressed + from; choosing another axis there simply
  creates the row on that axis.
- Validation on the edit screen (pinned share, semaphore) now follows the TARGET axis and parent as you change them.

### Know this
- The picker only exists when groups are enabled in settings, so moving needs groups enabled.
- A scheduled wake that targets a moved row is left as it is.

### Verified / not verified
`axis-move-test.kt`: a template loses its running history and keeps its configuration; a group moves with everything
real inside it, completed rows included; copies inside are removed, not moved; a lone leaf moves alone; cycle-safe;
a row cannot be moved under itself or its contents; only losses ask for confirmation. **Not built, not run.** On the
phone: edit a task → Parent group → tap `Y` → pick a group (or None) → Save, confirm → Queue·Y shows it and each X
entry now holds a copy; edit it from Queue·Y → Parent group → `X` → Save → the copies are gone and it is a normal
task; move a group that holds tasks and confirm they all go; try it on a running task and confirm the chips are greyed.

---

## 6.60.0 — Axes: the ACL tab actually shows, on every axis tab

Includes everything in 6.59.0. No database change, `versionCode` untouched.

### Fixed (6.59.0 showed no ACL anywhere)
- **X leaves.** Once Y entries exist an X leaf is flipped into a container (`isGroup`). The check for "is this a
  container" read a LiveData nothing on that screen observed, so it was always empty and every such leaf was treated
  as a real group — no ACL. The check now reads the database (`axisEditTarget`), never a screen's state.
- **Queue·Y / Queue·Z cards had no ACL at all** (6.59.0 gave a definition no door). They have one now.

### Changed — what each edit screen's ACL tab lists
- **Edit an X entry (Queue·X, Schedule):** ACL tab lists the **Y** entries — that leaf's own door.
- **Edit a Y entry in Queue·Y:** ACL tab lists the **Z** entries only — the ACL for **every copy** of that Y entry,
  edited once. Breadcrumb reads `Z · every copy`.
- **Edit a Y copy (a row under an X leaf):** Config still edits the shared definition; the ACL tab is **that copy's
  own** list, layered over the entry's: a row set here wins, **Inherit means follow the entry's ACL** (shown as
  "from the entry's ACL"), and so does the "hide new" default. So one copy can differ from the others while the
  rest follow the entry. Breadcrumb reads `Z · this copy`.
- No tab on real groups, on copies/definitions that are groups (they hold no copies), or on the last axis.
- A change to an entry's door re-reconciles every copy everywhere; a copy's door only its own leaf.
- A copy's "hide new" switch stores nothing when it matches the entry's (it follows it); otherwise it stores its own.
- Deleting a definition also removes its own door.

### Added
- `Axes.definitionDoor` / `layerDoorOf`, `AxisAclRules.merge` / `effectiveHideNew`, `AxisEditTarget`,
  `TaskRepository.axisEditTarget`.

### Verified / not verified
`axis-rules-test.kt` plus: copy rule wins per key, a silent copy follows the entry, Show on a copy overrides the
entry's Hide for that entry only, a hidden group still locks its inside, hide-new precedence (copy, entry, none),
door-key helpers. **Not built, not run.** On the phone: Queue tab, X axis, long-press a task → ACL tab lists Y.
Queue·Y, long-press a Y entry → ACL tab lists Z, Hide one → it disappears from every copy of that Y entry.
Long-press a Y copy under an X leaf → ACL → Show that Z entry → only that copy gets it back.

---

## 6.59.0 — Axes: inclusion is an ACL, one tab per row, per copy

Includes everything in 6.58.0. **Database 35 → 36.** `versionCode` untouched. Run `scripts/apply-6.59.0-deletions.sh`
after extracting. With no y/z entries defined nothing changes.

### Changed
- **What a row includes from the next axis is the hardlink ACL, for axes.** Per definition entry, at any depth:
  Inherit / Hide / Show, inherited downward, deepest rule wins; hiding a group hides its whole inside and nothing
  inside a hidden group can be shown. The old switch-off list (`axis_exclusion`, topmost-only, no Show) is gone.
- **A "hide new" default per row**, like a hardlink's: with it on, only what is Shown appears, and definitions
  created later do not appear on that row by themselves. This is the "user-1 only a" case in two taps.
- **Every row with a next axis has its own ACL tab** on its edit screen: an X leaf lists the Y entries; each Y
  copy lists the Z entries — **its own list, not shared**, so the copy under one X leaf can include different Z
  entries than the copy under another. No tab on the last axis, on real groups, on copies of definition groups,
  or on the Queue·Y/Z definition cards (a definition has no outer row). Editing a copy still opens its definition
  for the config; the ACL tab on that same screen is that copy's own door.
- **Same screen as the hardlink ACL tab** (same layouts and row adapter): one level at a time, three circles per row,
  tap a group to open it, breadcrumb/back to go up, "hide new" switch, saves on every tap, locked rows under a
  hidden group. Every tap also brings that door's real copies in step. The two never coexist on one screen.
- **Deleting a copy by hand (or Clear completed) writes a Hide rule** for its definition at its door.
- The door key stays the stable path (`L`, `L/a`), so rules survive a copy being hidden and shown again.

### Added
- `axis_acl` (rule per door and entry), `axis_door` (the "hide new" default). `AxisAclRules` (pure) is asserted
  equal to the hardlink's `MembershipAclRules.resolve` over every combination of ancestors, rules and default, so
  the two cannot drift apart. The hardlink code itself is untouched.
- Migration 35 → 36 carries every old exclusion over row for row as a Hide rule (the door keys already had the ACL's
  format) and drops `axis_exclusion`.

### Removed
The "Axis inclusion…" overflow entry and dialog, `AxisChecklist`, `AxisRules`.

### Know this
- The Z list is per Y copy, so it only exists once that copy does (a copy is created by the background pass after
  the X leaf's ACL lets its definition through).
- Old Z rules were stored per (X leaf, Y entry) pair, which is already one door per copy — nothing is merged.

### Verified / not verified
`axis-rules-test.kt` (rewritten for the ACL): default all-included, Hide, hide new + Show, Show beats the default for
one entry, group hide locks its inside, deeper rule wins, hide new is direct-only, one axis only, cycle-safe.
`axis-acl-rules-test.kt`: axis rules equal hardlink rules for 2 defaults × 11 rule sets × 4 depths.
`axis-reconcile-test.kt`: plus hide new creates only Shown entries, a later definition does not appear, Show under a
hidden group stays hidden. **Not built, not run.** On the phone: edit an X leaf → ACL tab → Hide `b` and watch its
copy disappear; turn "hide new" on and Show only `a`; define a new entry and confirm it does not appear on that leaf;
open a Y copy → ACL tab → Hide a Z entry under one X leaf and confirm the same Y under another X leaf still has it.

---

## 6.58.0 — Axes: later-axis entries are real nodes

Includes everything in 6.57.0. **Database 34 → 35.** `versionCode` untouched. Run `scripts/apply-6.58.0-deletions.sh`
after extracting. With no y/z entries defined nothing changes (one cheap check, then out).

### Changed — this reverses three things 6.54.0–6.57.0 said were by design
- **Y/Z entries are real rows.** An included definition exists under each first-axis leaf as an ordinary task
  row (axisId `x`, a real `parentId`); a Y leaf holds its Z copies the same way. While it holds any, the parent
  is a container (`isGroup`) and flips back when the last copy goes. So `z2` has its own timer, Run, Complete,
  Sleep, Wake, Revert, semaphore slot, run history, alarm, backup and sync — every existing feature, because it
  *is* an existing row. **Run and Complete are no longer refused** (only the Queue·Y/Z *definition* cards still
  are: they are templates).
- **Next / rotate / auto select them like child leaves of a real group.** Nothing in the selection code
  changed — they are simply children now. The timer runs the node itself.
- **A run credits the node, then every ancestor** (the existing `creditAncestors`), exactly as a child of a real
  group. The 6.54.0 "credit all later axes the same seconds" step, `axis_instance`, `AxisDescent`,
  `selectNextPath`, `creditAxisRun` and the 6.57.0 virtual tree are removed.

### Added
- `axis_node` (which row is a copy of which definition, and where), `axis_container` (leaves that are containers
  only because copies hang under them). `AxisReconcile` (pure) decides create / refresh / remove;
  `TaskRepository.reconcileAxes()` applies it, idempotent, serialised by a mutex.
- **Definitions are edited once.** Saving a definition pushes its shared fields (name, priority, slice, class,
  quota size, DL/RT settings, load factor, semaphore config…) to every copy. A copy's own state — timer,
  vruntime, quota used, completed, sleep, wake, slot — is never overwritten, a running copy is left alone, an
  untouched timer follows a new slice. Editing a copy from a list opens its definition.
- **Per-scope switches decide existence.** Switching a definition off for a leaf removes that copy; switching
  it on creates one. **Deleting a copy by hand is the same switch-off**, so it does not come back (also for
  Clear completed). Deleting a definition deletes all its copies.
- A small `Y` / `Z` badge on copies. Reconcile runs at startup, when a leaf or definition is added or edited, when
  switches change, and right after each run (a leaf that was running when a definition appeared becomes a
  container then).
- `getChildrenOf(non-null id)` now finds children on any axis (it was first-axis-only since 6.53.0, which would
  have left a Y group's children behind when it was deleted or when inherited slices propagated).

### Know this
- **Rows scale with what you include.** Every included pair is a real row (leaves × entries); default is
  everything included, and the switches are how you control the count. The app keeps all active rows in memory,
  so thousands of pairs are fine and a million are not.
- The Queue tab (X) shows the copies as real children, because they are.
- The 34-era `axis_instance` table is dropped; the first reconcile after upgrading creates the copies fresh.
- A restore from the legacy `tasks.json` brings copies back as ordinary tasks (the node table is only in the
  `.db` backup, which is what normal restore uses).

### Verified / not verified
`axis-reconcile-test.kt`: create / skip-existing / remove switched-off and deleted, group subtree, parents before
children, one axis only; scope of a first-axis row and of a copy equals the checklist's key; parent placement;
a copy takes the definition's fields with fresh runtime; editing never touches copy state; untouched timer follows
a new slice; internal weight not copied. **Not built, not run.** On the phone: define two Y entries, open the
Schedule tab — each X leaf is now a group holding them; run `a` under one leaf, then the same `a` under another and
confirm their run counts differ; Complete one and confirm only that copy completes; Next from the top and confirm
it lands on the first Y copy; delete a copy and confirm it stays gone; rename the definition and confirm every copy
follows.

---

## 6.57.0 — Axes: the Schedule tab shows the real x → y → z tree

Includes everything in 6.56.0 (built and passed). No database change, `versionCode` untouched. With no y/z
entries nothing looks or behaves differently.

### Fixed (6.56.0 got this wrong)
6.56.0 only appended `Y: a › Z: p` to the category line of the X card. Every axis chains to the one above it, so
the Schedule tab must show the structure, not just the winner. That text is removed.

### Added
- **Schedule tab tree.** A first-axis leaf holds its included second-axis entries the way a group holds children;
  each second-axis leaf holds its included third-axis entries, and so on:

      x   Task-1            ▾
       y  Entry a           ▾      attached under THIS x leaf
        z Item p                   attached under THIS y leaf
       y  Entry b           ▸
      x   Task-2            ▸

  Same cards as every other row, indented by depth, with the group expand arrow on any leaf that has entries
  under it, a small axis badge (`Y`, `Z`) on later-axis rows, and hierarchical numbers (`1`, `1.1`, `1.1.1`).
- **Own copy, own runtime.** The same definition under two outer entries is two rows with two identities
  (axis, scope, id) and each shows ITS scope's vruntime, quota, DL budget and run count. Switch-offs are applied
  per outer entry, so counts and children differ per leaf.
- **Collapsed by default.** A leaf's children are built only when opened (one count per leaf otherwise), so
  1000 × 1000 stays manageable. A group inside a scope starts open like any group. Both survive restarts.
- **Run order.** Each level is ordered DL → RT → EEVDF over its own siblings, the same order the run's descent
  picks, so the first child of a level is what runs next there.
- **Class filter at every level** (Deadline / Realtime / Fair), by each entry's own class; a group stays only as
  context while a matching entry sits inside it.
- `AxisScheduleRows` (pure, `task-storage`) builds the rows from data and never mutates the shared lists.
  `TaskDisplayItem` gains `axisId`, `scopeKey`, `axisChildScope`, `axisChildCount`, `isAxisRow`; the diff
  identity now includes axis and scope.

### Behaviour on later-axis rows
Long-press edits the shared definition (every copy changes). Run and Complete are refused with a toast. Sleep,
wake, revert and reset-slice are hidden (they would write one scope's runtime back onto the definition). Delete
asks first and says it removes the entry everywhere it is included.

### Not changed on purpose
Next / Auto / rotate still read the ORIGINAL schedule list, never the tree, so they can only ever choose a
first-axis task. The tree has its own rendered list used only by the Schedule adapter. The timer still runs the
first-axis leaf's slice; later axes are the rotation inside it.

### Verified / not verified
`axis-schedule-rows-test.kt`: no later entries → list untouched; collapsed default (arrow + count, no rows);
expanded rows with scope/depth/numbers; z under a y leaf; same definition under two leaves has two identities;
per-leaf switch-offs and all-off; per-scope runtime; order by vruntime; groups open/closed; groups and link rows
get no children; class filter keeps group context; inputs never mutated. **Not built, not run.** On the phone:
Schedule tab → arrow on an X leaf → Y entries appear under it → arrow on a Y entry → Z entries appear under that
one; open a second X leaf and confirm its Y entries show their own numbers.

---

## 6.56.0 — Axes: runs credit the x → y → z path, Schedule shows it, Z checklist

Includes everything in 6.55.0 (built and passed). No database change, `versionCode` untouched. With no y/z entries
defined nothing changes: the extra work is skipped after one cheap query.

### Added
- **Runs credit the later axes.** `TaskRepository.updateVruntimeAfterRun` — the one funnel every caller uses
  (timer expiry, pause, startup recovery, call-switch, bubble) — now also credits, inside the run's leaf, whichever
  later-axis entries the descent picks, the same seconds, on their private `axis_instance` rows (vruntime, quota,
  DL budget, run count). The first axis is credited exactly as before. Because vruntime advances, successive runs of
  a leaf rotate through its Y/Z entries by each axis's own class.
- **Schedule tab path.** Each first-axis leaf row appends `Y: a › Z: p` to its category line — the entries the
  descent would pick inside it right now, using the same code as the run. Refreshes on every list rebuild and when
  an include switch changes. Computed in the background for up to 200 leaves; skipped entirely when no later axis
  has entries.
- **Z include checklist.** Edit a Y entry → ⋮ → "Axis inclusion…" asks *inside which X entry*, then shows the Z
  tree (scope `x/y`). X entries still manage Y as before.

### Changed
- **Edit screen looks at its own axis.** Pinned-share and save validation (and the semaphore wait check) now read
  the siblings of the entry's axis, not the x list. Pinned weights are normalised per axis after add/edit.

### Not changed on purpose
The running timer still runs the first-axis leaf's time slice; later axes are the rotation inside it, shown and
credited, not separate timers. Y/Z cards still refuse Run/Complete.

### Verified / not verified
Nothing new is unit-testable without a database (the descent and credit math are covered by 6.54.0's tests). **Not
built, not run.** On the phone: define Y entries, run an X leaf twice, and confirm the Schedule row's `Y:` name
rotates; switch an entry off in ⋮ → Axis inclusion and confirm it disappears from that leaf's path only.

---

## 6.55.0 — Axes: Queue-tab axis popup, add/edit per axis, include checklist

Includes everything in 6.54.0. No database change, `versionCode` untouched. With no y/z entries nothing looks or
behaves differently: the Queue tab is still axis x.

### Added
- **Queue-tab axis popup.** Tap the already-selected Queue tab → popup of axes (X / Y / Z, from `Axes.ALL`),
  same mechanism as the Schedule tab's class filter. The tab label follows the choice (`Queue·Y`). The same
  cards, groups, drill-down and edit screen then show that axis's entries. View-only: it changes no task.
- **Add / edit on the selected axis.** `+` from Queue·Y creates a Y entry (`axis_id` extra); editing carries the
  entry's own axis; the parent-group picker lists groups of that axis only; the form is identical for every axis.
- **Axis inclusion checklist** (Edit screen → ⋮ → "Axis inclusion…", first-axis entries only). Lists the next
  axis's definition tree, indented, everything on by default; unchecking a group unchecks its subtree; only the
  topmost switched-off entries are stored (`axis_exclusion`, scope = this entry's id).
- `AxisFacade` (one new property, `TaskViewModel.axes`): Queue-axis state and the checklist's reads/writes, so
  the view model and activities grow no per-axis code. `AxisChecklist` (pure): flatten + topmost-exclusions.
- Repository: `activeTasksOfAxis`, `activeGroupsOfAxis`, `axisDefinitions`, `axisExclusions`, `setAxisIncluded`.

### Behaviour on Queue·Y / Queue·Z
- Run is refused with a toast: those entries are scheduled inside the X pick, never from their own card.
- Complete is refused (only X entries use the Completed tab).
- Delete works and also clears that entry's exclusions/instances (6.54.0 hooks).

### Known gaps (next)
- Inclusion for the THIRD axis (scope `x/y`) has no UI yet; everything is included by default, which is correct.
- Pinned-share / load-factor / semaphore sections still read x siblings when editing a y/z entry.
- The running timer and the Schedule tab do not use `selectNextPath` yet.

### Verified / not verified
`axis-checklist-test.kt` (flatten order and depth, orphan as root, topmost-exclusion rules). **Not built, not
run** — build, run `verifyAll`, then on the phone: tap Queue twice → pick Y, add an entry, long-press → edit,
open an X entry → ⋮ → Axis inclusion.

---

## 6.54.0 — Axes: x → y → z selection and per-scope accounting

Includes everything in 6.53.0 (which built and passed). No database change, no UI change, `versionCode`
untouched. Nothing calls the new API yet, so running behaviour is identical to 6.53.0.

### Added
- **`AxisDescent`** (pure Kotlin). Starting from the first axis's leaf, each following axis: take its
  definition entries → drop the ones this scope switched off (children follow parents) → overlay each
  survivor's private runtime for the scope → let the existing per-level pass resolve the whole hierarchy to a
  leaf. If an axis has nothing included or runnable, the descent stops and the path so far stands — with no
  y/z definitions that is exactly today's behaviour.
- **`TaskRepository.selectNextPath()`** — the full x → y → z outcome. Same single sampled "now" and the same
  per-level selection (`selectNextCgroup`: DL > RT > EEVDF) used once per axis; the first element is always
  what `selectNextTask()` returns.
- **`TaskRepository.creditAxisRun(path, seconds)`** — vruntime, quota and DL budget advance on the PRIVATE
  `axis_instance` of each y/z step; the shared definition row is never written. The first axis keeps its
  existing credit path.
- `AxisPick`, `AxisInstance.applyTo` / `Task.toAxisInstance` (overlay and its inverse).

### Changed
- `forceDelete` now also clears `axis_exclusion` / `axis_instance` rows for the deleted entry, both as a
  definition (any scope) and as an outer leaf (every scope under it).
- `TaskRepository` takes `AxisDao` (Hilt binding `provideAxisDao` added; androidTest constructor updated).

### Verified / not verified
`axis-descent-test.kt`: no y/z → path is the first leaf; scope keys `""`, `L`, `L/a`; per-outer-leaf
exclusions; all-excluded stops (z never reached); runtime overlay is per scope; nothing runnable stops;
instance ↔ overlay round-trip. **Not built, not run** — run `verifyAll`.

### Not yet
Wiring the running timer to `selectNextPath`/`creditAxisRun`, Queue-tab axis popup, add/edit on the selected
axis, include toggles on x cards, Schedule-tab path rows.

---

## 6.53.0 — Axes (x, y, z): data layer

Includes everything in 6.52.0. **Database 33 → 34.** `versionCode` untouched. No visible change yet: every
existing row is axis `x`, every existing screen and scheduler pass sees exactly the rows it saw before.

### Added
- **Generic axes.** `Axes` (`x`, `y`, `z`) is only a list of ids; nothing in code assumes what an axis means.
  Each axis has one definition tree made of ordinary `Task` rows tagged with `Task.axisId` (default `x`).
- **`axis_exclusion` table.** Per outer leaf, which definition entries of the next axis are switched OFF.
  Default is included; only exclusions are stored. Switching a group off removes its whole subtree.
- **`axis_instance` table.** Private runtime per (scope, entry): vruntime, quota used, DL period, run count.
  Created on first run, never up front. Scope key = ids of the leaves picked on the outer axes, joined by `/`.
- **`AxisRules`** (pure Kotlin) — which entries are included under an outer leaf. Unit-tested with the
  user-1 (only a) / user-2 (b, c, a) example, group cascade, and a parent-cycle guard.
- New axis-aware DAO reads for y/z (`getActiveTasksOfAxis`, `getActiveGroupsOfAxis`, …).

### Changed
- Every existing `TaskDao` read is now X-only (`axisId = 'x'`). Backup still reads all rows and now writes /
  reads `axisId`.
- `tasks` freeze list: `axisId` added as the narrow exception (read on every list query and scheduler pass;
  sync snapshots the Task row). Everything else is in the two new tables.

### Not yet
Axis-descent selection, repository delete hooks for the new tables, Queue-tab axis popup, include toggles,
Schedule-tab path rows. **Not built, not run:** regenerate the Room schema for v34 and run `verifyAll`.

---

## 6.52.0 — Wake popup: each box selects its value when it gets focus

Includes everything in 6.51.1. No database change, no layout change, `versionCode` untouched.

### Added
- **Fast entry in the wake popup.** Whenever one of the six boxes (Day, Month, Year, Hour, Min, Sec) receives
  focus — a tap, the keyboard's Next / Enter moving in, an arrow or D-pad move — its whole value is selected,
  so the first digit you type replaces it; nothing has to be cleared first. Only the box that gained focus is
  selected. Example: tap Hour, type 3, Enter, 4, Enter, 0, Enter → 3 h 4 m 0 s (the keyboard already moved
  Day → Month → Year → Hour → Min → Sec, and Done on Sec; the last Enter still just closes the keyboard).
- Tapping a box that ALREADY has focus places the cursor where you tapped, so a single digit can still be
  fixed; select-all happens only on arrival.
- After switching Alarm ↔ Timer the boxes are refilled, and if one has focus its new value is selected too.

### How
The selection is made in code (`EditText.selectAllSoon`), one loop turn after the focus change, rather than
with `android:selectAllOnFocus`: a tap's touch-up lands after the focus change and places the cursor, which
collapses a selection made inside the focus callback on some Android versions. It is skipped if focus has
moved on in the meantime. Wake popup only; the semaphore and sleep fields are unchanged.

### Verified / not verified
Nothing to unit-test (it is focus behaviour on a real keyboard); the UI geometry guard passes. **Not built,
not run:** check on the phone with the example above — tap Hour, 3 Enter 4 Enter 0 Enter — and that a second
tap on the same box puts the cursor instead of selecting everything.

## 6.51.1 — Wake: the icon and Select / Preempt no longer die after the screen stops

Includes everything in 6.51.0. No database change, `versionCode` untouched.

### Fixed
- **After switching apps or locking the screen and coming back, a newly set wake showed as "not armed"
  and the card's Select / Preempt selected nothing** — until the app was closed and reopened. The card
  itself still appeared and its buttons still reached the wake, which is why it looked inconsistent.
  Cause: `MainActivity.onStop()` calls `bus.unsubscribeAll("task-list-screen")`, which removes EVERY
  subscription under that id — including the two the ViewModel had registered once, when it was created,
  for the wake (`wake.armed-changed` → the icon, `scheduler.task-select-requested` → the selection). The
  ViewModel never registers again, so from the first stop on both links were dead for the rest of the
  process. It only shows after a stop, hence "works on a fresh start, then sometimes not".
  Fresh start → set a wake → fine; switch app / lock → come back → set a wake → icon not armed, Select
  does nothing: exactly the reproduction.
- **The same flaw hit older ViewModel subscriptions**, which also stopped working after the first stop:
  refreshing the schedule stats when a task is saved elsewhere, pausing the timer and clearing the
  selection before a backup export, the call-bubble state, and the timer-running snapshot that mirrors
  what call-auto-switch publishes. Multi-device sync's two subscriptions had a related bug: they shared one
  fixed id, and the app runs TWO `TaskViewModel` instances (main screen and Add screen), so clearing the
  Add screen's ViewModel removed the main screen's sync subscriptions too.

### Changed
- **`WakeLinks`** (new, task-list-screen): the two wake links now belong to the main SCREEN, under an id
  of their own (`task-list-screen.wake`). Registered when the screen is created — reading the retained
  armed set so the icon is right at once — and removed only when it is DESTROYED, never on a stop. One
  instance listens at a time, so a recreated screen does not leave two, and the Add screen's ViewModel no
  longer answers selection requests as well. `TaskSelectDelegate` is gone; `setWakeArmedIds` feeds the
  ViewModel's LiveData (which still survives a rotation).
- **Every ViewModel subscription uses a per-instance id** (`task-list-screen.vm.<uuid>`), removed in
  `onCleared` — only that instance's. An id is a unit of fate (removal and quarantine), so this also keeps
  one instance's failure from silencing another's.
- `EventBus.unsubscribeAll` documents the rule: never register something that must outlive a screen under an
  id the screen tears down.
- Run `scripts/apply-6.51.1-deletions.sh` after extracting the zip (removes `task-select-delegate.kt`).

### Tests
New `bus-subscription-ownership-test.kt` (kernel, 7): `unsubscribeAll` removes only its id; an own-id
subscription survives the screen tearing down its capability id; sharing an id shares fate; two instances
with their own ids do not remove each other's; re-registering leaves exactly one; a retained topic lets a
late subscriber read what it missed; a failing subscriber is quarantined under its own id without silencing a
neighbour. The end-to-end harness (89 scenarios, +8) replays the reported sequence on the real wake
coordinator: with the old registration the icon update is lost and Preempt selects nothing after the stop;
with the new one both still work, and a screen created afterwards reads the current armed set.

### Verified / not verified
- **Run:** the 7 new and the 17 existing kernel bus tests (24); the end-to-end harness (89), including the
  exact stop-and-return reproduction, old vs new. UI geometry guard passes; architecture guard unchanged.
- **Not built, not run** (no Android SDK here): `WakeLinks`, the ViewModel changes, the real stop / start
  of the activity. Check on the phone: set a wake; switch apps or lock and come back; set another — the
  icon must blink and the card's Select / Preempt must select; also open the Add screen and return.
- **Left as it was, flagged:** `ObserverDelegate.observeCallEvents` subscribes to the phone-call state under
  the screen's id when the screen is created, so after the first stop it is not registered again (it keeps
  the ViewModel's in-memory call state in step while the screen is visible; call-auto-switch's own service
  does the actual switching). It looks like the same flaw, but it is call-switching logic I cannot test
  here, so I did not change it.

## 6.51.0 — Wake popup: remembers each task's mode, explanations move behind an info icon

Includes everything in 6.50.0. No database change, `versionCode` untouched.

### Added
- **The popup opens in the mode the task was last set with.** Alarm or Timer is remembered **per task**,
  not for the whole app: setting task A with a Timer and task B with an Alarm, each opens in its own mode
  next time. "Last set" means the mode you pressed **Set** with — toggling without setting does not
  count, and neither do the numbers you typed. It is also what an armed wake opens in, and it survives the
  wake firing, being answered or being cleared. A task that never had a wake opens in Alarm. It is stored
  in the `mode` column the task's `task_wake` row already had, so there is nothing new to migrate.
- **Universal info icon** (`InfoIconView`, in design-system): a small circled "i" that explains something
  when tapped. Give it text (`infoText`) and it shows it in an `InfoBubble` anchored under the icon, in the
  card look, closing on a tap anywhere. The text can be reassigned at any time; empty text makes the icon do
  nothing. It follows light / dark mode through the design tokens. The bubble is its own window, so it works
  over a dialog.

### Changed
- **The wake popup**: the two explanation lines ("Fires on this date and time (24 h)" and "Fires this long
  from now — Day, Month, Year are how many, not a date") are gone from the body. An ⓘ sits at the top right
  of the popup, in the title row (new `dialog_wake_title.xml`), and explains the mode currently selected —
  its text changes when you switch Alarm and Timer. Same wording as before.

### Tests
`wake-rules-test.kt` 27 (+4: initial mode, unknown stored mode, per-mode explanation). 81 end-to-end
scenarios on the real coordinator and controller (+8: the mode is per task, survives clearing and a fired
and answered wake, is independent between tasks, and a profile change does not reset it).

### Verified / not verified
- **Run:** the rules and the per-task persistence through the real `WakeCoordinator` on the real bus. The UI
  geometry guard passes; the architecture guard is unchanged from 6.50.0.
- **Not built, not run** (no Android SDK here): `InfoIconView`, `InfoBubble`, the custom dialog title and
  the popup layout. Nothing was tried on a device — check that the ⓘ sits top right, the bubble opens under
  it over the dialog and closes on a tap, its text follows the Alarm / Timer toggle, and the popup opens in
  each task's own last mode.

## 6.50.0 — timer-card: one capability draws the card above the list (timer, expired alarm, wake)

Includes everything in 6.49.1. No database change, `versionCode` untouched. New capability
`:capabilities:timer-card`.

### Why
The card above the list was drawn by several independent code paths that did not know about each
other — `renderTimerCard`, the menu's hide toggle setting `cardTimer.visibility` directly, display
scaling that padded only two of its blocks, and (from 6.49.0) a wake override. A wake card could lose to
any of them, and a card could stay up because the answer that was meant to take it down never arrived
("preempt card not visible, only sound and vibration"; "Dismiss stops the sound but the card stays").
Now exactly one thing draws the card, and what it draws arrives as a card spec on the event bus.

### Changed
- **One view, three faces, unchanged look.** The card's layout moved byte for byte from
  `activity_main.xml` into `timer-card/view_timer_card.xml`; `TimerCardView` only toggles which face
  shows, sets the card colour and writes text, as before. Timer · alarm · wake faces, the 7-segment
  strip, the fonts, colours, paddings and the display-scale padding are all as they were.
- **Card specs over the bus.** `card.show` (a card), `card.hide` (by id), `card.action` (a button
  press, back to the publisher), `card.user-hide` (the hand-hide, retained). Several cards can wait at
  once; the highest priority is on screen — **expired alarm over wake over timer** — and a lower card
  comes back when the higher one is hidden. Publishing the same id replaces the card in place.
- **`closeOnPress`.** A button marked so removes its card from the screen the instant it is pressed,
  before the press is announced, so a card can never stay up because an answer was slow or lost. The
  wake card's Dismiss and Preempt/Select use it.
- **The card counts for itself.** A spec carries the instant the countdown ends (or the alarm began),
  and the card ticks it once a second on its own, aligned to the second. Nothing is published per
  second; the task list republishes only when a spec really changed (a clock instant must move by more
  than 0.7 s).
- **7-segment status strip.** Which states are active (quota exhausted, notice delay, notice wait) and
  how badly quota is exceeded are still decided in the task list; the strip's 7 segments, ping-pong
  colours and blinking (normal / fast) are now drawn by timer-card from a `CardStrip` in the spec.
- **The controller is process-wide** (built at app start like every bus subscriber), so a card
  published while no screen is open is waiting when one opens, and a rotation loses nothing.
- **Hand-hide.** Holding key1 still hides / reopens the timer card; the flag is persisted as before and
  now also published, so selecting a task (which clears it) reopens the card immediately. Alarm and wake
  cards ignore it.
- **task-wake** publishes its card as `card.show` and receives `card.action`; its `wake.card-*` topics,
  the host activity's preempt mode and `PreemptCardView` are gone (6.49.1's floating-card leftovers).

### What stayed in task-list-screen, and why
The timer / notice-phase / expired-alarm state machine, the overrun counter and what every button does
(`TimerCardPublisher` calls the same ViewModel functions the old listeners did). Specifically the
**expired-alarm card is published by the task list**, not derived by timer-card from `alarm.ringing`:
that topic is retained but there is no retained "stopped" state, so after an app restart the card could
not know whether an alarm was still ringing, while the task list already owns that state (and
`stopAlarmSound()`'s re-seat-the-task logic). The card still counts the overrun by itself.

### Removed
`TimerCardDelegate`, the card views and their `findViewById`s in `MainActivity`, the card-writing
observers in `ObserverDelegate`, and the strip's drawing code in `phase-status-bar.kt`.
Run `scripts/apply-6.50.0-deletions.sh` after extracting the zip (`timer-card-delegate.kt`, and
`res/values/ids.xml`, which held only the strip's blink tag).

### Tests
`timer-card-test.kt` (20): card stack order and replacement, hand-hide rules, clock formatting and
rounding, strip ping-pong. `wake-rules-test.kt` unchanged (23).

### Verified / not verified
- **Run:** the new capability's pure code and `TimerCardController` compile against the real kernel;
  the real `WakeCoordinator` and real `TimerCardController` run together on the real `EventBus` — 73
  scenarios, including card shown, Dismiss and Preempt/Select pressed on the real controller and
  delivered back to the wake, the card removed synchronously on press even with no owner listening,
  stacking (alarm over wake over timer, and back), hand-hide for each kind (also for a controller built
  after the flag), long-press and ignored presses. The UI geometry guard passes (6/6); the architecture
  guard shows nothing new from timer-card (the two `WakePrefs` findings are from 6.49.0).
- **Not built, not run** (no Android SDK here): `TimerCardView`, the strip drawing and blinking, the
  moved layout's resource references (every one was checked to exist in design-system), `TimerCardPublisher`
  and its LiveData wiring, Hilt for the controller, the activity changes. Nothing was tried on a
  device. This release replaces all three cards at once — the timer card especially needs a careful look
  on the phone: Start / Pause / Cancel, the countdown, Next / Auto and INT (tap and hold), hold-Start
  to pause and close, the status strip (quota, delay, wait, blink), an expired alarm and Stop, hold
  key1 to hide, and a wake card.

## 6.49.1 — Wake: card in the timer card, Select / Preempt really select, equal tasks may preempt

Includes everything in 6.49.0. No database change, `versionCode` untouched.

### Fixed
- **The preempt card floated on top of the screen** (a see-through activity). It now takes the **timer
  card's own place**, exactly like an expired alarm: same card, alarm colour, same structure, same
  height, Dismiss + Preempt / Select. An expired alarm still wins the card; the wake card waits behind
  it. It is shown even if the timer card was hand-closed. Over the bus: task-wake publishes
  `wake.card-changed` (retained), the task list renders it and answers with `wake.card-answered`.
  The old `PreemptCardView`, its layout and the host activity's preempt mode are gone — run
  `scripts/apply-6.49.1-deletions.sh` after extracting the zip to remove the two retired files.
- **Tapping Select or Preempt selected nothing visible.** The select handler set the current task
  directly and skipped what a selection has to do. It now calls `TaskViewModel.setCurrentTask` — the very
  function a tap on a task's row uses — so the running timer is paused with its time saved, the notice
  state is reset, a hand-closed timer card is reopened and the selection is remembered. `TaskSelectRequest`
  lost its `pauseCurrent` flag (selecting always pauses first).
- **Preempt rule.** A wake may now preempt when its class is higher, OR the class is the same and its
  priority is the same or higher (a wake may take over from an equal). The current task is protected
  only by a higher class or, inside the same class, a higher priority. "Priority" is `rtPriority` for
  realtime and the task's own `priority` (its EEVDF weight, higher = more important) otherwise.
  `WakeRules.outranks` became `canPreempt` / `effectivePriority`.
- A pending card whose task was deleted is dropped instead of waiting forever.

### Changed
- The wake alert (sound + vibration) is started by task-wake when the card goes up and the app is on
  screen, refreshed text never re-rings it, and answering or clearing the wake stops it. In the
  background the notification stands in and the alert starts when the app opens.

### Verified / not verified
- **Run:** 23 unit tests of the rules (new `canPreempt` cases: equal, higher and lower priority per class)
  and the real `WakeCoordinator` on the real kernel `EventBus` with in-memory fakes — 53 scenarios,
  including the card being published and hidden, answered through `wake.card-answered`, refreshed (not
  re-rung) when the current task changes, background then open, and the new rule for fair and realtime.
- **Not built, not run** (no Android SDK here): the new card block in `activity_main.xml`, its rendering in
  `TimerCardDelegate`, and the call into `setCurrentTask` (which is what the row tap already runs). Nothing
  was tried on a device.

## 6.49.0 — Scheduled wake: a task can preempt the current one at a set time

Includes everything in 6.48.1. **Database change: version 32 → 33** (two NEW tables, nothing added to
`tasks`; migration `MIGRATION_32_33`). `versionCode` untouched. New capability `:capabilities:task-wake`.

### Added
- **Wake icon** (alarm clock) on every leaf task card, right of the moon. **Tap** opens the prompt,
  **hold** shows the wake's state (mode, due time, time left, profile, and whether it is waiting on the
  running task). It **blinks** orange while a wake is armed or waiting for an answer. Not shown on groups,
  links, hardlink placements or the third tab.
- **Prompt** (mode row on top, 2 × 3 number-only boxes below). *Alarm*: `Day Month Year` / `Hour Min Sec`
  (24 h), prefilled a minute ahead; past or non-existent dates are refused. *Timer*: the same boxes as a
  duration from now ("2 day 11 s" = Day 2, Sec 11); months and years are calendar months and years.
  Set / Cancel, plus Clear when a wake is armed.
- **Preempt card** (alarm-coloured card, Dismiss + Preempt) when a wake comes due:
  - Compared by scheduler class, stop > deadline > realtime > fair > idle; inside realtime the higher
    `rtPriority` wins; equal fair / idle / deadline never preempt each other.
  - The current task is the one in the timer card, running OR paused. If it is of equal or higher class the
    wake keeps waiting and is looked at again whenever the current task changes or the timer card closes.
  - If nothing is current the same card offers **Select**. A wake whose task is already current is cleared
    silently.
  - **Preempt** remembers the current task, pauses it and *selects* the wake task. **Nothing is started
    automatically** — the person presses Start.
  - The card rings the wake's profile (sound + vibration) until answered. Back leaves it pending; it comes
    back the next time the app opens. It survives an app restart.
- **Return**, like INT-A's "back": when the wake task ends (stops being current with nothing replacing it)
  the remembered task is selected again, paused, through the same placement (symlink / hardlink), with a
  "Returned to …" toast. If the person picks another task meanwhile, the pending return is dropped. A wake
  preempting a wake stacks. If the old task was deleted, finished, asleep or queued, nothing returns.
- **Unavailable wake task:** asleep → woken first, then the normal rule; queued behind a semaphore or hidden
  by its group → skipped with a notification; completed or deleted → cleared.
- **One-shot.** A wake is cleared once answered. Repeating wakes are for later.
- **Edit screen → Wake card** (leaf tasks): the wake's sound / vibration **profile** (Default / Notice /
  Alert / Custom, default Alert), the same dropdown as Task Config.
- **Settings → platform → alarm** (new page): *Re-arm wakes after reboot* (default on). Off: nothing is
  re-armed at boot, wakes are re-armed when the app is opened. **Missed wakes** (due while the phone was
  off) are always reported, whatever this says: a notification at boot and the card at the next open.
- Doze-immune: each wake is an `AlarmManager.setAlarmClock` entry, like the task timer.

### How it talks to the rest of the app — the event bus
New topics in `kernel/event-bus/topics.kt`, used instead of imports:
`wake.edit-requested`, `wake.state-requested`, `wake.armed-changed` (retained), `wake.alert-requested`,
`wake.alert-stop-requested`, `wake.profile-set`, request topic `wake.profile-query`, and the general
`scheduler.task-select-requested`. `CurrentTaskState` now also carries `symlinkId` / `membershipId`.
- **task-wake** (the whole feature: `WakeCoordinator`, receivers, host activity, scheduler, rules) imports
  no other capability but task-storage, and no capability imports it. Wired by one `@Provides` in
  `composition` and one `@Inject` in `ApplicationEntry`, as the project's guide prescribes.
- **Where other modules had to change, and why it could not be done over the bus:**
  - `task-list-screen`: the icon itself (`item_task.xml`, adapter) and a `TaskSelectDelegate` that answers
    `task-select-requested` (the list owns the current task, so only it can change it); the icon publishes
    topics, nothing more. `CurrentTaskOwner` also publishes the placement.
  - `add-task-screen`: the Wake card (it only asks / tells the profile over the bus).
  - `settings-screens`: the new alarm page + nav card. `settings-storage`: `WakePrefs`.
  - `sound`, `vibration`: two small subscribers each (`wake.alert-*`) + their manifests. The existing
    `alarm.ringing` could not be reused: it means "a timer expired" and multi-device sync acts on it.
  - `multi-device-sync`: `SyncSchema` excludes `task_wake` / `wake_return` — wakes are device-local.
  - `task-storage`: `TaskWake`, `WakeReturn`, `TaskWakeDao`, database v33.

### Not included
Wakes are not backed up and not synced (device-local, like the OS alarm they mirror). No snooze.

### Tests
`wake-rules-test.kt` (21): class ranking, preempt / wait / clear decisions, alarm and timer arithmetic
(leap years, month ends, invalid dates), text. The pure rules compile with a standalone Kotlin compiler and
pass.

### Verified / not verified
- **Run:** the real `WakeCoordinator` against the real kernel `EventBus` with in-memory fakes — 37 scenarios
  (nothing current, equal class waits then fires when the timer card closes, lower-class switch, preempt,
  return through the same placement, user takes control, stacked preempts, sleeping / queued / deleted /
  already-current wake tasks, background notification then card on open, profile over the bus, armed set).
  That harness is throwaway and is not part of the project.
- **Not built, not run** (no Android SDK here): Room (DAO, migration), Hilt wiring, the host activity and
  its theme, the layouts, receivers, AlarmManager, the notification, the adapter blink, the Edit and
  Settings screens. Nothing was tried on a device. `schemas/…/33.json` is written by the first build —
  commit it (`check_architecture.sh` rule 7 asks for it).
- `scripts/check_architecture.sh` already failed before this change (kernel file count 11 vs ceiling 9; 33
  cross-capability `*Prefs` imports). This change adds the same kind of finding twice (`WakePrefs`, shared
  with settings-screens like every other prefs object) and no new kernel file.

## 6.48.1 — Auto Scroll jumps in one step again (no visible slide)

Includes everything in 6.48.0. No database change, no sync change, `versionCode` untouched.

### Fixed
- **Selecting a task showed a fast slide:** the card first appeared right under the timer card, then
  moved to the bottom of the screen (above the add buttons). Cause: the follow-up checks of the new
  Auto Scroll ran in `View.post`, which can run *before* the layout that the jump requested. The
  check then found no running row, wrongly decided the chain was taller than the screen, and pinned
  the card to the bottom edge — after the first position had already been painted.
- The same ordering problem could shift the list a second time when the timer card appeared or
  disappeared (it resizes the list) while the walls were active.

### Changed
- Every follow-up step (the chain-fits check, the tall-chain pin, wall corrections after a layout
  or a far jump) now runs in a **pre-draw hook**: after the layout, before the screen is painted. If
  a step has to jump again it cancels the pending paint, so any number of steps shows only the final
  position. The normal case — chain fits — is the single `scrollToPositionWithOffset` jump of the old
  Auto Scroll, nothing after it.
- Range locking, wall stops and the symlink exception are unchanged.

### Verified / not verified
Not built and not run (no Android SDK here): the pre-draw hooks and the absence of the slide have
not been tried on a device. The pure range maths is unchanged (49 rule tests passed in 6.48.0).

## 6.48.0 — Auto Scroll frames the parent chain and boxes the list in while a task runs

Includes everything in 6.47.1. No database change, no sync change, `versionCode` untouched.

### Changed
- **Frame the chain.** When the running task changes and its row is not on screen, Auto Scroll
  now jumps so the ROOT ancestor's row is at the top and the rows down to the running task are
  visible (`task1 > task2 > task3` running shows `task1` first). Before, the running row itself
  went to the top and its parents were left above the screen. If the chain is taller than the
  screen the running task wins and is pinned to the bottom edge. Instant jump, no animation, never
  switches tab; if the running row is already visible nothing moves.
- **Walls while a timer runs.** The list can no longer be scrolled above the root row or below the
  running row. Between them it scrolls freely; a fling that reaches a wall stops there. With the
  root at row 172 and the running task at row 221, rows 172–221 are the whole scrollable range.
  Pause / stop, deselecting, collapsing the root group (the running row leaves the list) and turning
  the Auto Scroll switch off free the list again.
- **No more pull-back.** Auto Scroll used to react to every re-publish of the current task (it
  re-publishes during the countdown), so scrolling the running row out of view was undone. It now
  acts only when the running row or the timer-running state really changes.

### Behaviour to know about
- A running task with no parent (every task, when groups are off) is boxed to its own row: while
  its timer runs the list cannot be scrolled. Switch Auto Scroll off in the menu to scroll freely.
- When the box is shorter than the screen, the root row stays at the top and the rows that follow
  the running task are still drawn below it (hiding them would mean filtering the list).
- A symlink's "go to the real location" frees the walls until the running task or timer state next
  changes, so the explicit jump is not pushed back.
- Drill-down list style and the Completed tab are unchanged.

### Code
- `ScrollRangeRules` (task-storage, pure): box rows of a running row, far-jump target, pixel
  correction for the walls, bottom-pin offset. `ScrollRangeDelegate` (task-list-screen) reads row
  positions off the RecyclerView and applies them; it replaces `MainActivity.scrollToTask`.

### Tests
New `scroll-range-rules-test.kt` (18 tests): box of a grandchild / child / root task, siblings inside
the box, nearest root, unknown row, far jumps (172–221 example), any-row-visible, wall corrections,
box shorter than the screen, top wall wins, padding, convergence, bottom pin.

### Verified / not verified
The pure rules compile with a standalone Kotlin 2.0.21 compiler and all 49 tests of the three rule test
files (scroll 18, semaphore 22, sleep 9) pass. **Not built and not run** (no Android SDK here):
`ScrollRangeDelegate` and its wiring, and the on-screen feel — wall stops, fling handling, the
chain-taller-than-screen case — have not been tried on a device.

## 6.47.1 — App renamed "Scheduler" in every title

Includes everything in 6.47.0. No database change, no sync-protocol change, `versionCode` untouched.

### Changed
- **App name** (launcher, system app list): `app_name` "EEVDF Scheduler" → "Scheduler".
- **Toolbar title** on the main screen: was hardcoded "EEVDF Task Scheduler"; now the app's own
  label, so the toolbar and the app name can no longer drift apart.
- **Notification titles**: "Scheduler – call task switch", "Scheduler – call bubble active".
- **Settings** `about_eevdf` string: "About Scheduler" (the string is currently not referenced by
  any layout or code).
- **Bluetooth service name**: "Scheduler sync". It is only a label; phones find each other by
  `SERVICE_UUID`, which is unchanged, so older phones still connect.
- **Messages**: "not a Scheduler phone" (the protocol check itself is the `MAGIC` number, unchanged)
  and "This file does not contain Scheduler data". Nothing matches either string.
- **Log tag** `EEVDFAlarm` → `SchedulerAlarm` (alarm activity and foreground service). Anyone
  filtering logcat on the old tag needs the new one.

### Not changed
Package id `com.eevdf.scheduler`, Kotlin packages, database name, APK file name
(`eevdf-scheduler-v…apk`), the sync `MAGIC` and `SERVICE_UUID`, and text that names the
algorithm (the scheduler-class labels on the Edit screen).

### Verified / not verified
Not built and not run. The edits are string literals, plus the toolbar line, which now reads the
label with `applicationInfo.loadLabel(packageManager)` instead of a resource id because the
list-screen library cannot see the app module's resources.

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
