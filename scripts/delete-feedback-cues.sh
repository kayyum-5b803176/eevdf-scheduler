#!/usr/bin/env bash
#
# Deletes the retired `feedback-cues` capability, now fully replaced by two
# independent, bus-only capabilities: `sound` and `vibration`.
#
# This can't be done by the changelog zip alone — a zip can only ADD files
# into your checkout, never remove them. Run this script from your project
# root (the directory containing settings.gradle.kts) AFTER extracting the
# zip's other files on top of your checkout.
#
# What it does:
#   1. Removes the capabilities/feedback-cues/ directory entirely.
#   2. Verifies no remaining file still references it (belt-and-suspenders —
#      every other file that used to depend on it was already updated as
#      part of this change; this just double-checks nothing was missed).
#
# What it does NOT do: touch git. Review `git status` / `git diff` afterward
# and commit yourself.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ ! -f "settings.gradle.kts" ]; then
    echo "error: run this from the project root (settings.gradle.kts not found here: $REPO_ROOT)" >&2
    exit 1
fi

if [ ! -d "capabilities/feedback-cues" ]; then
    echo "capabilities/feedback-cues already removed — nothing to do."
    exit 0
fi

echo "Removing capabilities/feedback-cues/ ..."
rm -rf "capabilities/feedback-cues"

echo ""
echo "Checking for any remaining live references (manifest.kt narrative"
echo "comments and test fixture strings that just use the old name as an"
echo "example capabilityId are expected and fine to see below):"
echo ""
grep -rn "feedback-cues\|feedbackcues\|AlarmCueHandler" \
    --include="*.kt" --include="*.kts" . 2>/dev/null || echo "  (none found)"

echo ""
echo "Done. capabilities/feedback-cues has been deleted."
echo "Review 'git status' and commit when ready."
