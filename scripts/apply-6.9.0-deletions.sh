#!/usr/bin/env bash
#
# Deletions for v6.9.0 — Phase 9: the last capabilities, and the end of the
# ownership-based module layout.
#
# Run AFTER verifying the app builds and these flows work end to end:
#   1. Settings screens open, every preference still applies.
#   2. Stats screens render.
#   3. Multi-user sync export/import.
#   4. Alarm rings on timer expiry (this phase replaced AlarmController's 6
#      command methods with bus topics — exercise start/pause/expire/stop).
#   5. Incoming call auto-switches and the bubble appears (OverlayController
#      replaced the same way).
#   6. Kill the app while an alarm is RINGING, reopen it — overrun recovery
#      must still work. That path uses AlarmRingingQuery, the one synchronous
#      query deliberately NOT turned into a bus topic.

set -euo pipefail

echo "v6.9.0: removing migrated settings / stats / sync sources"
rm -rf feature/src/main/settings
rm -rf feature/src/main/stats
rm -rf feature/src/main/sync
rm -rf data/src/main/kotlin/com/eevdf/data/sync
rm -rf data/src/test/kotlin/com/eevdf/data/sync

echo "v6.9.0: removing migrated alarm / autoswitch sources"
rm -rf feature/src/main/alarm
rm -rf feature/src/main/autoswitch

echo "v6.9.0: retiring :feature entirely (nothing left in it)"
rm -rf feature

echo "v6.9.0: retiring :platform entirely"
# media/ -> feedback-cues (v6.1.0), notification/ -> reminder-notifier (v6.3.0),
# scheduler/ -> task-scheduling (v6.2.0). alarm/AndroidAlarmPort.kt is NOT
# migrated: it had zero call sites anywhere in the project — dead code, same
# finding as DurationFormat.kt in v6.5.0. Deleted rather than carried forward.
rm -rf platform

echo "v6.9.0: retiring :core entirely"
# scheduler/ -> task-scheduling (v6.2.0), time/ -> kernel/clock (v6.2.0).
# platform/PlatformPorts.kt (AlarmPort/SoundPort/NotificationPort/VibrationPort)
# is NOT migrated: AlarmPort's only implementor was the dead AndroidAlarmPort,
# and the other three ports were replaced by bus topics across Phases 3-9.
# Verified zero remaining references before deleting.
rm -rf core

echo "v6.9.0: retiring :data entirely"
# task/ -> task-storage, runlog/ -> run-history, scheduler/ -> task-scheduling
# (all v6.2.0), backup/ -> backup-restore (v6.8.0), sync/ -> multi-device-sync
# (this phase).
rm -rf data

echo "v6.9.0: retiring contract/control's replaced files"
# AlarmController + OverlayController -> bus topics (Topics.ALARM_*_REQUESTED,
# Topics.OVERLAY_*_REQUESTED) with AlarmCommandHandler / OverlayCommandHandler
# as the subscribers. AlarmActions -> Topics.ALARM_STOPPED for the
# cross-capability signal; ACTION_STOP_ALARM is now a private constant inside
# alarm-ringer. RingingAlarm moved into AlarmRingingQuery.kt.
rm -f contract/src/main/kotlin/com/eevdf/contract/control/AlarmController.kt
rm -f contract/src/main/kotlin/com/eevdf/contract/control/OverlayController.kt
rm -f contract/src/main/kotlin/com/eevdf/contract/control/AlarmActions.kt

echo "v6.9.0: done."
echo ""
echo "Root now contains only: app/, contract/, kernel/, capabilities/, scripts/,"
echo "config/, build-logic/, docs/ — plus the Gradle files."
echo ""
echo ":contract survives holding ONE file, AlarmRingingQuery.kt — the single"
echo "documented synchronous-query exception to rule 3. Phase 10 folds it into"
echo "composition/ or a capability and retires the module."
