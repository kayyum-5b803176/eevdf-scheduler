# Full-Screen Alarm Overlay (Notification Style Routing)

How a timer expiry decides between the full-screen alarm overlay
(`AlarmActivity`) and a plain banner notification, why the "textbook"
implementation of this is not enough on its own, and what actually made it
reliable.

---

## The Problem

The standard Android mechanism for "launch a full-screen UI over the lock
screen when something important happens" is a **full-screen intent**:

```kotlin
NotificationCompat.Builder(context, CHANNEL_ALARM)
    .setPriority(NotificationCompat.PRIORITY_MAX)
    .setCategory(NotificationCompat.CATEGORY_ALARM)
    .setFullScreenIntent(fullScreenPendingIntent, true)
```

This is exactly what AOSP Clock uses. Implementing it correctly — HIGH
channel importance, the `USE_FULL_SCREEN_INTENT` permission, `AlarmActivity`
calling `setShowWhenLocked(true)` / `setTurnScreenOn(true)` — got the
mechanism *right*, but it did not make it *reliable*. In practice the
platform can still silently decline to launch the activity and fall back to
a plain notification instead, for reasons outside app code's control:

- OEM background-launch restrictions (MIUI/ColorOS/OneUI-style extra gates
  on top of stock AOSP behavior).
- Battery/App Standby throttling of notification alerting while the app is
  "Optimized" rather than "Unrestricted".
- Platform-side rate limiting of full-screen intents on rapid repeat
  firings (observed directly: same decision inputs, different outcome,
  correlated with how recently the previous alarm fired).

None of this is visible from the notification API — the notification still
posts successfully either way. The only symptom is "sometimes the overlay
shows, sometimes it doesn't," with no exception, no callback, nothing to
catch.

---

## What Actually Fixed It

Chasing the platform's exact throttling rule was a dead end — it isn't
documented and isn't something app code can control. **The fix that
actually mattered was accepting that the primary mechanism can silently
fail, and adding a bounded, one-shot fallback that detects that and
recovers:**

```kotlin
// AlarmForegroundService, after posting the notification with a
// full-screen intent attached:
if (decision.attachFullScreenIntent) {
    Handler(Looper.getMainLooper()).postDelayed({
        if (isAlarmRinging && !AlarmActivity.isShowing) {
            // The platform declined to honor the full-screen intent.
            // One direct, best-effort attempt — may itself be blocked by
            // background-start restrictions, but costs nothing to try.
            startActivity(AlarmActivity.createIntent(this, taskName))
        }
    }, FULLSCREEN_FALLBACK_DELAY_MS) // 2s
}
```

This needed two prerequisites to even be possible:

1. **`AlarmActivity.isShowing`** — a simple flag set in `onCreate()` /
   cleared in `onDestroy()`. Without this, the service has no way to know
   whether the automatic launch actually worked.
2. **`AppForegroundTracker` excluding `AlarmActivity` from its own count** —
   a separate, earlier bug where the overlay's own presence was being
   counted as "the EEVDF app is in the foreground," which could suppress a
   *later* alarm shortly after the first one was dismissed (symptom: "works
   once, then never again"). Unrelated to the platform throttling, but had
   to be fixed first or the fallback would have been fighting the wrong
   bug.

Two smaller, still-real correctness fixes came along the way:

- **Foreground service type**: `mediaPlayback` → `specialUse`. The service
  doesn't run a `MediaSession`-backed player, so `mediaPlayback` was the
  wrong type and invites OS scrutiny (idle/no-session timeouts) that a
  plain alarm ringer doesn't need.
- **Wake lock**: kept as `FULL_WAKE_LOCK` + `ACQUIRE_CAUSES_WAKEUP`. AOSP
  Clock uses `PARTIAL_WAKE_LOCK` only and relies on the full-screen launch
  itself to wake the screen — but on this codebase's test devices, that
  assumption didn't hold (the platform declining the launch left the
  screen off entirely), which was a worse regression than the small
  theoretical wake/full-screen-intent-eligibility race `FULL_WAKE_LOCK`
  risks. Documented as a known trade-off, not resolved either way.

---

## Permission / Capability Groundwork

Necessary, but on their own **did not** fix the flakiness — they rule out
the "not granted at all" failure mode, which is a different, simpler
problem than the rate-limiting/OEM one above:

| Capability | Checked by | Symptom if missing |
|---|---|---|
| `POST_NOTIFICATIONS` | `AlarmReliabilityChecker.hasNotificationPermission` | No notification shows at all (Android 13+) |
| Full screen intents | `AlarmReliabilityChecker.canUseFullScreenIntent` | Full-screen intent silently downgrades to banner (Android 14+) |
| Alarms & reminders | `AlarmReliabilityChecker.hasExactAlarmPermission` | Timer fires late/inexact (Android 12+) |
| Battery optimization | `AlarmReliabilityChecker.isIgnoringBatteryOptimizations` | Alerting (incl. full-screen) can be throttled |
| Usage access | `AlarmReliabilityChecker.hasUsageStatsPermission` | Exclude App feature can never match |
| Display over other apps | `AlarmReliabilityChecker.hasOverlayPermission` | Bubble/auto-switch overlay feature can't draw |

All six are surfaced on one screen — Settings ▸ platform tab ▸
**Permissions** — each with live status and tap-to-fix, backed entirely by
`AlarmReliabilityChecker` so the UI and the service can never disagree
about what's actually granted.

---

## How It Works — Decision Flow

```
Timer expires
    │
    ▼
AppForegroundTracker.isAppInForeground?
    │                              │
   yes                             no
    │                              │
    ▼                              ▼
Suppress everything      ForegroundAppDetector.getForegroundPackage()
(in-app UI covers it)    (try/catch — failure degrades to "no match",
    │                     never takes down the alarm)
    │                              │
    │                              ▼
    │                     AlarmNotificationPolicy.decide(
    │                       appForeground, excludeAppMatch,
    │                       lockScreenOverlayEnabled
    │                     )
    │                              │
    │                              ▼
    │                     showExpiredNotification(
    │                       suppressBanner, attachFullScreenIntent
    │                     )
    │                              │
    │                    attachFullScreenIntent?
    │                              │
    │                             yes
    │                              ▼
    │                    Platform decides: launch full-screen
    │                    or downgrade to banner (its call, not ours)
    │                              │
    │                    Wait 2s ── AlarmActivity.isShowing?
    │                              │              │
    │                             yes             no
    │                              │              │
    │                          (done)     One fallback startActivity()
    │                                     attempt, best-effort
    ▼
(nothing shown)
```

`AlarmNotificationPolicy.decide()` is a pure function — no `Context`, no
Android APIs, no side effects — so the decision itself is unit-testable in
isolation from the service that acts on it.

---

## Key Files

```
platform/.../notification/
├── AlarmNotificationPolicy.kt      ← pure style decision (suppressBanner / attachFullScreenIntent)
├── AlarmReliabilityChecker.kt      ← single source of truth for all 6 permission checks
├── AppForegroundTracker.kt         ← excludes AlarmActivity from its own count (see above)
└── ForegroundAppDetector.kt        ← best-effort foreground-app read for Exclude App

feature/alarm/.../
├── AlarmForegroundService.kt       ← owns the decision call site, the notification post,
│                                      and the bounded fallback
└── AlarmActivity.kt                ← isShowing flag; setShowWhenLocked/setTurnScreenOn

feature/settings/.../
├── PermissionsActivity.kt          ← renders all 6 checks from AlarmReliabilityChecker
└── NotificationSettingsActivity.kt ← the two actual feature toggles only
                                       (Lock Screen Overlay, Exclude App)
```

---

## What Didn't Work (Ruled Out, For The Record)

So the next person debugging this doesn't re-walk the same path:

- **Manually pre-checking device-locked state** before attaching the
  full-screen intent (`KeyguardManager` / `PowerManager.isInteractive`).
  Removed — AOSP never does this either; the platform's own real-time
  check at post time is authoritative and a pre-check only adds a race.
- **`PARTIAL_WAKE_LOCK` only** (matching AOSP exactly). Regressed the app
  on this codebase's test devices — see wake lock note above.
- **A fixed "safe gap" between alarms** (5 minutes, then narrowed to
  "somewhere between 1–5 minutes"). Never a hard, reproducible constant;
  abandoned once the fallback made the exact threshold moot.
- **Assuming it was a permission problem** at several points along the
  way (Full Screen Intent access, Usage Access, Battery optimization).
  All three were real gaps worth closing, and are now in the Permissions
  page — but closing them individually did not fix the underlying
  flakiness. The fallback did.
