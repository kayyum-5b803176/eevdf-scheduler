Full redesign — kernel/capabilities/bus vocabulary, QNX-style isolation (supervisor restarts or kills a crashed capability, never the whole system) plus Home-Assistant-style manifest+event model (every capability declares what it publishes/subscribes, and has an "unavailable" fallback instead of crashing callers). Every filename below is renamed to say what it actually does, not what pattern it follows.

```
eevdf-scheduler/
│
├── kernel/                          ← the microkernel: minimal, trusted,
│   │                                  never itself pluggable, never restarted
│   ├── event-bus/
│   │   ├── bus.kt                    publish(topic, payload), subscribe(topic, handler)
│   │   └── topics.kt                 registry of every known topic name
│   │                                 (e.g. "timer.expired", "alarm.ringing",
│   │                                  "alarm.stopped", "task.saved")
│   ├── supervisor/                   ← QNX process-manager equivalent
│   │   ├── supervisor.kt             attach(capability), detach(id), restart(id)
│   │   └── health-monitor.kt         watchdog — detects a hung/crashed
│   │                                 capability, marks it "unavailable"
│   │                                 instead of letting the crash propagate
│   ├── clock/
│   │   └── clock.kt                  now() — the one primitive every
│   │                                 capability needs, none reimplements
│   └── identity/
│       └── kernel-version.kt
│
├── capabilities/                    ← "drivers" (QNX) / "integrations" (Home
│   │                                  Assistant) — isolated, replaceable,
│   │                                  each folder self-contained with a
│   │                                  manifest.kt declaring PUBLISHES /
│   │                                  SUBSCRIBES / fallback-when-unavailable
│   │
│   ├── task-scheduling/              [was compute/eevdf-scheduler + rt-scheduler]
│   │   ├── manifest.kt
│   │   ├── fairness/
│   │   │   ├── share-ledger.kt       (was CpuShares.kt)
│   │   │   ├── vruntime.kt           (was SchedTask.kt)
│   │   │   └── rank-tasks.kt         (was EevdfScheduler.kt)
│   │   ├── realtime-window/
│   │   │   ├── window-config.kt      (was RtConfig.kt)
│   │   │   ├── budget.kt             (was DlBudget/QuotaBudget)
│   │   │   └── is-window-open.kt     (was RtPolicy.kt)
│   │   └── task-schedule-bridge.kt   (was SchedulerFacade.kt)
│   │
│   ├── task-storage/                 [was memory/task]
│   │   ├── manifest.kt
│   │   ├── task-record.kt            (was Task.kt)
│   │   ├── task-table.kt             (was TaskDao/TaskDatabase)
│   │   ├── save-task.kt              (was TaskRepository.kt)
│   │   └── recover-on-crash.kt       (was migration/regression tests)
│   │
│   ├── run-history/                  [was memory/runlog]
│   │   ├── manifest.kt
│   │   ├── session-log.kt            (was RunSession/RunLogEntry)
│   │   ├── record-session.kt         (was RunLogRepository.kt)
│   │   └── summarize.kt              (was daily/monthly summaries)
│   │
│   ├── backup-restore/               [was memory/backup]
│   │   ├── manifest.kt
│   │   ├── export-all.kt
│   │   ├── import-all.kt
│   │   └── verify-round-trip.kt
│   │
│   ├── countdown-timer/              [was timing/timer]
│   │   ├── manifest.kt               PUBLISHES: timer.expired
│   │   ├── tick.kt                   (was TimerEngine.kt)
│   │   ├── interrupt.kt              (was InterruptDelegate.kt)
│   │   └── publish-expiry.kt         bus.publish("timer.expired", taskId)
│   │
│   ├── alarm-ringer/                 [was timing/alarm]
│   │   ├── manifest.kt               SUBSCRIBES: timer.expired,
│   │   │                              realtime-window.expired
│   │   │                             PUBLISHES: alarm.ringing, alarm.stopped
│   │   ├── schedule-wake.kt          (was AlarmScheduler.kt)
│   │   ├── ringing-state.kt          (was AlarmState.kt)
│   │   ├── ring-screen.kt            (was AlarmActivity.kt)
│   │   ├── keep-alive-service.kt     (was AlarmForegroundService.kt)
│   │   ├── on-timer-expired.kt       (was TimerAlarmReceiver.kt)
│   │   └── on-stop-request.kt        (was AlarmStopReceiver.kt)
│   │
│   ├── reminder-notifier/            [was io/notification]
│   │   ├── manifest.kt               SUBSCRIBES: alarm.ringing,
│   │   │                              alarm.stopped, timer.expired
│   │   ├── post-reminder.kt          (was NotificationHelper.kt)
│   │   ├── cancel-reminder.kt
│   │   ├── reminder-record.kt        (was NotificationRecord)
│   │   ├── dedupe-expired.kt
│   │   ├── channel-quota.kt
│   │   ├── channel-permission.kt
│   │   └── survive-doze.kt           (was the doze-immune pattern)
│   │
│   ├── feedback-cues/                [was io/sound-vibration]
│   │   ├── manifest.kt               SUBSCRIBES: alarm.ringing, alarm.stopped
│   │   ├── play-sound.kt             (was SoundManager.kt)
│   │   └── play-vibration.kt         (was VibrationManager.kt)
│   │
│   ├── call-autoswitch/              [was io/autoswitch]
│   │   ├── manifest.kt               SUBSCRIBES: phone.call-state-changed
│   │   │                             PUBLISHES: overlay.shown
│   │   ├── on-call-state-changed.kt
│   │   ├── switch-active-task.kt
│   │   └── show-bubble.kt
│   │
│   ├── task-list-screen/             [was io/task-list]
│   │   ├── manifest.kt               SUBSCRIBES: timer.expired,
│   │   │                              alarm.ringing, overlay.shown,
│   │   │                              task.saved
│   │   ├── list-view-model.kt
│   │   ├── build-list.kt / sort-list.kt / crud-actions.kt
│   │   ├── on-startup-recover.kt      fallback when a subscribed
│   │   │                             capability never answers on boot
│   │   ├── render-rows.kt
│   │   └── main-screen.kt
│   │
│   ├── add-task-screen/              [was io/task-addtask]
│   ├── group-picker/                 [was io/task-group]
│   ├── notice-phase/                 [was io/task-notice]
│   ├── settings-screens/             [was io/settings]
│   ├── stats-screens/                [was io/stats]
│   ├── links-screen/                 [was io/links]
│   ├── design-system/                [was io/ui]
│   │
│   ├── multi-device-sync/            [was bus/sync]
│   │   ├── manifest.kt               SUBSCRIBES: task.saved
│   │   │                             PUBLISHES: task.conflict-detected
│   │   ├── field-access-policy.kt    (was TaskFieldClassification.kt)
│   │   ├── resolve-conflict.kt
│   │   └── push-pull.kt
│   │
│   ├── navigation-routes/            [was bus/contract-nav]
│   ├── feature-toggles/              [was power/feature-flags]
│   └── crash-guard/                  [was power/crash-isolation]
│       ├── manifest.kt
│       └── run-isolated.kt           what the supervisor wraps around
│                                     every capability call
│
└── composition/                     ← wires kernel + capabilities together,
    │                                  computes nothing itself
    ├── boot/
    │   └── application-entry.kt      (was SchedulerApplication.kt)
    ├── capability-bindings.kt        the full list of which capabilities
    │                                 exist — adding one is one new line here
    └── build/
        └── build-conventions.kt
```

**How isolated failure actually works here (the QNX/Home-Assistant part):** every capability is started by `kernel/supervisor.kt`, wrapped by `crash-guard/run-isolated.kt`. If `reminder-notifier` throws or hangs, `health-monitor.kt` marks it **unavailable** on the bus — `alarm-ringer` still publishes `alarm.ringing`, nothing crashes, the notification just silently doesn't appear (exactly like a Home Assistant integration going "unavailable" while the rest of the dashboard keeps working, or a QNX driver dying while the OS keeps running). Each `manifest.kt` is the single place that says what a capability needs to keep functioning without its optional dependencies.
