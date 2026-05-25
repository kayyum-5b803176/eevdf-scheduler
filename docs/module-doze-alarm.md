# Module: doze_immune_alarm

**Location:** `module/doze_immune_alarm/`  
**Purpose:** A self-contained, copy-paste-ready Android alarm module that fires reliably even in Doze mode — no special permissions required.

This module is extracted from the main app as a reusable component. It can be dropped into any Android project in under 10 minutes.

---

## The Problem It Solves

Standard Android timer implementations (`CountDownTimer`, `Handler.postDelayed`) stop firing when the screen has been off for more than ~30 seconds because Android's **Doze mode** defers all `Handler` callbacks to preserve battery.

| API | Screen on | Screen off < 30s | Doze (screen off > 30s) |
|-----|-----------|------------------|------------------------|
| `CountDownTimer` |  |  |  Deferred |
| `Handler.postDelayed` |  |  |  Deferred |
| `PARTIAL_WAKE_LOCK` alone |  |  |  Unreliable in Deep Doze |
| `AlarmManager.setExact()` |  |  |  Deferred |
| `setExactAndAllowWhileIdle()` |  |  |  Requires `SCHEDULE_EXACT_ALARM` on API 31+ (user must grant) |
| `AlarmManager.setAlarmClock()` |  |  |  **Always fires on time** |

`setAlarmClock()` is the only API that is:
- Fully exempt from Doze at all levels
- Visible in the status bar (clock icon)
- Not batched by the OS
- Available without extra permissions

This is the same API used by AOSP Clock, Google Calendar alerts, and Android system alarms.

---

## Files

```
doze_immune_alarm/
 DozeImmuneTimer.kt       ← schedule / cancel helper (Kotlin object)
 TimerAlarmReceiver.kt    ← BroadcastReceiver for AlarmManager callback
 README.md                ← quick-start
 integration_guide.md     ← full step-by-step integration
```

---

## DozeImmuneTimer

**Type:** Kotlin `object` (singleton)

### `schedule(context, label, remainingSeconds, showActivityClass?)`

Schedules a Doze-immune `AlarmManager.setAlarmClock()` to fire after `remainingSeconds`.

**Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `context` | `Context` | Application or service context |
| `label` | `String` | Payload forwarded to receiver/service (e.g. task name) |
| `remainingSeconds` | `Long` | Seconds from now until the alarm fires |
| `showActivityClass` | `Class<*>?` | Activity to open when user taps status-bar clock icon |

**Internals:**
```kotlin
triggerAtMs = now + remainingSeconds × 1000
receiverPi  = PendingIntent.getBroadcast(TimerAlarmReceiver, FLAG_IMMUTABLE)
showPi      = PendingIntent.getActivity(showActivityClass) or receiverPi if null
alarmManager.setAlarmClock(AlarmClockInfo(triggerAtMs, showPi), receiverPi)
```

Uses `REQUEST_CODE = 0xDEAD_A1A7` — stable across schedule/cancel calls to ensure the same `PendingIntent` is matched.

### `cancel(context)`

Cancels the pending alarm. Uses `FLAG_NO_CREATE` to get the existing `PendingIntent` — returns immediately if none exists (safe no-op).

```kotlin
val pi = buildReceiverPendingIntent(context, "", FLAG_NO_CREATE) ?: return
alarmManager(context).cancel(pi)
```

**Always call this on pause/stop.** Failing to cancel leaves the alarm in the system, which will fire even after the user paused.

---

## TimerAlarmReceiver

**Type:** `BroadcastReceiver`

Receives the `AlarmManager.setAlarmClock()` callback when the alarm fires.

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val label = intent.getStringExtra(DozeImmuneTimer.EXTRA_LABEL) ?: return
    context.startService(
        Intent(context, YourForegroundService::class.java).apply {
            action = YourForegroundService.ACTION_TIMER_EXPIRE
            putExtra(DozeImmuneTimer.EXTRA_LABEL, label)
        }
    )
}
```

**Uses `startService()` not `startForegroundService()`** — the foreground service is already running (started when the timer began). Calling `startForegroundService` on an already-foreground service causes a 5-second ANR timeout on some devices.

**Must be registered in AndroidManifest with `exported="false"`:**
```xml
<receiver android:name=".TimerAlarmReceiver" android:exported="false" />
```

---

## Integration Steps (Summary)

### 1. Copy files
Copy `DozeImmuneTimer.kt` and `TimerAlarmReceiver.kt` into your project. Change the package name at the top of each file. Replace `YourForegroundService` with your actual service class name.

### 2. Manifest
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<receiver android:name=".TimerAlarmReceiver" android:exported="false" />

<activity
    android:name=".AlarmActivity"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:excludeFromRecents="true"
    android:launchMode="singleInstance" />
```

### 3. Schedule on timer start
```kotlin
DozeImmuneTimer.schedule(context, task.name, remainingSeconds, MainActivity::class.java)
```

### 4. Cancel on pause or stop
```kotlin
DozeImmuneTimer.cancel(context)
```

### 5. Handle expiry in your foreground service

Use a boolean guard — both `CountDownTimer.onFinish()` and `TimerAlarmReceiver` can fire near the same time:

```kotlin
private var isAlarmRinging = false

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
        ACTION_TIMER_EXPIRE -> {
            if (!isAlarmRinging) {
                isAlarmRinging = true
                val label = intent.getStringExtra(DozeImmuneTimer.EXTRA_LABEL) ?: ""
                acquireWakeLock()
                playAlarmSound()
                showFullScreenNotification(label)
            }
        }
        ACTION_STOP -> {
            isAlarmRinging = false
            releaseWakeLock()
            stopSelf()
        }
    }
    return START_NOT_STICKY
}
```

### 6. Wake screen on Android 12+

`FULL_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` is deprecated on API 31+. Use `setFullScreenIntent` on the notification instead:

```kotlin
NotificationCompat.Builder(this, CHANNEL_ALARM)
    .setFullScreenIntent(alarmActivityPendingIntent, true)
    .setCategory(NotificationCompat.CATEGORY_ALARM)
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    .setPriority(NotificationCompat.PRIORITY_MAX)
    ...
```

In your `AlarmActivity.onCreate()`:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
} else {
    window.addFlags(FLAG_SHOW_WHEN_LOCKED or FLAG_TURN_SCREEN_ON)
}
window.addFlags(FLAG_KEEP_SCREEN_ON)
```

---

## Compatibility Table

| Android version | API | Doze mode | setAlarmClock() |
|----------------|-----|-----------|----------------|
| 5.x | 21–22 | No | Works (normal alarm) |
| 6.x – 11 | 23–30 | Yes | Fires exactly — immune to all Doze levels |
| 12 | 31–32 | Yes + stricter FGS | Fires exactly — no permission needed |
| 13+ | 33+ | Yes | Fires exactly — no permission needed |

---

## Common Mistakes

**Not calling `cancel()` on pause.**  
The alarm fires even after the user paused. Always pair every `schedule()` with a `cancel()` on pause/stop.

**Double-trigger without a guard.**  
`CountDownTimer.onFinish()` and `TimerAlarmReceiver` can fire within milliseconds of each other. Use the `isAlarmRinging` boolean to prevent playing sound twice.

**Calling `startForegroundService()` from the receiver.**  
The service is already foreground. Use `startService()` to send the action.

**Using `FLAG_UPDATE_CURRENT` instead of `FLAG_IMMUTABLE` on API 31+.**  
Android 31+ requires `FLAG_IMMUTABLE` or `FLAG_MUTABLE` on all `PendingIntent` calls. Missing this causes a crash on API 31+.

**Setting `exported="true"` on the receiver.**  
`TimerAlarmReceiver` only needs to receive intents from your own `AlarmManager` call. Keep it `exported="false"` to prevent external apps from triggering it.

---

## How This Module Is Used in the Main App

The main app builds on these concepts with additional layers:

| Module concept | Main app equivalent | Extra layer |
|---------------|-------------------|-------------|
| `DozeImmuneTimer.schedule()` | `AlarmScheduler.schedule()` | + `AlarmState.write(Scheduled)` before the call |
| `DozeImmuneTimer.cancel()` | `AlarmScheduler.cancel()` | + `AlarmState.write(Idle)` after |
| `TimerAlarmReceiver` | `TimerAlarmReceiver` + ghost guard | Checks `AlarmState == Scheduled` before proceeding |
| `isAlarmRinging` | `AlarmState.Ringing` | Persisted to disk (survives process death) |

The main app's `AlarmState` sealed class replaces the in-memory `isAlarmRinging` boolean with a disk-persisted state machine — essential for recovering from process death.

---

## Related files

- `ui/AlarmScheduler.kt` — main app's `AlarmManager` owner (extends these concepts)
- `ui/AlarmState.kt` — disk-persisted alarm state
- `docs/alarm-system.md` — complete alarm system documentation
