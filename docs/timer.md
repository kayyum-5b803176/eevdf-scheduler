# Timer System — State Machine

**Files:**
- `model/TimerState.kt` — sealed state machine + pure math helpers
- `model/TaskTimerExt.kt` — DB read/write bridge
- `scheduler/TimerEngine.kt` — CountDownTimer wrapper + LiveData emitter

---

## Overview

The timer system is split into three layers to avoid a common Android bug where timer state becomes inconsistent after a process kill:

```

  TaskTimerExt.kt                                        
  timerState    (Task → TimerState)   READ-ONLY gateway  
  withTimerState(Task, TimerState)    WRITE-ONLY gateway  

        ↕ sealed values only

  TimerState.kt                                          
  Pure math: elapsedMs, remainingMs, remainingSecs       
  Transitions: resume(), pause(), expire(), reset()      

        ↕ starts/stops CountDownTimer

  TimerEngine.kt                                         
  owns CountDownTimer, emits tickSeconds / expiredTask   

```

---

## TimerState — Sealed Class

### States

#### `Idle`
Timer has never started, or was fully reset.
- `accumulatedMs = 0`, `startTimeEpoch = 0`
- `isRunning = false`

#### `Paused(accumulatedMs)`
Timer was running and has been paused.
- `accumulatedMs` = total ms consumed across all sessions so far
- `startTimeEpoch = 0`
- `isRunning = false`
- Invariant: `accumulatedMs ≥ 0`

#### `Running(accumulatedMs, startTimeEpoch)`
Timer is actively counting down.
- `liveElapsedMs = accumulatedMs + (now − startTimeEpoch)`
- `remainingMs = sliceMs − liveElapsedMs`
- Invariant: `startTimeEpoch > 0`

#### `Expired(sliceMs)`
Slice fully consumed. Stored in DB as `Paused(sliceMs)` with `isRunning = false`.

---

### State Transitions

```
Idle     resume()  Running
Paused   resume()  Running   (carries forward accumulated ms)
Running  pause()  Paused
Running  expire()  Expired
any      reset()  Idle      (clears all accumulated ms)
```

No other transitions are legal. Any call site that bypasses these is a bug.

---

### Pure Math Functions (companion object)

All functions are stateless and take `nowMs` as an optional parameter (defaults to `System.currentTimeMillis()`). Safe to call from anywhere including tests with a fixed clock.

#### `elapsedMs(state, nowMs): Long`

THE central elapsed-time function. All other time calculations derive from this.

| State | Return |
|-------|--------|
| Idle | 0 |
| Paused | `state.accumulatedMs` |
| Running | `state.accumulatedMs + (nowMs − state.startTimeEpoch)` |
| Expired | `state.sliceMs` |

#### `remainingMs(state, sliceMs, nowMs): Long`

`(sliceMs − elapsedMs).coerceAtLeast(0)`. Never negative.

#### `remainingSecs(state, sliceSecs, nowMs): Long`

`remainingMs(state, sliceSecs × 1000, nowMs) / 1000`. For UI countdown display.

#### `progress(state, sliceMs, nowMs): Int`

`(elapsedMs × 100 / sliceMs).coerceIn(0, 100)`. For progress bars.

#### `isExpired(state, sliceMs, nowMs): Boolean`

`remainingMs == 0`.

---

### Transition Functions

#### `resume(state, nowMs): Running`

Converts Idle or Paused → Running. Carries forward any already-accumulated ms.
```kotlin
Running(
    accumulatedMs  = elapsedMs(state, nowMs),
    startTimeEpoch = nowMs
)
```

#### `pause(state, nowMs): Paused`

Snapshots elapsed ms at `nowMs`. Safe to call on already-Paused or Idle state.
```kotlin
Paused(elapsedMs(state, nowMs))
```

#### `reset(): Idle`

Full reset. Returns the `Idle` singleton.

#### `expire(sliceMs): Expired`

Marks slice complete.

---

## TaskTimerExt — DB Bridge

**Read gateway:**

```kotlin
val Task.timerState: TimerState
```

The **only** place in the codebase that reads `accumulatedMs` and `startTimeEpoch` directly from a `Task`. Reconstructs the sealed state:

| Condition | Returned State |
|-----------|---------------|
| `isCompleted = true` | `Expired(timeSliceSeconds × 1000)` |
| `startTimeEpoch > 0` | `Running(accumulatedMs, startTimeEpoch)` |
| `accumulatedMs > 0` | `Paused(accumulatedMs)` |
| otherwise | `Idle` |

**Write gateway:**

```kotlin
fun Task.withTimerState(newState: TimerState): Task
```

The **only** legal way to change timer columns on a Task. Updates all four columns atomically:
- `accumulatedMs`
- `startTimeEpoch`
- `isRunning`
- `remainingSeconds` (cached snapshot for adapters)

**Why this exists:** `task.copy(isRunning = false)` compiles but silently leaves `startTimeEpoch` stale, causing the timer to resume at the wrong offset after a pause. `withTimerState` makes that impossible.

---

## TimerEngine

### Responsibilities

- Own the single `CountDownTimer` for one active task.
- Re-derive remaining seconds from the epoch on every tick (immune to CountDownTimer drift).
- Emit `tickSeconds: LiveData<Long>` for the UI countdown label.
- Emit `expiredTask: LiveData<Task>` exactly once when the slice hits zero.

### Non-responsibilities (ViewModel's job)

- Database writes.
- EEVDF vruntime / virtualDeadline updates.
- Notifications, AlarmManager, sound, vibration.
- NOTIFICATION-type delay/wait phases.

### Idempotency rules

| Call | Condition | Behaviour |
|------|-----------|-----------|
| `start(task)` | Same task already running | No-op |
| `pause()` | No active timer | Returns `null` |
| `reset()` | No task loaded | Returns `null` |
| `clear()` | Any state | Always safe |

---

### `start(task)`

Starts or resumes the engine for a task. The task **must** already have correct epoch columns written via `task.withTimerState(TimerState.resume(…))` before this call.

1. Check idempotency (same task id already running → return).
2. Read `task.timerState`; if not `Running`, call `TimerState.resume()`.
3. Compute `remaining = TimerState.remainingMs(state, sliceMs)`.
4. Cancel any existing CountDownTimer.
5. Create a new CountDownTimer with `remaining` ms, 1000ms interval.
6. **On each tick:** re-derive `remainingSecs` from the epoch (not from `millisUntilFinished`) → post to `_tickSeconds`.
7. **On finish:** call `TimerState.expire(sliceMs)`, post expired task to `_expiredTask`.

---

### `pause(nowMs): Task?`

1. Stop the CountDownTimer.
2. Compute `paused = TimerState.pause(inMemoryState, nowMs)`.
3. Return `task.withTimerState(paused)` — caller is responsible for persisting to DB.

---

### `reset(): Task?`

1. Stop the CountDownTimer.
2. Set `inMemoryState = TimerState.Idle`.
3. Return `task.withTimerState(Idle)`.

---

### `restoreFromDb(task)`

Called from `ViewModel.init()` when the DB shows `isRunning = 1` after a process kill.

1. Read `task.timerState` (should be `Running`).
2. Compute remaining time.
3. If time remains → `attachTicker(task, remaining)` (re-attach normally).
4. If already elapsed → post to `_expiredTask` immediately (expired while app was dead).

**Does NOT reschedule AlarmManager** — the foreground service already did that.

---

### `clear()`

Hard-stop everything. Cancels CountDownTimer, nulls task reference, resets state to Idle. Always safe to call.

---

### LiveData outputs

| LiveData | Type | Emits |
|----------|------|-------|
| `tickSeconds` | `LiveData<Long>` | Remaining seconds on every 1-second tick |
| `expiredTask` | `LiveData<Task>` | The expired task exactly once when slice = 0 |

---

## Why epochs instead of CountDownTimer.millisUntilFinished

`CountDownTimer` accumulates small drift on each tick (each 1000ms callback fires slightly late). Over a 30-minute timer this drift can be several seconds. By anchoring on `System.currentTimeMillis()` and computing remaining from the epoch every tick, the display is always accurate regardless of system load or Doze delays.

---

## Related files

- `model/Task.kt` — fields: `accumulatedMs`, `startTimeEpoch`, `remainingSeconds`, `isRunning`
- `viewmodel/TaskViewModel.kt` — wires TimerEngine, calls `withTimerState` before `start()`
- `db/TaskRepository.kt` — persists `withTimerState()` results
- `docs/viewmodel.md` — how ViewModel orchestrates start/pause/skip/reset
