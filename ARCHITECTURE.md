# Architecture

## Module graph

```
:app  ──▶ :data ──▶ :core ◀── :platform          :shared ◀── (anyone)
  └──────────────────┴─────────▶
```

| Module      | Kind      | Rule |
|-------------|-----------|------|
| `:core`     | Pure JVM  | No Android, Room, Hilt or system clock. The Android plugin is deliberately withheld so purity is a **compile error**, not a convention. |
| `:data`     | AndroidLib| Room entities, DAOs, repositories, backup/sync, scheduler facades. |
| `:platform` | AndroidLib| Android adapters for `:core` ports (clock, alarms, RR store). |
| `:shared`   | Pure JVM  | Cross-cutting utilities, feature flags, crash isolation. |
| `:testing`  | Pure JVM  | Fakes and unit tests proving `:core` needs no emulator. |
| `:app`      | App       | All UI, ViewModels, DI wiring, manifest, resources. |

Feature packages live under `app/src/main/kotlin/com/eevdf/app/feature/`.
**Phase 2 promotes them to real Gradle modules.** Until then the boundary is
enforced by `scripts/check_architecture.sh`, not by the compiler.

---

## The five guard rails

Everything here exists to answer one question: *can a new feature break a
working one?*

### 1. Feature isolation
`scripts/check_architecture.sh` fails the build if a feature package imports
another feature. Grandfathered edges live in
`scripts/feature_import_allowlist.txt`. **That list may shrink, never grow.**

    v4.3.0  15 edges
    v4.4.0   5 edges   (Phase 2a)

The five survivors are all service control — one feature starting, stopping or
reading another feature's foreground service. See Phase 2b.

**Shared code lives in `app.core.*`, not in a feature.** If two features need
something, it belongs in `core.prefs`, `core.media`, `core.notification`,
`core.signals`, `core.nav`, or further down in `:data` / `:shared` / `:core`.

**Navigation goes through `AppRoutes`** (`app.core.nav`), which resolves screens
by class name so no screen holds a compile-time reference to another.
`AppRoutesTest` validates every route resolves.

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

### 5. Complexity ceilings
No new file over 800 lines (`MainActivity` and `TaskViewModel` grandfathered
until Phase 2). No growth in global mutable state. Detekt on top.

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

## Phase 2b — next

1. **Service-control abstraction.** `AlarmController` / `OverlayController`
   interfaces implemented in `:app` and injected, so `task` and `autoswitch`
   depend on a contract rather than on each other's `Service` classes. This
   clears the last 5 edges and unblocks the module split.
2. Split `MainActivity` (1281 lines) and `TaskViewModel` (1151) into per-feature
   fragments and ViewModels. **Highest value** — the two files every feature
   currently edits. Follow the delegate pattern already used here
   (`TaskSchedulerDelegate`, `TaskListBuilderDelegate`, ...) rather than
   inventing a new one.
3. Promote feature packages to Gradle modules; delete the allowlist.
4. Freeze `tasks`; new feature data goes to side tables
   (`docs/SIDE_TABLE_TEMPLATE.md`).
5. Multibound `BackupContributor` / `SyncContributor` so `BackupManager` stops
   being a file every feature edits.
6. `BubbleEventBus` → injected `@Singleton AppSignals` (the StateFlow seam is
   already in place).
7. `build-logic/` convention plugins to stop `compileSdk` drifting across four
   build files.
8. `explicitApi()` + `internal` by default.

### Known duplication (not yet resolved)

`TimerEngine` exists twice: `core.scheduler.timer.TimerEngine` (pure FSM,
currently unused by the app) and `app.feature.task.timer.TimerEngine` (the
Android implementation actually in use). Consolidating them is worthwhile but
was out of scope for a refactor done without a compiler.
