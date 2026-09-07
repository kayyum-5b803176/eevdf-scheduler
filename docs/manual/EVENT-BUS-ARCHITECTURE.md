# Event Bus & Microkernel Architecture — Status and Handoff

**Read this first if you are a fresh Claude session picking up this project.**
This document is the honest current state of the `eevdf-scheduler` microkernel
migration as of `versionName 6.10.11` — not the original plan, not what was
*intended*, but what is actually true in the code right now, verified by
grep against the real tree rather than recalled from memory. Where the two
differ, that gap is called out explicitly, because several were discovered
only by checking.

---

## 1. The architecture, in one paragraph

The app was rebuilt from an 8-root ownership-based module layout
(`:app :contract :core :data :platform :shared :testing :feature`) into a
microkernel: a minimal trusted `kernel/` (event bus, supervisor, crash-guard,
clock), ~20 independent `capabilities/*` modules (one per feature, each
owning 100% of its own state/logic/UI), and a `composition/` layer that wires
them together and is the only place allowed to know the full capability
list. Capabilities are supposed to never import each other's internals —
only `kernel/event-bus`'s `bus.publish(topic, payload)` /
`bus.subscribe(topic, id, handler)`, using `Topic<T>` constants declared in
`kernel/event-bus/topics.kt`, never a raw string.

## 2. Root structure today

```
app/            — thin shell: manifest, launcher icons, depends on every capability + composition
kernel/         — event-bus, supervisor, crash-guard, clock, contracts (1 file: AlarmRingingQuery)
composition/    — capability-bindings.kt (the one Hilt @Module wiring everything), build.gradle.kts
capabilities/   — 20 modules (see §4)
docs/           — untouched throughout the whole migration, per standing instruction
```

`:contract`, `:core`, `:data`, `:feature`, `:platform`, `:shared`, `:testing`
are **fully retired** — every file they held moved to a capability, kernel,
or composition, or was found to be dead code and deleted.

## 3. How this migration was actually done

Ten phases (Phase 0 kernel scaffold → Phase 10 composition layer), each
shipped as a `versionName`-bumped, changed-files-only zip with a paired
`scripts/apply-{version}-deletions.sh` script, so changes could be verified
before the old files were removed. Versioning stayed in one continuous
`6.x` line the whole way (`6.0.0` → `6.10.0`) rather than jumping to `7.0.0`,
per explicit instruction partway through.

After Phase 10 (`6.10.0`), **eleven more patch releases (`6.10.1`–`6.10.11`)
were needed to make the app actually compile and run.** This is the part
most worth reading carefully, because the *classes* of mistake made are
likely to recur if this project is touched again without care.

### 3a. The recurring mistake: rebuilding a file from a stale base

The single largest source of bugs across this whole patch series. Because
each phase's output was a separate saved snapshot (`phase1-build` through
`phase10-build`, then `patch-6.10.1` through `patch-6.10.11`), fixing one
file for an unrelated reason sometimes meant regenerating it from an
**older** snapshot than the one that already had a previous fix applied —
silently reverting that earlier fix. This happened at least four times to
`task-list-screen` alone (`6.10.3`, `6.10.5`, `6.10.7` all had to re-fix
something a *previous* patch had already fixed once).

**If you are patching this project further: before regenerating any file,
trace its true latest version by checking every prior patch in
chronological order — not just the immediately-preceding one.** A Python
snippet for this is in the conversation history; the pattern is: walk
`phase1-build ... phase10-build, patch-6.10.1 ... patch-6.10.N` in order,
and the last one that contains the file wins.

### 3b. Real dependency cycles found late

Two capability splits turned out to be genuine Gradle dependency **cycles**,
not just import-path staleness, discovered only when the build graph was
actually compiled:

- **`task-scheduling` ↔ `task-storage`** (`6.10.2`): the original plan put
  the `Task ↔ SchedTask` bridge in `task-scheduling`, but the bridge needs
  `Task` (owned by `task-storage`), while `task-storage` needs `SchedTask`
  and the schedulers (owned by `task-scheduling`). Fixed by moving every
  `Task`-aware file (`load-average.kt`, `load-ewma-reconstructor.kt`,
  `run-eevdf-scheduler.kt`, `run-rt-scheduler.kt`, `scheduler-facade.kt`,
  `task-schedule-bridge.kt`) into `task-storage/scheduling/`, leaving
  `task-scheduling` as pure domain logic with **no** capability
  dependencies at all.
- **`notice-phase` ↔ `task-list-screen`** (`6.10.3`): `TaskViewModel`
  constructed `NoticeStateMachine(this)` directly, and `NoticeStateMachine`
  read ~15 of `TaskViewModel`'s internals. The two were never actually
  separable. `notice-phase` was retired entirely and merged into
  `task-list-screen` (same package). The 21-capability count from the
  original spec is therefore **20** in practice.
- **`interrupt-delegate.kt`** (`6.10.6`): same shape, one file. Moved from
  `countdown-timer` into `task-list-screen` for the same reason.

**Before adding any new inter-capability Gradle dependency, check both
directions first** (`grep` the target capability's build file for the
source capability's name) — this is now cheap and mandatory, not optional.

### 3c. Blind spots in every stale-reference sweep, found late

Every sweep run during this migration searched `.kt`/`.kts` files only.
Two other file types silently carried stale `com.eevdf.feature.*` /
`com.eevdf.data.*` / `com.eevdf.platform.*` / `com.eevdf.contract.*`
references the whole time, undetected until `6.10.11`:

- **`app/proguard-rules.pro`** — `-keep class` rules referencing classes by
  fully-qualified name. R8 resolves these against real class files; a stale
  one produces `Could not find class file for '...'` — a genuinely
  confusing error since it doesn't point at a source line.
- **Resource XML** — worst offender: `<com.eevdf.feature.ui.NavCardView>`
  custom-view tags in `activity_settings.xml` / `activity_layout_demo.xml`.
  Android resolves a custom-view XML tag via `Class.forName()` **at inflate
  time**, not compile time — this was a guaranteed `InflateException` crash
  the moment those screens opened, invisible to the Kotlin compiler
  entirely. Also found: a stale `android:parentActivityName` in
  `backup-restore`'s manifest fragment.

**Any future sweep for stale package references must include `*.xml` and
`*.pro`, not just `*.kt`/`*.kts`.**

### 3d. The Hilt Application-class constraint

`@HiltAndroidApp` must be compiled inside the actual `com.android.application`
Gradle module — not a library module the app depends on, even transitively.
The Application class (originally `SchedulerApplication`) was moved into
`composition/boot/` during Phase 10 for architectural tidiness; this doesn't
build, full stop, regardless of how correct the dependency wiring is. Fixed
in `6.10.10` by moving it to `app/src/main/kotlin/com/eevdf/app/boot/`.
**`composition/capability-bindings.kt`** (a plain `@Module`, not the entry
point) has no such restriction and correctly stays in `composition/`.

---

## 4. Capabilities (20)

`feedback-cues`, `design-system`, `task-storage`, `task-scheduling`,
`run-history`, `reminder-notifier`, `settings-storage`, `group-picker`,
`navigation-routes`, `feature-toggles`, `task-list-screen` (absorbed
`notice-phase` and `countdown-timer`'s `interrupt-delegate.kt`),
`add-task-screen`, `countdown-timer`, `links-screen`, `backup-restore`,
`alarm-ringer`, `call-autoswitch`, `settings-screens`, `stats-screens`,
`multi-device-sync`.

---

## 5. THE EVENT BUS: what's actually wired vs. what's decorative

**This is the part most likely to surprise you if you trust the manifest
files or the topic list alone — verify with grep, don't assume.**

### Genuinely wired end-to-end (real `publish` + real `subscribe`)

| Topic | Publisher(s) | Subscriber(s) |
|---|---|---|
| `ALARM_STOPPED` | `alarm-ringer` (`AlarmStopReceiver`) | `task-list-screen` (`MainActivity`) |
| `PHONE_CALL_STATE_CHANGED` | `call-autoswitch` (`CallStateReceiver`, `CallSwitchService`) | `task-list-screen` (`ObserverDelegate`) |
| `ALARM_TIMER_START_REQUESTED` | `task-list-screen`, `call-autoswitch` (2 sites) | `alarm-ringer` (`AlarmCommandHandler`) |
| `ALARM_TIMER_PAUSE_REQUESTED` | `task-list-screen`, `call-autoswitch` (multiple sites) | `alarm-ringer` |
| `ALARM_TIMER_EXPIRE_REQUESTED` | `task-list-screen` | `alarm-ringer` |
| `ALARM_STOP_REQUESTED` | `task-list-screen` | `alarm-ringer` |
| `ALARM_CANCEL_SCHEDULED_REQUESTED` | `task-list-screen` | `alarm-ringer` |
| `ALARM_DELAY_START_REQUESTED` | `task-list-screen` | `alarm-ringer` |
| `OVERLAY_CALL_STARTED_REQUESTED` / `OVERLAY_CALL_ENDED_REQUESTED` | `task-list-screen` | `call-autoswitch` (`OverlayCommandHandler`) |
| `BACKUP_EXPORT_REQUESTED` / `BACKUP_IMPORT_REQUESTED` | `backup-restore` | `task-storage` (`BackupCheckpointHandler`), `task-list-screen` |
| `BUBBLE_TAPPED` | `call-autoswitch` (`BubbleOverlayService`) | `task-list-screen` |
| `TIMER_RUNNING_CHANGED` | `task-list-screen`, `call-autoswitch` (via `LatestValue.setAndPublish` — **not** a raw `.publish()` call, check for this helper too if you grep) | both, via `LatestValue.trackedOn` |

These 12 topics (grouping the 6 alarm-command topics as one family) are the
ones actually replacing real direct-call violations found during migration
(`AlarmController`, `OverlayController`, `AlarmActions`, `BubbleEventBus`,
`CallEvents`, the `TaskViewModel`→backup-restore violation from spec §4).
The kernel mechanism itself (`EventBus`, `Supervisor`, `HealthMonitor`,
`runIsolated` crash-guard) is unit-tested and solid.

### Declared but never implemented — dead topics

| Topic | Declared publisher | Declared subscriber | Reality |
|---|---|---|---|
| `TIMER_EXPIRED` | `countdown-timer` | `alarm-ringer`, `reminder-notifier`, `task-list-screen` | Zero real code. Superseded by `ALARM_TIMER_EXPIRE_REQUESTED`, which does the same job. |
| `ALARM_RINGING` | `alarm-ringer` | `reminder-notifier`, `feedback-cues` | **Zero real code — see §5b, this is the important one.** |
| `REALTIME_WINDOW_EXPIRED` | `task-scheduling` | `alarm-ringer` | Zero real code. Never implemented at all. |
| `TASK_SAVED` | `task-storage` | `task-list-screen`, `multi-device-sync`, `run-history` | Zero real code. `TaskRepository` does direct DB writes, no publish. |
| `OVERLAY_SHOWN` | `call-autoswitch` | `task-list-screen` | Zero real code. |
| `TASK_CONFLICT_DETECTED` | `multi-device-sync` | `task-list-screen` | Zero real code. |

These six are exactly the original known-topics table from the very first
design prompt, written before any capability existed. They were quietly
superseded by more specific command-topics invented later during actual
bus-ification work, and nobody went back to either finish wiring them or
delete them. **Right now they are pure documentation, not functioning code.**

### 5b. The concrete consequence: alarm sound/vibration/notification is NOT on the bus

`feedback-cues` and `reminder-notifier` both declare
`SUBSCRIBES = [ALARM_RINGING, ALARM_STOPPED]` in their `manifest.kt` files.
**Neither has a single real `bus.subscribe()` call anywhere in its source.**
Verified directly: `grep -rn "bus\.\|subscribe(" capabilities/feedback-cues/
... | grep -v manifest.kt` returns nothing, same for `reminder-notifier`.

Instead, `AlarmForegroundService` (in `alarm-ringer`) calls
`SoundManager.startAlarmForType()`, `VibrationManager.startAlarmForType()`,
and `AlarmNotificationPolicy.decide()` **directly** — the exact
direct-import pattern flagged as debt back in Phase 1 and Phase 3
(`feedback-cues/manifest.kt` and `reminder-notifier/manifest.kt` both
carry a `NOTE (flagged, not silently fixed)` KDoc comment saying so) and
never actually paid down.

**The app works correctly today because these direct calls work fine.**
This is not a functional bug. It is, specifically, the one part of the
original architecture's own example (`timer.expired → alarm.ringing →
{reminder-notifier, feedback-cues}`) that was never actually realized on
the bus.

---

## 6. Other known, flagged, deliberate debt (not bugs — documented trade-offs)

Each of these has an explanatory comment at its source; listed here for a
single place to check what's already been decided vs. still open:

- **`task-storage` → `task-scheduling`** (`task-schedule-bridge.kt`) — the
  one bridge the original spec (§4) explicitly sanctioned. One-way, no cycle.
- **`AlarmRingingQuery`** (`kernel/contracts/`) — the one deliberate
  synchronous-query exception to "bus only." Used once, for cold-start
  overrun recovery after process death, where a bus event genuinely cannot
  answer "what's true right now" (no cross-process memory). Documented in
  its own KDoc at length.
- **`add-task-screen` → `task-list-screen`** (`6.10.6`) — `AddTaskActivity`
  shares `TaskViewModel` directly (`by viewModels<TaskViewModel>()`), plus
  7 files reading its repository/state. One-way, verified no reverse edge.
  Proper fix would mirror what `links-screen` already does (talk to
  `TaskRepository` directly instead) — not done, flagged as future work.
- **`task-list-screen` → `multi-device-sync`** (`6.10.8`) — `TaskViewModel`
  calls `MultiUserSyncManager`/`SyncState` directly. Same shape as above.
- **`task-list-screen` → `alarm-ringer`** (`6.10.8`) — narrow: only reads
  two `Intent`-extra-key string constants off `AlarmActivity`. Not
  behavioral coupling.
- **`EevdfScheduler` class name** — still named after the pattern, not the
  behavior (rule 8's own stated bad example). Renaming it touches every
  call site in `task-scheduling`; deferred since Phase 2, never done.
- **`scheduler-facade.kt` + `task-schedule-bridge.kt`** — the Phase -1 audit
  proposed merging these into one bridge file. Never done; they're two
  separate files in `task-storage/scheduling/` today, functionally fine.
- **`vruntime-staleness-regression-test.kt`** (in `task-storage`) — has a
  direct test-only import into `run-history`'s classes rather than using
  `kernel/testing/FakeBus`. Flagged, not fixed.

---

## 7. If you're picking this up: recommended next steps, in order

1. **Decide the fate of the 6 dead topics** (§5, second table). Either
   finish wiring them for real, or delete them from `kernel/topics.kt` and
   the manifest `PUBLISHES`/`SUBSCRIBES` sets that reference them — leaving
   them as-is is the worst option, since they actively mislead anyone
   reading a manifest file about what the capability actually does.
2. **If continuing the bus-completion work**, `ALARM_RINGING`/
   `ALARM_STOPPED` in `feedback-cues`/`reminder-notifier` (§5b) is the
   highest-value target — it's the one piece of the architecture's own
   founding example that was never finished, and the direct-call sites are
   already precisely identified in each capability's own `manifest.kt`.
3. **Before touching any file, grep for it across every prior patch in
   chronological order** (§3a) — do not assume the most recent zip you can
   find is the true latest version of a given file.
4. **Any dependency you add, check both directions first** (§3b) — a
   one-line `implementation(project(":capabilities:X"))` addition is cheap
   to get wrong and expensive to discover wrong (a full Gradle sync).
5. **Any stale-reference sweep must include `.xml` and `.pro`**, not just
   `.kt`/`.kts` (§3c).
6. Run `./gradlew verifyAll` (architecture guard + detekt + all unit tests)
   before considering any further change complete — `scripts/
   check_architecture.sh` was rewritten in Phase 10 to check bus-only
   communication, topic-constant usage (no raw strings), manifest presence,
   root arity, and DB schema agreement; it does not yet check for the
   "declared but unimplemented topic" problem in §5 — that would be a
   reasonable addition to the guard script itself.

## 8. Quick reference

- Current `versionName`: `6.10.11`. `versionCode` has never been touched.
- Kernel event bus: `kernel/src/main/kotlin/com/eevdf/kernel/event-bus/`
  (`bus.kt`, `topics.kt`, `latest-value.kt`).
- Composition wiring: `composition/src/main/kotlin/com/eevdf/composition/
  capability-bindings.kt`.
- Application entry point: `app/src/main/kotlin/com/eevdf/app/boot/
  application-entry.kt` (class `ApplicationEntry`, package `com.eevdf.
  app.boot` — not `com.eevdf.composition.boot`, see §3d).
- Architecture guard: `scripts/check_architecture.sh`, run via
  `./gradlew verifyAll`.
