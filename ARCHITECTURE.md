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
:app ──▶ :contract, :feature, :core, :data, :platform, :shared
:feature ──▶ :contract, :core, :data, :platform, :shared
```

All eight conceptual roots now have a real home: six as Gradle modules
(`:core`, `:data`, `:platform`, `:shared`, `:testing`, `:contract`), one as
the newest module (`:feature`), and `:app` as the composition root. What's
left is internal reorganization within already-correct boundaries — see
Phases 5, 6, 8, 9, 10 below — plus one still-informal piece: `:feature`'s
own `ui/` subfolder is a genuine design system, physically inside `:feature`
for now rather than its own module. See Phase 7 for why.

| Module      | Kind      | Status | Rule |
|-------------|-----------|--------|------|
| `:core`     | Pure JVM  | ✅ real module | No Android, Room, Hilt or system clock. The Android plugin is deliberately withheld so purity is a **compile error**, not a convention. |
| `:data`     | AndroidLib| ✅ real module | Room entities, DAOs, repositories, backup/sync, scheduler facades. |
| `:platform` | AndroidLib| ✅ real module | Android adapters for `:core` ports (clock, alarms, RR store). Currently thin — `SoundManager`, `VibrationManager`, `NotificationHelper` belong here by the doc's own rule (`platform` = Android APIs) but still live in `app.core.*`. See Phase 5. |
| `:shared`   | Pure JVM  | ✅ real module | Cross-cutting utilities, feature flags, crash isolation. |
| `:testing`  | Pure JVM  | ✅ real module | Fakes, plus `:core`'s own unit tests (arguably belongs in `:core` — the doc defines `testing/` as reusable *infrastructure*, not the tests themselves; unresolved, low priority). |
| `:contract` | AndroidLib| ✅ real module (Phase 4, v5.11.0) | `AppRoutes` (`contract.nav`), `AlarmController`, `OverlayController`, `AlarmActions` (`contract.control`). Deliberately dependency-free — no `:core`, no `:data` — because a contract that needs another module's types has stopped being a contract. `AppRoutesTest` stays in `:app` (needs the Activity classes on its classpath, which `:contract` should never have). |
| `:feature`  | AndroidLib| ✅ real module (Phase 7, v5.12.0) | The unbounded growth surface, one Gradle module. Physically co-located per subfeature — `feature/src/main/task/{kotlin,res}/`, `feature/src/main/settings/{kotlin,res}/`, etc. — rather than scattered into type-based top-level folders, matching the ownership philosophy's "organize by who owns it" rule applied to resources too. One namespace (`com.eevdf.feature`), one manifest, one `R` class; per-feature isolation (task cannot import settings) is still `scripts/check_architecture.sh`'s job, not the compiler's, until a future per-feature-module split. `feature/ui/` holds the design system (5 card views, `colors.xml`/`dimens.xml`/`themes.xml`) — genuinely used by multiple features, physically inside `:feature` for now rather than a dedicated module. See Phase 7. |
| `:app`      | App       | ✅ real module | Composition root only, now that Phase 7 emptied it out: `SchedulerApplication`, DI wiring, a manifest with permissions + the bare `<application>` shell (components merge in from `:feature`'s manifest), and `res/` holding only the launcher icon + `app_name` (everything else moved to `feature/*/res/`). |

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

**Cross-feature behaviour goes through `:contract`** (`AlarmController`,
`OverlayController`, both in `contract.control`), with the implementation
living inside the feature that owns the behaviour and bound by that feature's
own Hilt module (`AlarmController` -> `feature/alarm/AlarmControlModule`,
`OverlayController` -> `feature/autoswitch/OverlayControlModule`). The arrow
points inward to an interface, never sideways to a sibling. As of Phase 4
(v5.11.0) this is a real Gradle module: a feature that reaches for
`app.core.control` no longer compiles, it has to depend on `:contract`.

**Shared code that is genuinely ownerless lives in `app.core.*`.** Currently:
`core.prefs` (`AutoSwitchPrefs`, `DisplayPrefs`, `HardwareKeyPrefs`,
`QuickActionPrefs` — each read by 2+ features), `core.media`,
`core.notification`, `core.signals`. **Single-owner code that only
looked shared has been moved out** — `RecentGroupPrefs` and `GroupTaskPrefs`
went to `feature/task`, `SettingsPage`/`SettingsChangeLogger` went to
`feature/settings` (Phase 3, v5.10.0), and `AppRoutes`/`AlarmController`/
`OverlayController`/`AlarmActions` went to `:contract` (Phase 4, v5.11.0) —
per the ownership philosophy's rule that sharing must be earned by proven
multi-feature use, not assumed from a folder name.

**Navigation goes through `AppRoutes`** (`contract.nav`, moved from
`app.core.nav` in Phase 4), which resolves screens by class name so no screen
holds a compile-time reference to another. `AppRoutesTest` stays in `:app`
rather than moving with it — it needs the actual Activity classes on its test
classpath for `Class.forName` to resolve them, which `:app` has and
`:contract` deliberately never will.

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

## Phase 4 — done (v5.11.0): the `:contract` module

Promoted `app.core.control` (`AlarmController`, `OverlayController`,
`AlarmActions`) and `app.core.nav` (`AppRoutes`) into a real `:contract`
Gradle module (`com.eevdf.contract.control`, `com.eevdf.contract.nav`).
Confirmed as contracts, not platform code: both controller interfaces have
zero Android imports; their implementations live inside the owning feature
bound via that feature's own Hilt module — exactly the shape
`Scalable_Feature-Boundary_Architecture.md` §8 describes. `:contract` takes no
dependency on `:core`, `:data`, `:platform` or `:shared` — a contract needing
another module's types has stopped being a contract.

**Resolved:**

- `AppRoutesTest` stays in `:app` as `com.eevdf.app.contract.AppRoutesTest`,
  importing `AppRoutes` from `:contract`. It needs the real Activity classes
  on its classpath for `Class.forName` — `:app` has them (transitively, until
  Phase 7), `:contract` should never carry that weight.
- `core.signals` (`BubbleEventBus`, `CallEvents`) stays in `app.core.signals`
  for now. It's cross-feature but a concrete `StateFlow` singleton, not an
  interface implemented per-feature like `AlarmController` — a different
  enough shape that folding it into `:contract` was deferred rather than
  assumed. Revisit in a later phase.

## Phase 7 — done (v5.12.0): promote `feature/` to a real Gradle module

**Done out of order, ahead of Phase 5 and 6.** The original plan sequenced
this after platform relocation and the `feature/task` internal restructuring.
It moved first instead because the ownership trace found `feature/` had zero
external callers into it — nothing outside the tree depended on its internals
— making it the cleanest, lowest-risk extraction available, independent of
Phase 5/6's unrelated work. Phase 5 and 6 are still pending, just no longer
prerequisites.

**What moved:** all 69 feature files (`task`, `alarm`, `autoswitch`, `backup`,
`settings`, `stats`, `sync`) plus the `app/ui` design system (5 card views),
from `app.feature.*`/`app.ui` into one new `:feature` Gradle module,
`com.eevdf.feature.*`. `AppRoutes`'s constants (in `:contract`) and
`AppRoutesTest`'s package-prefix filter were updated to match — anything
still saying `com.eevdf.app.feature.*` after this phase is stale.

**Physical layout, not just package rename:** rather than one flat
`feature/src/main/{kotlin,res}/`, each subfeature is physically co-located —
`feature/src/main/task/kotlin/` sits next to `feature/src/main/task/res/`,
same for every other subfeature, plus `feature/src/main/ui/` for the shared
design system. `feature/build.gradle.kts` wires all of them into one
`sourceSets` block (`subfeatures.map { "src/main/$it/kotlin" }`), so it
remains a single module — one namespace, one `R` class — with resources
organized by owner instead of scattered into type-based folders (`layout/`,
`drawable/`, `values/` as one shared bucket for the whole app). This is the
ownership philosophy applied to resources, not just Kotlin packages.

**The resource-ownership trace** (every layout, drawable, string traced to
its consuming Activity/Fragment) found:

- Every layout/menu/drawable except the launcher icon has exactly one owning
  feature — moved cleanly, e.g. `item_task.xml` → `feature/task/res/layout/`.
- Two drawables (`ic_sync_dot`, `outline_skip_next_24`) are genuinely used by
  two different features (task's menu, autoswitch's bubble/services) →
  `feature/ui/res/drawable/`, the shared bucket, same test as any other
  shared code.
- `colors.xml`/`dimens.xml`/`themes.xml`/`values-night/colors.xml` (166 + 64
  + 244 + 61 entries) went to `feature/ui/res/values/` as units rather than
  split entry-by-entry — they're design tokens, used pervasively across every
  feature by nature, and tracing 500+ individual entries to a single owner
  each would manufacture ownership that doesn't exist. `:app` itself touches
  zero of them directly (confirmed — `SchedulerApplication`/`di/*` reference
  no `R.*` at all), so nothing was lost by not keeping a copy there.
- `strings.xml`'s 16 entries were **entirely unwired** — none referenced
  anywhere in code except `app_name` (via the manifest, not code). 15 moved
  to their name-matched feature (14 → `feature/task`, 1 → `feature/settings`,
  the lowest-confidence placement in this phase since no About screen
  actually exists yet) per the same "unwired code still gets a plausible
  future owner" rule applied earlier to `GroupTaskPrefs`. `app_name` stayed
  with `:app` — genuine app-identity, not feature content.
- `:app`'s own `res/` is now just the launcher icon assets (`mipmap-*/`,
  `drawable/ic_launcher_{background,foreground}.xml`,
  `values/ic_launcher_background.xml`) and a one-string `strings.xml`.

**Resolved:** whether `feature/ui/` deserves its own module (`:designsystem`,
sibling to `:contract`/`:shared`) was raised explicitly and deferred —
correct destination long-term, but adding a fifth new module in the same
session as `:contract` and `:feature`, none of which had a real compiler
signal yet, was assessed as unnecessary risk for this phase. `:shared` was
considered and rejected as the interim home: it's pure JVM by design (no
Android plugin), and that purity is the entire reason `DurationFormat`/
`FeatureFlag`/`SafeRun` are trustworthy without an emulator — bolting Android
resources onto it would blur the one-sentence purpose every subsystem here
is supposed to have. Revisit `:designsystem` once `:contract` and `:feature`
are both build-verified.

**Not done, despite the original plan:** `scripts/feature_import_allowlist.txt`
was *not* deleted. The original Phase 7 description assumed promoting
`feature/` would let the compiler replace the isolation script entirely, but
that was only true for per-feature *modules* — one `:feature` module gives
compiler enforcement of `app → feature` direction, not of `task ↛ settings`
within it. The allowlist and `scripts/check_architecture.sh`'s isolation scan
still do that job; both were updated for the new physical paths
(`feature/src/main/<name>/kotlin/...`) rather than removed.

## Phase 5 — next: platform relocation

`SoundManager`, `VibrationManager` (`app.core.media`) and `NotificationHelper`
(`app.core.notification`) are Android API code, not shared app-layer logic —
the doc's rule is `core = what, platform = how on this OS`. These move to
`:platform`.

## Phase 6 — next: `feature/task` internal restructuring

`feature/task` is flat with ~30 top-level files (in
`feature/src/main/task/kotlin/com/eevdf/feature/task/`, since Phase 7) while
`adapter/`, `notice/`, `timer/` already exist as subfolders — the "grow
downward" rule applies. Planned subfolders: `list/`, `addtask/`, `group/`.
Includes full renames (files, classes, and Activities) — Activity renames
require touching `feature/src/main/AndroidManifest.xml` and `AppRoutes` (in
`:contract`) in the same commit, per your instruction to rename everything
including Activities.

## Phase 8 — next: `data/scheduler` split

`RtScheduler.kt` mixes `SharedPreferences` read/write (a `:data` concern) with
window-activation math (a `:core` concern — pure logic, no reason to depend on
Android). Split so the policy math moves to `:core`, and `:data` keeps only
the thin persistence adapter.

## Phase 9 — next: resolve `TimerEngine` duplication

`core.scheduler.timer.TimerEngine` (pure FSM, currently unused) and
`com.eevdf.feature.task.timer.TimerEngine` (the Android implementation
actually in use, moved from `app.feature.task.timer` in Phase 7) — carried
over from Phase 2c, still unresolved.

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
