# Doze-Immune Alarm Timer for Android

A reliable alarm/timer implementation that fires on time regardless of Doze mode,
battery optimization, or OEM power management — using the same mechanism as AOSP Clock.

---

## The Problem

Most timer implementations use `CountDownTimer` or `Handler.postDelayed`.
These work while the screen is on, but Android's **Doze mode kicks in ~30 seconds
after the screen turns off** and defers all `Handler` callbacks — including your timer.

| Approach | Screen on | Screen off < 30s | Screen off > 30s (Doze) |
|---|---|---|---|
| `CountDownTimer` / `Handler` | Works | Works | **Deferred / silent** |
| `PARTIAL_WAKE_LOCK` alone | Works | Works | **Unreliable in Deep Doze** |
| `AlarmManager.setAlarmClock()` | Works | Works | **Always fires on time** |

`AlarmManager.setAlarmClock()` is specifically exempted from Doze by Android.
No special permission is required — it is the same API AOSP Clock uses.

---

## What Is Included

```
doze_immune_alarm/
├── README.md                       ← this file
├── TimerAlarmReceiver.kt           ← AlarmManager callback receiver
├── DozeImmuneTimer.kt              ← drop-in helper (schedule / cancel)
└── integration_guide.md            ← step-by-step integration
```

---

## How It Works

```
User taps Start
    │
    ├─► CountDownTimer          (drives UI countdown display)
    │
    └─► AlarmManager            (Doze-immune guaranteed trigger)
            .setAlarmClock()
                │
                │  screen off for 35 minutes...
                │
                ▼
        TimerAlarmReceiver.onReceive()
                │
                ▼
        YourForegroundService   (play sound, wake screen, show AlarmActivity)
```

The two paths are independent. Whichever fires first rings the alarm;
the second is ignored via an `isAlarmRinging` guard.

---

## Quick Integration

### 1. Add TimerAlarmReceiver to your manifest

```xml
<receiver
    android:name=".TimerAlarmReceiver"
    android:exported="false" />
```

### 2. Schedule on timer start

```kotlin
DozeImmuneTimer.schedule(context, taskName, remainingSeconds)
```

### 3. Cancel on pause or stop

```kotlin
DozeImmuneTimer.cancel(context)
```

### 4. Handle expiry in your foreground service

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_TIMER_EXPIRE && !isAlarmRinging) {
        playAlarmSound()
        wakeScreen()
    }
    return START_NOT_STICKY
}
```

See `integration_guide.md` for the full walkthrough.
