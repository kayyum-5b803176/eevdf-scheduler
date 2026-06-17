# EEVDF Scheduler — Linux-style subsystem rewrite

A clean-room rewrite of the EEVDF productivity scheduler into a Linux-kernel-style
subsystem architecture. Existing project code was used as **reference only**; no
dead code or muddled logic was carried over.

Priority order honored: **performance → stability → scale → maintainability → testability.**

## Module layout

```
:core        Pure Kotlin/JVM. No Android, no Room, no Context, no System.currentTimeMillis.
             The EEVDF algorithm, RT/DL/quota rules, timer FSM, and all ports live here.
:data        Room entities, DAOs, repositories. Implements core ports. Maps Entity <-> domain.
:platform    Android API adapters (clock, RR-store, CountDownTimer driver, alarm/sound/notif).
             Implements core ports.
:shared      Cross-cutting pure utilities (formatters).
:feature:*   User-facing features (Activities/Fragments/ViewModels). Currently: :feature:task.
:testing     JVM fakes + unit tests. Proves the core is testable without an emulator.
:app         Composition root: wires concrete implementations into core ports.
```

### Dependency rule (compiler-enforced)

```
feature ──▶ core ◀── data
                 ◀── platform
   app wires implementations into ports
shared ◀── (anyone);  shared depends on nothing
```

`:core` deliberately has **no Android Gradle plugin**. If any core file ever imports
`android.*`, `androidx.*`, or Room, the build fails — the architecture guards itself.

## Build

```bash
# add a Gradle wrapper jar first (not shipped in this archive):
gradle wrapper --gradle-version 8.9
./gradlew :testing:test      # runs the pure EEVDF unit tests
./gradlew :app:assembleDebug # builds the app
```

## Status — what is implemented vs. scaffolded

This archive is an **honest, buildable foundation**, not a finished port of every screen.

**Fully implemented (the logic-bearing layers):**
- `core` — complete: `SchedTask` + RT/DL/Quota value objects, `EevdfScheduler` (pure,
  single-pass, reference bugs removed), `CpuShares` (cgroup shares + Jain fairness),
  `RtPolicy` (incl. midnight-crossing windows + RR), `SchedulerService` (DL→RT→EEVDF
  precedence), pure `TimerEngine` FSM, `Clock`/`WallClock`, all ports.
- `data` — `TaskEntity` + mapper, `TaskDao`/`TaskDatabase`, `TaskRepositoryImpl`
  (implements `TaskQueuePort`, does placement + pinned-weight sync).
- `platform` — `SystemClock`, `SharedPrefsRrStateStore`, `CountdownTimerDriver`,
  `AndroidAlarmPort` (Doze-immune, consolidates the orphaned reference module).
- `shared` — `DurationFormat`.
- `testing` — `InMemoryRrStateStore`, `FakeTaskQueue`, `EevdfSchedulerTest`.
- `app` — `AppContainer` composition root + `EevdfApp`.

**Scaffolded (architecture proven, body to be ported):**
- `feature:task` — `SchedulerHost` interface + `SchedulerDelegate` + `TaskViewModel`.
  This demonstrates the cycle-break: delegates depend on the narrow `SchedulerHost`
  interface, never the concrete ViewModel, so the reference's 5 ViewModel cycles cannot recur.

## Remaining work (mechanical ports needing the original layouts/resources)

These were intentionally **not** fabricated, because they are large, view-heavy, and
unverifiable without the real XML layouts/resources — guessing them would risk the
stability priority. Each ports onto the structures above with the noted owner:

| Reference source | New owner | Notes |
|---|---|---|
| `ui/task/MainActivity` (1038 L), `AddTask*Section` | `feature:task` | Split along existing section seams; bind to `TaskViewModel`. |
| `viewmodel/{interrupt,notice,autoswitch,groupExpand}` delegates | `feature:task` | Port onto the `SchedulerHost` pattern. |
| `ui/stats/*` (Charts 715, Overview 616) | `feature:stats` | New module; split chart vs data-prep. |
| `ui/settings/*` + Sound/Vibration managers | `feature:settings` + `platform/audio` | Managers implement `SoundPort`/`VibrationPort`. |
| `ui/alarm/*` foreground service + receivers | `feature:alarm` + `platform/alarm` | Receiver targets `AndroidAlarmPort`. |
| `ui/autoswitch/*` overlay + call-state | `feature:autoswitch` + `platform/{overlay,telephony}` | Overlay/telephony are platform. |
| `sync/MultiUserSyncManager` (701 L) | `data/sync` + `core/sync` | Extract pure conflict-resolution rules into core. |
| `backup/BackupManager` | `data/backup` | Relocate. |
| `db/RunLog*` | `data/runlog` | Relocate as entities + DAO. |
| layouts, `res/*`, drawables | each `feature:*` | Copy from reference; no logic. |

## Note on verification

The build toolchain (kotlinc / Android SDK) is not available in the authoring sandbox,
so these files are **reviewed, not compiled**. Cross-checks were run on symbol resolution
and module-boundary purity. Build locally before relying on them.
