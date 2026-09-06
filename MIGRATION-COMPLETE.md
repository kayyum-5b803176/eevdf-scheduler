# Migration Complete — Final Reconciliation

`eevdf-scheduler` 5.44.0 (ownership modules) → 6.10.0 (microkernel).
Eleven releases, each a changed-files-only zip with a paired deletion script.

---

## Final structure

```
kernel/          18 files  — event-bus, supervisor, crash-guard, clock, contracts, testing
capabilities/   301 files  — 21 capabilities, each owning its own state/logic/UI/resources
composition/      3 files  — capability-bindings.kt, boot/application-entry.kt, build.gradle.kts
app/                       — thin shell: manifest, launcher icons, dependency on composition
```

Root arity is fixed (rule 7). Only `capabilities/` grows.

## File reconciliation

| | Count |
|---|---|
| Original files | 340 |
| `docs/` — untouched, never moved or bundled, per instruction | −15 |
| **In scope** | **325** |

Every one of the 325 is accounted for:

**Migrated to a capability, kernel, or composition** — the large majority, tracked file-by-file in the Phase -1 mapping table and each release's deletion script.

**Merged (fewer files out than in), each called out explicitly:**
- 5 DI modules (`AppCoreModule`, `DatabaseModule`, `PlatformModule`, + 2 empty) → 1 `capability-bindings.kt`
- `SchedulerFacade.kt` + `RtScheduler.kt` → `task-schedule-bridge.kt`
- `AlarmController.kt` + `RingingAlarm` → `alarm-ringing-query.kt`

**Retired as replaced by a bus topic** (behaviour preserved, mechanism changed):
- `BubbleEventBus.kt` → `Topics.TIMER_RUNNING_CHANGED` + `Topics.BUBBLE_TAPPED`
- `CallEvents.kt` → `Topics.PHONE_CALL_STATE_CHANGED`
- `AlarmController.kt` (6 command methods) → `Topics.ALARM_*_REQUESTED`
- `OverlayController.kt` (2 methods) → `Topics.OVERLAY_*_REQUESTED`
- `AlarmActions.kt` → `Topics.ALARM_STOPPED`
- `feature/src/main/AndroidManifest.xml` → per-capability manifest fragments

**Deleted as dead code** — found during migration, each verified zero call sites before removal:
- `DurationFormat.kt` (v6.5.0)
- `SafeRun.kt`'s `safeFeature`/`safeFeatureOr` helpers — `internal`, no callers (v6.5.0)
- `AndroidAlarmPort.kt` (v6.9.0)
- `PlatformPorts.kt` — all four ports (v6.9.0)
- `RepositoryModule.kt`, `SchedulerModule.kt` — zero bindings each (v6.10.0)
- `scripts/feature_import_allowlist.txt` — obsolete once modules were real (v6.10.0)

**Modules retired entirely:** `:testing` (v6.2.0), `:shared` (v6.5.0), `:feature` `:core` `:platform` `:data` (v6.9.0), `:contract` (v6.10.0).

## The four architectural changes

Most of this was mechanical relocation. Four things were genuine restructuring:

**1. The shared preference substrate (v6.4.0).** Six pref files in `feature/shared/` were imported by 8 capabilities, blocking all of them. Single-ownership doesn't work here — `settings-screens` writes them while others read — so they became `settings-storage`, a state capability, mirroring the `task-storage`/`task-list-screen` split.

**2. The `TaskViewModel` hub (v6.6.0).** Six capabilities shared one live `@HiltViewModel` instance. `prepareForDbExport()` alone did three jobs owned by three capabilities. Split: DB checkpoint → `task-storage`, timer pause → `task-list-screen`, and `backup-restore` now just publishes an event. This was the violation named in spec §4.

**3. The ad-hoc buses (v6.7.0).** `BubbleEventBus` wasn't events — it was three globals read *synchronously mid-draw* and written by two capabilities. Each now holds its own `LatestValue` snapshot synced by bus events: reads stay instant, the global is gone.

**4. The ports pattern (v6.9.0).** `AlarmController`/`OverlayController`'s 8 one-way commands became bus topics with handler subscribers.

## The two sanctioned exceptions

Both are documented in their own KDoc rather than hidden:

**`task-storage` → `task-scheduling`** — the `Task ↔ SchedTask` bridge, a compile-time dependency. Named in the original spec §4 as intentional.

**`AlarmRingingQuery`** (`kernel/contracts/`) — one synchronous query, used once, on cold start after process death, to reconstruct alarm overrun. A bus event cannot answer "what is true right now" across a process restart: the bus has no cross-process memory and a cache starts empty until something republishes, racing the recovery check that needs it. Getting it wrong shows "not ringing" while an alarm rings. It lives in the kernel because both sides are in different capabilities.

## Enforcement

`scripts/check_architecture.sh` was rewritten. The old version's main job — grepping for cross-feature imports against an allowlist — is obsolete: capabilities are real Gradle modules, so an illegal import is a build failure. What it checks now:

1. Kernel imports no capability, no Android, and stays under a file-count ceiling (rule 1)
2. No capability reaches into another's `logic/`/`state/`/`input/` (rules 2, 3)
3. No hardcoded topic strings — `Topics.*` constants only (spec §3)
4. Every capability has a `manifest.kt` (rule 4)
5. Root folder set unchanged (rule 7)
6. `capability-bindings.kt` contains no control flow (rule 6)
7. DB version, migration count and exported schemas agree (carried over unchanged)

## Verification still owed

The migration is structurally complete but **has not been compiled or run** — I have no Android SDK or Gradle here. Before trusting it:

```
./gradlew verifyAll        # architecture guard + detekt + all unit tests
./gradlew :app:assembleDebug
```

Then exercise, in order of risk:

1. **Backup export/import round-trip** — the ordering guarantee changed shape (v6.6.0). `publish()` suspends until subscribers finish, which preserves it, but this is the one place a mistake corrupts data.
2. **Room migration** — schema folder was renamed to match the DB class's new FQN (v6.2.0).
3. **Alarm start/pause/expire/stop** — 8 command paths moved to the bus (v6.9.0).
4. **Kill the app while an alarm is ringing, reopen** — exercises `AlarmRingingQuery`, the deliberate exception.
5. **Incoming call** — auto-switch, bubble overlay, dot colour (the synchronous reads from v6.7.0).

Apply each release's `scripts/apply-{version}-deletions.sh` only after that release's changes are verified — they are separate from the change zips precisely so you can verify before deleting.
