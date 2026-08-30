#!/usr/bin/env bash
#
# Run this ONCE against your local checkout BEFORE extracting/copying in the
# full v5.12.0 zip. It removes every path that this refactor moved or emptied
# out — Phases 3, 4, and 7 of ARCHITECTURE.md, plus the post-Phase-7 fixes
# (media/notification -> :platform, prefs/signals -> feature/shared).
#
# Same purpose as scripts/apply-4.4.0-deletions.sh already in this repo:
# unzipping writes the NEW files but cannot remove the OLD ones, and leaving
# both on disk means duplicate class declarations across two modules.
#
# Safe to run from the repo root. Idempotent — rm -f/-rf on an already-gone
# path is a no-op, not an error.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting paths superseded by v5.12.0..."

# ── Phase 3 (v5.10.0): orphan relocation + app/ui extraction ────────────────
rm -rf app/src/main/kotlin/com/eevdf/app/core/settings
rm -rf app/src/main/kotlin/com/eevdf/app/core/template

# ── Phase 4 (v5.11.0): :contract module ──────────────────────────────────────
rm -rf app/src/main/kotlin/com/eevdf/app/core/control
rm -rf app/src/main/kotlin/com/eevdf/app/core/nav
rm -rf app/src/test/kotlin/com/eevdf/app/core

# ── Phase 7 (v5.12.0): :feature module ───────────────────────────────────────
rm -rf app/src/main/kotlin/com/eevdf/app/feature

# ── Post-Phase-7 fixes: media/notification -> :platform, prefs/signals -> feature/shared ──
rm -rf app/src/main/kotlin/com/eevdf/app/core/media
rm -rf app/src/main/kotlin/com/eevdf/app/core/notification
rm -rf app/src/main/kotlin/com/eevdf/app/core/prefs
rm -rf app/src/main/kotlin/com/eevdf/app/core/signals

# ── Resources: everything except the launcher icon moved to feature/*/res/ ──
rm -rf app/src/main/res/layout
rm -rf app/src/main/res/menu
rm -rf app/src/main/res/values-night
rm -f  app/src/main/res/values/colors.xml
rm -f  app/src/main/res/values/dimens.xml
rm -f  app/src/main/res/values/themes.xml
rm -f app/src/main/res/drawable/badge_bg.xml
rm -f app/src/main/res/drawable/baseline_delete_24.xml
rm -f app/src/main/res/drawable/bg_dl_badge.xml
rm -f app/src/main/res/drawable/bg_edit_text.xml
rm -f app/src/main/res/drawable/bg_group_picker_button.xml
rm -f app/src/main/res/drawable/bg_rt_badge.xml
rm -f app/src/main/res/drawable/bg_warning_notice.xml
rm -f app/src/main/res/drawable/bubble_background.xml
rm -f app/src/main/res/drawable/ic_check_modern.xml
rm -f app/src/main/res/drawable/ic_chevron_down_modern.xml
rm -f app/src/main/res/drawable/ic_close_modern.xml
rm -f app/src/main/res/drawable/ic_notification.xml
rm -f app/src/main/res/drawable/ic_reset_timer.xml
rm -f app/src/main/res/drawable/ic_search_modern.xml
rm -f app/src/main/res/drawable/ic_sync.xml
rm -f app/src/main/res/drawable/ic_sync_dot.xml
rm -f app/src/main/res/drawable/ic_undo_24.xml
rm -f app/src/main/res/drawable/outline_arrow_circle_down_24.xml
rm -f app/src/main/res/drawable/outline_autopause_24.xml
rm -f app/src/main/res/drawable/outline_check_circle_24.xml
rm -f app/src/main/res/drawable/outline_play_arrow_24.xml
rm -f app/src/main/res/drawable/outline_rotate_left_24.xml
rm -f app/src/main/res/drawable/outline_skip_next_24.xml
rm -f app/src/main/res/drawable/outline_timer_play_24.xml

# ── Prune now-empty parent directories (harmless if not empty/not present) ──
rmdir app/src/main/kotlin/com/eevdf/app/core 2>/dev/null || true
rmdir app/src/test/kotlin/com/eevdf/app 2>/dev/null || true

echo "Done. Now extract or copy in the v5.12.0 full zip contents."
