Full tree, unchanged capabilities kept exactly as before (no empty placeholder folders for hardware that doesn't exist yet). The only real addition is **one fixed slot mechanism** inside `bus/` — the PCIe-equivalent — plus fallback wiring on the capabilities that are genuinely optional/pluggable today. The slot never needs to know what future hardware looks like; it only defines the plug shape.

```
eevdf-scheduler/
│
├── compute/                          = CPU class
│   ├── eevdf-scheduler/
│   │   ├── state/         SchedTask.kt
│   │   ├── logic/          EevdfScheduler.kt
│   │   ├── resources/      CpuShares.kt
│   │   ├── security/       SchedulerPorts.kt
│   │   └── communication/  api.kt
│   ├── rt-scheduler/
│   │   ├── state/          RtConfig.kt, DlBudget.kt, QuotaBudget.kt
│   │   ├── logic/           RtPolicy.kt
│   │   ├── time/            window arithmetic
│   │   ├── resources/       budget math
│   │   └── communication/   api.kt
│   └── scheduler-facade/
│       ├── state/          LoadAverage.kt, LoadEwmaReconstructor.kt
│       ├── logic/           SchedulerFacade.kt, RtScheduler.kt,
│       │                    RtSchedulerService.kt, EevdfSchedulerService.kt
│       ├── resilience/      facade-mismatch bug prevention
│       └── communication/  api.kt
│
├── memory/                           = storage class
│   ├── task/
│   │   ├── identity/        Task.kt
│   │   ├── state/           TaskDao, TaskDatabase, TaskDisplayItem,
│   │   │                    TaskLink, TaskMembership, TaskTimerState,
│   │   │                    TaskLoadFactor
│   │   ├── logic/            TaskRepository.kt, InterruptReturn.kt
│   │   ├── resilience/       migration + regression tests
│   │   └── communication/   api.kt
│   ├── runlog/
│   │   ├── state/           RunSession, RunLogEntry, summaries
│   │   ├── logic/            RunLogRepository.kt
│   │   └── communication/   api.kt
│   └── backup/
│       ├── state/           SettingsBackup.kt
│       ├── logic/            round-trip serialize rules
│       ├── output/           DataBackupActivity.kt, BackupManager.kt
│       ├── resilience/       BackupRoundTripCoverageTest
│       └── communication/   api.kt
│
├── io/                                = GPU/peripheral class
│   ├── notification/
│   │   ├── input/
│   │   ├── state/            NotificationRecord + its own mini
│   │   │                     data/logic/communication (as before)
│   │   ├── logic/             priority, dedupe, formatting
│   │   ├── output/            NotificationHelper.kt
│   │   ├── time/              delay/repeat scheduling
│   │   ├── resources/         channel quota limits
│   │   ├── security/          channel access policy
│   │   ├── resilience/        doze-immune delivery
│   │   └── communication/    api.kt      ⟵ registers into bus/slots/
│   ├── sound-vibration/
│   │   ├── output/            SoundManager.kt, VibrationManager.kt
│   │   └── communication/    api.kt      ⟵ registers into bus/slots/
│   ├── ui/
│   │   ├── state/             LayoutTokenPrefs.kt
│   │   ├── output/            DesignTokens, CardDensity, *CardView,
│   │   │                    ModelDiagramView
│   │   └── communication/    api.kt
│   ├── autoswitch/                     ⟵ OPTIONAL peripheral — see fallback
│   │   ├── input/             CallStateReceiver.kt
│   │   ├── logic/              CallSwitchService.kt
│   │   ├── output/             BubbleOverlayService.kt, AutoSwitchActivity.kt
│   │   ├── runtime/            overlay service lifecycle
│   │   └── communication/     api.kt      ⟵ registers into bus/slots/
│   ├── task-list/
│   │   ├── state/             TaskViewModel.kt
│   │   ├── input/              ObserverDelegate.kt
│   │   ├── logic/               13 delegates (Crud, Scheduler, ListBuilder,
│   │   │                      Sort, MenuSync, ListToggles, GroupExpand,
│   │   │                      DisplayScale, CallSwitch, BubbleTap,
│   │   │                      TimerCard, TimerLifecycle, AlarmOverrun,
│   │   │                      QueueLastRun)
│   │   ├── output/              MainActivity.kt, TaskListStyle.kt,
│   │   │                      TaskAdapter, TaskViewHolder, BindHelpers,
│   │   │                      NoticeSegments, CardScale, Formatters,
│   │   │                      UnitFormat, TaskDiffCallback
│   │   ├── resilience/         StartupRecoveryDelegate.kt +
│   │   │                      default in-app switch fallback (see below)
│   │   ├── runtime/             MainActivity lifecycle
│   │   └── communication/      (leaf; calls slots for autoswitch/notification)
│   ├── task-addtask/
│   │   ├── input/              10 *Section.kt files
│   │   ├── logic/               SaveHandler.kt
│   │   ├── output/               AddTaskActivity.kt
│   │   └── communication/
│   ├── task-group/
│   │   ├── state/              RecentGroupPrefs.kt, GroupTaskPrefs.kt
│   │   ├── output/               PickerDialog.kt
│   │   └── communication/       api.kt
│   ├── task-notice/
│   │   ├── state/              NoticePhase.kt
│   │   ├── logic/                NoticeStateMachine.kt
│   │   └── communication/       api.kt
│   ├── settings/
│   │   ├── state/              AppPreferences.kt, prefs/*
│   │   ├── output/               SettingsActivity + 8 sub-screens
│   │   ├── resilience/          SettingsChangeLogger.kt
│   │   └── communication/       api.kt
│   ├── stats/
│   │   ├── output/               Stats* screens/adapters
│   │   └── communication/
│   └── links/
│       ├── output/               LinksActivity.kt
│       └── communication/
│
├── timing/                           = oscillator class
│   ├── clock/
│   │   ├── logic/              Clock.kt, WallClock.kt
│   │   └── communication/      api.kt
│   ├── timer/
│   │   ├── state/              TimerStartEvent.kt
│   │   ├── logic/                TimerEngine.kt, InterruptDelegate.kt
│   │   ├── output/               TimerCardAction.kt
│   │   └── communication/       api.kt
│   └── alarm/
│       ├── input/              TimerAlarmReceiver.kt, AlarmStopReceiver.kt
│       ├── state/               AlarmState.kt
│       ├── logic/                AlarmScheduler.kt
│       ├── output/               AlarmActivity.kt
│       ├── runtime/              AlarmForegroundService.kt
│       └── communication/       api.kt      ⟵ registers into bus/slots/
│
├── bus/                              = motherboard class
│   ├── contract-nav/
│   │   ├── identity/            ContractVersion.kt
│   │   └── communication/      AppRoutes.kt
│   ├── sync/
│   │   ├── state/               SyncState.kt, SyncWriteStats.kt
│   │   ├── logic/                 SyncConflict.kt, MultiUserSyncManager.kt
│   │   ├── security/              TaskFieldClassification.kt, SyncFieldGuard.kt
│   │   ├── output/                MultiUserSyncActivity.kt
│   │   └── communication/        api.kt
│   │
│   └── slots/                     ⟵ THE PCIe SLOT — fixed, generic, never
│       │                            grows, never lists a capability by name
│       ├── identity/              SlotContract.kt — the generic plug shape
│       │                          every attachable capability must implement:
│       │                          { capabilityId, api, isAlive() }
│       ├── communication/
│       │   └── registry.kt        attach(capability), detach(id),
│       │                          route(id, call) — the motherboard doesn't
│       │                          enumerate GPU/sound/autoswitch by name; it
│       │                          only knows "something implementing
│       │                          SlotContract is plugged into slot N"
│       └── resilience/
│           └── fallback.kt        if route(id, call) finds the capability
│                                  disconnected/failed, it returns the
│                                  registered fallback instead of crashing —
│                                  e.g. no autoswitch attached → task-list's
│                                  default in-app switch used instead of the
│                                  bubble overlay; no sound-vibration →
│                                  silent no-op instead of a crash
│
├── power/                            = PSU class
│   ├── feature-flags/
│   │   ├── state/               FeatureFlag.kt, SharedPrefsFeatureFlags.kt
│   │   ├── security/             flag-access policy
│   │   └── communication/       api.kt — also decides WHICH capability
│   │                            occupies a slot when more than one could
│   │                            (e.g. hardware-key vs. software toggle)
│   └── crash-isolation/
│       ├── resilience/           SafeRun.kt — wraps every slots/registry.kt
│       │                        call, so one capability's exception
│       │                        disconnects only that capability, never
│       │                        the app
│       └── communication/       api.kt — safeRun(block)
│
└── runtime/                          = BIOS/composition class
    ├── di/
    │   ├── identity/             SchedulerApplication.kt
    │   └── modules/              AppCoreModule, DatabaseModule,
    │                             PlatformModule, RepositoryModule,
    │                             SchedulerModule
    │                             (each module now BINDS a capability to
    │                              bus/slots/ instead of wiring it directly —
    │                              adding capability #1001 only needs one new
    │                              binding line, never a new DI module shape)
    └── build-runtime/
        └── runtime/               build-logic convention plugin, gradlew
```

**How the reservation actually works, with no empty folders:**

- `bus/slots/` is the only piece of infrastructure that exists *in anticipation* of future hardware, and it stays generic forever — it defines `SlotContract` (the plug), not a list of expected devices.
- A capability becomes attachable the moment it implements `SlotContract` in its own `communication/api.kt` — nothing in `bus/` or `runtime/di/` needs to change to add it.
- Optional/pluggable capabilities today (`autoswitch`, `sound-vibration`, `notification` channels, `alarm`) already register through the slot instead of being hardwired, so they're the working example of the pattern, not placeholders.
- Failure handling is the GPU→CPU-render analogy exactly: `power/crash-isolation` wraps every slot call, so a failed/disconnected capability returns to its `bus/slots/fallback.kt`-registered default (e.g. `task-list` renders its own switch UI if `autoswitch` never attached or crashed) instead of taking the whole app down.
