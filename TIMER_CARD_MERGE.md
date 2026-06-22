# Timer card merge + bug fixes (v4.0.0)

## Summary

The former two cards — `cardTimer` (running countdown) and `cardAlarmBanner`
(expired/alarm) — are now a single `CardView` whose entire appearance is driven
by one `TimerCardAction` value through one observer. Three latent bugs in the
old two-card design are fixed, and the selected card now persists across reboot
and app re-open.

## Files changed

- `app/src/main/kotlin/com/eevdf/app/feature/task/timer/TimerCardAction.kt`
  Added `Hidden` (no task → card removed) and `Expired(taskName, elapsedSeconds)`
  (red alarm view) states, plus `cardHidden` / `isExpiredAlarm` flags.

- `app/src/main/kotlin/com/eevdf/app/feature/task/TaskViewModel.kt`
  - `timerCardAction` mediator now `addSource`s `_alarmTaskName` and
    `_alarmElapsedSeconds` and derives `Expired` / `Hidden` (Bug 2).
  - `setCurrentTask()` resets notice state and clears any live alarm before
    seating a task (Bug 1), and persists the selection.
  - Genuine deselection paths (delete / skip / complete / hold-to-deselect)
    clear the persisted id; expiry paths keep it (requirement 3).
  - Startup recovery gained "Step 3": re-seat the persisted last-selected task.
  - New facade: `setCardManuallyHidden`, `getCardManuallyHidden`,
    `restorePersistedSelection`.

- `app/src/main/kotlin/com/eevdf/app/feature/task/notice/TaskNoticeStateMachine.kt`
  `triggerAlarmExpire()` persists the expired task id and stores its reset task.

- `app/src/main/kotlin/com/eevdf/app/feature/settings/TaskSettingsDelegate.kt`
  Persists `selected_timer_task_id` and `timer_card_manually_hidden`.

- `app/src/main/kotlin/com/eevdf/app/feature/task/MainActivity.kt`
  - Single `renderTimerCard()` owns all card visibility / tint / content
    (Bug 3 — removed the hand-rolled `cardTimer.visibility = GONE` hack).
  - `currentTask` observer no longer touches visibility.
  - Manual-hide flag restored on startup and persisted on the key1-hold toggle;
    the toggle refuses to hide a ringing alarm.
  - Removed the obsolete `cardAlarmBanner` field and observer.

- `app/src/main/res/layout/activity_main.xml`
  Merged both cards into one `CardView` (`cardTimer`) hosting two child layouts
  (`layoutTimerContent`, `layoutAlarmContent`) inside a `FrameLayout`.

## Bug fixes

1. Stale `NoticePhase.Expired` no longer locks the button to "—" when a
   just-expired NOTIFICATION task is re-selected.
2. The alarm can no longer be visible while the button simultaneously reports
   an actionable Start/Pause — alarm state is part of the same atomic derivation.
3. Mutual exclusivity is now structural (one card, one observer) instead of an
   imperative UI side-effect.

## Persistence behaviour

- The timer card never auto-closes on expiry; it stays seated on the task,
  showing the expired/alarm state.
- The card only closes when the user closes it by hand (key1 hold), or on a
  genuine deselection (delete / skip / complete / hold-to-deselect).
- Across reboot / app re-open: the last-selected task and the open/closed state
  are both restored.
