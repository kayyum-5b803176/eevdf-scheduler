# EEVDF Scheduler — Linux-style subsystem architecture (full migration)

The complete app, restructured into a Linux-kernel-style subsystem layout. The
scheduler **core was rewritten from scratch** (pure, deterministic, dead-code
removed); the rest of the app was **migrated faithfully** into the new module
boundaries (working UI logic preserved, packages/imports/R-class re-pointed,
seams adapted to the new domain split). Existing code was reference; no garbage
logic was carried into the core.

Priority order honored: **performance → stability → scale → maintainability → testability.**

## Modules

```
:core      Pure Kotlin/JVM. No Android/Room/Context/System-clock. The EEVDF
           algorithm, RT/DL/quota rules, fairness/shares, timer FSM, ports.
:data      Room entity (Task), DAOs, repositories, RunLog, sync, backup, AND the
           EEVDFScheduler/RtScheduler *facades* that expose the legacy API to the
           UI while delegating the math to :core (mapping Task <-> SchedTask).
:platform  Clean Android adapters for the core ports (SystemClock, RR-store,
           CountdownTimerDriver, Doze-immune AlarmPort). Wired-in incrementally.
:shared    Pure cross-cutting utilities.
:testing   JVM fakes + unit tests proving the core is testable without Android.
:app       All UI (feature-packaged: task/stats/settings/alarm/autoswitch/sync/
           backup/notification), ViewModels, the Android TimerEngine, resources,
           and the manifest. Depends on :core/:data/:platform/:shared.
```

Dependency rule (`:core` has NO Android plugin → purity is compiler-enforced):
```
app ──▶ data ──▶ core ◀── platform        shared ◀── (anyone)
```

## Why a facade instead of rewriting every call site

The UI called `EEVDFScheduler.recalculate(...)`, `.computeShares(...)`, etc. on
the rich `Task`. Rather than touch ~70 UI files, `:data` keeps those exact
method names on objects named `EEVDFScheduler`/`RtScheduler`, so UI changes were
**import-path only**. The facade maps `Task` → the pure `SchedTask`, runs the
clean core math, and writes results back onto the entity. The pure core never
mutates; the adapter bridges to the legacy mutate-in-place callers. You can
delete the facade later by migrating callers onto the core directly.

## Build

```bash
gradle wrapper --gradle-version 8.9     # wrapper jar is not shipped in this zip
./gradlew :testing:test                 # pure EEVDF unit tests (no emulator)
./gradlew :app:assembleDebug
```

## Honest status — expect a compile-fix pass

This was assembled WITHOUT a working Kotlin/Android toolchain in the authoring
sandbox (the toolchain hosts are off the network allowlist), so it is
**reviewed, not compiled.** Structural checks passed: zero stale package
references, core purity intact, every import resolves to a module, facade fields
match the entity, manifest components re-pointed. But first build WILL surface
issues to iterate on. Most likely:

- A few residual type/seam mismatches where UI code touched scheduler internals
  in ways the facade signatures don't exactly mirror.
- Room `@Database`/DAO details (schema version, type converters) from the
  migrated `TaskDatabase`.
- MPAndroidChart resolves via jitpack (configured in settings.gradle.kts).
- Kotlin 2.0.21 + KSP 2.0.21-1.0.25 + Room 2.6.1 alignment.

Send the Android Studio errors and we'll resolve them module by module. Start
with `:core` (should be clean) → `:data` → `:app`.

## What was rewritten vs migrated

- **Rewritten from scratch (core):** EEVDF algorithm (single-pass, no mutation,
  dead code removed), RT window/DL/quota rules as pure value objects, timer FSM,
  Clock/WallClock, ports, fairness/shares.
- **Migrated faithfully:** all Activities/Fragments/adapters/ViewModels, the
  Android TimerEngine, sync/backup, RunLog, RtScheduler (relocated out of core
  into data — its only fault was its location), and all 65 resource files.
