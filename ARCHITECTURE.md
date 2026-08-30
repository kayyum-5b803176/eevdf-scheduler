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
| `:platform` | AndroidLib| ✅ real module | Android adapters for `:core` ports (clock, alarms, RR store), plus `SoundManager`, `VibrationManager` (`platform.media`) and `NotificationHelper` (`platform.notification`) — moved from `app.core.*` during Phase 7's aftermath (see below), completing what Phase 5 had planned. |
| `:shared`   | Pure JVM  | ✅ real module | Cross-cutting utilities, feature flags, crash isolation. |
| `:testing`  | Pure JVM  | ✅ real module | Fakes, plus `:core`'s own unit tests (arguably belongs in `:core` — the doc defines `testing/` as reusable *infrastructure*, not the tests themselves; unresolved, low priority). |
| `:contract` | AndroidLib| ✅ real module (Phase 4, v5.11.0) | `AppRoutes` (`contract.nav`), `AlarmController`, `OverlayController`, `AlarmActions` (`contract.control`). Deliberately dependency-free — no `:core`, no `:data` — because a contract that needs another module's types has stopped being a contract. `AppRoutesTest` stays in `:app` (needs the Activity classes on its classpath, which `:contract` should never have). |
| `:feature`  | AndroidLib| ✅ real module (Phase 7, v5.12.0) | The unbounded growth surface, one Gradle module. Physically co-located per subfeature — `feature/src/main/task/{kotlin,res}/`, `feature/src/main/settings/{kotlin,res}/`, etc. — rather than scattered into type-based top-level folders, matching the ownership philosophy's "organize by who owns it" rule applied to resources too. One namespace (`com.eevdf.feature`), one manifest, one `R` class; per-feature isolation (task cannot import settings) is still `scripts/check_architecture.sh`'s job, not the compiler's, until a future per-feature-module split. Two buckets every feature is allowed to import without tripping the isolation check: `feature/ui/` (design system — 5 card views, `colors.xml`/`dimens.xml`/`themes.xml`) and `feature/shared/` (`AutoSwitchPrefs`, `DisplayPrefs`, `HardwareKeyPrefs`, `QuickActionPrefs`, `BubbleEventBus`, `CallEvents`, the `@AppPreferences` Hilt qualifier — genuinely cross-feature, Android-dependent, but not "an OS adapter for a `:core` port" so `:platform` was the wrong fit either). Both are physically inside `:feature` for now rather than dedicated modules. See Phase 7. |
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

**Shared code that is genuinely ownerless no longer lives in `app.core.*` at
all — that package doesn't exist anymore.** What was there moved to one of
three places, each per the ownership test: single-owner code went to its
feature (`RecentGroupPrefs`, `GroupTaskPrefs` → `feature/task`;
`SettingsPage`/`SettingsChangeLogger` → `feature/settings`, Phase 3, v5.10.0);
genuine cross-feature contracts went to `:contract` (`AppRoutes`,
`AlarmController`, `OverlayController`, `AlarmActions`, Phase 4, v5.11.0);
and genuinely-shared-but-Android-dependent code that isn't a `:core`-port
adapter went to `feature/shared` (`AutoSwitchPrefs`, `DisplayPrefs`,
`HardwareKeyPrefs`, `QuickActionPrefs`, `BubbleEventBus`, `CallEvents`, the
`@AppPreferences` qualifier — forced out of `:app` once `:feature` became a
separate module and could no longer reach back into it). `SoundManager`,
`VibrationManager`, `NotificationHelper` went to `:platform` for the same
reason, completing Phase 5 ahead of schedule. Nothing is shared by folder
name alone anymore — see Phase 7 for the compiler error that forced this.

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
No new file over 800 lines. `MainActivity`/`TaskViewModel` are over that limit
already, but are no longer exempt from the check. The metric isn't line
count, though — an early draft of this ratchet tried that and was wrong,
inconsistent with the long-function ratchet's own point below. It's function
count: each file's function count at the time this ratchet was introduced
(`MainActivity` 54, later re-set to 35 after Phase 10's extraction;
`TaskViewModel` 76) is its ceiling, and the build fails if either grows past
it. Growing an existing function doesn't move this number; adding a new one
does — that's the actual signal a new responsibility landed here instead of
in its own delegate. This was introduced in Phase 10 after the previous full
filename exemption let both files grow silently (1281→1362, 1151→1176 lines)
with no guard ever checking. No growth in global mutable state.

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
- `core.signals` (`BubbleEventBus`, `CallEvents`) stayed in `app.core.signals`
  at the time — cross-feature but a concrete `StateFlow` singleton, not an
  interface implemented per-feature like `AlarmController`, so folding it
  into `:contract` was deferred rather than assumed. It didn't stay there
  long: see Phase 7's aftermath below for why it had to move again.

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

### Phase 7 aftermath: what the first real compiler run found

Static analysis (grep-based verification) cleared Phase 7 before it shipped.
The first actual `:feature:kspDebugKotlin` run did not — it failed on
`TaskViewModel`'s `@AppPreferences`-qualified constructor parameter resolving
to `error.NonExistentClass`. Investigating that one error surfaced a category
of mistake the static checks had missed entirely: **every reference from
`:feature` back into whatever was still left in `app.core.*` was silently
broken**, because verification had checked "does anything outside `feature/`
import `feature/`" and "do the 4 files moving to `:contract` have external
callers," but never re-checked the *rest* of `app.core.*` against the full
69-file tree once `:feature` became a module that could no longer reach back
into `:app`. Four distinct breaks, all the same root cause:

1. **32 files** had `import com.eevdf.app.R` — untouched by the Phase 7
   package-rename `sed`, since it matches neither `com.eevdf.app.feature.`
   nor `com.eevdf.app.ui.`. Fixed to `com.eevdf.feature.R`.
2. **`SoundManager`, `VibrationManager`, `NotificationHelper`** — Phase 5's
   planned move, done now instead of later since it was the correct fix
   either way. `app.core.media`/`app.core.notification` → `platform.media`/
   `platform.notification`.
3. **`AutoSwitchPrefs`, `DisplayPrefs`, `HardwareKeyPrefs`, `QuickActionPrefs`,
   `BubbleEventBus`, `CallEvents`** — genuinely shared across 2+ features,
   need `android.content.SharedPreferences`/`Context` so pure-JVM `:shared`
   can't hold them, and aren't `:core`-port adapters so `:platform` doesn't
   fit either. Same shape as the design-token problem Phase 7 already solved
   for `colors.xml` et al. — moved into a new `feature/shared/` bucket,
   alongside `feature/ui/`, with the identical caveat: physically inside
   `:feature` for now, `:designsystem`-style module is the long-term answer.
4. **`@AppPreferences`** (the Hilt qualifier) moved into `feature/shared`
   alongside the prefs classes that need it. Its `@Provides` binding stayed
   in `app/di/PlatformModule.kt` — Hilt aggregates `@InstallIn` modules into
   one graph regardless of which module declares them; only the annotation
   *type* needs to resolve wherever it's referenced as a qualifier.

The lesson for future phases: when a module stops being able to reach
another, re-verify **every remaining reference in the moved code**, not just
the files that were the direct subject of the move.

## Phase 5 — done (as of Phase 7's aftermath, v5.12.0)

`SoundManager`, `VibrationManager` (`app.core.media`) and `NotificationHelper`
(`app.core.notification`) moved to `:platform` — see Phase 7 aftermath above
for why this happened as an emergency fix rather than its own planned phase.

## Phase 6 — done (v5.13.0): `feature/task` internal restructuring

`feature/task` was flat with ~30 top-level files while `adapter/`, `notice/`,
`timer/` already existed as subfolders — the "grow downward" rule applied.
Added `list/`, `addtask/`, `group/`, and renamed every file/class that had
been carrying a now-redundant `Task`/`AddTask`/`Group` prefix now that the
owning folder says the same thing.

**The split, based on actual coupling, not the first guess.** Tracing real
usage moved `TaskGroupExpandDelegate` into `list/` rather than the originally
planned `group/` — it's tightly coupled to `ListBuilderDelegate`/`SortHelper`
(building the rendered list), not to `PickerDialog` (a standalone picker
dialog). `group/` ended up holding only the picker dialog and its two prefs
classes.

- **`list/`**: `MainActivity`, `TaskViewModel` (kept — not verbose, and
  `TaskViewModel` is genuinely the authoritative state holder, unlike the
  delegates below it), `ListBuilderDelegate`, `SortHelper`, `SchedulerDelegate`,
  `CallSwitchDelegate`, `GroupExpandDelegate`, `QueueLastRunDelegate` (kept,
  no prefix to drop), `ListTogglesDelegate` (renamed from
  `TaskSettingsDelegate` — it owns list-view toggles: Groups mode, Global
  Rotate, Allow Edit, Auto Scroll; plain `SettingsDelegate` would've read as
  the unrelated `feature/settings` feature).
- **`addtask/`**: `AddTaskActivity` (kept — entry Activity), `SaveHandler`,
  and 10 `*Section.kt` files, all extension functions on `AddTaskActivity`
  (`internal fun AddTaskActivity.setupQuotaSection()`), so these were pure
  file renames — no class identifier to update, no reference-site changes.
- **`group/`**: `PickerDialog` (renamed from `GroupPickerDialog`),
  `RecentGroupPrefs`, `GroupTaskPrefs` (both kept as-is — "Group" isn't a
  leading prefix matching the folder name the way `Task`/`AddTask` were
  elsewhere, so the mechanical drop-rule didn't apply).
- **`adapter/`**: unchanged location, five files renamed (`BindHelpers`,
  `CardScale`, `Formatters`, `NoticeSegments`, `UnitFormat`); `TaskAdapter`,
  `TaskDiffCallback`, `TaskViewHolder` kept — framework-shaped names
  (`RecyclerView.Adapter`, `DiffUtil.ItemCallback`, `RecyclerView.ViewHolder`
  subclasses) are idiomatic as-is.
- **`notice/`, `timer/`**: unchanged location, `TaskNoticeStateMachine` →
  `NoticeStateMachine`, `TaskInterruptDelegate` → `InterruptDelegate`;
  `NoticePhase`, `TimerCardAction`, `TimerEngine`, `TimerStartEvent` kept.

**Two names deliberately deviate from the mechanical prefix-drop rule**,
because the drop would have created misleading collisions:
`TaskAdapterDisplayPrefs.kt` isn't preferences at all — it's the
`applyCardScale` function — becoming plain `DisplayPrefs` would've collided
in meaning (not compilation — different packages — but in a global symbol
search) with the unrelated `feature/shared/prefs/DisplayPrefs.kt`. Renamed to
`CardScale.kt`, matching what the code does. `TaskSettingsDelegate` → the
`ListTogglesDelegate` naming above, same reasoning against `feature/settings`.

**The `Delegate` suffix stays**, deliberately, even though the ownership
philosophy's "drop redundant labels" instinct might suggest cutting it.
Checked against the actual code first: `TaskViewModel` still literally
instantiates every one of these
(`internal val groupExpand = TaskGroupExpandDelegate(prefs, this)`) and each
takes `vm: TaskViewModel` as a constructor parameter to act back on it — the
delegation relationship is real, today, not just a naming leftover. Phase 6
is a pure rename with no behavior or structure change, so removing "Delegate"
now would make the name less accurate, not more. Revisit when Phase 10
actually eliminates `MainActivity`/`TaskViewModel` as the composing god
objects — if `ListBuilderDelegate` becomes its own fragment/ViewModel that
nothing delegates *to* it from, that's when the name should change too.

**The lesson repeated from Phase 7, this time inside one feature instead of
across a module boundary:** splitting one flat package into subpackages
means every cross-subpackage reference that previously worked for free
(same package, no import needed) needs an explicit `import` now. Static
verification caught this systematically — cross-checking every declared
identifier against every file that used it, outside its own new subpackage —
and found real gaps beyond the obvious ones: `MainActivity.kt` needed a new
`import` for `AddTaskActivity` it never needed before (same package until
this phase); three files (`AddTaskActivity.kt`, `NoticeStateMachine.kt`,
`InterruptDelegate.kt`) had a *stale* import at the old top-level
`com.eevdf.feature.task.TaskViewModel` path (correct before this phase,
since `notice/`/`timer/`/`addtask` were already subpackages — now wrong since
`TaskViewModel` moved into `list/`); several `list/*.kt` files had *redundant*
same-package imports left over from before the split. All three shapes of
mistake — missing, stale, redundant — are easy to introduce and easy to miss
without checking every declared type against every consumer, not just the
files that were the direct subject of a move.

**Also fixed as part of this phase** (doc-comment accuracy, no code impact):
stale mentions of the old names in `:core`, `:data`, `:contract`, and
`feature/autoswitch` (cross-feature doc comments describing the relationship
to these classes), plus an orphaned, never-applied `AppTheme.GroupPickerDialog`
style renamed to `AppTheme.PickerDialog` for consistency — same "unwired
things still get a proper name" rule applied throughout this refactor.

`MainActivity`'s and `AddTaskActivity`'s package changes required updating
`feature/src/main/AndroidManifest.xml` (both `android:name` and
`android:parentActivityName` attributes) and `AppRoutes.MAIN` (in
`:contract`) in the same commit — exactly the Activity-rename cost flagged
back in the original renaming-scope decision.

### Phase 6 aftermath: the same lesson, one scope wider

The first real `:feature:compileDebugKotlin` run found two more gaps, both
one level outside what Phase 6's own verification checked:

1. **`feature/backup/DataBackupActivity.kt`** imported `TaskViewModel` at its
   old top-level path. This is the one deliberately-tolerated cross-feature
   edge in `scripts/feature_import_allowlist.txt` (`backup -> task`,
   documented there with its own removal criteria) — not an architecture
   violation, just a stale import from a feature Phase 6's verification never
   looked at, because that verification only checked references *within*
   `feature/task/`. The lesson from Phase 7's aftermath ("re-check every
   remaining reference, not just the files that were the direct subject of
   the move") applied one scope wider than it was applied here: within-module
   renames need the *whole module* re-checked for consumers, not just the
   package being restructured.
2. **`PickerDialog.kt`** referenced `R.style.AppTheme_GroupPickerDialog` —
   the *generated* Kotlin-side identifier for the `AppTheme.GroupPickerDialog`
   style renamed during this phase. Plain-text search for the dotted XML form
   doesn't catch the underscored generated form actual code uses; anything
   that renames an Android resource needs to grep for both forms.

Both are one-line fixes. Recorded here because the pattern (check the
direct-subject files exhaustively, miss a consumer one hop away) is now the
second time it's caused a compile failure in this refactor — worth treating
as a standing checklist item for any future phase that moves or renames
something with external consumers: grep the *whole repo* for the old
identifier, not just the tree being restructured.

## Phase 8 — done (v5.14.0): `data/scheduler` split

**What this phase was expected to be, per the plan above:** move `RtScheduler`'s
pure window-activation math out of `:data` into `:core`, leaving `:data` with
only the `SharedPreferences` adapter.

**What it actually was, once the code was checked before touching anything:**
that move had already happened — just not by this refactor, and never
finished. `:core` already contained `RtConfig.isWindowActive()`/
`secondsUntilClose()` and a whole `RtPolicy` object (`hasActiveRtDescendant`,
`pickRr`, `advanceRr`, `secondsUntilNextActivation`), all characterization-
tested, all explicitly documented as "ported from the reference `RtScheduler`."
None of it was wired to a live caller. `:data`'s `RtScheduler.kt` still ran
its own independent, duplicate, Calendar-based reimplementation of the exact
same rules, and every real call site — `ListBuilderDelegate`,
`SchedulerDelegate`, `TaskAdapter`, `BindHelpers`, `TaskRepository` (5 files,
~12 call sites) — used that duplicate, not the tested pure version.

**This made the phase materially riskier than every phase before it.**
Every prior phase (3 through 7) was a rename or a file move — provably
behavior-preserving by construction. This one could change what the
scheduler actually decides, so it was treated differently: investigated and
proposed before any code was touched, rather than executed and corrected
after a compiler run, per the norm this refactor otherwise followed.

**The fix:** rewrote `RtScheduler.kt`'s internals only — every public
function keeps its exact original signature, so **zero call sites needed to
change**. Internally, each function now converts `Task` -> `SchedTask` (via
`:data`'s existing `toSched()`, the same adapter `SchedulerFacade` already
uses for EEVDF) and the current instant -> `(dayIndex, secondOfDay,
prevDayIndex)`, then delegates to `RtPolicy`/`RtConfig`. `DAY_SUN`..`DAY_ALL`
now delegate to a new `RtConfig` companion (single source of truth).

**Deliberately not migrated:** `pickRrTask`/`advanceRrIndex` — confirmed dead
code, called by nothing except the equally-unused `RtSchedulerService` DI
wrapper; no live path exercises FIFO/RR cohort selection today. Migrating
dead code for symmetry would add risk for zero behavioral benefit.
`clearRrState` — pure `SharedPreferences` removal with no policy content,
already a thin adapter as-is. `RtScheduler.kt` and `RtSchedulerService.kt`
are left in place (per the standing no-delete rule) even though the latter
is now fully dead weight — nothing outside its own file and the DI module
that provides it ever calls it.

**A real bug found and fixed, not just a refactor risk avoided:**
`RtPolicy.hasActiveRtDescendant` in `:core` was missing a self-check that
`:data`'s original `RtScheduler.hasActiveRtDescendant` has and explicitly
documents: *"A group with its OWN active RT window counts — without this
check a RT-class group with only CFS children returns false, breaking the
upward chain."* `:core`'s port dropped this silently — nothing caught it
because nothing had ever called the `:core` version with this exact shape
(a group with its own active window and only non-RT children). Fixed in
`RtPolicy.kt` directly (not worked around locally in `:data`), with three new
regression tests added to `RtWindowCharacterizationTest.kt` covering: a
group's own window counting despite fair-scheduled children, a group with an
inactive own window and no RT descendants correctly returning false, and a
grandparent correctly seeing a hoisted RT group through an intermediate fair
group. `:core` is now actually correct for this case, not just delegated-to.

## Phase 9 — done (v5.15.0): resolved `TimerEngine` duplication — by deletion

**Not the same resolution as Phase 8.** Phase 8's dormant `:core` code
(`RtPolicy`/`RtConfig`) turned out correct and complete once checked — the
fix was wiring it up. This one is the opposite: checked before assuming
anything, and `core.scheduler.timer.TimerEngine` turned out to be an
**abandoned prototype that never reached feature parity** with what the app
actually needs, not a finished replacement waiting to be connected.

**What was actually different, not just smaller:** `:core`'s reducer tracked
remaining time by *accumulating tick deltas* (`accumulatedMs + elapsedMs`) —
exactly the kind of arithmetic that drifts if a tick is delayed or skipped.
`com.eevdf.feature.task.timer.TimerEngine` (the live one) does something
better: it re-derives remaining time from real wall-clock epochs on every
tick, by design — its own doc comment says CountDownTimer's
`millisUntilFinished` is "NEVER used for display values" for exactly this
reason. The live version also does things `:core`'s has no model for at
all: `restoreFromDb()` recovers correctly if the process was killed mid-timer
(including detecting expiry-while-dead); `RunSession.Paused`/`Expired`/
`Recovered` carry real elapsed wall-clock time for accurate vruntime/stats
crediting across multiple pause/resume cycles; and a documented,
deliberate fix for a race that used to silently drop run-time crediting.

**`:core`'s own test suite admitted the gap.**
`TimerEngineCharacterizationTest.kt` had a `KNOWN BUG` test asserting
`TimerEffect.Expired.ranSeconds` is hardcoded to `0L` — "anything downstream
that credits run time from this effect... will record zero." Migrating the
live implementation to this reducer would have been a regression:
reintroducing exactly the zero-crediting bug the live version exists to
prevent, while also losing app-kill recovery and race-safety.

**Deleted, not migrated-to:** `core/scheduler/timer/TimerEngine.kt`,
`platform/scheduler/CountdownTimerDriver.kt` (the tick-source adapter that
only existed to drive the deleted reducer — dead the moment its one consumer
is gone), and `TimerEngineCharacterizationTest.kt`. This is the first actual
deletion in this refactor, as opposed to every prior phase's renames/moves —
justified because this was confirmed dead *and* confirmed inferior to what
replaced it, unlike earlier "unwired but correct" cases (`GroupTaskPrefs`,
Phase 8's `RtPolicy`) which were parked for a future wiring, not removed.
`com.eevdf.feature.task.timer.TimerEngine` is untouched and remains the one
timer implementation in the app.

## Phase 10 — partially done (v5.16.0): complexity-ratchet backlog (carried over from Phase 2c)

Item 1's `MainActivity` half is done and real-device verified. `TaskViewModel`
(item 1's other half) and items 2–4 are still open.

**Item 1 was investigated before touching anything, per the pattern
established in Phase 8.** Splitting `MainActivity`/`TaskViewModel` is
categorically different from every phase before it: phases 3–7 were renames
and moves, provably behavior-preserving by construction; phase 8 had existing
tested pure logic to lean on; phase 9 was a clean deletion of confirmed-dead
code. This one has none of that — there's no pre-built decomposition to
discover, and lifecycle-bound Android UI wiring can't be characterization-
tested the way pure functions can. A mistake here doesn't fail loudly at
compile time; it fails as "the timer card doesn't update," discovered later,
in the running app. `MainActivity` was decomposed anyway, on explicit
instruction to do the whole file in one pass rather than incrementally — see
the testing checklist shipped alongside this phase's export for what to
verify on a real device, since that verification could not happen here.

**The guard-rail metric was wrong on the first attempt, and corrected before
anything shipped.** The first fix tried was a raw line-count ratchet (each
file's current line count as a hard ceiling). That was inconsistent with this
project's own stated philosophy one section up in the same guard
(`[7/7] Long-function ratchet`): *"File length is deliberately NOT the
metric... splitting `setupObservers()` into nine functions made the file 65
lines longer and far easier to work in."* A line-count ceiling would have
blocked exactly that kind of healthy change, and treated a genuinely-required
new Android override identically to a new, unrelated capability bolted
directly onto the god file. **The guard now ratchets on function count
instead** — the number of top-level functions in `MainActivity.kt`/
`TaskViewModel.kt` can only go down, never up, without a visible edit to
`scripts/check_architecture.sh` in the same change. Growing an existing
function doesn't move this number; adding a new one does, which is the actual
signal that a new responsibility landed somewhere it shouldn't have.

**What "eliminating the god file" actually means, and what it doesn't:**
neither file can be deleted — `MainActivity` must exist as a real
`AppCompatActivity` subclass implementing certain Android-required overrides
directly (`onCreate`, `onCreateOptionsMenu`, etc.); that's the platform's
contract, not a design choice. What's achievable is functional elimination —
the file stops being a shared edit surface where logic accumulates, even
though it still exists as a thin wiring shell.

**`MainActivity`: 1362 → 588 lines, 54 → 35 functions. Verified working on a
real device** — every item on the Phase 10 testing checklist (display
scaling/compact mode/FABs, timer card + buttons, menu/sync icon/schedule-next
dot, all 9 observers) checked out with no regressions. Four concerns were
extracted into their own delegate classes, all in `feature/task/list/`,
following the exact `internal class XDelegate(private val activity:
MainActivity)` pattern `TaskViewModel` already uses for its own 7 delegates
(fields/functions the delegates need were promoted from `private` to
`internal`, module-visible, same as every existing delegate/owner pair in
this codebase):

- **`DisplayScaleDelegate`** — `applyDisplayPrefs`, `updateCompactMode`,
  `applyCardScaleToView`, `applyFabVisibility` (184 lines)
- **`TimerCardDelegate`** — `renderTimerCard`, `setupTimerCard` (126 lines)
- **`MenuSyncDelegate`** — menu inflation/selection logic, `updateSyncIcon`,
  `updateScheduleNextDot`, the content-description view-tree walk (262 lines).
  `onCreateOptionsMenu`/`onOptionsItemSelected` themselves stay as thin
  one-line overrides on `MainActivity` — Android calls those directly on the
  Activity, they cannot be delegated away — forwarding into
  `inflateMenu`/`handleItemSelected` here.
- **`ObserverDelegate`** — `setupObservers` and its 9 `observeX` functions
  (303 lines), the single largest extraction. Calls into the other three
  delegates (`activity.timerCardDelegate.renderTimerCard(...)`,
  `activity.menuSyncDelegate.updateSyncIcon(...)`, etc.) and into what stayed
  on `MainActivity` (`updateEmptyView`, `updateScheduleRankBadge`,
  `scrollToTask` — all promoted to `internal` for exactly this reason).

**What stayed on `MainActivity`, deliberately:** every lifecycle override
(`onCreate` through `onPictureInPictureModeChanged`), view setup
(`setupToolbar`/`setupViews`/`setupAlarmBanner`/`setupAdapters`/
`setupRecyclerView`/`setupTabs`/`makeAdapter`), and the handful of small
helpers (`haptic`, `tickQuotaOnVisibleItems`, `scrollToTask`,
`updateScheduleRankBadge`, `updateEmptyView`, `showTaskDetail`,
`confirmDelete`) that either are Android-required overrides or are called
from multiple delegates and don't belong to any single one of them.

**Total lines across all 5 files grew (1362 → 1463)** — same reasoning as the
guard-rail fix above: more files, each with their own doc comments and class
boilerplate, is a fair trade for zero files over ~300 lines and each concern
having exactly one place it can be edited.

**`TaskViewModel` (1176 lines, 76 functions) was not touched this pass.**
It's a different-shaped problem — it already composes 7 delegates, so
whatever logic still lives directly on it (not yet in a delegate) is the
target for a future pass, not a wholesale restructure.

1. Split `MainActivity` (done, see above) and `TaskViewModel` (1176 lines,
   **in progress, one cluster at a time**) into delegates / per-feature
   ViewModels. `TaskViewModel` has a different risk shape than `MainActivity`:
   a mistake in vruntime crediting or app-kill recovery doesn't look wrong —
   it looks completely normal and silently credits the wrong run time, a
   much worse class of bug than a visibly-broken UI element. Extracting one
   cluster at a time, lowest-risk first, each with its own check-in, rather
   than one pass like `MainActivity`.

   - **`TaskCrudDelegate` — done, v5.17.0.** `addTask`/`updateTask`/
     `deleteTask`/`revertTask`/`markCompleted`/`clearCompleted`/`clearToast`/
     `toggleGroupExpanded`/`getTaskById`/`getLoadFactor`/`saveLoadFactor` +
     the private `syncPinnedWeights` they all trigger. Chosen first because
     it never touches the timer/alarm state machine directly — it calls
     `pauseTimer()`/`stopTimer()` through the same internal surface any
     other caller would, not by reaching into timer internals.
     `TaskViewModel` keeps every one of these as a one-line facade (`fun
     addTask(task: Task) = crud.addTask(task)`), matching the existing
     Interrupt/CallSwitch/Scheduler facade pattern exactly, so **zero
     external callers changed** — confirmed by grep across every consumer
     (`MainActivity`, `MenuSyncDelegate`, `ObserverDelegate`,
     `AddTaskActivity`, `LoadFactorSection`, `SaveHandler`).
   - **`AlarmOverrunDelegate` — done, v5.18.0.** `startInAppOverrunCounter`/
     `stopAlarmSound`/`isAlarmActive`/`restartAfterExpire` + the private
     `stopOverrunCounter`/`startFreshSlice` they use. Same shape as CRUD: calls
     into not-yet-extracted timer lifecycle only through `setCurrentTask()`/
     `startTimer()`, TaskViewModel's own public surface. All fields needed
     were already `internal` — no visibility promotions this time. Function
     count 76 → 72.
   - **`BubbleTapDelegate` — done, v5.19.0. Build-verified only, not
     functionally verified** — the test device has no telephony support, so
     the actual call-switching behavior (Cases A/B/C) couldn't be exercised.
     The deprecated `toggleCallTaskTimer` stays as a facade forwarding to it, per
     the no-delete rule — it has zero real callers beyond its own
     declaration, but is kept rather than removed. Same shape as the
     previous two: calls into not-yet-extracted timer lifecycle only through
     `pauseTimer()`/`startTimer()`. All fields already `internal`/public —
     no visibility promotions.
   - **`StartupRecoveryDelegate` — done, v5.20.0.** The 3-step app-kill
     recovery (ringing-alarm finalization, mid-run task resume/finish,
     persisted-selection restore) that used to run inline inside `init{}`.
     The riskiest cluster moved so far — a mistake here means a run is
     silently mis-credited or double-credited. `init{}` itself couldn't
     move: Kotlin requires `TaskViewModel`'s own `val` LiveData fields
     (`allTasks`, `activeTasks`, etc.) to be assigned inside an init block or
     at declaration, not from an external delegate — but the actual recovery
     *decision logic* could, and does. `init{}` now just calls
     `startupRecovery.recover()` inside the same `viewModelScope.launch` it
     always used. `onTimerFinished` promoted from `private` to `internal` so
     the delegate can call it for the "task expired while app was dead" path.
   - **Still on `TaskViewModel`:** Timer lifecycle (~350 lines) — the last
     and biggest cluster, the actual state machine (`startTimer`/
     `pauseTimer`/`resetTimer`/`onTimerFinished`/`setCurrentTask`) everything
     else calls into.
2. Multibound `BackupContributor` / `SyncContributor` so `BackupManager` stops
   being a file every feature edits.
3. `build-logic/` convention plugins to stop `compileSdk` drifting across four
   build files.
4. `explicitApi()` + `internal` by default.
