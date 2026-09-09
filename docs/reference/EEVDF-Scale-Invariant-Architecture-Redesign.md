# EEVDF Scheduler — Scale-Invariant Recursive Architecture Redesign

**Status:** design proposal only. No source files have been moved or edited —
per instructions, this document is the entire deliverable for this pass. It is
meant to be read next to the existing `README.md`, `ARCHITECTURE.md`, and
`docs/reference/*`, not instead of them.

---

## 0. What this document is doing

`architecture.md` proposes one blueprint — IDENTITY, INPUT, STATE, LOGIC,
OUTPUT, COMMUNICATION, TIME, RESOURCES, SECURITY, RESILIENCE, RUNTIME — that
should be recognizable at every scale of a system, recursively: the whole
product is a SYSTEM, each major part of it is a SYSTEM, each part of *that*
is a SYSTEM, down to the smallest unit worth naming.

The EEVDF scheduler project already has a scaling philosophy
(`docs/reference/Linux Subsystem & Ownership Philosophy.md`): divide by
*ownership*, let complexity grow inside an owner's boundary. That philosophy
and the organ blueprint are not in conflict — ownership answers **who owns
this**, the organ blueprint answers **what shape does an owner's interior
have**. This redesign keeps every existing ownership boundary (the 8 Gradle
roots, the per-subfeature isolation inside `:feature`) and applies the organ
blueprint *inside* each one, recursively, exactly as `architecture.md`
describes for embedded device → app → server → cluster → planet.

Concretely, the recursion levels chosen for this codebase are:

| Recursion depth | What plays the role of "SYSTEM" |
|---|---|
| 0 | the whole app (`eevdf-scheduler/`) |
| 1 | each of the 8 Gradle roots (`:app :contract :core :data :platform :shared :testing :feature`) |
| 2 | each subfeature inside `:feature` (`task`, `alarm`, `autoswitch`, `backup`, `settings`, `stats`, `sync`, `links`, `ui`, `shared`), and each major package inside the infra roots (`core/scheduler`, `data/task`, `data/sync`, `platform/scheduler`, …) |
| 3 | the components inside a subfeature that are complex enough to deserve their own organ set (`task/list`, `task/timer`, `task/addtask`, `task/notice`; `core/scheduler/eevdf`, `core/scheduler/rt`) |

A node only gets the full 11-organ treatment if it's complex enough to need
it — `architecture.md`'s tiny-embedded-device example only manifests
INPUT/STATE/LOGIC/OUTPUT/COMMUNICATION/TIME/RUNTIME, and several leaves in
this codebase are exactly that small. Forcing all 11 folders onto a
3-file package would be cargo-cult, not architecture.

---

## 1. The organs, translated into this domain

| Organ | General meaning | What it means for an EEVDF-scheduler subsystem |
|---|---|---|
| **IDENTITY** | Who am I? | Module namespace, entity primary keys, route name, version markers |
| **INPUT** | What enters? | UI gestures, `BroadcastReceiver`s, sensor/call state, incoming sync payloads |
| **STATE** | What do I know? | `Task`, `SchedTask`, `AlarmState`, `TaskTimerState`, `NoticePhase`, ViewModel state |
| **LOGIC** | How do I transform state? | `EevdfScheduler`, `RtPolicy`, `NoticeStateMachine`, delegates, `SaveHandler` |
| **OUTPUT** | What do I produce? | Rendered list rows, notifications, sound/vibration, exported backup file, DB writes |
| **COMMUNICATION** | How do I talk to other systems? | `:contract` (`AppRoutes`, `AlarmController`, `OverlayController`), event buses |
| **TIME** | When do things happen? | `Clock`/`WallClock`, `RtConfig` windows, `TimerEngine`, `AlarmScheduler`, `RunLog` |
| **RESOURCES** | What do I consume? | CPU shares, `DlBudget`/`QuotaBudget`, DB storage, battery/foreground constraints |
| **SECURITY** | What am I allowed to access? | `SchedulerPorts`/`PlatformPorts`, `TaskFieldClassification`, feature import allowlist |
| **RESILIENCE** | What happens on failure? | `SafeRun`/`CrashIsolation`, doze-immune alarm, migration tests, `StartupRecoveryDelegate` |
| **RUNTIME** | Where/how do I execute? | Hilt DI (`app/di`), Activity/Service lifecycle, `build-logic` convention plugin |

Two things are worth naming up front because they make this particular
project an unusually good fit for the blueprint, not a forced exercise:

1. **TIME and RESOURCES are not metaphors here — they are the literal subject
   matter.** An EEVDF/RT/DL scheduler *is* a TIME-and-RESOURCES organ made
   into a whole app. That's a rare case where the blueprint's abstract organ
   names and the domain's real vocabulary are the same words.
2. **SECURITY and RESILIENCE already exist in the codebase, just unnamed.**
   `SchedulerPorts`, `TaskFieldClassification`, and the doze-immune-alarm
   pattern are exactly the SECURITY/RESILIENCE organs — they're just scattered
   under `ports/`, `sync/`, and a standalone doc today, with no shared
   vocabulary tying them together. Naming them consistently is most of the
   value of this redesign.

---

## 2. Recursion level 1 — the eight roots as SYSTEM nodes

| Root | Dominant organs today | Organs that are thin or implicit |
|---|---|---|
| `:contract` | COMMUNICATION (this is its *entire* purpose) | IDENTITY (no version/compat marker on the promise itself) |
| `:core` | STATE, LOGIC, TIME, RESOURCES, SECURITY (via `ports/`) | RESILIENCE (pure functions rarely fail, but budget edge-cases deserve a home) |
| `:data` | STATE, LOGIC (facades), OUTPUT (backup/export), SECURITY (`sync/`) | RESILIENCE (migration tests exist but aren't named as a resilience organ) |
| `:platform` | INPUT (receivers), OUTPUT (media/notification), TIME (`SystemClockAndRrStore`) | RESILIENCE (doze immunity, alarm reliability — currently under `notification/`) |
| `:shared` | none dominant by design ("most things that looked shared turned out to have exactly one owner") | RESILIENCE (`SafeRun`/`CrashIsolation` is the one thing genuinely ownerless) |
| `:testing` | none — it's a harness, not a SYSTEM | — (correctly organ-less; a test harness plays no organ role of its own) |
| `:app` | RUNTIME (DI composition root), IDENTITY (manifest/app class) | — |
| `:feature` | every organ, once per subfeature (see §3) | — |

### 2.1 `:contract` — proposed interior

```
contract/src/main/kotlin/com/eevdf/contract/
├── identity/
│   └── ContractVersion.kt          [new] — the one thing a dependency-free
│                                     promise still needs: a marker of which
│                                     shape of the promise a caller compiled
│                                     against. Optional, low priority.
└── communication/
    ├── nav/
    │   └── AppRoutes.kt            (was contract/nav/AppRoutes.kt)
    └── control/
        ├── AlarmActions.kt        (was contract/control/AlarmActions.kt)
        ├── AlarmController.kt    (was contract/control/AlarmController.kt)
        └── OverlayController.kt  (was contract/control/OverlayController.kt)
```
Only a folder-nesting change (`nav/`, `control/` become children of a single
`communication/` folder), because `:contract`'s entire reason to exist *is*
the COMMUNICATION organ — there is nothing else in this root to separate it
from.

### 2.2 `:core` — proposed interior

`:core` is already the closest thing in the repo to organ-shaped (`time/`,
`scheduler/model/`, `scheduler/ports/` map almost directly). The redesign is
mostly a rename + one new grouping level:

```
core/src/main/kotlin/com/eevdf/core/
├── time/
│   ├── Clock.kt                    (unchanged)
│   └── WallClock.kt                (unchanged)
├── state/
│   ├── SchedTask.kt                (was scheduler/model/SchedTask.kt)
│   └── SchedConfig.kt              (was scheduler/model/SchedConfig.kt —
│                                     see note below: this file's *types*
│                                     span STATE and RESOURCES)
├── logic/
│   ├── SchedulerService.kt         (was scheduler/SchedulerService.kt)
│   ├── eevdf/                      — nested level-3 SYSTEM, see §4
│   │   ├── EevdfScheduler.kt
│   │   └── CpuShares.kt
│   └── rt/                         — nested level-3 SYSTEM, see §4
│       └── RtPolicy.kt
└── security/
    ├── SchedulerPorts.kt           (was scheduler/ports/SchedulerPorts.kt)
    └── PlatformPorts.kt            (was platform/PlatformPorts.kt)
```

Note on `SchedConfig.kt`: it currently holds `RtConfig` (a TIME-window
concept — activation days/hours, midnight-crossing logic) *and* `DlBudget`/
`QuotaBudget` (RESOURCES concepts — deadline and quota budgets) in one file.
The organ lens says these are two organs sharing a file. That's fine to leave
as-is for now (small, cohesive, well-tested); if the file grows again, the
natural split is `state/time/RtConfig.kt` vs. `state/resources/DlBudget.kt` +
`QuotaBudget.kt`, not a state/model split.

Reclassifying `SchedulerPorts`/`PlatformPorts` as `security/` rather than a
generic `ports/` is the single highest-value rename in this whole document:
a port *is* a capability boundary — "what is `:core` allowed to reach
outside itself" is a direct restatement of the SECURITY organ's definition
("what is the system allowed to access"), and today that fact is implicit in
the word "port" rather than stated.

### 2.3 `:data` — proposed interior

```
data/src/main/kotlin/com/eevdf/data/
├── identity/
│   └── Task.kt                     (was task/Task.kt — the entity IS the
│                                     identity of a persisted task; its
│                                     primary key and structural fields are
│                                     literally what TaskFieldClassification
│                                     calls SyncFieldKind.IDENTITY)
├── state/
│   ├── task/
│   │   ├── TaskDao.kt
│   │   ├── TaskDatabase.kt
│   │   ├── TaskDisplayItem.kt
│   │   ├── TaskLink.kt
│   │   ├── TaskLoadFactor.kt
│   │   ├── TaskMembership.kt
│   │   └── timer/
│   │       ├── TaskTimerState.kt
│   │       └── TaskTimerExt.kt
│   └── runlog/
│       ├── RunSession.kt
│       ├── RunLogEntry.kt
│       ├── RunDailySummary.kt
│       └── RunMonthlySummary.kt
├── logic/
│   ├── task/
│   │   ├── TaskRepository.kt
│   │   └── InterruptReturn.kt
│   ├── runlog/
│   │   ├── RunLogDao.kt
│   │   └── RunLogRepository.kt
│   └── scheduler/                  — the facade layer, see README §"Why
│       │                             RtScheduler/EEVDFScheduler are facades"
│       ├── SchedulerFacade.kt
│       ├── RtScheduler.kt
│       ├── RtSchedulerService.kt
│       ├── EevdfSchedulerService.kt
│       ├── LoadAverage.kt
│       └── LoadEwmaReconstructor.kt
├── output/
│   └── backup/
│       └── BackupManager.kt        (was backup/BackupManager.kt)
├── communication/
│   └── sync/
│       ├── MultiUserSyncManager.kt
│       ├── SyncConflict.kt
│       ├── SyncState.kt
│       └── SyncWriteStats.kt
├── security/
│   └── sync/
│       └── TaskFieldClassification.kt   (was sync/TaskFieldClassification.kt
│                                          — this IS the sync boundary's
│                                          access-control policy: which
│                                          fields a remote peer may overwrite)
│       └── SyncFieldGuard.kt            (co-located with the classification
│                                          it enforces)
└── resilience/
    └── task/                       — migration + regression tests already
                                       live in androidTest/, this groups the
                                       *concept*, not necessarily the files:
                                       TaskDatabaseMigrationTest,
                                       VruntimeStalenessRegressionTest,
                                       TaskSchemaFreezeTest,
                                       BackupRoundTripCoverageTest
```

Sync deserves its own callout: `MultiUserSyncManager` (COMMUNICATION — it
talks to another peer) and `TaskFieldClassification`/`SyncFieldGuard`
(SECURITY — it decides what that peer is allowed to change) are two
different organs that currently sit in one `sync/` folder. Splitting them
by organ makes it obvious, just from the folder a file lives in, whether a
change affects *what gets sent* or *what's allowed to land*.

### 2.4 `:platform` — proposed interior

```
platform/src/main/kotlin/com/eevdf/platform/
├── input/
│   └── alarm/
│       └── AndroidAlarmPort.kt      (receives the alarm-fired signal)
├── output/
│   └── media/
│       ├── SoundManager.kt
│       └── VibrationManager.kt
├── time/
│   └── scheduler/
│       └── SystemClockAndRrStore.kt
├── communication/
│   └── notification/
│       └── NotificationHelper.kt    (this is how the platform layer talks
│                                      to the user via the OS's own channel)
└── resilience/
    └── notification/
        ├── AlarmReliabilityChecker.kt
        ├── AppForegroundTracker.kt
        └── ForegroundAppDetector.kt
```
`AlarmNotificationPolicy.kt` stays adjacent to whichever of `output/` or
`resilience/` its actual content leans toward — it wasn't opened in this
pass; classify by whether it decides *what a notification says* (OUTPUT) or
*whether/when a notification is guaranteed to fire despite Doze* (RESILIENCE).

### 2.5 `:shared` and `:testing`

`:shared` keeps its existing one-idea-per-file shape, just labeled:
```
shared/src/main/kotlin/com/eevdf/shared/
├── resilience/
│   └── SafeRun.kt                   (CrashIsolation — this is the one
│                                      shared thing that's genuinely a
│                                      RESILIENCE organ with no single owner)
└── FeatureFlag.kt, DurationFormat.kt  — leave ungrouped; neither is an organ,
                                          both are small cross-cutting utils
                                          the README already flags as
                                          "kept small deliberately"
```
`:testing` is intentionally left out of the organ tree — a fakes/harness
module plays no SYSTEM role of its own; it exists to let other SYSTEMs be
tested in isolation. Forcing it into an organ shape would be exactly the
cargo-cult mistake `architecture.md` warns against implicitly (not every
tiny thing needs all 11 organs — some things need none).

### 2.6 `:app`

```
app/src/main/kotlin/com/eevdf/app/
├── identity/
│   └── SchedulerApplication.kt
├── runtime/
│   └── di/
│       ├── AppCoreModule.kt
│       ├── DatabaseModule.kt
│       ├── PlatformModule.kt
│       ├── RepositoryModule.kt
│       └── SchedulerModule.kt
└── security/
    └── core/
        └── SharedPrefsFeatureFlags.kt   (feature flags gate what runtime
                                            capability is switched on — a
                                            SECURITY-organ concern, not a
                                            generic "core" bucket)
```

---

## 3. Recursion level 2 — `:feature`'s subfeatures as SYSTEM nodes

`:feature` is the one root the README calls "the one unbounded growth
surface." That is exactly where the recursive property in `architecture.md`
§3 matters most: every subfeature should be a small SYSTEM with the same
organ shape, so a new contributor recognizes `settings/` the same way they
recognize `alarm/`, regardless of which one they saw first.

### 3.1 `task/` (the primary screen — the biggest subfeature, so it needs
the most organs and is treated at recursion level 3 in §4)

### 3.2 `alarm/`

| Current file | Organ |
|---|---|
| `AlarmActivity.kt` | OUTPUT (renders the ringing screen) |
| `AlarmForegroundService.kt` | RUNTIME (keeps the process alive while ringing) |
| `AlarmScheduler.kt` | TIME (schedules the fire time) |
| `AlarmState.kt` | STATE |
| `TimerAlarmReceiver.kt` | INPUT (the OS delivering the fired-alarm broadcast) |
| `AlarmStopReceiver.kt` | INPUT (the user's stop action delivered as a broadcast) |
| `AlarmControlModule.kt` | COMMUNICATION (implements the `:contract` `AlarmController`) |

Proposed: `alarm/{input,state,logic(implicit—thin),output,time,communication,runtime}/`.
No file currently represents SECURITY or RESILIENCE for this subfeature —
that is exactly the gap the doze-immune-alarm pattern in
`docs/module/doze-immune-alarm/` is meant to fill; see §5.

### 3.3 `autoswitch/`

| Current file | Organ |
|---|---|
| `CallStateReceiver.kt` | INPUT |
| `CallSwitchService.kt` | LOGIC + RUNTIME |
| `BubbleOverlayService.kt` | OUTPUT + RUNTIME |
| `OverlayControlModule.kt` | COMMUNICATION (implements `:contract` `OverlayController`) |
| `AutoSwitchActivity.kt` | OUTPUT (settings UI for this subfeature) |

### 3.4 `backup/`

| Current file | Organ |
|---|---|
| `DataBackupActivity.kt` | OUTPUT (UI) + COMMUNICATION (the one documented cross-feature edge, `backup -> task`, see `scripts/feature_import_allowlist.txt`) |
| `SettingsBackup.kt` | OUTPUT (export) + STATE (what "all settings" means) |

This is the one subfeature where the existing allowlist already documents a
COMMUNICATION-organ debt in plain language ("Deliberately left alone...
needs a `TimerSessionController`"). The organ lens doesn't change that
decision — it just gives the debt a name: `backup/`'s COMMUNICATION organ
currently reaches directly into `task/`'s STATE organ instead of through a
contract.

### 3.5 `settings/`, `stats/`, `sync/`, `links/`

These four are almost entirely OUTPUT (each is a screen or a small set of
screens) plus STATE (each screen's own prefs/view state) with thin or absent
LOGIC, TIME, RESOURCES, SECURITY, RESILIENCE organs — which is correct, not
a gap: a settings screen genuinely doesn't need its own resilience story
distinct from the platform's. `SettingsChangeLogger.kt` is this
subfeature's one RESILIENCE-adjacent file (an audit trail for diagnosing bad
states after the fact).

### 3.6 `ui/` and `shared/` — the two buckets every feature imports

`ui/` (`DesignTokens`, `CardDensity`, `*CardView`, `ModelDiagramView`) is
almost pure OUTPUT — it is the organ that renders things, shared across every
other subfeature. `shared/` (`AppPreferences`, `prefs/*`, `signals/*`) is
mostly STATE (`prefs/`) and COMMUNICATION (`signals/BubbleEventBus`,
`CallEvents` — literally event buses). Both are correctly *not* modeled as
full SYSTEMs: `architecture.md` §1's blueprint is for systems, and `ui`/
`shared` are single-organ utility buckets every subfeature-SYSTEM imports,
the same way a Kotlin file imports a class rather than being one.

---

## 4. Recursion level 3 — the deepest nested SYSTEMs

Two places in the codebase are complex enough that a single organ
("LOGIC", "OUTPUT") isn't a fine enough grain — they are themselves small
SYSTEMs nested inside a subfeature-SYSTEM, exactly as `architecture.md` §3's
`authentication/messaging/payments` example nests inside `app/`.

### 4.1 `core/scheduler/eevdf/` and `core/scheduler/rt/`

These are sibling SYSTEMs inside `:core`'s `logic/` organ, each with its own
inner STATE/LOGIC/TIME/RESOURCES split:

```
core/logic/eevdf/                    [SYSTEM: EEVDF fairness policy]
├── state/    — virtual runtime, weight/share bookkeeping (inside CpuShares)
├── logic/    — EevdfScheduler.kt: the actual fairness comparison/selection
└── resources/ — CpuShares.kt: literally "what fraction of CPU has each task
                  consumed" — the RESOURCES organ, named as such

core/logic/rt/                       [SYSTEM: real-time window policy]
├── state/    — RtConfig (already proposed to live in core/state/, referenced
├── logic/    — RtPolicy.kt: FIFO/RR decision + midnight-crossing math
└── time/     — the activation-window arithmetic is itself a TIME organ
```

### 4.2 `feature/task/list/`, `feature/task/timer/`, `feature/task/addtask/`,
`feature/task/notice/`

`task/` is where the recursive property is most visible today — it already
*is* structured as four nested SYSTEMs, just without organ labels:

```
feature/task/                        [SYSTEM: the primary screen]
├── list/                            [SYSTEM: the task list itself]
│   ├── state/    — TaskViewModel.kt (the aggregate state holder)
│   ├── logic/    — the 13 *Delegate.kt files: TaskCrudDelegate,
│   │                SchedulerDelegate, ListBuilderDelegate, SortHelper,
│   │                MenuSyncDelegate, ListTogglesDelegate,
│   │                GroupExpandDelegate, DisplayScaleDelegate,
│   │                CallSwitchDelegate, BubbleTapDelegate,
│   │                TimerCardDelegate, TimerLifecycleDelegate,
│   │                AlarmOverrunDelegate, QueueLastRunDelegate
│   ├── input/    — ObserverDelegate.kt (observes upstream state changes)
│   ├── resilience/ — StartupRecoveryDelegate.kt (exactly a RESILIENCE
│   │                  organ: "what happens when something fails" on cold
│   │                  start — currently the only delegate named for what
│   │                  it recovers from rather than what it manages)
│   ├── output/   — MainActivity.kt, TaskListStyle.kt
│   └── runtime/  — MainActivity's lifecycle wiring
│
├── timer/                           [SYSTEM: the running-timer engine]
│   ├── logic/    — TimerEngine.kt, InterruptDelegate.kt
│   ├── state/    — TimerStartEvent.kt
│   └── output/   — TimerCardAction.kt
│
├── notice/                          [SYSTEM: the notice/reminder state machine]
│   ├── state/    — NoticePhase.kt
│   └── logic/    — NoticeStateMachine.kt
│
├── addtask/                         [SYSTEM: the task-creation form]
│   ├── input/    — the 10 *Section.kt files each own one form section:
│   │                TypeSection, ConfigSection, SchedulerSection,
│   │                QuotaSection, TimeSliceSection, InterruptSection,
│   │                LoadFactorSection, PinnedShareSection,
│   │                CategoryPrioritySection, GroupSection
│   ├── logic/    — SaveHandler.kt
│   └── output/   — AddTaskActivity.kt
│
├── group/                           [thin SYSTEM: grouping picker]
│   ├── output/   — PickerDialog.kt
│   └── state/    — RecentGroupPrefs.kt, GroupTaskPrefs.kt
│
└── adapter/                         [thin SYSTEM: list-row rendering]
    ├── output/   — TaskAdapter.kt, TaskViewHolder.kt, BindHelpers.kt,
    │                NoticeSegments.kt, CardScale.kt
    └── logic/    — Formatters.kt, UnitFormat.kt, TaskDiffCallback.kt
```

The single clearest win in this section: renaming `StartupRecoveryDelegate`'s
folder to `resilience/` rather than leaving it flat among 13 siblings in
`list/` makes its purpose legible from the path alone, before opening the
file — which is the entire point of the organ blueprint.

---

## 5. Where organs are genuinely missing, not just unlabeled

The mapping above mostly *renames* things that already exist. A few organs
are thin or absent and are worth calling out as real gaps, not naming
exercises:

1. **`alarm/` has no SECURITY or RESILIENCE folder**, even though
   `docs/module/doze-immune-alarm/` describes exactly the RESILIENCE story
   this subfeature needs (surviving Doze mode) and it currently lives as a
   standalone doc rather than inside the subfeature it documents.
   Recommendation: fold `DozeImmuneTimer.kt`'s pattern into
   `feature/alarm/resilience/` once code changes are in scope.
2. **`:contract` has no IDENTITY organ.** A dependency-free promise between
   features has no marker of its own shape/version, so a breaking change to
   `AppRoutes` or `AlarmController` is only caught by every caller failing to
   compile, not by an explicit compatibility check.
3. **RESOURCES is invisible outside `:core`.** CPU shares/budgets are
   modeled richly in `core/logic/eevdf/` and `core/state/` (`DlBudget`,
   `QuotaBudget`), but nothing in `:data` or `:feature` names *storage* or
   *battery* as a RESOURCES concern the same way — `TaskLoadFactor.kt` is the
   closest existing candidate and would be the natural resident of a future
   `data/resources/` folder.
4. **RESILIENCE is scattered across three different vocabularies**:
   `SafeRun`/`CrashIsolation` in `:shared`, `AlarmReliabilityChecker` in
   `:platform`, and the migration/regression tests in `:data`'s `androidTest`.
   Nothing currently signals that these three are the same organ. Grouping
   them under a consistently-named `resilience/` folder per root (as done in
   §2) is the cheapest fix available without touching logic.

---

## 6. How this reconciles with the existing guard rails

The project already enforces several rules mechanically
(`scripts/check_architecture.sh`, `TaskFieldClassificationTest`,
schema/migration checks, the `MainActivity`/`TaskViewModel` function
ratchet). Each maps onto an organ boundary the redesign makes explicit,
rather than requiring a new one:

| Existing guard rail | Organ boundary it's actually enforcing |
|---|---|
| Feature import isolation (`task` can't import `settings`) | SECURITY — no subfeature-SYSTEM may reach into another's STATE/LOGIC directly |
| `:core` may not gain an Android import | RUNTIME — `:core`'s execution environment is deliberately narrower than every other root's |
| Every `Task` field classified for sync | SECURITY (§2.3) — what a remote peer may write |
| Every `Task` field present in backup | RESILIENCE — nothing is silently unrecoverable after a restore |
| DB version/migration/schema agreement | RESILIENCE — upgrades must not corrupt STATE |
| `MainActivity`/`TaskViewModel` function ratchet | a proxy for "don't let one SYSTEM node quietly absorb another organ's responsibility" — the same instinct the recursive blueprint is trying to enforce structurally instead of by line-count |

None of these need to change. The redesign gives them a shared name so a new
guard rail (say, a future `RESOURCES` budget check) has an obvious home to
join, instead of inventing a sixth ad hoc vocabulary.

---

## 7. Suggested migration plan (documentation and structure, no logic changes)

This is a sequencing suggestion for *when* code changes are eventually
authorized — nothing here should be executed yet.

1. **Phase A — naming only.** Rename `ports/` → `security/` in `:core` and
   `:platform` (a folder rename, zero behavior change). This is the
   highest-value, lowest-risk step and unlocks the vocabulary for everything
   else.
2. **Phase B — regroup within roots that are already organ-shaped.**
   `:core` and `:contract` need folder regrouping only (§2.1, §2.2) — no file
   contents change, imports update mechanically.
3. **Phase C — split mixed-organ folders.** `:data/sync/` splits into
   `communication/sync/` and `security/sync/` (§2.3); `:platform/notification/`
   splits into `communication/` and `resilience/` (§2.4).
4. **Phase D — subfeature-by-subfeature relabeling inside `:feature`.**
   Lowest urgency, highest volume: rename `list/`'s 13 delegate files' owning
   folder structure per §4.2, one subfeature at a time, verified by the
   existing `check_architecture.sh` guard plus the function-ratchet guard
   (neither should need to change, since neither cares about folder names).
5. **Phase E — fill the named gaps.** Add `feature/alarm/resilience/`
   sourced from `docs/module/doze-immune-alarm/`, and a `ContractVersion.kt`
   identity marker in `:contract`, once §5's gaps are prioritized against
   other roadmap work.

Each phase is independently mergeable and none require touching
`EevdfScheduler`, `RtPolicy`, or any tested algorithm — the redesign is a
map of where things live, not a change to what they compute.

---

## 8. Template for every future subfeature

To keep `:feature` recognizable as it grows (the README calls it "the one
unbounded growth surface"), a new subfeature should start from this skeleton
and only add the organs it actually needs — most subfeatures in this
codebase never need all 11:

```
feature/<name>/
├── identity/        (only if the subfeature has its own persisted ID scheme)
├── input/           (receivers, gestures, incoming events)
├── state/           (view state, prefs, entities specific to this subfeature)
├── logic/           (delegates, state machines, handlers)
├── output/          (Activities/Fragments/Views, notifications, exports)
├── communication/   (contract implementations, event bus usage)
├── time/            (only if this subfeature schedules or times something)
├── resources/       (only if this subfeature has its own budget/quota concept)
├── security/        (only if this subfeature has its own access boundary)
├── resilience/      (only if this subfeature has a documented failure mode)
└── runtime/         (only if lifecycle/DI wiring is non-trivial)
```

`settings/`, `stats/`, `links/` today would honestly only populate
`state/`, `logic/`, and `output/` — and that's the correct, minimal instance
of the blueprint for a screen that just displays and edits preferences,
exactly as `architecture.md` §4's tiny embedded device only manifests seven
of the eleven organs.
