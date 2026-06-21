# Hilt Dependency Injection Migration — EEVDF Scheduler 3.0

This document summarizes the introduction of Hilt DI across the project. The
scheduling algorithms, Room schema (still v20), and UI behavior are unchanged;
only construction and wiring were touched.

---

## 1. Architecture Summary

### Object graph (data flow)

```
@HiltViewModel TaskViewModel
        │  @Inject constructor(Application, @AppPreferences SharedPreferences, TaskRepository)
        ▼
   TaskRepository            (@Singleton, @Inject constructor)
        │            ├─────────────► TaskDao
        ▼            └─► RunLogRepository (@Singleton, @Inject constructor)
   TaskDao  / RunLogDao                       │
        │                                     ▼
        └──────────────┬──────────────►  RunLogDao
                       ▼
                 TaskDatabase  (@Singleton, Room)
                       │
                       ▼
                 SQLite file (eevdf_task_database)
```

### Scheduler services

```
Feature / ViewModel delegate
        │  (inject instead of referencing the global object)
        ▼
EevdfSchedulerService / RtSchedulerService / LoadAverageService   (@Singleton, @Inject)
        │  delegate 1:1, no math changed
        ▼
data.scheduler.EEVDFScheduler / RtScheduler / LoadAverage   (stateless Kotlin objects)
        │
        ▼
core.scheduler.*   (pure JVM: EevdfScheduler, CpuShares, TimerEngine, RtPolicy)
```

### Platform services

```
@AndroidEntryPoint component
        │  @Inject
        ▼
AlarmManager / NotificationManager / UsageStatsManager / Vibrator / @AppPreferences SharedPreferences
        ▲
        │  @Provides (PlatformModule, from @ApplicationContext)
   SchedulerApplication (@HiltAndroidApp)  →  SingletonComponent
```

### Module layout (all `@InstallIn(SingletonComponent::class)`)

- **DatabaseModule** — provides `TaskDatabase` (`@Singleton`) + `TaskDao`, `RunLogDao`.
- **RepositoryModule** — documentation home; both repositories self-bind via
  `@Inject` constructors, so no `@Provides` needed.
- **SchedulerModule** — documentation home; the three scheduler services
  self-bind via `@Inject` constructors.
- **PlatformModule** — provides Android framework services + the qualified
  `@AppPreferences` SharedPreferences ("eevdf_prefs").

### Why `core` / `shared` / `testing` stay Hilt-free

`core` is a pure-JVM module whose build file intentionally excludes
Android/Room/Hilt as an architectural guard rail. Hilt is Android-only, so the
scheduler core is never annotated; instead the `data` layer exposes injectable
`@Singleton` service classes that delegate 1:1 to the stateless core objects.
This keeps the core deterministic and unit-testable while still giving every
consumer constructor-injectable scheduler dependencies.

---

## 2. File Changes

### New files
- `gradle/libs.versions.toml` — version catalog (all deps centralized + Hilt).
- `app/.../SchedulerApplication.kt` — `@HiltAndroidApp` entry point.
- `app/.../di/DatabaseModule.kt`
- `app/.../di/RepositoryModule.kt`
- `app/.../di/SchedulerModule.kt`
- `app/.../di/PlatformModule.kt` (also defines the `@AppPreferences` qualifier)
- `data/.../scheduler/EevdfSchedulerService.kt`
- `data/.../scheduler/RtSchedulerService.kt` (contains `RtSchedulerService` + `LoadAverageService`)

### Removed files
- `app/.../EevdfApp.kt` — replaced by `SchedulerApplication`.

### Modified — build / config
- `build.gradle.kts` (root) — plugins via catalog aliases incl. Hilt.
- `app/build.gradle.kts`, `data/build.gradle.kts` — Hilt + KSP + catalog.
- `core/`, `platform/`, `shared/`, `testing/build.gradle.kts` — catalog only
  (no Hilt; core keeps its guard-rail comment).
- `app/src/main/AndroidManifest.xml` — `android:name` → `SchedulerApplication`.

### Modified — data layer
- `TaskRepository.kt` — `@Singleton @Inject constructor(TaskDao, RunLogRepository)`;
  dropped the `Context` param and the internal `RunLogRepository(context)` /
  `TaskDatabase.getDatabase()` construction.
- `RunLogRepository.kt` — `@Singleton @Inject constructor(RunLogDao, @ApplicationContext Context)`;
  dropped `TaskDatabase.getDatabase(context).runLogDao()`.

### Modified — app layer
- `TaskViewModel.kt` — `@HiltViewModel @Inject constructor(...)`; repository and
  prefs injected; removed manual DB/repo construction in `init{}`. Split
  `prepareForDbExport()` (non-destructive WAL checkpoint, Room stays open) from
  new `prepareForDbImport()` (checkpoint + close, used only before a process
  restart) — see Risk Assessment.
- `MainActivity`, `AddTaskActivity`, `AutoSwitchActivity`, `DataBackupActivity`,
  `StatsActivity` — `@AndroidEntryPoint`. (`by viewModels()` now resolves the
  Hilt ViewModel factory automatically.)
- `DataBackupActivity.kt` — `@Inject TaskDao`; export uses injected DAO; import
  now fully kills the process after the file swap so the Hilt graph rebuilds.
- `CallSwitchService`, `BubbleOverlayService` — `@AndroidEntryPoint` +
  `@Inject TaskRepository`; removed per-call `TaskDatabase.getDatabase()` /
  `TaskRepository(dao, context)`.
- `StatsOverviewFragment`, `StatsChartsFragment`, `StatsCalendarFragment` —
  `@AndroidEntryPoint` + `@Inject TaskDao`/`RunLogDao`; removed in-method
  `TaskDatabase.getDatabase()` lookups.

---

## 3. Remaining Manual Dependencies

| Site | What remains | Why it was left |
|---|---|---|
| `TaskDatabase.getDatabase()` | Still the single source of DB construction; called **once** from `DatabaseModule`. | It owns the full 1→20 migration chain and WAL helpers. Funnelling Hilt through it keeps one Room instance and one migration definition. |
| `MultiUserSyncManager` (object) | `TaskDatabase.getDatabaseFile()` + `checkpointWal/AndroidClose` for raw `.db` file copy. | Stateless `object` invoked off the Android-component graph during background sync; file-path/lifecycle ops, not injectable services. Uses the same singleton, so no second Room handle. |
| `SyncFieldGuard` (object) | `TaskDatabase.getDatabase(context).taskDao()` for conflict detection. | Same as above — pure helper reached only via `MultiUserSyncManager`; converting would require threading deps through two singletons for no behavioral gain. |
| `AlarmScheduler`, `NotificationHelper`, `SoundManager`, `VibrationManager`, `*Prefs` | Stateless `object`s taking `Context` per call. | No DI benefit; converting risks regressions in alarm/key handling. They can incrementally inject `PlatformModule` handles later. |
| `StatsRepository` / `SettingsRepository` / `BackupRepository` | Do not exist. | The prompt listed them as *examples*. Stats read DAOs directly, settings use `SharedPreferences` prefs objects, backup is a stateless `BackupManager`. Not fabricated — that would change behavior, which the task forbids. |
| `androidx.hilt:hilt-navigation-compose` | Added per the prompt but unused. | The app is View/XML-based with no Compose. Kept because it was a required dependency; flagged as forward-looking only. Safe to drop if Compose is never adopted. |
| `WorkManager` | Not provided. | Not on the classpath / not used. Providing it would fail to compile. Add a binding to `PlatformModule` if/when WorkManager is introduced. |

---

## 4. Risk Assessment (lifecycle / scope)

1. **DB close vs. cached Hilt singleton (highest-impact finding).**
   `TaskDatabase` is an `@Singleton`, so the Hilt graph caches one Room handle.
   The legacy `prepareForDbExport()` called `checkpointAndClose()`, relying on
   `getDatabase()` to lazily rebuild the nulled companion singleton on next DAO
   access. Under Hilt that auto-reinit does **not** cover the handle Hilt already
   cached, so a closed DB would be served afterward.
   *Mitigation:* `prepareForDbExport()` now uses a non-destructive WAL
   checkpoint (Room stays open). A separate `prepareForDbImport()` performs the
   close, and **all** import paths now fully restart the process
   (`killProcess`) so the Hilt graph is rebuilt against the new file. The remote
   sync import already restarted via `restartNeeded`; the manual ZIP import was
   hardened to match (previously it only relaunched the activity, which would
   have surfaced the closed-DB bug post-migration).

2. **Fragment injection requires an `@AndroidEntryPoint` host.**
   The three Stats fragments field-inject DAOs, which requires `StatsActivity`
   to be `@AndroidEntryPoint`. That was added. Any future host of these
   fragments must also carry the annotation.

3. **ViewModel scope unchanged.**
   `TaskViewModel` stays an `AndroidViewModel` (it still needs `Application` for
   `AlarmScheduler`/`MultiUserSyncManager`). `@HiltViewModel` only changes who
   constructs it; lifecycle/scope are identical, and the shared-VM pattern
   (`by viewModels()` per Activity) is preserved.

4. **Repository singleton semantics preserved.**
   `TaskRepository` and `RunLogRepository` are `@Singleton`, matching the
   previous de-facto single-instance usage (one per VM / per service call). The
   `allTasks`/`activeTasks`/… LiveData are created once on the singleton, which
   is correct and slightly more efficient than the old per-construction streams.

5. **Manifest / Application swap.**
   `EevdfApp` → `SchedulerApplication`. The manifest `android:name` was updated;
   no other reference to the old class remained.

6. **No app→data dependency inversion.**
   The `@AppPreferences` qualifier lives in `:app`; `data` only depends on
   `dagger.hilt.android.qualifiers.ApplicationContext` + `javax.inject`. The
   module dependency direction (app → data → core) is intact.

---

## Build note

The migration was verified structurally (annotation coverage, import
correctness, no remaining manual construction outside the DI layer, brace
balance unchanged vs. original). A full Gradle/AGP compile requires the Android
SDK and the Hilt/KSP toolchain, which should be run in your CI/IDE to produce
the final generated DI code.
