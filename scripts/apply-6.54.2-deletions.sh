#!/usr/bin/env bash
#
# Deletions for v6.54.2 — the tab menus share one row layout.
#
# item_queue_kind_menu.xml (6.54.1, the Queue tab's menu row) is now item_tab_menu.xml, used by the
# Queue, Schedule and Completed menus alike. The old file is unused; nothing references it any more.
#
# Run this against your local checkout, after extracting v6.54.2's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the renamed Queue menu row layout (v6.54.2)..."

rm -f capabilities/task-list-screen/src/main/res/layout/item_queue_kind_menu.xml

echo "Done."
