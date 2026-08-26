#!/usr/bin/env bash
#
# UI geometry guard for EEVDF Scheduler.
#
# The design-system doc (App.Row.*, App.Card.*, App.Text.*, App.ValueRow,
# App.Slider, App.SwitchRow in themes.xml) only produces "identical relative
# position at 1001 pages" if nothing ever bypasses it. Nothing in the Kotlin
# compiler stops a raw `android:textSize="14sp"` or `android:paddingTop="16dp"`
# from shipping next to a token-based sibling — that's exactly how
# activity_hardware_key_action.xml and the hand-rolled switch/slider cards in
# activity_ui_customization.xml, activity_sound_vibration.xml, and
# activity_profile_settings.xml drifted from every other settings screen.
# This script catches that class of drift in CI.
#
# Run locally:  bash scripts/check_ui_geometry.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAILURES=0
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
fail() { red "  FAIL: $*"; FAILURES=$((FAILURES+1)); }

LAYOUT_DIR="app/src/main/res/layout"

# ─────────────────────────────────────────────────────────────────────────────
echo "[1/6] Raw dp on margin/padding — must come from the spacing/card-padding scale"
# ─────────────────────────────────────────────────────────────────────────────
# Grandfathered: dialog/list-item/toolbar-menu layouts pre-date the settings-row
# template and are a different component family (single-line picker rows,
# toolbar action-views with a status dot) — not settings screens, out of this
# pass's scope. Shrink this list as each file is migrated; never add a *new*
# settings screen to it.
#
# A literal 0dp is excluded from detection: zero carries no scale-consistency
# risk (there is no "wrong" zero), so App.Row.Value.Flush-style explicit
# zero-overrides are not drift and would otherwise force this check red on
# code this same pass just wrote correctly.
GRANDFATHERED_DP="
dialog_group_picker.xml
fragment_stats_calendar.xml
item_group_picker_entry.xml
item_group_picker_header.xml
item_group_picker_divider.xml
menu_action_schedule_next.xml
menu_action_sync.xml
"

RAW_DP_RE='android:(layout_margin|margin|padding)[A-Za-z]*="[0-9]+dp"'

VIOLATIONS=""
COUNT=0
if [ -d "$LAYOUT_DIR" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    base="$(basename "$f")"
    case "$GRANDFATHERED_DP" in *"$base"*) continue;; esac
    # Does this file have a match that is NOT exactly ="0dp"?
    if grep -oE "$RAW_DP_RE" "$f" 2>/dev/null | grep -qvE '="0dp"$'; then
      COUNT=$((COUNT+1))
      VIOLATIONS+="  ${f#./}"$'\n'
    fi
  done < <(find "$LAYOUT_DIR" -name '*.xml')
fi

echo "  raw-dp files outside the grandfather list: $COUNT (baseline 0)"
if [ "$COUNT" -gt 0 ]; then
  fail "raw dp margin/padding found outside the grandfathered dialog/list-item/menu files"
  printf '%s' "$VIOLATIONS"
  ylw "  Use a token from dimens.xml (app_spacing_*, app_card_padding_*, app_label_slot_*,"
  ylw "  app_value_row_gap, app_slider_top_gap, ...) or add a new named token if the"
  ylw "  scale genuinely has no slot — never a raw dp literal. (Literal 0dp is exempt —"
  ylw "  it's an explicit flush override, not a scale value.)"
else
  grn "  OK"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[2/6] Raw sp on textSize — must come from the text-size scale"
# ─────────────────────────────────────────────────────────────────────────────
GRANDFATHERED_SP="
dialog_group_picker.xml
item_group_picker_entry.xml
item_group_picker_header.xml
"

VIOLATIONS=""
COUNT=0
if [ -d "$LAYOUT_DIR" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    base="$(basename "$f")"
    case "$GRANDFATHERED_SP" in *"$base"*) continue;; esac
    COUNT=$((COUNT+1))
    VIOLATIONS+="  ${f#./}"$'\n'
  done < <(grep -rlE 'android:textSize="[0-9]+sp"' "$LAYOUT_DIR" 2>/dev/null)
fi

echo "  raw-sp files outside the grandfather list: $COUNT (baseline 0)"
if [ "$COUNT" -gt 0 ]; then
  fail "raw sp textSize found outside the grandfathered dialog/list-item files"
  printf '%s' "$VIOLATIONS"
  ylw "  Use @dimen/app_text_size_xs..cl, or a semantic App.Text.* / App.Row.* /"
  ylw "  App.ValueRow.* / App.Slider.Caption* style that already sets it."
else
  grn "  OK"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[3/6] Legacy text-color tokens — textPrimary/textSecondary/textOnHeader"
# ─────────────────────────────────────────────────────────────────────────────
# These predate the app_text_title / app_text_body / app_text_label family and
# read identically in light mode but do NOT track values-night the same way,
# which is its own latent bug. Settings screens should use the app_text_*
# family exclusively. Grandfathered elsewhere until those screens are migrated.
GRANDFATHERED_LEGACY_COLOR="
activity_multiuser_sync.xml
activity_stats.xml
section_add_task_buttons.xml
"

VIOLATIONS=""
COUNT=0
if [ -d "$LAYOUT_DIR" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    base="$(basename "$f")"
    case "$GRANDFATHERED_LEGACY_COLOR" in *"$base"*) continue;; esac
    COUNT=$((COUNT+1))
    VIOLATIONS+="  ${f#./}"$'\n'
  done < <(grep -rlE '@color/(textPrimary|textSecondary|textOnHeader)' "$LAYOUT_DIR" 2>/dev/null)
fi

echo "  legacy-color-token files outside the grandfather list: $COUNT (baseline 0)"
if [ "$COUNT" -gt 0 ]; then
  fail "legacy textPrimary/textSecondary/textOnHeader color token found"
  printf '%s' "$VIOLATIONS"
  ylw "  Use @color/app_text_title, @color/app_text_body, or @color/app_text_label instead."
else
  grn "  OK"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[4/6] Directional anchoring — no left/right, only start/end"
# ─────────────────────────────────────────────────────────────────────────────
# Zero tolerance, zero grandfather: a left/right anchor silently breaks the
# moment the app ships an RTL locale, and every screen in the app already
# passes this check today. This is the one category allowed to regress to
# zero and stay there.
VIOLATIONS=""
COUNT=0
if [ -d "$LAYOUT_DIR" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    COUNT=$((COUNT+1))
    VIOLATIONS+="  ${f#./}"$'\n'
  done < <(grep -rlE 'android:(margin|padding)(Left|Right)="|android:layout_alignParent(Left|Right)="' "$LAYOUT_DIR" 2>/dev/null)
fi

echo "  left/right directional attributes found: $COUNT (must be 0)"
if [ "$COUNT" -gt 0 ]; then
  fail "left/right directional attribute found — use Start/End so RTL locales don't break"
  printf '%s' "$VIOLATIONS"
else
  grn "  OK — every layout uses start/end"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[5/6] Style implicit-parent resolution — dot-notation inheritance must resolve"
# ─────────────────────────────────────────────────────────────────────────────
# Android's dot-notation implicit style inheritance strips the LAST segment
# of a style name to find its parent when no parent= is given. A style named
# "App.Foo.Bar" with no explicit parent silently tries to inherit from a
# style literally named "App.Foo" — if that doesn't exist, AAPT fails at
# link time with "resource style/App.Foo not found", pointing at the WRONG
# style name (the missing implicit parent, not the one you actually wrote).
#
# This has broken the build twice on this project (App.List.Content in the
# prior session, App.SettingsCard.Body in this one). Both were runtime/build
# failures the review process here didn't catch beforehand. This check
# parses every style tag out of values/*.xml and, for any dotted name with no
# explicit parent=, verifies the implied parent is either declared locally or
# is a real AndroidX/Material/platform root — so a violation is a build
# failure caught in CI, not in Android Studio's build output.
ZERO_TOLERANCE="
Widget.MaterialComponents.CardView
ShapeAppearance.MaterialComponents.SmallComponent
ShapeAppearance.MaterialComponents.MediumComponent
Widget.MaterialComponents.TextInputLayout.OutlinedBox
Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu
ThemeOverlay.MaterialComponents.MaterialAlertDialog
Widget.MaterialComponents.CompoundButton.CheckBox
Widget.MaterialComponents.CompoundButton.RadioButton
Widget.MaterialComponents.TabLayout
TextAppearance.Design.Tab
Theme.MaterialComponents.DayNight.NoActionBar
Theme.MaterialComponents.DayNight.Dialog.MinWidth
android:TextAppearance
android:Widget
"

VALUES_DIR="app/src/main/res/values"
PY_OUT=""
if [ -f "$VALUES_DIR/themes.xml" ]; then
  PY_OUT=$(python3 - "$VALUES_DIR/themes.xml" "$ZERO_TOLERANCE" << 'PYEOF'
import re, sys

path = sys.argv[1]
known_roots = set(l.strip() for l in sys.argv[2].splitlines() if l.strip())

with open(path) as f:
    content = f.read()

styles = {}
for m in re.finditer(r'<style name="([^"]+)"(?:\s+parent="([^"]+)")?\s*/?>', content):
    name, parent = m.group(1), m.group(2)
    styles[name] = parent

problems = []
for name, parent in styles.items():
    if parent is not None:
        if parent not in styles and parent not in known_roots:
            problems.append(f"{name}  parent=\"{parent}\" does not resolve")
        continue
    if '.' not in name:
        continue
    implicit = name.rsplit('.', 1)[0]
    if implicit in styles or implicit in known_roots:
        continue
    problems.append(f"{name}  -> implicit parent '{implicit}' NOT FOUND (add parent=\"...\")")

for p in problems:
    print(p)
PYEOF
)
fi

if [ -n "$PY_OUT" ]; then
  COUNT=$(printf '%s\n' "$PY_OUT" | grep -c .)
  fail "$COUNT style(s) with unresolved parent (implicit or explicit)"
  printf '%s\n' "$PY_OUT" | sed 's/^/  /'
  ylw "  Add an explicit parent=\"...\" — Android will NOT infer one correctly"
  ylw "  just because the dotted name looks like it should nest under something."
else
  grn "  OK — every style's parent (implicit or explicit) resolves"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[6/6] XML comment validity — no literal -- inside a comment body"
# ─────────────────────────────────────────────────────────────────────────────
# A literal double-hyphen anywhere inside an XML comment's body is invalid
# per the XML spec (only the opening <!-- and closing --> delimiters may
# contain it). AAPT fails the whole file on this with a parse error that
# gives no indication it's a comment-formatting issue. This exact mistake —
# using "--" as a prose dash separator inside a doc comment — has recurred
# multiple times across this project's layout and values files. Checked
# here with an actual comment-body parser, not a naive text search (which
# false-positives on em-dashes and box-drawing characters that are legal).
PY_OUT=""
if [ -d "$LAYOUT_DIR" ] || [ -d "$VALUES_DIR" ]; then
  PY_OUT=$(python3 - "$LAYOUT_DIR" "$VALUES_DIR" << 'PYEOF'
import re, sys, glob

dirs = sys.argv[1:]
problems = []
for d in dirs:
    for fpath in glob.glob(f"{d}/*.xml"):
        content = open(fpath).read()
        for m in re.finditer(r'<!--(.*?)-->', content, re.DOTALL):
            if '--' in m.group(1):
                snippet = m.group(1).strip().replace('\n', ' ')[:60]
                problems.append(f"{fpath}: \"{snippet}...\"")

for p in problems:
    print(p)
PYEOF
)
fi

if [ -n "$PY_OUT" ]; then
  COUNT=$(printf '%s\n' "$PY_OUT" | grep -c .)
  fail "$COUNT XML file(s) with a literal -- inside a comment body"
  printf '%s\n' "$PY_OUT" | sed 's/^/  /'
  ylw "  Use a single hyphen, an em dash, or box-drawing characters instead —"
  ylw "  a literal double-hyphen anywhere inside a comment's body is invalid XML."
else
  grn "  OK — no invalid comment bodies found"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo
if [ "$FAILURES" -gt 0 ]; then
  red "UI geometry guard FAILED with $FAILURES problem(s)."
  exit 1
fi
grn "UI geometry guard passed."
