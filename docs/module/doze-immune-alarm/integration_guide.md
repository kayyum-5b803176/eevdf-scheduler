# Integration Guide — Doze-Immune Alarm Timer

## Prerequisites

- Minimum SDK 23 (Android 6.0) — Doze mode was introduced in API 23
- A foreground service already running during the timer countdown
- `FOREGROUND_SERVICE` permission in your manifest

---

## Step 1 — Copy the files

Copy these three files into your project, changing the package name at the top of each:

```
DozeImmuneTimer.kt
TimerAlarmReceiver.kt
```

In `TimerAlarmReceiver.kt`, replace `YourForegroundService` with your actual service class.

---

## Step 2 — Update AndroidManifest.xml

Register the receiver. No extra permissions are needed for `setAlarmClock()`.

```xml
<manifest>

    <!-- Required for foreground service (you likely already have this) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <!-- Required to keep screen on at alarm time -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application>

        <!-- Register the AlarmManager receiver -->
        <receiver
            android:name=".TimerAlarmReceiver"
            android:exported="false" />

        <!-- Your existing foreground service -->
        <service
            android:name=".YourForegroundService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />

    </application>
</manifest>
```

---

## Step 3 — Schedule on timer start

Call `DozeImmuneTimer.schedule()` at the same time you start your `CountDownTimer`.

```kotlin
// In your ViewModel or wherever you start the timer:
fun startTimer() {
    val task = currentTask ?: return
    val remainingSeconds = task.remainingSeconds

    // 1. Start your UI countdown (for display only)
    countDownTimer = object : CountDownTimer(remainingSeconds * 1000L, 1000L) {
        override fun onTick(ms: Long) { /* update UI */ }
        override fun onFinish() { onTimerFinished() }
    }.start()

    // 2. Schedule the Doze-immune backup trigger
    DozeImmuneTimer.schedule(
        context       = getApplication(),
        label         = task.name,
        remainingSeconds = remainingSeconds,
        showActivityClass = MainActivity::class.java   // optional
    )
}
```

---

## Step 4 — Cancel on pause or stop

```kotlin
fun pauseTimer() {
    countDownTimer?.cancel()
    DozeImmuneTimer.cancel(context)
}

fun stopTimer() {
    countDownTimer?.cancel()
    DozeImmuneTimer.cancel(context)
    stopAlarmSound()
}
```

---

## Step 5 — Handle expiry in your foreground service

Both `CountDownTimer.onFinish()` and `TimerAlarmReceiver` can fire the expiry.
Use a boolean flag to prevent double-triggering.

```kotlin
class YourForegroundService : Service() {

    companion object {
        const val ACTION_TIMER_EXPIRE = "your.app.TIMER_EXPIRE"
    }

    private var isAlarmRinging = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_TIMER_EXPIRE -> {
                // Guard: AlarmManager and CountDownTimer can both fire
                if (!isAlarmRinging) {
                    isAlarmRinging = true
                    val label = intent.getStringExtra(DozeImmuneTimer.EXTRA_LABEL) ?: ""
                    onTimerExpired(label)
                }
            }

            ACTION_STOP -> {
                isAlarmRinging = false
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    private fun onTimerExpired(label: String) {
        acquireWakeLock()       // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP (pre-Android 12 fallback)
        playAlarmSound()
        showAlarmNotification(label)    // notification with setFullScreenIntent
    }
}
```

---

## Step 6 — Wake the screen reliably on Android 12+

`FULL_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` is deprecated on Android 12.
The modern replacement is `setFullScreenIntent` on your alarm notification combined
with `setTurnScreenOn(true)` in your alarm Activity.

```kotlin
// In your alarm notification builder:
val alarmActivityIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, AlarmActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        putExtra("label", label)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

NotificationCompat.Builder(this, CHANNEL_ALARM)
    .setSmallIcon(R.drawable.ic_alarm)
    .setContentTitle("Timer expired")
    .setContentText(label)
    .setPriority(NotificationCompat.PRIORITY_MAX)
    .setCategory(NotificationCompat.CATEGORY_ALARM)
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    .setFullScreenIntent(alarmActivityIntent, true)   // ← wakes screen on Android 12+
    .setOngoing(true)
    .build()
```

```kotlin
// In your AlarmActivity.onCreate():
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
} else {
    @Suppress("DEPRECATION")
    window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
    )
}
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

Also declare these on your AlarmActivity in the manifest:

```xml
<activity
    android:name=".AlarmActivity"
    android:exported="false"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:excludeFromRecents="true"
    android:launchMode="singleInstance" />
```

And add the permission:

```xml
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```

---

## Compatibility

| Android version | Doze introduced | setAlarmClock() behavior |
|---|---|---|
| 5.x (API 21–22) | No Doze | Works (normal alarm) |
| 6–11 (API 23–30) | Yes | Fires exactly, immune to Doze |
| 12 (API 31–32) | Yes + stricter FGS rules | Fires exactly, no permission needed |
| 13+ (API 33+) | Yes | Fires exactly, no permission needed |

---

## Common Mistakes

**Forgetting to cancel the AlarmManager when the timer is paused.**
If you don't cancel, the alarm fires even after the user paused.
Always pair `schedule()` with `cancel()` on pause/stop.

**Not guarding against double-trigger.**
Both `CountDownTimer.onFinish()` and `TimerAlarmReceiver` will fire near the same time.
Without the `isAlarmRinging` guard, the sound starts twice.

**Using `startForegroundService()` from the receiver.**
The foreground service is already running (it started when the timer began).
Use `startService()` instead — calling `startForegroundService()` on an already-foreground
service causes a 5-second ANR timeout on some devices.

**Registering the broadcast receiver with `android:exported="true"`.**
`TimerAlarmReceiver` only needs to receive intents from your own `AlarmManager` call.
Keep it `exported="false"`.
