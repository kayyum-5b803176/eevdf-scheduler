#!/usr/bin/env bash
#
# Deletions for v6.14.0 — capabilities/feedback-cues/ retired entirely,
# replaced by two fully independent capabilities: capabilities/sound/ and
# capabilities/vibration/ (see that version's changelog for why they were
# split rather than just renamed — SoundManager/VibrationManager are now
# reachable ONLY via the kernel event bus, never a direct import of one
# another). Every real import, project(...) dependency, and
# settings.gradle.kts entry was already repointed at the two new modules as
# part of this version's zip; this script only removes the now-superseded
# old folder.
#
# Run this against your local checkout, after extracting v6.14.0's zip on
# top of it — the new capabilities/sound/ and capabilities/vibration/ files
# need to already be in place before the old capabilities/feedback-cues/ is
# removed.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting capabilities/feedback-cues/ (replaced by sound/ + vibration/, v6.14.0)..."

rm -rf capabilities/feedback-cues

echo "Done. capabilities/sound/ and capabilities/vibration/ are the two"
echo "independent capabilities that replace it, communicating only via the"
echo "kernel event bus."
