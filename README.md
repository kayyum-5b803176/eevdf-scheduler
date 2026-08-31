# EEVDF Scheduler — Linux-style subsystem architecture

An Android task scheduler modeled on Linux's scheduling classes (SCHED_DEADLINE
> SCHED_FIFO/RR > SCHED_NORMAL/EEVDF), restructured into an 8-root module
layout matched to the same subsystem-ownership philosophy: every root is
either **stable infrastructure** or the **one designated growth surface**
(`:feature`). Full reasoning, and the phase-by-phase history of how the
codebase got here, lives in [`ARCHITECTURE.md`](ARCHITECTURE.md) — this file
is the current-state overview.

Priority order honored: **performance → stability → scale → maintainability → testability.**

## Modules

```
:app          Composition root only. DI wiring (Hilt), manifest shell,
              application class. Depends on :contract and :feature.
:contract     Stable promises between features — AppRoutes (string-based
              navigation), AlarmController, OverlayController, AlarmActions.
              Deliberately dependency-free: no :core, no :data. A contract
              that needs another module's types has stopped being a contract.
:core         Pure Kotlin/JVM. No Android/Room/Context/system clock — the
              Android plugin is withheld so purity is a compile error, not a
              convention. EEVDF algorithm, RT window/RR policy, DL/quota
              rules, fairness/shares, ports.
:data         Room entity (Task), DAOs, repositories, RunLog, sync, backup.
              RtScheduler/EEVDFScheduler here are thin adapters: they convert
              Task <-> SchedTask and delegate the actual policy math to
              :core's RtPolicy/EevdfScheduler.
:platform     Android adapters for :core's ports — SystemClock, RR-store,
              AlarmPort, plus device-level SoundManager/VibrationManager/
              NotificationHelper.
:shared       Genuinely ownerless generic utilities (FeatureFlag,
              CrashIsolation). Kept small deliberately — most things that
              looked shared turned out to have exactly one owner.
:testing      JVM fakes + :core's own unit tests — provably testable without
              an emulator.
:feature      The one unbounded growth surface. One Gradle module, physically
              co-located per subfeature (task/alarm/autoswitch/backup/
              settings/stats/sync, plus ui/ and shared/ buckets every feature
              can import) rather than scattered into type-based folders.
              Per-feature compile isolation (task can't import settings) is
              enforced by scripts/check_architecture.sh, not the compiler yet.
build-logic/  Gradle included build. One convention plugin
              (com.eevdf.android-library-convention) is the single source of
              truth for compileSdk/jvmTarget across :contract/:data/:feature/
              :platform — each module still declares its own namespace and
              minSdk locally, since those genuinely differ.
```

## Current directory layout

```
eevdf-scheduler/
│
├── app/                          composition root
│   └── src/main/kotlin/com/eevdf/app/
│       ├── SchedulerApplication.kt
│       ├── di/                   Hilt modules
│       └── core/                 remaining genuinely-shared app.core.* (media/notification/prefs/signals migration in progress)
│
├── contract/
│   └── src/main/kotlin/com/eevdf/contract/
│       ├── nav/                  AppRoutes
│       └── control/              AlarmController, OverlayController, AlarmActions
│
├── core/
│   └── src/main/kotlin/com/eevdf/core/
│       ├── platform/              ports for :platform to implement
│       ├── time/                  WallClock
│       └── scheduler/
│           ├── eevdf/             EevdfScheduler, CpuShares
│           ├── model/             SchedTask, RtConfig, DlBudget, QuotaBudget
│           ├── ports/              SchedulerPorts (RrStatePort, etc.)
│           └── rt/                RtPolicy
│
├── data/
│   └── src/main/kotlin/com/eevdf/data/
│       ├── task/                  Task entity, TaskRepository, TaskDao
│       │   └── timer/             TaskTimerState
│       ├── runlog/                 RunSession, RunLogRepository
│       ├── scheduler/             RtScheduler, EEVDFScheduler, SchedulerFacade (adapters over :core)
│       ├── sync/                  MultiUserSyncManager
│       └── backup/                BackupManager
│
├── platform/
│   └── src/main/kotlin/com/eevdf/platform/
│       ├── alarm/                 AndroidAlarmPort
│       ├── media/                 SoundManager, VibrationManager
│       ├── notification/          NotificationHelper
│       └── scheduler/             SystemClockAndRrStore, SharedPrefsRrStateStore
│
├── shared/
│   └── src/main/kotlin/com/eevdf/shared/
│       ├── FeatureFlag.kt / FeatureFlags
│       ├── SafeRun.kt             CrashIsolation (public); safeFeature (internal, unused)
│       └── DurationFormat.kt      (internal, unused)
│
├── testing/                       fakes/fixtures + :core's own unit tests
│
├── build-logic/
│   └── convention/                com.eevdf.android-library-convention
│
└── feature/                       one module, physically co-located per subfeature
    └── src/main/
        ├── task/       — the primary screen
        │   ├── list/               MainActivity, TaskViewModel + 7 delegates
        │   ├── addtask/            AddTaskActivity + 11 section files
        │   ├── group/              PickerDialog, RecentGroupPrefs, GroupTaskPrefs
        │   ├── adapter/            TaskAdapter + 5 helper files
        │   ├── notice/             NoticeStateMachine, NoticePhase
        │   └── timer/              TimerEngine (live), InterruptDelegate, TimerCardAction
        ├── alarm/
        ├── autoswitch/
        ├── backup/
        ├── settings/
        ├── stats/
        ├── sync/
        ├── shared/                 cross-feature prefs/signals — feature/ui's sibling bucket
        └── ui/                     design system (card views + colors/dimens/themes)
```

## Dependency rule

`:core` has no Android plugin, so purity is a compile error, not a convention:

```
:app ──▶ :contract, :feature, :core, :data, :platform, :shared
:feature ──▶ :contract, :core, :data, :platform, :shared
:data, :platform ──▶ :core
:shared ◀── (anyone)
```

## Why RtScheduler/EEVDFScheduler are facades, not the real logic

The UI calls `EEVDFScheduler.recalculate(...)`, `.computeShares(...)`,
`RtScheduler.isRtWindowActive(...)`, etc. on the rich `Task` entity. Rather
than touch every UI call site, `:data` keeps those exact method names on
objects named `EEVDFScheduler`/`RtScheduler` — UI code never changed. Each
facade method converts `Task` → the pure `SchedTask`/`RtConfig`, runs the
actual policy math in `:core`, and writes results back onto the entity. The
pure core never mutates; the adapter bridges to the mutate-in-place callers.

This paid off directly: `RtScheduler`'s window-activation math used to be an
independent, duplicate reimplementation of logic that already existed,
tested, in `:core` — nobody had connected the two. Once found, fixing it was
a one-file change (`RtScheduler`'s internals only), because every caller
already went through this facade. See `ARCHITECTURE.md` Phase 8 for the full
story, including a real bug the mismatch uncovered.

## Build

```bash
./gradlew verifyAll              # architecture guard + all unit tests + detekt
./gradlew :testing:test          # pure core unit tests, no emulator
./gradlew :app:assembleDebug
```

**One-time setup after your first successful build:**

```bash
./gradlew :data:assembleDebug     # Room writes data/schemas/*.json
git add data/schemas              # commit them — migration tests need them
```

## Guard rails

Before adding a feature, read `docs/ADDING_A_FEATURE.md`. The build fails if:

- a feature imports another feature (`feature/<name>/` isolation, checked by
  `scripts/check_architecture.sh`; `feature/ui/` and `feature/shared/` are
  exempt — every feature is expected to import those)
- `:core` gains an Android import
- a `Task` field is missing from backup or unclassified for sync
- the DB version, migrations and exported schemas disagree
- `MainActivity.kt`/`TaskViewModel.kt` gain a **function** they didn't have
  before (a ratchet, not a line-count limit — see `ARCHITECTURE.md` guard
  rail 5 for why line count was tried first and rejected)

## Status

Every phase of the ownership-boundary refactor (Phases 3–10) is done and
verified, or closed with a documented reason — see `ARCHITECTURE.md`'s
"Status: paused" section for exactly what's finished, what's deliberately
deferred, and what confirmed-dead code is still sitting in the tree pending a
future cleanup pass. This file describes the *current* structure; that one
describes *how it got here and why*.
