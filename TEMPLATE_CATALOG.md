# UI template catalog — geometric specification

This is a proposal to review before anything is applied to the codebase.
It defines a closed set of fundamental templates. Every settings row on
every settings page — no matter how many thousand pages the app grows
to — is required to be an instance of one of these templates. No other
shape is permitted to exist in a settings layout.

Biology reference for the model: a body has a small, fixed number of
organ and cell TYPES. Complexity comes from INSTANCE COUNT, not TYPE
COUNT. A settings page is a cell; a template is a cell type. Thousands
of pages, single-digit template types.

---

## Governance rule — when a new template is allowed to be added

A new fundamental template may be added ONLY when both are true:

1. It is needed at more than one location (never justified by a single
   screen's convenience).
2. It cannot be produced by any arrangement of existing templates'
   pieces, sequence rules, or state parameters — it occupies a
   geometric role nothing else can be reconfigured to fill.

If a new requirement CAN be expressed as an existing template with a
different piece combination, a new optional piece, or a new state
parameter on an existing indicator — it MUST be expressed that way.
Visual difference alone is not sufficient justification for a new
template. A checkbox and a radio button are the same fundamental
type (small fixed indicator, anchored to a label's line box) with a
selection-mode parameter — not two templates. Radio and checkbox are
allowed to exist, but a developer should reach for one that's already
in the catalog before ever proposing a new fundamental.

---

## Piece notation

Every template is a numbered, ordered stack of full-width pieces.

- **[F] fundamental** — always renders its row and reserves its
  height, whether or not it has content. Position in the sequence is
  fixed by index, never by content presence. A card that only defines
  piece 2 still shows pieces 0 and 1 in their normal position, blank.
- **[O] optional** — entirely removed when unused; the sequence closes
  the gap. Never reserves dead space.
- **[S] spacer** — has no content, ever. Not authored by a developer.
  Inserted automatically by the template wherever the running total
  height of the pieces above it does not already land on a defined
  rung of the spacing scale (see below). Its height is whatever value
  closes the gap up to the next rung — never a fixed number itself.
- Order is fixed per template. A developer may only include a subset
  of pieces in their original relative order — e.g. from (0,1,2,3,4)
  a developer may use (0,1,2,3) or (0,3) or (0) — never (2,0) or any
  reordering.
- A piece's [F]/[O] marking is fixed by the template, never chosen
  per-instance by the developer. [S] pieces are never chosen by
  anyone — they are computed.

---

## Scale-alignment rule

Every dimension a template touches (card height, padding, row height,
piece boundary position) must land on a defined rung of a fixed-step
scale — e.g. 10, 20, 30, 40 ... The scale may have as many rungs as
needed and may always grow a new rung to cover a genuine new case.
What is never permitted is a real card settling at an off-scale value
between two rungs.

Two consequences:

1. **Conflict resolution order.** If snapping a dimension to the scale
   would break rendering (content doesn't fit, a piece overflows),
   the scale requirement is dropped for that instance rather than
   forcing broken content to fit the scale. Correctness first, scale
   discipline second — never the reverse.
2. **Spacer insertion.** If pieces 0..N sum to a height that is not
   itself a rung (e.g. piece 0 + piece 1 = 28dp, with rungs at
   10/20/30), the template inserts an [S] spacer sized to close the
   gap to the next rung (2dp here, landing piece 2's start at 30dp).
   This is automatic and invisible — a developer never places or
   tunes a spacer directly. It exists purely so every piece boundary
   in the system lands on-scale, not just each piece's own height.

---

## Template 1 — NavCard

For rows that navigate to another screen. (Settings → display,
Settings → sound and vibration, etc.)

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Title + trailing indicator | F | Indicator = chevron only. No other trailing type permitted on NavCard. |
| 1 | Subtitle | O | Full-width row below piece 0, never a sibling of the trailing indicator. |

Legal: (0), (0,1)
Illegal: (1) alone, (1,0)

---

## Template 2 — ToggleCard

For rows that flip a binary/tri-state setting.

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Title + trailing indicator | F | Indicator = switch, checkbox, or radio (state-mode parameter, not a new template). Anchored to title's line box only. |
| 1 | Subtitle | O | Full-width row below piece 0. Never shares a row-box with the indicator. |
| 2 | Divider | O | Full-width rule, separates a secondary header pair from the primary one within the same card. |
| 3 | Secondary title + trailing indicator | O | Same indicator rule as piece 0. Only legal after piece 2. |
| 4 | Secondary subtitle | O | Same rule as piece 1, applied to piece 3. |

Legal subsets preserve order: (0), (0,1), (0,1,2,3), (0,1,2,3,4)
Illegal: (0,3) skipping the divider, (3) alone, indicator on piece 1

---

## Template 3 — ValueCard

For rows that display a current value, optionally editable via a
paired slider beneath.

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Label (reserved slot) + trailing value text | F | Label width comes from a fixed slot tier (sm/md/lg), never sized to its own text. Value text anchored to label's line box only. |
| 1 | Description | O | Full-width row below piece 0. |
| 2 | Slider | O | Full-width. Only legal if piece 0's value is slider-driven. |
| 3 | Min/max caption row | O | Only legal directly after piece 2 — a caption with no slider above it is meaningless and therefore illegal. |

Legal: (0), (0,1), (0,2,3), (0,1,2,3)
Illegal: (0,3) caption with no slider, (2) alone, (3,2) reordered

---

## Template 4 — DropdownCard

For rows that open a selection list (spinner/exposed dropdown).

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Title | F | No trailing indicator on this piece — the dropdown itself is piece 1, not a trailing accessory. |
| 1 | Dropdown control | F | Always renders, even with no options loaded yet (shows disabled/empty state, never disappears). |
| 2 | Helper action (e.g. Preview button) | O | |

Legal: (0,1), (0,1,2)
Illegal: (0) alone — piece 1 is fundamental, dropping it is not permitted for this template
Illegal: (1) alone, (2,0,1)

---

## What is deliberately NOT a template

- A row that is only a title with no indicator and no further pieces
  is not a distinct template — it is ToggleCard/ValueCard/NavCard with
  every optional piece and the indicator omitted, which the sequence
  rules already permit without inventing anything new.
- A "reserved / coming soon" row (seen today as App.Row.Reserved) is
  not a new template — it is a ToggleCard/NavCard instance with piece
  0's indicator omitted and piece 1 populated with the placeholder
  text. No new fundamental required.

---

## Open question before this is applied

Every existing settings screen needs to be checked against this table
to confirm which template + which piece subset it should have been
using all along, and where today's code has silently produced an
illegal shape (e.g. the toggle-anchored-to-title-plus-subtitle bug
found earlier). That audit is the next step, not included here.
