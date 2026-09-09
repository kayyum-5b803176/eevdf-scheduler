# EEVDF Scheduler → Microkernel Redesign — Implementation Prompt

Paste this whole file as the first message in a new Claude Code / Claude
session to begin the actual implementation. It captures every design
decision already made so the next session doesn't have to re-derive them.

---

## 0. Context to give Claude

> I have an existing Android app (`eevdf-scheduler`, multi-module Gradle,
> Kotlin) currently organized by **ownership** (`:app :contract :core :data
> :platform :shared :testing :feature`, with `:feature` further split into
> subfeatures like `task`, `alarm`, `autoswitch`, `settings`, `stats`,
> `sync`, `backup`, `links`). I want to redesign it into a
> **microkernel architecture**: a minimal trusted kernel, isolated
> pluggable "capabilities" (what used to be modules/subfeatures), a central
> event bus, and a supervisor that isolates and recovers from a capability's
> failure without crashing the whole app — the same philosophy as QNX
> (microkernel + isolated drivers) and Home Assistant (integrations that go
> "unavailable" independently, coordinated over one event bus).
>
> Read the attached `eevdf-scheduler-5.0.0.zip` project first. Then follow
> the redesign spec below. Ask me before deleting or moving any file — do
> the migration incrementally, one capability at a time, verified by
> existing tests after each step.

---

## 1. Non-negotiable architecture rules

1. **`kernel/` is the only trusted, non-pluggable code.** It contains the
   event bus, the supervisor, the health monitor, the crash guard, and the
   one shared clock primitive. Nothing else may be added to `kernel/`
   without a deliberate, reviewed decision — it must stay small forever.
2. **Every feature lives in exactly one folder under `capabilities/`.**
   A capability owns 100% of its own state, logic, and UI. No other
   capability, and nothing in `kernel/`, may import a capability's internals
   directly — only its `communication/api.kt` (or, in the microkernel
   layout, only through bus events declared in its `manifest.kt`).
3. **Capabilities never call each other directly.** All cross-capability
   communication goes through `kernel/event-bus/bus.kt`:
   `bus.publish(topic, payload)` / `bus.subscribe(topic, handler)`.
   No capability may hold a reference to another capability's class.
4. **Every capability declares a `manifest.kt`** stating:
   - `PUBLISHES`: which topics it emits
   - `SUBSCRIBES`: which topics it listens to
   - `fallbackWhenUnavailable()`: what happens to callers/subscribers if
     this capability is not attached, disconnected, or marked unavailable
5. **Every bus dispatch is wrapped by `crash-guard/run-isolated.kt`.**
   An exception or hang inside one capability must never propagate to the
   kernel or to any other capability. The health monitor marks the failing
   capability `unavailable`; the bus simply stops routing to it until the
   supervisor restarts it.
6. **The composition layer wires, it never computes.** `composition/` is
   the only place allowed to know the full list of capabilities that exist
   (`capability-bindings.kt`). Adding capability N+1 means adding one line
   there — nothing in `kernel/` or existing capabilities changes.
7. **Root folder arity stays fixed.** Only `kernel/`, `capabilities/`, and
   `composition/` exist at the repo root. `capabilities/` is the only
   folder allowed to grow without bound.
8. **Naming rule: every file name states what it does, in plain language**,
   not what pattern/role it plays. (`rank-tasks.kt`, not `EevdfScheduler.kt`;
   `post-reminder.kt`, not `NotificationHelper.kt`.) Keep old names as
   `@DeprecatedName` type-aliases only if needed for a gradual migration —
   remove them once all call sites are updated.

---

## 2. Target tree (already-scoped capabilities from this project)

```
eevdf-scheduler/
├── kernel/
│   ├── event-bus/
│   │   ├── bus.kt
│   │   └── topics.kt
│   ├── supervisor/
│   │   ├── supervisor.kt
│   │   └── health-monitor.kt
│   ├── crash-guard/
│   │   └── run-isolated.kt
│   └── clock/
│       └── clock.kt
│
├── capabilities/
│   ├── task-scheduling/        (was core/scheduler eevdf + rt)
│   ├── task-storage/           (was data/task)
│   ├── run-history/            (was data/runlog)
│   ├── backup-restore/         (was data/backup)
│   ├── countdown-timer/        (was feature/task/timer)
│   ├── alarm-ringer/           (was feature/alarm)
│   ├── reminder-notifier/      (was platform/notification)
│   ├── feedback-cues/          (was platform/media)
│   ├── call-autoswitch/        (was feature/autoswitch)
│   ├── task-list-screen/       (was feature/task/list)
│   ├── add-task-screen/        (was feature/task/addtask)
│   ├── group-picker/           (was feature/task/group)
│   ├── notice-phase/           (was feature/task/notice)
│   ├── settings-screens/       (was feature/settings)
│   ├── stats-screens/          (was feature/stats)
│   ├── links-screen/           (was feature/links)
│   ├── design-system/          (was feature/ui)
│   ├── multi-device-sync/      (was data/sync)
│   ├── navigation-routes/      (was contract/nav)
│   ├── feature-toggles/        (was shared/FeatureFlag + app/core)
│   └── crash-recovery/         (was shared/SafeRun)
│
└── composition/
    ├── boot/
    │   └── application-entry.kt   (was SchedulerApplication.kt)
    ├── capability-bindings.kt
    └── build/
        └── build-conventions.kt
```

Each capability folder should internally use whatever subfolders it
actually needs (`state/`, `logic/`, `output/`, `input/`, `time/`,
`resources/`, `security/`, `resilience/`) — don't force all of them if a
capability is small (`links-screen/` may only need `output/` +
`manifest.kt`).

---

## 3. Known event topics to wire first

| Topic | Published by | Subscribed by |
|---|---|---|
| `timer.expired` | `countdown-timer` | `alarm-ringer`, `reminder-notifier`, `task-list-screen` |
| `alarm.ringing` | `alarm-ringer` | `reminder-notifier`, `feedback-cues` |
| `alarm.stopped` | `alarm-ringer` | `reminder-notifier`, `feedback-cues`, `task-list-screen` |
| `realtime-window.expired` | `task-scheduling` | `alarm-ringer` |
| `task.saved` | `task-storage` | `task-list-screen`, `multi-device-sync`, `run-history` |
| `phone.call-state-changed` | (platform receiver) | `call-autoswitch` |
| `overlay.shown` | `call-autoswitch` | `task-list-screen` |
| `task.conflict-detected` | `multi-device-sync` | `task-list-screen` |

Add new topics to `kernel/event-bus/topics.kt` as new capabilities need
them — never hardcode a topic string inline in a capability, always
reference the constant.

---

## 4. Known cross-capability edges to eliminate during migration

These are the specific violations found in the existing project that the
microkernel redesign must fix, not just relabel:

- `feature/backup -> feature/task` direct import (`DataBackupActivity`
  calling `TaskViewModel.prepareForDbExport/Import` directly) — replace
  with `backup-restore` publishing a `backup.export-requested` /
  `backup.import-requested` event that `countdown-timer` and
  `task-storage` subscribe to, so backup never reaches into the timer or
  list screen directly.
- `RtScheduler`/`EevdfScheduler` facades in `:data` that convert
  `Task <-> SchedTask` — keep this bridge but move it into
  `task-scheduling/task-schedule-bridge.kt`, and make sure `task-storage`
  never imports `task-scheduling` internals directly, only calls through
  the bus or a single bridge file with an explicit dependency (documented
  exception, same spirit as the old allowlist file, but now there should
  be close to zero of these).

---

## 5. Migration plan — do this in order, verifying tests after each phase

### Phase -1 — full inventory audit (do this first, before any scaffold)

The capability list in §2 above only covers Kotlin source files that were
manually inspected while designing this spec. It is **not** guaranteed
complete. Before moving anything:

1. Produce a full file listing of the existing project
   (`find . -type f | sort`, excluding `.git`/build output).
2. For every single file — not just `.kt` sources — assign it to exactly
   one destination:
   - Kotlin source → its capability (or `kernel/`/`composition/`)
   - `AndroidManifest.xml` entries (Activities/Services/Receivers/
     permissions) → the capability that owns that component; if the app
     has one manifest file, plan how manifest entries are organized or
     merged per capability (e.g. per-capability manifest fragments merged
     by the build, or one composition-owned manifest listing every
     capability's components)
   - `res/layout/*.xml`, `res/drawable/*`, `res/values/*`, `res/menu/*` →
     the capability whose screen/output uses them
   - `data/schemas/*.json` (Room exported schemas) → stay with
     `task-storage` (or whichever capability owns the database), and
     confirm migration tests still resolve them at their new path
   - `build.gradle.kts` per module, `settings.gradle.kts`,
     `build-logic/convention/*`, `gradle/libs.versions.toml` →
     `composition/build/` — decide whether capabilities become separate
     Gradle modules (preserves compiler-enforced isolation) or packages
     inside fewer modules (simpler build graph, weaker isolation); state
     the tradeoff and pick one before Phase 0
   - `androidTest/` and `testing/` files → move with the capability they
     test; `testing/Fakes.kt` may need to split per capability
   - `docs/`, `scripts/check_architecture.sh`,
     `scripts/feature_import_allowlist.txt`, `scripts/apply-*-deletions.sh`
     → rewrite or explicitly retire, never leave silently orphaned
3. Output this as a single mapping table: **old path → new path**, with an
   explicit "UNASSIGNED" bucket for anything that doesn't yet have a clear
   home. The UNASSIGNED bucket must be empty before Phase 0 starts.
4. Confirm the total file count before and after matches (accounting for
   intentional merges/splits, each one called out explicitly).
5. Show me this full table before touching anything.

### Phase 0 — scaffold only

1. Create `kernel/event-bus`,
   `kernel/supervisor`, `kernel/crash-guard`, `kernel/clock` with minimal
   working implementations and unit tests. No existing code moves yet.

### Phase 1 — leaf capabilities first (no dependents yet)
`feedback-cues`, `design-system`, `links-screen`, `stats-screens`.
Move **every** file assigned to them in the Phase -1 table (source,
resources, manifest entries, build config, tests) — not just the `.kt`
files — add `manifest.kt`, update imports, run existing tests.

### Phase 2 — core domain capabilities
`task-storage`, `task-scheduling`, `run-history`. These carry the most
tests (`EevdfSchedulerTest`, `RtWindowCharacterizationTest`,
`TaskSchemaFreezeTest`, etc.) plus the Room schema files — keep every
test green and confirm the DB schema/migration path still resolves.

### Phase 3 — event-driven capabilities
`countdown-timer`, `alarm-ringer`, `reminder-notifier`. Wire the
`timer.expired` → `alarm.ringing` → `{reminder-notifier, feedback-cues}`
chain through the real bus and confirm with an integration test that a
crash-guard failure in `reminder-notifier` doesn't block `feedback-cues`.

### Phase 4 — screens
`task-list-screen`, `add-task-screen`, `group-picker`, `notice-phase`,
`settings-screens` — including every layout/drawable/values resource file
the Phase -1 table assigned to each.

### Phase 5 — cross-cutting
`call-autoswitch`, `multi-device-sync`, `backup-restore`,
`navigation-routes`, `feature-toggles`, `crash-recovery`.

### Phase 6 — composition + cleanup
Move DI/manifest wiring into `composition/`, merge/reorganize
`AndroidManifest.xml` per the Phase -1 plan, delete the old module
structure, remove any `@DeprecatedName` aliases, delete
`scripts/feature_import_allowlist.txt` once zero direct cross-capability
imports remain (verify with a new `check_architecture.sh` rule that checks
for bus-only communication). Re-run the full file-count reconciliation
from Phase -1 to confirm nothing was silently dropped.

---

## 6. Definition of done for each capability migration

A capability is considered migrated when:
- [ ] It has a `manifest.kt` with accurate `PUBLISHES`/`SUBSCRIBES`/fallback
- [ ] No file inside it is imported by anything outside its own folder
      except through `kernel/event-bus`
- [ ] All its pre-existing unit/instrumented tests still pass
- [ ] A forced-failure test exists proving the rest of the app survives
      this capability throwing or hanging (via `crash-guard`)
- [ ] Every renamed file's new name describes its actual behavior

---

## 7. Instruction to Claude for this implementation session

> Work one phase at a time from §5. Before writing any code, show me the
> exact file moves and manifest.kt contents for that phase and wait for my
> confirmation. After I approve, make the changes, then run the existing
> test suite (`./gradlew :testing:test` and any other relevant module
> tests) and report the results before moving to the next phase. Do not
> skip ahead to a later phase's capabilities. Flag anywhere the existing
> code doesn't cleanly fit the manifest/bus model (e.g. hidden shared
> mutable state) instead of silently working around it.
