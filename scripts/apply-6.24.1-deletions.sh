#!/usr/bin/env bash
#
# Deletions for v6.24.1 — capabilities/design-system/.../drawable/
# outline_power_settings_24.xml, shipped in v6.24.0's zip, is now
# superseded: the skeleton demo's toggle reverted to the real native
# Material switch widget (not an icon standing in for one, per correction),
# so this icon has no remaining reference anywhere.
#
# Run this against your local checkout, after extracting v6.24.1's zip on
# top of it.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the obsolete power-settings toggle icon (v6.24.1)..."

rm -f capabilities/design-system/src/main/res/drawable/outline_power_settings_24.xml

echo "Done."
