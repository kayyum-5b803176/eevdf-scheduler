# Alarm System

**Files:**
- `ui/AlarmState.kt` — sealed alarm state + disk persistence
- `ui/AlarmScheduler.kt` — sole AlarmManager owner
- `ui/AlarmForegroundService.kt` — foreground service (sound, WakeLock, notification)
- `ui/TimerAlarmReceiver.kt` — BroadcastReceiver for alarm fires
- `ui/AlarmStopReceiver.kt` — notification "Stop" action handler
- `ui/AlarmActivity.kt` — full-screen alarm UI

---

## Design Principles

1. **Single ownership:** `AlarmScheduler` is the **only** class that calls `AlarmManager`. No other class — not the ViewModel, not the service, not `onCleared()` — may schedule or cancel alarms.

2. **State before action:** `AlarmState.write()` is called **before** the `AlarmManager` call. If the process dies between the two operations, the state on disk is correct and the receiver will handle the alarm correctly on next start.

3. **Ghost-alarm guard:** `TimerAlarmReceiver` checks `AlarmState.read()` before waking the app. If the state is not `Scheduled`, the alarm is a ghost (e.g., cancelled but already queued) and is silently dropped.

4. **No cancel in onDestroy:** `AlarmForegroundService.onDestroy()` releases sound and WakeLock only. It must NOT cancel the alarm. Cancelling in `onDestroy` would silently remove the alarm on process death — the exact bug that caused random alarm disappearance.

---

## AlarmState — Sealed Class

### States

#### `Idle`
No alarm scheduled. `AlarmManager` has no pending entry.

#### `Scheduled(taskName, triggerEpoch, taskType)`
A Doze-immune alarm is pending.
- `triggerEpoch = System.currentTimeMillis() + remainingSecs × 1000`
- Written **before** the `AlarmManager.setAlarmClock()` call.

#### `Ringing(taskName, taskType, firedEpoch)`
The alarm fired and the foreground service is active.
- `AlarmManager` entry is gone (it already fired).
- `firedEpoch` = epoch ms when `onAlarmFired()` ran. Used to compute overrun elapsed time.

### State Machine

```
Idle  schedule()  Scheduled  onAlarmFired()  Ringing
                                                          
 cancel()                               
 stop()
```

All transitions go through `AlarmScheduler`.

### Disk persistence

Stored in `SharedPreferences("eevdf_alarm_state_v2")`.

| Key | Values |
|-----|--------|
| `state` | `"IDLE"` / `"SCHEDULED"` / `"RINGING"` |
| `task_name` | Task display name |
| `trigger_epoch` | Long — target fire time (Scheduled only) |
| `task_type` | `"DEFAULT"` / `"ALARM"` / `"NOTIFICATION"` |
| `fired_epoch` | Long — when alarm actually fired (Ringing only) |

`AlarmState.write()` uses **`commit()`** (synchronous), not `apply()`. The state on disk must be authoritative before the function returns so that a crash immediately after is recoverable.

`AlarmState.read()` is safe to call from any process or thread.

---

## AlarmScheduler

**File:** `ui/AlarmScheduler.kt`

The sole owner of `AlarmManager` interactions. Every call to `AlarmManager.setAlarmClock()` or `AlarmManager.cancel()` in the entire app goes through this class.

### `schedule(context, taskName, remainingSecs, taskType)`

1. Compute `triggerEpoch = now + remainingSecs × 1000`.
2. `AlarmState.write(context, Scheduled(taskName, triggerEpoch, taskType))` — write state FIRST.
3. `AlarmManager.setAlarmClock(AlarmClockInfo(triggerEpoch, pendingIntent), pendingIntent)`.

Uses `setAlarmClock()` because it's the only API that fires in Doze mode, is visible in the system clock indicator, and cannot be batched by the OS.

### `cancel(context)`

1. `AlarmManager.cancel(pendingIntent)` — remove from system.
2. `AlarmState.write(context, Idle)` — clear state.

### `onAlarmFired(context): Boolean`

Called from `TimerAlarmReceiver`. Ghost-alarm guard:

1. `AlarmState.read(context)` — check current state.
2. If state is NOT `Scheduled`: return `false` (ghost — drop silently).
3. If state IS `Scheduled`:
   - `AlarmState.write(context, Ringing(taskName, taskType, now))`.
   - Return `true` — tells the receiver to start `AlarmForegroundService`.

### `stop(context)`

Called when the user dismisses the alarm:
1. `AlarmState.write(context, Idle)`.
2. Optionally cancel any lingering `AlarmManager` entry (in case it hasn't fired yet).

---

## AlarmForegroundService

**File:** `ui/AlarmForegroundService.kt`  
**Lifecycle:** `START_NOT_STICKY` — not restarted if Android kills it.

### Actions

| Action constant | Trigger | Effect |
|----------------|---------|--------|
| `ACTION_TIMER_START` | `timerStart()` | Show countdown notification |
| `ACTION_DELAY_START` | `startDelayPhase()` | Show delay-phase notification |
| `ACTION_TIMER_PAUSE` | `timerPause()` | Remove notification, stop self |
| `ACTION_TIMER_EXPIRE` | `TimerAlarmReceiver` | Acquire WakeLock, play alarm sound, show expired notification |
| `ACTION_STOP` | User tap / `stopAlarmSound()` | Stop sound, release WakeLock, stop self |

### Public API (static companion functions)

All must be called from the ViewModel — not from other services or receivers.

#### `timerStart(context, taskName, remainingSecs, taskType)`
1. `AlarmScheduler.schedule(ctx, taskName, remainingSecs, taskType)` — schedule Doze-immune alarm.
2. `send(ctx, ACTION_TIMER_START, taskName, remainingSecs, taskType)` — start foreground service.

#### `timerPause(context)`
1. `AlarmScheduler.cancel(ctx)` — cancel alarm.
2. `send(ctx, ACTION_TIMER_PAUSE, ...)` — stop foreground service.

#### `timerExpire(context, taskName, taskType)`
Called only by `TimerAlarmReceiver` after `AlarmScheduler.onAlarmFired()` returns `true`.  
**Do not call from ViewModel** — it bypasses the ghost-alarm guard.

#### `timerStop(context)`
Convenience: sends `ACTION_STOP`.

### WakeLock

Acquired in `ACTION_TIMER_EXPIRE` handling with a 1-hour timeout tag `"EEVDFScheduler:AlarmWake"`. Ensures the device screen and CPU stay on to ring the alarm even after deep sleep.

Released in `ACTION_STOP` and `onDestroy`.

### Notification channels

| Channel ID | Purpose |
|------------|---------|
| `eevdf_timer_fg_channel` | Countdown notification |
| `eevdf_delay_fg_channel` | Delay phase notification |
| `eevdf_alarm_fg_channel` | Expired / alarm notification (high importance) |

All channels registered in `NotificationHelper.kt`.

### onDestroy

**Releases:** sound, WakeLock.  
**Does NOT cancel:** `AlarmManager`. Cancelling here would silently remove the alarm on process death — the root cause of the "alarm disappears" bug.

---

## TimerAlarmReceiver

**File:** `ui/TimerAlarmReceiver.kt`  
Receives the `AlarmManager` broadcast when the Doze-immune alarm fires.

1. `AlarmScheduler.onAlarmFired(context)`:
   - Returns `false` → ghost alarm, do nothing.
   - Returns `true` → proceed.
2. Extract `taskName`, `taskType` from the intent extras.
3. `AlarmForegroundService.timerExpire(context, taskName, taskType)` — start the foreground service in ringing state.

---

## AlarmStopReceiver

**File:** `ui/AlarmStopReceiver.kt`  
Handles the "Stop" action button tap in the expanded notification.

1. `AlarmScheduler.stop(context)` — clear alarm state.
2. `AlarmForegroundService.timerStop(context)` — stop the service.

---

## AlarmActivity

**File:** `ui/AlarmActivity.kt`  
Full-screen alarm UI shown over the lock screen.

- Uses `setShowWhenLocked(true)` + `setTurnScreenOn(true)` to display over lock screen.
- Shows task name, elapsed overrun time.
- "Stop" button calls `viewModel.stopAlarmSound()`.
- Launched by the foreground service notification's pending intent.

---

## Process-Death Recovery

When the app is killed while a timer is running:

1. `AlarmManager` is in the **system process** — unaffected by app death. It will fire at the scheduled time.
2. `TimerAlarmReceiver` wakes the app.
3. `AlarmScheduler.onAlarmFired()` checks `AlarmState` (which was written to disk before the kill).
4. `AlarmForegroundService.timerExpire()` starts, acquires WakeLock, plays sound.
5. Next time the user opens the app, `ViewModel.init()` sees `isRunning = 1` and calls `TimerEngine.restoreFromDb()` to re-attach the countdown (or post as expired if time already elapsed).

---

## Related files

- `ui/AutoSwitchPrefs.kt` — separate prefs file for call-detection settings
- `viewmodel/TaskViewModel.kt` — calls `timerStart`, `timerPause`, `stopAlarmSound`
- `model/AlarmState.kt` — sealed state definition and disk persistence
- `docs/viewmodel.md` — how ViewModel drives the alarm lifecycle
