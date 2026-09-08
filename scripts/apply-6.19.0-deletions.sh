#!/usr/bin/env bash
#
# Deletions for v6.19.0 — AppForegroundTracker extracted to a new,
# genuinely generic capability: capabilities/app-foreground/. It used to
# live inside capabilities/alarm-ringer/ with an AlarmActivity-specific
# exclusion baked in; that exclusion is now handled locally by alarm-ringer's
# own new AlarmOverlayTracker instead, and the extracted tracker publishes
# Topics.APP_FOREGROUND_CHANGED (retained) rather than exposing a directly
# readable property — any capability can read it via the bus now, not just
# alarm-ringer.
#
# Every real import, project(...) dependency, and settings.gradle.kts entry
# was already repointed as part of this version's zip — this script only
# removes the now-superseded old file path.
#
# Run this against your local checkout, after extracting v6.19.0's zip on
# top of it — capabilities/app-foreground/ needs to already be in place
# first.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the old alarm-ringer-local AppForegroundTracker (v6.19.0)..."

rm -f capabilities/alarm-ringer/src/main/kotlin/com/eevdf/capabilities/alarmringer/app-foreground-tracker.kt

echo "Done. capabilities/app-foreground/ is the one AppForegroundTracker in the app."
