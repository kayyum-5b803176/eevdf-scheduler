#!/usr/bin/env bash
#
# Deletions for v6.17.0 — capabilities/reminder-notifier/ renamed to
# capabilities/notification/ (package com.eevdf.capabilities.remindernotifier
# -> com.eevdf.capabilities.notification). Every real import, project(...)
# dependency, and settings.gradle.kts entry was already repointed at the new
# module as part of this version's zip; this script only removes the
# now-superseded old folder.
#
# Run this against your local checkout, after extracting v6.17.0's zip on
# top of it — the new capabilities/notification/ files need to already be in
# place before the old capabilities/reminder-notifier/ is removed.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting capabilities/reminder-notifier/ (renamed to capabilities/notification/, v6.17.0)..."

rm -rf capabilities/reminder-notifier

echo "Done. capabilities/notification/ is the one notification-related capability in the app."
