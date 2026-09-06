#!/usr/bin/env bash
#
# Deletions for v6.3.0 — Phase 3 (partial): reminder-notifier only.
#
# NOTE: countdown-timer and alarm-ringer are NOT part of this release.
# countdown-timer imports TaskViewModel (task-list-screen, Phase 4) and
# alarm-ringer imports HardwareKeyPrefs/NotificationPrefs (settings-screens,
# Phase 5) — both blocked until those capabilities exist. They stay in
# feature/task/timer and feature/alarm untouched. This also means the
# timer.expired -> alarm.ringing bus chain isn't wired end-to-end yet —
# reminder-notifier's SUBSCRIBES are declared but have no live publisher
# until those two land.

set -euo pipefail

echo "v6.3.0: removing old reminder-notifier source (now capabilities/reminder-notifier/)"
rm -rf platform/src/main/kotlin/com/eevdf/platform/notification

echo "v6.3.0: done."
