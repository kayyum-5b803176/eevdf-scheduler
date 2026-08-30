# Architecture

## Ownership & boundary philosophy

This document, and every phase below it, is governed by two source documents
kept alongside the repo (not committed here — ask if you need them re-attached):

- **`Linux Subsystem & Ownership Philosophy.md`** — the placement rule for any
  new file is *"which subsystem owns this responsibility?"*, never *"what type
  of file is this?"*. A folder exists because something owns it, not because
  a technical label matches. Shared code is the exception, not the default —
  it exists only when genuinely no subsystem owns the code AND multiple
  independent subsystems need it. Complexity is not eliminated, it is
  *contained*: pushed downward inside a stable boundary instead of spreading
  across the top level.

- **`Scalable Feature-Boundary Architecture.md`** — applies that philosophy to
  this specific shape of app. Every root is either **stable infrastructure**
  (changes slowly, defines how the app fundamentally works) or the **one
  designated growth surface** (`feature/`, which is allowed to grow without
  limit). A `contract/` layer exists specifically so features can participate
  in the system without the system — or a sibling feature — hardcoding their
  internals. The ownership test before creating anything new, in order: does
  a feature own it → does core own it → is it a stable cross-feature contract
  → is it shared persistence → is it platform/OS integration → is it
  genuinely ownerless and generic → only then, `shared/`.

Every phase in this file is a step toward the 8-root target those two
documents describe:

```
app/        composition root — DI wiring, manifest, application shell
contract/   stable extension points — what a feature is allowed to provide
core/       fundamental logic — pure JVM, no Android
data/       shared persistence
platform/   OS/Android integration
shared/     genuinely ownerless generic utilities
testing/    reusable fakes and fixtures
feature/    the one unbounded growth surface
```

Not all eight are real Gradle modules yet. The table below states which are
compiler-enforced today and which are still enforced only by
`scripts/check_architecture.sh` — a weaker guarantee, and the reason the
later phases exist.

## Module graph

**Current:**

```
:app  ──▶ :data ──▶ :core ◀── :platform          :shared ◀── (anyone)
  └──────────────────┴─────────▶
```

**Target (Phase 4 onward):**

```
:app ──▶ :contract          :app ──▶ :data ──▶ :core ◀── :platform
:app ──▶ :feature ──▶ :contract                 :shared ◀── (anyone)
:feature ──▶ :core, :data, :shared
```

| Module      | Kind      | Status | Rule |
|-------------|-----------|--------|------|
| `:core`     | Pure JVM  | ✅ real module | No Android, Room, Hilt or system clock. The Android plugin is deliberately withheld so purity is a **compile error**, not a convention. |
| `:data`     | AndroidLib| ✅ real module | Room entities, DAOs, repositories, backup/sync, scheduler facades. |
| `:platform` | AndroidLib| ✅ real module | Android adapters for `:core` ports (clock, alarms, RR store). Currently thin — `SoundManager`, `VibrationManager`, `NotificationHelper` belong here by the doc's own rule (`platform` = Android APIs) but still live in `app.core.*`. See Phase 5. |
| `:shared`   | Pure JVM  | ✅ real module | Cross-cutting utilities, feature flags, crash isolation. |
| `:testing`  | Pure JVM  | ✅ real module | Fakes, plus `:core`'s own unit tests (arguably belongs in `:core` — the doc defines `testing/` as reusable *infrastructure*, not the tests themselves; unresolved, low priority). |
| `:contract` | —         | 🚧 target, not yet a module | Currently informal: `AppRoutes`, `AlarmController`, `OverlayController`, `AlarmActions` live in `app.core.nav` / `app.core.control`. These are genuine contracts — `AlarmController`/`OverlayController` are interfaces with zero Android imports, implemented inside the feature that owns the behaviour and bound by that feature's own Hilt module. See Phase 4. |
| `:feature`  | —         | 🚧 target, not yet a module | Feature packages live under `app/src/main/kotlin/com/eevdf/app/feature/`. Boundary enforced by `scripts/check_architecture.sh`, not the compiler, until Phase 7. |
| `:app`      | App       | ✅ real module | UI, ViewModels, DI wiring, manifest, resources. Also holds `app/ui/` — a genuinely shared design-system package (`NavCardView`, `ModelDiagramView`, etc.), used only by `feature/settings` today but classified shared rather than feature-owned per the ownership test in `Scalable_Feature-Boundary_Architecture.md` §12: a design-system component isn't owned by the one feature that happens to use it first. |

---

## The five guard rails

Everything here exists to answer one question: *can a new feature break a
working one?*

### 1. Feature isolation
`scripts/check_architecture.sh` fails the build if a feature package imports
another feature. Grandfathered edges live in
`scripts/feature_import_allowlist.txt`. **That list may shrink, never grow.**

    v4.3.0  15 edges
    v4.4.0   5 edges   (Phase 2a: relocations + AppRoutes)
    v4.5.0   1 edge    (Phase 2b: service-control contracts)

**Cross-feature behaviour goes through a contract in `app.core.control`**, with
the implementation living inside the feature that owns the behaviour and bound
by that feature's own Hilt module (`AlarmController` ->
`feature/alarm/AlarmControlModule`, `OverlayController` ->
`feature/autoswitch/OverlayControlModule`). The arrow points inward to an
interface, never sideways to a sibling. **This becomes the real `:contract`
module in Phase 4** — nothing about the rule changes, only its enforcement
from script to compiler.

**Shared code that is genuinely ownerless lives in `app.core.*`.** Currently:
`core.prefs` (`AutoSwitchPrefs`, `DisplayPrefs`, `HardwareKeyPrefs`,
`QuickActionPrefs` — each read by 2+ features), `core.media`,
`core.notification`, `core.signals`, `core.nav`. **Single-owner code that only
looked shared has been moved out** — `RecentGroupPrefs` and `GroupTaskPrefs`
went to `feature/task`, `SettingsPage`/`SettingsChangeLogger` went to
`feature/settings` (Phase 3, v5.10.0) — per the ownership philosophy's rule
that sharing must be earned by proven multi-feature use, not assumed from a
folder name.

**Navigation goes through `AppRoutes`** (`app.core.nav`), which resolves screens
by class name so no screen holds a compile-time reference to another.
`AppRoutesTest` validates every route resolves. Moving to `:contract` in
Phase 4; how `AppRoutesTest` travels with it is an open decision (see Phase 4).

### 2. Core purity
`:core` has no Android plugin. Adding `import android.*` there fails to compile.
The script double-checks in case someone adds the plugin.

### 3. Backup and sync field coverage
Two reflection-driven tests enumerate `Task`'s constructor:

- `BackupRoundTripCoverageTest` — fills every field with a distinct sentinel,
  round-trips it through the real export/import path, and names any field that
  didn't survive.
- `TaskFieldClassificationTest` — fails unless every field is declared
  `CONTENT`, `OPERATIONAL` or `IDENTITY` in `TaskFieldClassification`.

Neither has a hardcoded field list, so **adding field #52 and forgetting about
backup or sync turns the build red automatically.**

### 4. Database integrity
The guard checks that `@Database(version)`, the `MIGRATION_x_y` objects and the
committed schema JSON all agree; that every migration is registered in
`addMigrations()`; that no two branches claimed the same version; and that
`fallbackToDestructiveMigration` (silent data loss) never appears.

`TaskDatabaseMigrationTest` walks 1 → 21 on a device and asserts a v1 row still
exists at the end — schema validation alone won't catch a migration that
recreates a table without copying rows.

### 5. Complexity ratchets
No new file over 800 lines (`MainActivity`, `TaskViewModel` grandfathered). No
growth in global mutable state.

**Long-function ratchet** — the metric that actually matters for a team. No
function may exceed today's worst (199), and the count of functions over the
60-line target (29) may shrink but never grow. File length is deliberately NOT
the metric: splitting `setupObservers()` into nine functions made the file 65
lines longer and far easier to work in.

### 6. The `tasks` table is frozen
51 columns, enforced by `TaskSchemaFreezeTest` and by a guard check on widening
`ALTER TABLE`s. New feature data goes in a side table
(`docs/SIDE_TABLE_TEMPLATE.md`). This is the only guard that prevents a cause
rather than detecting a symptom.

---

## Runtime safety

This app runs a foreground alarm service, an overlay window and a call-state
receiver. Isolation between features matters more here than in a typical app,
because a crash in stats shouldn't stop an alarm from firing.

- **`FeatureFlag`** — local kill switches. A broken feature gets turned off, not
  hotfixed.
- **`safeFeature("name") { }`** — contains a crash at a feature's outer edge and
  reports it as a non-fatal. Outer edges only.

---

## Commands

```bash
./gradlew verifyAll              # everything CI runs
./gradlew checkArchitecture      # boundary + DB guards (~2 seconds)
./gradlew :testing:test          # pure core tests, no emulator
./gradlew :data:testDebugUnitTest  # backup/sync coverage guards
./gradlew detekt                 # static analysis
./gradlew :data:connectedDebugAndroidTest   # migration tests, needs a device
```

---

## Phase 2a — done (v4.4.0)

Relocated misfiled shared code out of feature packages, renamed the colliding
`TimerState` to `TaskTimerState`, and added the `AppRoutes` navigation seam.
Cross-feature edges 15 → 5.

## Phase 2b — done (v4.5.0)

`AlarmController` / `OverlayController` contracts, `AlarmActions`,
`AutoSwitchActivity` onto the repository. Edges 5 -> 1.

## Phase 3 — done (v5.10.0)

Ownership audit against `Linux Subsystem & Ownership Philosophy.md` and
`Scalable Feature-Boundary Architecture.md`. First pass: relocate code that
was filed as shared but has exactly one owner, and extract the one thing that
actually is a shared design system.

- `RecentGroupPrefs`, `GroupTaskPrefs` — `app.core.prefs` → `feature/task`
  (single caller / zero callers respectively; `GroupTaskPrefs` has no wiring
  today but is kept rather than deleted, on the basis that unwired code still
  has a future owner, not no owner).
- `SettingsPage`, `SettingsChangeLogger` — `app.core.settings` → `feature/settings`
  (an unwired contract pair meaningful only within that feature).
- `app.core.template` (`NavCardView`, `DropdownCardView`, `ToggleCardView`,
  `ValueCardView`, `ModelDiagramView`) → `app.ui` — kept shared, not moved into
  `feature/settings`, because a design system is infrastructure multiple
  features are expected to draw on, not settings-specific behaviour.

## Phase 4 — next: the `:contract` module

Promote `app.core.control` (`AlarmController`, `OverlayController`,
`AlarmActions`) and `app.core.nav` (`AppRoutes`) into a real `:contract`
Gradle module. Confirmed as contracts, not platform code: both controller
interfaces have zero Android imports; their implementations live inside the
owning feature bound via that feature's own Hilt module — exactly the shape
`Scalable_Feature-Boundary_Architecture.md` §8 describes.

**Open decisions, not yet resolved:**

- `AppRoutesTest` resolves Activity classes via `Class.forName`. If `AppRoutes`
  moves to `:contract`, does the test move with it (needing Activity classes
  visible from a module that should stay Android-light), or does `:app` keep a
  thin runtime check while only the constants live in `:contract`?
- `core.signals` (`BubbleEventBus`, `CallEvents`) is used across `task` and
  `autoswitch` — a cross-feature communication channel, which smells like a
  contract by the ownership test, but it's a concrete `StateFlow` singleton,
  not an interface implemented per-feature like `AlarmController`. Whether it
  belongs in `:contract` alongside the interfaces, or stays genuinely-shared
  infrastructure in `:app`/`:shared`, needs a decision before it moves.

## Phase 5 — next: platform relocation

`SoundManager`, `VibrationManager` (`app.core.media`) and `NotificationHelper`
(`app.core.notification`) are Android API code, not shared app-layer logic —
the doc's rule is `core = what, platform = how on this OS`. These move to
`:platform`.

## Phase 6 — next: `feature/task` internal restructuring

`feature/task` is flat with 22 top-level files while `adapter/`, `notice/`,
`timer/` already exist as subfolders — the "grow downward" rule applies.
Planned subfolders: `list/`, `addtask/`, `group/`. Includes full renames
(files, classes, and Activities) — Activity renames require touching
`AndroidManifest.xml` and `AppRoutes` (or `:contract`, if Phase 4 lands
first) in the same commit, per your instruction to rename everything
including Activities.

## Phase 7 — next: promote `feature/` to a real Gradle module

Deletes `scripts/feature_import_allowlist.txt` entirely — the compiler
enforces what the script currently checks.

## Phase 8 — next: `data/scheduler` split

`RtScheduler.kt` mixes `SharedPreferences` read/write (a `:data` concern) with
window-activation math (a `:core` concern — pure logic, no reason to depend on
Android). Split so the policy math moves to `:core`, and `:data` keeps only
the thin persistence adapter.

## Phase 9 — next: resolve `TimerEngine` duplication

`core.scheduler.timer.TimerEngine` (pure FSM, currently unused) and
`app.feature.task.timer.TimerEngine` (the Android implementation actually in
use) — carried over from Phase 2c, still unresolved.

## Phase 10 — next: complexity-ratchet backlog (carried over from Phase 2c)

1. Split `MainActivity` (1362 lines) and `TaskViewModel` (1177) into
   per-feature fragments and ViewModels. **Highest value** — the two files
   every feature currently edits. Follow the delegate pattern already used
   here rather than inventing a new one.
2. Multibound `BackupContributor` / `SyncContributor` so `BackupManager` stops
   being a file every feature edits.
3. `build-logic/` convention plugins to stop `compileSdk` drifting across four
   build files.
4. `explicitApi()` + `internal` by default.
