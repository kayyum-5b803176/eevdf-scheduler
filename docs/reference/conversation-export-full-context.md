# Conversation Export — EEVDF Scheduler Microkernel Redesign (Full Context)

This file is a complete record of a design conversation that evolved an
existing Android app's architecture from module-based ownership all the way
to a microkernel + pluggable-capabilities + event-bus design. Read this
whole file before doing any work — later sections supersede earlier ones,
but the reasoning in earlier sections explains *why* the final design looks
the way it does.

**Companion file:** `microkernel-redesign-prompt.md` is the action-oriented
implementation spec (rules, target tree, migration phases). This file is
the narrative record of how we got there and why each decision was made.

---

## 1. Starting point

### 1.1 The trigger document (`architecture.md`)

The user supplied a document describing a **"Scale-Invariant Recursive
System Architecture"**: the idea that one blueprint should look the same
whether the system is a tiny embedded device, an Android app, a server, a
cluster, or a planetary-scale simulation. The blueprint is 11 "organs":

```
IDENTITY, INPUT, STATE, LOGIC, OUTPUT, COMMUNICATION,
TIME, RESOURCES, SECURITY, RESILIENCE, RUNTIME
```

The doc's core claims:
- Every SYSTEM should be describable by these organs.
- The property is **recursive**: a SYSTEM can contain SYSTEMs, each with
  the same organ shape, all the way down.
- Scale should change *implementation*, not *architecture* — a 32KB
  embedded device and a planetary simulation should still be recognizable
  as the same blueprint, just with different-sized organs (some organs may
  be absent entirely at small scale, e.g. a tiny device has no SECURITY
  organ).
- Biological analogy: nervous system = communication, brain = logic/
  decision, circulatory system = data transport, immune system = security +
  fault detection, metabolism = resource management.

### 1.2 The real project

The user uploaded `eevdf-scheduler-5.0.0.zip` — a real, mature, multi-module
Android/Kotlin app: a task scheduler modeled on Linux's CPU scheduling
classes (EEVDF fairness, SCHED_FIFO/RR real-time windows, SCHED_DEADLINE
budgets). Its existing architecture (documented in the project's own
`README.md` and `docs/reference/Linux Subsystem & Ownership Philosophy.md`)
is **ownership-based**: 8 Gradle root modules —

```
:app        composition root, DI (Hilt), manifest
:contract   dependency-free cross-feature contracts (AppRoutes,
            AlarmController, OverlayController, AlarmActions)
:core       pure Kotlin/JVM, no Android imports allowed (compile-enforced)
            — EevdfScheduler, RtPolicy, CpuShares, SchedTask/SchedConfig,
            SchedulerPorts (ports = capability boundary)
:data       Room entities/DAOs/repositories — Task, RunLog, sync, backup;
            RtScheduler/EEVDFScheduler here are thin facades that convert
            Task <-> SchedTask and delegate real math to :core
:platform   Android adapters — SystemClock, alarm, sound/vibration,
            notifications
:shared     genuinely-ownerless utilities (FeatureFlag, SafeRun/
            CrashIsolation)
:testing    JVM fakes + :core's own unit tests
:feature    the one unbounded growth surface — physically co-located
            subfeatures: task, alarm, autoswitch, backup, settings, stats,
            sync, links, plus shared ui/ and shared/ buckets every feature
            imports
```

Key existing guard rails found in the project:
- `scripts/check_architecture.sh` blocks any feature from importing
  another feature, except an explicit allowlist
  (`scripts/feature_import_allowlist.txt`) — which currently has exactly
  **one** intentional debt edge: `backup -> task` (DataBackupActivity
  calling TaskViewModel's export/import prep methods directly), documented
  as deliberately deferred.
- `TaskFieldClassification.kt` / `SyncFieldGuard.kt` — every `Task` field
  must be classified as `IDENTITY`, `CONTENT`, or `OPERATIONAL` for
  multi-user sync; a test fails the build if any field is unclassified.
- `BackupManager.kt` — every `Task` field must round-trip through backup;
  covered by `BackupRoundTripCoverageTest`.
- DB migration/schema-freeze tests (`TaskDatabaseMigrationTest`,
  `TaskSchemaFreezeTest`, `VruntimeStalenessRegressionTest`).
- `docs/module/doze-immune-alarm/` — a standalone doc describing a
  resilience pattern (using `AlarmManager.setAlarmClock()` to survive
  Android Doze mode) that isn't yet folded into the alarm feature's own
  folder.
- `MainActivity`/`TaskViewModel` are guarded by a "function ratchet" — the
  build fails if either gains a **function** they didn't have before (not a
  line-count limit).

Full file inventory gathered during this conversation (representative, not
exhaustive — see Phase -1 audit requirement in the implementation prompt
for the real complete list):
- `:contract` — 4 files (`nav/AppRoutes.kt`, `control/AlarmActions.kt`,
  `AlarmController.kt`, `OverlayController.kt`)
- `:core` — `time/{Clock,WallClock}.kt`, `scheduler/model/{SchedTask,
  SchedConfig}.kt` (SchedConfig holds `RtConfig`, `DlBudget`, `QuotaBudget`),
  `scheduler/eevdf/{EevdfScheduler,CpuShares}.kt`, `scheduler/rt/RtPolicy.kt`,
  `scheduler/ports/SchedulerPorts.kt`, `platform/PlatformPorts.kt`,
  `scheduler/SchedulerService.kt`
- `:data` — `task/*` (Task, TaskDao, TaskDatabase, TaskDisplayItem,
  TaskLink, TaskLoadFactor, TaskMembership, TaskRepository,
  InterruptReturn, timer/TaskTimerState+Ext), `runlog/*` (RunSession,
  RunLogEntry, RunLogDao, RunLogRepository, Run{Daily,Monthly}Summary),
  `scheduler/*` (SchedulerFacade, RtScheduler, RtSchedulerService,
  EevdfSchedulerService, LoadAverage, LoadEwmaReconstructor), `sync/*`
  (MultiUserSyncManager, SyncConflict, SyncState, SyncWriteStats,
  TaskFieldClassification), `backup/BackupManager.kt`
- `:platform` — `alarm/AndroidAlarmPort.kt`, `media/{SoundManager,
  VibrationManager}.kt`, `notification/{NotificationHelper,
  AlarmNotificationPolicy, AlarmReliabilityChecker, AppForegroundTracker,
  ForegroundAppDetector}.kt`, `scheduler/SystemClockAndRrStore.kt`
- `:shared` — `FeatureFlag.kt`, `SafeRun.kt`, `DurationFormat.kt`
- `:app` — `SchedulerApplication.kt`, `di/{AppCoreModule,DatabaseModule,
  PlatformModule,RepositoryModule,SchedulerModule}.kt`,
  `core/SharedPrefsFeatureFlags.kt`
- `:feature/task` — `list/` (TaskViewModel + 13 delegates: Crud, Scheduler,
  ListBuilder, Sort, MenuSync, ListToggles, GroupExpand, DisplayScale,
  CallSwitch, BubbleTap, TimerCard, TimerLifecycle, AlarmOverrun,
  QueueLastRun, Observer, StartupRecovery, MainActivity, TaskListStyle),
  `addtask/` (AddTaskActivity + 10 *Section.kt files + SaveHandler),
  `group/` (PickerDialog, RecentGroupPrefs, GroupTaskPrefs), `adapter/`
  (TaskAdapter, TaskViewHolder, BindHelpers, CardScale, Formatters,
  NoticeSegments, UnitFormat, TaskDiffCallback), `notice/` (NoticePhase,
  NoticeStateMachine), `timer/` (TimerEngine, InterruptDelegate,
  TimerCardAction, TimerStartEvent)
- `:feature/alarm` — AlarmActivity, AlarmControlModule,
  AlarmForegroundService, AlarmScheduler, AlarmState, AlarmStopReceiver,
  TimerAlarmReceiver
- `:feature/autoswitch` — AutoSwitchActivity, BubbleOverlayService,
  CallStateReceiver, CallSwitchService, OverlayControlModule
- `:feature/backup` — DataBackupActivity, SettingsBackup
- `:feature/settings` — SettingsActivity + 8 sub-screens,
  SettingsChangeLogger
- `:feature/stats` — StatsActivity + 3 Fragments + StatsPagerAdapter
- `:feature/sync` — MultiUserSyncActivity
- `:feature/links` — LinksActivity
- `:feature/ui` — DesignTokens, CardDensity, 4 *CardView files,
  ModelDiagramView, LayoutTokenPrefs
- `:feature/shared` — AppPreferences, prefs/* (5 files), signals/*
  (BubbleEventBus, CallEvents)

---

## 2. Design iteration 1 — organ-blueprint mapping onto existing modules
*(kept all 8 existing Gradle roots; applied the 11 organs recursively
inside each root and each subfeature)*

Key idea: don't flatten the repo into 11 top-level organ folders (would
destroy real compiler-enforced boundaries, e.g. `:core` having no Android
plugin). Instead treat the 8 roots as recursion-level-1 SYSTEM nodes, each
`:feature` subfeature as level-2, and a few complex components
(`task/list`, `task/timer`, `core/scheduler/eevdf`, `core/scheduler/rt`) as
level-3 nested SYSTEMs — organs applied *inside* each, not replacing module
boundaries.

Notable reclassifications proposed:
- `SchedulerPorts`/`PlatformPorts` (`:core`'s `ports/`) → renamed to
  `security/` — a port is literally "what am I allowed to reach outside
  myself," which is the SECURITY organ's definition, just previously
  unnamed as such.
- `TaskFieldClassification`/`SyncFieldGuard` (SECURITY: what a remote peer
  may overwrite) split from `MultiUserSyncManager` (COMMUNICATION: talking
  to the peer) — both currently live in one `sync/` folder.
- `StartupRecoveryDelegate` (currently flat among 13 sibling delegates in
  `task/list/`) → RESILIENCE organ, made explicit.
- `SafeRun`/`CrashIsolation` in `:shared` → the one genuinely ownerless
  RESILIENCE organ.
- Doze-immune-alarm pattern (currently a standalone doc) → should live
  inside `feature/alarm/resilience/`.
- `:contract` has no IDENTITY organ (no version/compat marker on the
  promise itself) — flagged as a gap.

Gap analysis found: SECURITY and RESILIENCE are the two organs most
scattered/unnamed across the existing codebase; TIME and RESOURCES are
unusually literal in this domain (the app's entire subject matter *is*
CPU time and resource shares).

Migration plan proposed (doc-only, no logic changes): Phase A rename
`ports/`→`security/`; Phase B regroup `:core`/`:contract`; Phase C split
mixed-organ folders (`:data/sync/`, `:platform/notification/`); Phase D
relabel `:feature` subfeatures one at a time; Phase E fill named gaps.

**This iteration was delivered as a full markdown file:**
`EEVDF-Scale-Invariant-Architecture-Redesign.md` (already generated in this
conversation, still valid as historical reference, superseded by later
iterations for actual implementation).

---

## 3. User pushback — "too complex, no reason"

The user's core objection: organs-at-root makes every small feature touch
5+ scattered folders. The correct shape should be **capability-rooted**:
one folder per capability (e.g. "notification"), owning *everything* about
that capability — state, logic, UI — with **one public call** other
features use to reach it. No other folder anywhere in the app may hold a
duplicate/second copy of that capability's data or logic. Physical
duplication (e.g. for build reasons) should only ever be a symlink/hardlink
back to the one owning folder, never a second definition.

Example given: `alarm` needs to fire a notification → it calls
`notification(params)` callback — there is no separate notification logic
inside `alarm/`.

---

## 4. Design iteration 2 — capability-rooted, one folder per capability

Every capability = one folder with its own internal organ set
(`identity/ input/ state/ logic/ output/ communication/ time/ resources/
security/ resilience/ runtime/`), exposing exactly one
`communication/api.kt` as its only public surface. ~20 capabilities
identified for this specific project:

```
clock, eevdf-scheduler, rt-scheduler, scheduler-facade, task, task-list,
task-addtask, task-group, task-notice, timer, notification,
sound-vibration, alarm, autoswitch, backup, sync, settings, stats, runlog,
links, ui, feature-flags, crash-isolation, contract-nav, di, build-runtime
```

Notable design decisions:
- `scheduler-facade` is its **own** capability (not folded into `task`),
  because it's the one thing allowed to know both the rich `Task` entity
  shape and the pure `SchedTask`/`RtConfig` shapes — matches the existing
  project's own documented rationale for why RtScheduler/EEVDFScheduler are
  facades, not the real logic.
- The old `backup -> task` allowlisted debt edge is explicitly fixed here:
  `backup` calls `timer.api.pauseForExport()` and `task.api` instead of
  reaching into `task-list`'s internals.
- `notification` capability shown with recursion going *one level deeper*
  inside its own `state/` folder (a "GPU has its own memory, which itself
  has its own mini-processor" analogy) — demonstrating the organ blueprint
  isn't capped at a fixed depth.

---

## 5. User: cognitive-load comparison request (organ-root vs capability tree)

At hypothetical 1,000-feature scale, computed (assumption-labeled)
estimates:
- Organ-root tree: ~200x more entries scanned per directory lookup (since
  `logic/`, `state/`, `output/` etc. each contain ~1,000 feature-named
  children); ~5x more CODEOWNERS/ownership-mapping lines; blast radius per
  change balloons to "whole organ folder" unless per-feature submodules are
  reintroduced inside each organ (which just re-derives the feature axis
  one level deeper, without compiler enforcement).
- Feature-axis merge-collision rate (≈1.2 expected pairwise collisions at
  50 concurrent devs / 1,000 features) is **identical** regardless of root
  layout — collision rate depends on feature count, not folder shape.
- Conclusion: capability/feature-rooted trees win decisively at scale;
  organ-rooted trees only make sense as an *internal* layout inside each
  capability, not as the top-level axis.

---

## 6. User's hardware analogy — the real turning point

The user reframed the whole problem: think of the app as a computer made of
independent, fully replaceable hardware components (GPU, CPU, motherboard,
PSU, HDD). Each component has its own memory, power, processor —
completely independent of the rest of the system. Recursion doesn't stop
at a fixed depth: GPU memory has its own data, mini embedded processor, and
communication channel; at the very bottom everything is made of the same
silicon (same *material*, different *instances* — never a source of
conflict). A CPU's embedded memory and a GPU's memory are different, even
though both are "memory."

This directly maps onto the architecture:
- CPU ≈ `eevdf-scheduler` (a fully independent processor)
- GPU ≈ `notification` (a different, fully independent processor — same
  *kind* of internal parts as CPU, never shared instances)
- HDD ≈ `task` (persistent storage, its own controller)
- The silicon/material level ≈ primitive types (`Int`, `String`, `Long`) —
  shared *kind*, never shared *instance*.

---

## 7. Design iteration 3 — full organ-at-every-level tree, 7-class root

User asked for: (a) full tree with organ folders (state/logic/etc.) at
every level, (b) root folder expanded to include more component classes,
each fundamentally different (like CPU/GPU/HDD/PSU/motherboard), each
capable of existing independently.

**Root fixed at 7 classes**, chosen to mirror real computer component
classes:

```
compute/    = CPU class   — pure decision/processing engines
              (eevdf-scheduler, rt-scheduler, scheduler-facade)
memory/     = storage class — persisted, owned data
              (task, runlog, backup)
io/         = GPU/peripheral class — renders output / receives input
              (notification, sound-vibration, ui, autoswitch, task-list,
               task-addtask, task-group, task-notice, settings, stats,
               links)
timing/     = oscillator class — TIME-organ-dominant capabilities
              (clock, timer, alarm)
bus/        = motherboard class — routes signals, computes nothing itself
              (contract-nav, sync)
power/      = PSU class — regulates whether/how much a component may run
              (feature-flags, crash-isolation)
runtime/    = BIOS/composition class — the one place allowed to know
              every other root exists; wires, never computes
              (di, build-runtime)
```

Each capability inside each class still carries its own full organ set
(`identity/ input/ state/ logic/ output/ communication/ time/ resources/
security/ resilience/ runtime/`) wherever needed. Key point: two
capabilities can each have (e.g.) a `state/` folder without conflict —
exactly like GPU memory and CPU-adjacent RAM both being "memory" but never
the same chip; each capability's organ folders are private, reachable only
through its `communication/api.kt`.

---

## 8. User: root arity must be fixed/stable — never exceed folder limit

Earlier response (iteration between capability-rooted and 7-class) briefly
proposed collapsing root to exactly 2 folders
(`capabilities/` + `composition/`) so root fan-out never grows no matter
how many capabilities are added. This was superseded almost immediately by
the hardware analogy (§6-7 above), which asked for *more* root classes, not
fewer — but each fixed permanently at 7, never growing past that, since new
capabilities always nest inside an existing class rather than creating a
new root. **The 7-class root (§7) is the version that stuck** and carried
forward into all subsequent designs.

---

## 9. Design iteration 4 — future-hardware reservation (PCIe slot pattern)

User's requirement: like a motherboard's PCIe slot, the system should
reserve a *generic* attachment mechanism for future hardware without
needing to know what that hardware will be. When real hardware is missing/
fails, fall back gracefully (GPU render → CPU render fallback) instead of
crashing.

Added exactly one new piece of fixed, generic infrastructure inside
`bus/`:

```
bus/slots/
├── identity/SlotContract.kt    — the generic plug shape every attachable
│                                 capability must implement:
│                                 { capabilityId, api, isAlive() }
├── communication/registry.kt   — attach(capability), detach(id),
│                                 route(id, call) — never enumerates
│                                 capabilities by name
└── resilience/fallback.kt      — if a routed capability is
                                  disconnected/failed, returns the
                                  registered fallback instead of crashing
                                  (e.g. no autoswitch attached → task-list's
                                  default in-app switch used instead of
                                  the bubble overlay)
```

`power/crash-isolation` wraps every `slots/registry.kt` call so one
capability's exception disconnects only that capability, never the app.
`runtime/di` modules bind capabilities to `bus/slots` instead of wiring
them directly — adding capability #1001 needs one new binding line, never
a new folder shape.

---

## 10. Timer-expiry event flow (concrete worked example)

User asked: when a timer expires, what's the call direction to
notification, alarm, sound, vibration?

```
timing/timer/logic/TimerEngine.kt
  → timing/timer/communication/api.kt : onExpire(taskId, params)
  → bus/slots/communication/registry.kt : route() fans out to every
    registered subscriber — timer never knows their names
    ├─→ timing/alarm.api : fire() — alarm/logic decides whether to
    │     escalate this expiry into a full ring
    ├─→ io/notification.api : fire(NotificationParams)
    └─→ io/sound-vibration.api : play(cue) + vibrate(pattern)
```

Key rules demonstrated:
- `timer` never calls `alarm`/`notification`/`sound-vibration` directly —
  only through `bus/slots`.
- If `alarm` decides to escalate, it *also* calls `notification` and
  `sound-vibration` through the bus — meaning they can each be called
  **twice** for one timer expiry that escalates (once for "expired," once
  for "now ringing") — this is correct, not a duplication bug, since
  they're two distinct events.
- `power/crash-isolation` wraps every hop; if `notification` is
  disconnected/throws, `timer`'s call still returns cleanly via the
  registered fallback.
- Flow is strictly one-way, one level deep, no cycles: nothing calls back
  into `timer`.

---

## 11. Two feature-addition case studies with file counts

### 11.1 RT-window-expire → also fire notification/alarm/sound

- **Existing (ownership) architecture:** ≈13–15 files across 7 Gradle
  modules, plus a new allowlist entry (`RtPolicy.kt`, `SchedulerPorts.kt`,
  `RtScheduler.kt`, `RtSchedulerService.kt`, `AlarmController.kt`,
  `AlarmControlModule.kt`, `AndroidAlarmPort.kt`, `NotificationHelper.kt`,
  `SoundManager/VibrationManager.kt`, `SchedulerDelegate.kt`,
  `AlarmOverrunDelegate.kt`/new delegate, DI module changes, allowlist
  file, fakes, new characterization test).
- **New (capability+bus) architecture:** ≈2–4 files (`RtPolicy.kt` detects
  expiry; `rt-scheduler`'s `api.kt` publishes one bus event; possibly one
  new topic constant; `alarm`'s `api.kt` subscribes to it).
  `notification`/`sound-vibration` change in **zero** files — they already
  expose generic `fire()`/`play()` and don't care who calls them.

### 11.2 Bluetooth multi-device alarm sync (fan-out ring, fan-in stop-wins)

- **Existing architecture:** ≈17–19 files across 6 modules + 1 new feature
  dir + manifest + 2 DI modules + build guard (new
  `BluetoothConnectionManager`/`BluetoothTransport`, `BtSyncState`/
  `BtSyncManager`, contract changes, `AlarmControlModule`/`AlarmActivity`/
  `AlarmStopReceiver`/`AlarmScheduler` changes, new `BtPairingActivity`
  subfeature, settings entry point, notification variant, DI bindings,
  manifest permissions/receivers, new two-way allowlist edges, fakes, a new
  race-condition characterization test).
  - Named cognitive cost: no existing owner is unambiguous for "fan-in
    stop-wins across multiple devices" — this ambiguity, not raw file
    count, is the real cost.
- **New architecture:** ≈3–4 files (`io/bluetooth-sync/` new capability
  with its own `logic/`, `state/`, `communication/api.kt` implementing
  `SlotContract`; one new subscribe/publish line in `timing/alarm`'s
  `api.kt` reusing the *existing* `alarm-ringing`/`alarm-stop` event names).
  `notification`, `sound-vibration`, and the local stop button change in
  **zero** files.
  - The fan-in race has an unambiguous home: the one new capability that's
    the only thing that knows about "multiple devices" at all.

### 11.3 Side-by-side summary table

| | Existing (ownership) | New (capability+bus) |
|---|---|---|
| Files touched (RT-expire case) | 13–15 | 2–4 |
| Files touched (BT-sync case) | 17–19 | 3–4 |
| New cross-boundary contracts needed | Yes, each time | No — reuse existing event names |
| Where does new cross-cutting logic live? | Must be invented/argued each time | Obvious — the one new capability that owns the new concern |
| DI/manifest/allowlist friction | High, recurring | Near zero |

---

## 12. "Biggest possible future feature" question

User asked what the largest feature this app could ever grow into. Answer:
a **distributed, multi-person real-time scheduler** — extending the
existing "one CPU's fairness/RT/deadline scheduling" metaphor to scheduling
*people's* time against *shared* resources (meeting rooms, on-call
rotations, shared task queues), matching `architecture.md`'s own
embedded→app→server→cluster→**planetary** progression.

Requires (beyond everything built so far):
1. A real distributed-scheduling capability (new math: fairness negotiation
   under network partition/clock skew, not just local CPU time).
2. A negotiation/consensus capability (CRDT/OT-like conflict resolution,
   generalizing the earlier fan-out/fan-in Bluetooth pattern).
3. A much stronger authorization model (who may see/edit *another
   person's* schedule — beyond today's field-merge-only sync policy).
4. Resilience semantics that matter physically to real people (don't
   double-book a shared room, don't wake the wrong person's phone), not
   just "don't corrupt the DB."

### File-count estimate for this feature

- **Existing architecture:** ≈70–95 files across nearly every module,
  10–15 new allowlist edges (which would break the allowlist's own
  "signal of debt" purpose by ceasing to be rare).
- **New (capability+bus) architecture:** ≈26–35 files — 3–4 new
  capabilities, existing capabilities gaining one subscriber line each, no
  DI rewiring beyond one binding per new capability.
- **Ratio:** existing needs ~2.5–3x more files; the gap is almost entirely
  plumbing (allowlist edges, DI rewiring, cross-feature contracts), not the
  genuinely new algorithm work (which is comparable either way).

**Biggest single structural issue identified in the existing architecture
for this feature:** `:core`'s purity rule (no Android imports, deterministic,
compile-enforced) is fundamentally incompatible with distributed scheduling,
because clock skew / partial-acknowledgment / network partition are
*inputs to the fairness algorithm itself*, not side effects that can be
cleanly separated the way `SystemClock` was separated from `EevdfScheduler`.
There's no existing mechanism in the ownership architecture for "a
responsibility that's genuinely new and cross-cutting" other than manually
renegotiating every guard rail (allowlist, sync field classification,
`check_architecture.sh`) at once.

---

## 13. Prior-art question

User asked which real apps already use capability+bus. Answer: no exact
match to this specific folder convention, but the underlying pattern
(microkernel/plugin-bus, isolated failure) is well-established:
- **VS Code** — extensions talk only through the extension API, each in
  its own process; closest match for fallback/disconnect behavior.
- **Home Assistant** — integrations are isolated, communicate only via a
  central event bus (`hass.bus`), go "unavailable" independently instead
  of crashing the dashboard — closest match to the GPU-disconnect/fallback
  requirement.
- **Eclipse (OSGi + extension points)** — original inspiration: bundles
  independently loadable/replaceable; platform defines extension points
  without knowing which bundle fills them (≈ `SlotContract`).
- **Kubernetes controllers** — watch events from the API server, act
  independently, never call each other directly, one crashing doesn't
  affect others.
- **Redux/Flux frontends** — one central store/bus instead of direct
  component-to-component calls.
- **Android OS itself** — apps/services talk only through Binder IPC/
  Intents, never direct memory calls, which is why one app crashing
  doesn't crash another.

---

## 14. Design iteration 5 (final) — full microkernel rewrite

User asked for the full tree rewritten in explicit microkernel vocabulary
(QNX-style microkernel + isolated drivers, Home-Assistant-style
manifest+event model), with every filename renamed to describe actual
behavior rather than pattern/role.

### 14.1 Final root structure

```
kernel/            — minimal, trusted, never itself pluggable/restarted
  event-bus/         bus.kt (publish/subscribe), topics.kt (topic registry)
  supervisor/         supervisor.kt (attach/detach/restart), health-monitor.kt
                       (watchdog — detects hung/crashed capability, marks
                       it "unavailable" instead of propagating the crash)
  crash-guard/        run-isolated.kt (wraps every dispatch in try/catch +
                       timeout)
  clock/              clock.kt (the one shared primitive, now())
  identity/           kernel-version.kt

capabilities/       — "drivers"/"integrations": isolated, replaceable,
                      self-contained, each with manifest.kt declaring
                      PUBLISHES / SUBSCRIBES / fallbackWhenUnavailable()
  task-scheduling/    (fairness/, realtime-window/, task-schedule-bridge.kt)
  task-storage/       (task-record.kt, task-table.kt, save-task.kt,
                       recover-on-crash.kt)
  run-history/
  backup-restore/
  countdown-timer/    PUBLISHES: timer.expired
  alarm-ringer/       SUBSCRIBES: timer.expired, realtime-window.expired
                      PUBLISHES: alarm.ringing, alarm.stopped
  reminder-notifier/  SUBSCRIBES: alarm.ringing, alarm.stopped, timer.expired
  feedback-cues/      SUBSCRIBES: alarm.ringing, alarm.stopped
  call-autoswitch/    SUBSCRIBES: phone.call-state-changed
                      PUBLISHES: overlay.shown
  task-list-screen/   SUBSCRIBES: timer.expired, alarm.ringing,
                      overlay.shown, task.saved
  add-task-screen/, group-picker/, notice-phase/, settings-screens/,
  stats-screens/, links-screen/, design-system/
  multi-device-sync/  SUBSCRIBES: task.saved
                      PUBLISHES: task.conflict-detected
  navigation-routes/
  feature-toggles/
  crash-guard-policy/ (crash-recovery — what the supervisor wraps around
                       every capability call)

composition/        — wires kernel + capabilities, computes nothing
  boot/               application-entry.kt
  capability-bindings.kt   the full list of which capabilities exist —
                           adding one is one new line here
  build/              build-conventions.kt
```

### 14.2 Renamed file examples (behavior-describing names)

| Old name | New name |
|---|---|
| `CpuShares.kt` | `share-ledger.kt` |
| `SchedTask.kt` | `vruntime.kt` |
| `EevdfScheduler.kt` | `rank-tasks.kt` |
| `RtConfig.kt` | `window-config.kt` |
| `RtPolicy.kt` | `is-window-open.kt` |
| `SchedulerFacade.kt` | `task-schedule-bridge.kt` |
| `Task.kt` | `task-record.kt` |
| `TaskRepository.kt` | `save-task.kt` |
| `NotificationHelper.kt` | `post-reminder.kt` |
| `SoundManager.kt` | `play-sound.kt` |
| `VibrationManager.kt` | `play-vibration.kt` |
| `AlarmScheduler.kt` | `schedule-wake.kt` |
| `TimerAlarmReceiver.kt` | `on-timer-expired.kt` |
| `AlarmStopReceiver.kt` | `on-stop-request.kt` |
| `TaskFieldClassification.kt` | `field-access-policy.kt` |

### 14.3 Timer-expiry flow under the microkernel (final version)

```
capabilities/countdown-timer/tick.kt  (internal, private)
 → capabilities/countdown-timer/publish-expiry.kt
     bus.publish("timer.expired", taskId)
 → kernel/event-bus/bus.kt  (looks up every SUBSCRIBES: timer.expired
   capability by manifest, doesn't know their names)
 → wrapped by capabilities/crash-guard-policy/run-isolated.kt at every hop
     ├─→ alarm-ringer/on-timer-expired.kt
     │      → may publish("alarm.ringing", ...) → routed again through
     │        the bus/crash-guard to reminder-notifier + feedback-cues
     ├─→ reminder-notifier (also subscribed directly to timer.expired,
     │      e.g. a lightweight "task done" notice even without full ring)
     └─→ task-list-screen (subscribed to refresh the UI row)

kernel/supervisor/health-monitor.kt watches every hop in parallel — any
subscriber that throws or hangs past a timeout is marked "unavailable" and
the bus stops routing to it until the supervisor restarts it; other
subscribers are unaffected.
```

---

## 15. File-count comparison — capability+bus vs. microkernel

For the distributed multi-person scheduler feature (§12):

| Area | Capability+bus | Microkernel |
|---|---|---|
| New fairness algorithm | 6–8 | 6–8 (same — real math either way) |
| Negotiation/consensus | 5–7 | 5–7 (same) |
| Pairing/permissions UI | 5–6 | 5–6 (same) |
| Security/ACL | 3–4 | 3–4 (same) |
| Existing capabilities gaining a subscriber | 6–8 | 6–8 code lines + 4–6 new `manifest.kt` SUBSCRIBES entries (extra cost — manifests didn't exist before) |
| DI/composition wiring | 1–2 | 1 (single fixed `capability-bindings.kt`) |
| Supervisor/health-monitor changes needed | — (no such concept) | **0** — generic, never needs editing for a new capability |
| **Total** | **≈26–35** | **≈30–40** |

Microkernel costs **slightly more files (+~15%)** for this one feature — the
honest tradeoff is the new `manifest.kt` bookkeeping — but the payoff is
structural, not per-feature: failure isolation and live health visibility
(`supervisor`, `health-monitor`, `crash-guard`) are built once and never
re-earned per feature, whereas the plain capability+bus version has no
systemic answer to "does this new capability crash safely" — that has to be
verified file-by-file, feature-by-feature.

---

## 16. Reliability comparison — capability+bus vs. microkernel

**Verdict: microkernel is more reliable, systemically, not incrementally.**

| Failure scenario | Capability+bus | Microkernel |
|---|---|---|
| Subscriber throws mid-delivery | Depends on whether that specific `route()` call happened to be wrapped in try/catch | Always caught by `run-isolated.kt`, unconditionally, for every dispatch |
| Subscriber hangs (deadlock/infinite loop) | No detection mechanism at all | `health-monitor.kt` watchdog/timeout catches it, marks unavailable |
| Subscriber crash-loops repeatedly | Re-triggers the same crash forever, no circuit breaker | Supervisor can stop retrying after N failures, mark unavailable until manual restart |
| "Which capability is broken right now?" | No structural answer — grep logs and infer | `supervisor`'s live status list — first-class, queryable |
| New capability author forgets to handle exceptions | Whole system's reliability depends on that author remembering | Reliability doesn't depend on it — the kernel wraps it regardless |

**Core distinction:** in capability+bus, "don't let one failure take down
another" is a *policy* every author must remember to implement. In the
microkernel, it's a *mechanism*, enforced centrally once, applying
automatically to every future capability. Policy can be forgotten; an
enforced mechanism cannot.

---

## 17. Deliverables already produced in this conversation

1. `EEVDF-Scale-Invariant-Architecture-Redesign.md` — the organ-blueprint
   mapping onto the original 8 ownership roots (§2 above). Historical
   reference; superseded by the capability/microkernel design for actual
   implementation, but still useful for understanding organ vocabulary and
   the specific gap analysis (SECURITY/RESILIENCE naming gaps,
   doze-immune-alarm folding, `ports/`→`security/` rename rationale).
2. `microkernel-redesign-prompt.md` — the action-oriented implementation
   spec: non-negotiable architecture rules, target tree (§14.1 above),
   known event topics table, known cross-capability edges to eliminate,
   a **Phase -1 full inventory audit** (mandatory before any scaffolding —
   requires enumerating every file including resources, manifest entries,
   build config, Room schemas, tests, docs, scripts, with an empty
   "UNASSIGNED" bucket and file-count reconciliation before proceeding),
   Phase 0–6 migration plan, per-capability "definition of done" checklist,
   and a direct instruction block for Claude to work one phase at a time,
   wait for confirmation, and run tests after each phase.
3. This file — the full narrative context export.

---

## 18. What a fresh Claude session should do with this file

1. Read this whole file for context — don't start implementing from a
   partial understanding.
2. Read `microkernel-redesign-prompt.md` for the actual rules and
   phase-by-phase instructions to execute.
3. Read the real project zip (`eevdf-scheduler-5.0.0.zip`) directly —
   this export's file inventory (§1.2) is representative, not exhaustive;
   the Phase -1 audit in the implementation prompt is what guarantees
   completeness.
4. Do not skip the Phase -1 inventory audit, even though a lot of design
   thinking already happened in this conversation — that thinking covered
   the *pattern*, not a verified complete file mapping.
5. Follow the phase order in the implementation prompt; don't reorder or
   parallelize phases even if it looks safe, since later phases assume
   earlier ones' bus/manifest infrastructure already exists and is tested.
