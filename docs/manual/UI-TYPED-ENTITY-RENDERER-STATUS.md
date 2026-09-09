# UI Redesign — Typed Entity, Centralized Renderer, Skeleton Card — Status and Handoff

**Read this first if you are a fresh Claude session picking up this project's
UI work.** This document is the honest current state as of `versionName
6.25.0` — not the original plan, not what was *intended*, but what is
actually true in the code right now, verified by grep against the real tree
rather than recalled from memory. Where an earlier plan's assumption turned
out to be wrong, that's called out explicitly, because it happened more than
once this session and matters for anyone continuing the work.

**Companion documents** (predate this session, still the source of truth for
governance rules):
- `docs/manual/TEMPLATE_CATALOG.md` — the original 4-template settings-row
  catalog, piece notation, and the governance rule for adding a new
  template.
- `TEMPLATE_CATALOG-full-app-audit.md` — the 9-template full-app audit
  (uploaded, not yet moved into `docs/manual/`). **Its Part A claim that the
  original 4 templates were already "confirmed correct usage" everywhere is
  WRONG for 7 of the 9 real screens checked** — see §2 below. Trust the
  piece tables and the governance rule in that document; do not trust its
  claim about which screens already used the templates correctly.
- `ui-typed-entity-renderer-prompt.md` — the original 6-phase implementation
  plan (uploaded). Phases 1-2 below map to that plan; Phases 3+ (TaskCard,
  ChartCard, CalendarCard, SearchableListDialog, FullScreenAlert,
  add-task-screen's 10-section audit) have **not been started**.

---

## 1. The architecture, in one paragraph

Every capability except `design-system` is meant to build a typed entity
describing what to show — never construct a View. `design-system/renderers/`
is meant to be the only place a View is ever constructed for one of the 9
catalog template shapes. This session built that for 4 of the 9 templates
(NavCard, ToggleCard, ValueCard, DropdownCard) and migrated every real
screen that needed one onto it. It also built, and is actively iterating on,
an experimental 10th shape — a unified "skeleton" card — kept fully isolated
to one demo screen, not yet a decision about whether it replaces anything.

## 2. What's actually done — the 4 existing templates

**Entities + renderers exist** in `design-system/entities/` and
`design-system/renderers/`:
`{Nav,Toggle,Value,Dropdown}CardEntity` + `render{NavCard,ToggleCard,
ValueCard,DropdownCard}` — each a thin wrapper around the pre-existing
`design-system/output/*CardView.create(...)` factory. All 4 stay `public`
(not walled off with `internal`) — every real screen listed below imports
them directly; see §4 for why the walling idea was tried and reverted.

**7 real screens were checked file-by-file (not assumed from the audit
doc), and migrated where they genuinely matched a template shape:**

| Screen | What moved onto a real template | What stayed hand-built, and why |
|---|---|---|
| `settings-activity.kt` | All 13 rows -> NavCard. Its own XML used to declare `<NavCardView>` tags directly (a construction reference living outside `design-system`) — now 4 empty containers, populated via `renderNavCard()` in Kotlin. | — |
| `layout-demo-activity.kt` | All 4 templates' demo instances, plus the self-referencing scale-slider pattern. | — |
| `notification-settings-activity.kt` | Lock Screen Overlay -> ToggleCard. Exclude App -> NavCard (its `onNavigate` opens a dialog, not another Activity — NavCard's contract is just "tapping does something," not "must launch a screen"). | — |
| `sound-vibration-activity.kt` | Haptic Feedback -> ToggleCard. | "Profile Settings" row (title+subtitle+button, no dropdown) — doesn't match any of the 4. |
| `display-settings-activity.kt` | Simple Mode, SI Unit Format -> ToggleCard. Layout, Color rows -> NavCard. | Dark Mode (3-way `MaterialButtonToggleGroup`, not a boolean switch). Window Calibrate (a switch bundled in the *same card* as unrelated custom content — live stats, a 3-column profile picker; splitting the switch out would break that card's own intentional grouping into two cards). |
| `profile-settings-activity.kt` | 5 sliders (Sound Timeout, Default Volume, Gradual Volume Increase, Vibration Timeout, Action Volume) -> ValueCard. Vibration Pattern -> DropdownCard. | Timer/Execute/Wait Sound rows (title+subtitle+button, no dropdown — same shape gap as sound-vibration's Profile Settings row). |
| `button-action-activity.kt` | Quick Action -> ToggleCard. Hardware Keys -> NavCard. | — |
| `hardware-key-option-activity.kt` | **Nothing.** | Whole screen is a dynamically-built `RadioGroup` with per-option disable/relabel logic — a different interaction pattern from DropdownCard's fixed single-select spinner, not a template match. |
| `hardware-key-action-activity.kt` | **Nothing.** | 3 rows (title + trailing value, no chevron, no subtitle) packed into ONE shared card with dividers — doesn't match NavCard (needs subtitle+chevron, own card) or ValueCard (not clickable) without changing the actual card grouping. |

**The real finding, stated plainly:** the audit's claim that these screens
already used the templates was false for all 7 — every one of them
hand-built a lookalike (`SwitchMaterial`, `Slider`, `AutoCompleteTextView`,
`CardView` constructed directly) instead of using `design-system`'s shared
components. That drift is exactly what this whole redesign exists to make
structurally impossible; the audit itself was written without actually
checking, which is why "verify against the real tree" is the standing rule
for this document too.

**Verification standard applied after every migration:** a repo-wide grep
for direct construction of the 4 View classes outside `design-system`
returns zero hits. This was re-run after each screen's migration, not just
claimed once at the end.

## 3. What's genuinely NOT done from the original 9-template plan

- **TaskCard, ChartCard, CalendarCard, SearchableListDialog, FullScreenAlert**
  — no entities, no renderers, nothing started. The real `task-view-holder.kt`
  was never touched.
- **`add-task-screen`'s 10 section files** — never audited. The original
  plan explicitly said not to assume their shape from file names; that audit
  still needs to happen before any of them are migrated.
- **`ColorMatrixActivity`** — correctly left alone; single usage site, fails
  the governance rule's "needed in 2+ places" test.
- **Phase 6 of the original plan** (enforcement check in
  `check_architecture.sh`, merging the audit doc into the canonical
  `TEMPLATE_CATALOG.md`) — not started.

## 4. The `internal`-wall attempt — tried, reverted, why

Partway through, an attempt was made to mark the 4 entities/renderers
`internal` and force every caller through one dispatcher
(`EntityRenderer.render(UiEntity)`) taking a sealed `UiEntity` class (see
`design-system/entities/ui-entity.kt` — **this file still exists in the
tree, still compiles, but nothing constructs it and no dispatcher was ever
built**). This broke all 7 already-migrated screens immediately, since they
call the per-template renderers directly. It was reverted in full before
those screens shipped broken — the 4 renderers went back to `public`, and
`ui-entity.kt` was left in place as an unused, orphaned file rather than
deleted, in case the dispatcher idea is picked up again later.

**If continuing this specific idea:** `ui-entity.kt`'s 9-variant sealed
class (5 fleshed out, 4 placeholder — `Task`/`Calendar`/`SearchableList`/
`Alert`) is still there and still a reasonable starting shape. Nothing
currently depends on it. Either finish wiring a real dispatcher and
re-migrate all 7 screens onto it in one atomic pass (so the build is never
left broken mid-way — the mistake made the first time), or delete it if the
idea is abandoned for good.

## 5. The skeleton-card experiment — current real shape

A **separate, deliberately isolated** idea from the `internal`-wall attempt:
instead of routing between 4 differently-SHAPED cards, use one shared
skeleton (fixed row order, cards show only the rows they need) — closer to
what a Home Assistant dashboard card actually looks like. Explicitly scoped
to ONE demo screen only, never touching a real settings screen, per direct
instruction early in this exploration.

**Where it lives:** `design-system/entities/skeleton-card-entity.kt`,
`design-system/output/skeleton-card-view.kt`,
`design-system/renderers/render-skeleton-card.kt`, plus
`design-system/res/layout/view_skeleton_card_internal.xml` and
`view_skeleton_dropdown_internal.xml`. Wired into
`settings-screens/layout-demo-activity.kt`'s **existing "template" tab**
(not a separate tab — that was tried and explicitly corrected back), below
the 4 real template demos, rebuilt on every scale-slider change exactly like
the real templates above it (so it inherits live token scale for free —
no separate wiring needed for that part).

**Current entity shape — 4 fixed, ordered slots, never reordered:**

1. **Title** [F]
2. **Subtitle** [O] — category/group text
3. **Metric** [O] — a changeable/dynamic readout
4. **Input** [O] — splits into two independent row KINDS:
   - `fullInput` — at most ONE full-width control per card: `Slider` or
     `Dropdown` (rendered as the complete native outlined box, not a
     compact form).
   - `smallInputs` — zero or more COMPACT controls in their own row,
     right-aligned, packed in list order: `Toggle` (the real native
     `SwitchMaterial` widget — explicitly NOT an icon standing in for one,
     after an earlier wrong attempt to fake it with a tinted power-icon was
     corrected) or `IconButton` (icon-only, opaque square background so a
     bare glyph doesn't have to be guessed as tappable).

**The closed icon set** (`SkeletonIcon` enum): `HAMBURGER` (opens
something IN PLACE, e.g. a dialog; used by Exclude App's demo),
`NAV_ARROW` (reserved for a card that actually navigates to a
different page/Activity; **currently unused by any of the 5 demo cards**,
since none of them change screens), `PREVIEW`, `EXPORT`.

**The 5 demo cards currently built, each explicitly labeled against the
real row it mirrors:**

| Demo card | Mirrors | Shape used |
|---|---|---|
| Toggle-shaped | Lock Screen Overlay | subtitle + metric + real Switch |
| Nav-shaped | Exclude App | metric + hamburger icon -> opens a real multi-select `AlertDialog` |
| Value-shaped | Default Volume | metric + slider, live-updating |
| Dropdown-shaped | Vibration Pattern | full dropdown box (no metric row — redundant with the dropdown's own shown value) + preview icon |
| Button-shaped | Export Database | subtitle + export icon |

**Sizing is measurement-derived, not a separate hardcoded dimen** — the
current (and most experimental) state: `SkeletonCardView` builds a real,
unattached `SwitchMaterial`/`Slider`, calls `.measure()` with both
dimensions `UNSPECIFIED`, and reads the *actual* rendered
`measuredWidth`/`measuredHeight` at the live theme/token scale. Icon
buttons are sized to exactly match the real switch's measured footprint;
the dropdown's outer box height is forced to exactly match the real
slider's measured height. **This last one is flagged as a live risk, not a
proven-safe choice** — a `TextInputLayout`'s natural height comes from font
metrics + label/box padding, not slider-style track geometry, so forcing it
down to a slider's (typically shorter) height could clip or crowd the
field's own content at some token scales. Worth a real visual check before
trusting it; if it clips, reverting just that one `layoutParams` line to
`WRAP_CONTENT` is independent of the icon-button sizing change.

**Real bugs found and fixed along the way, worth knowing about if touching
this again:**
- A `TextInputLayout` built purely in Kotlin with a constructor-based
  `defStyleAttr` (`com.google.android.material.R.attr.
  textInputOutlinedExposedDropdownMenuStyle`) **did not actually apply the
  outlined-box style** — rendered as a plain underlined field despite the
  attr being a real, standard Material attr. Fixed by inflating a small XML
  fragment using the exact same `style="@style/App.TextInput.Dropdown"`
  XML-attribute application already proven to work elsewhere in this
  codebase (`profile-settings-activity.kt`'s real Vibration Pattern row) —
  a general lesson: prefer inflating a proven XML style over trying to
  reproduce Material styling via Kotlin constructor tricks.
- `FrameLayout.addView(view)` with no explicit `LayoutParams` defaults each
  child to its own `WRAP_CONTENT` — this is why the first version of the
  action-slot widgets rendered at generic platform-default size instead of
  the card's actual width; every widget now gets an explicit `LayoutParams`
  assigned.

## 6. Version timeline this session (for git log/changelog cross-reference)

| Version | What it did |
|---|---|
| 6.20.0 | Phase 1: 4 entities + 4 renderers built; `settings-activity.kt` + `layout-demo-activity.kt` migrated (the only 2 screens the audit's claim happened to be true for). |
| 6.21.0 | The 7-screen cleanup — real findings per §2's table above. |
| 6.22.0 | First skeleton-card draft: 5-slot shape (title/subtitle/action/metric/buttons), separate "Skeleton" tab. |
| 6.22.1 | Corrected: folded into the existing "template" tab (tab removed), task-card comparison dropped entirely, fixed a real widget-sizing bug (missing `LayoutParams`). |
| 6.23.0 | Made all 5 demo cards genuinely interactive (real dialog, live slider value, etc.) — previously they were visually present but inert. |
| 6.24.0 | Redesigned to 4 slots (title/subtitle/metric/input) with two input row kinds (full vs. small), icon-only small inputs. |
| 6.24.1 | Corrected: toggle reverted to the real native `SwitchMaterial` (was wrongly an icon), Exclude App's icon corrected from nav-arrow to hamburger (it doesn't navigate), Vibration Pattern's metric row dropped, dropdown's broken outlined-box style fixed, opaque square backgrounds added to icon buttons. |
| 6.25.0 | Icon-button size and dropdown-box height changed from hardcoded dimens to live-measured references against a real `SwitchMaterial`/`Slider` instance. |

Every version shipped as a changed-files-only zip with a matching
`scripts/apply-{version}-deletions.sh` where a file was renamed/removed —
same discipline `EVENT-BUS-ARCHITECTURE.md` documents for the kernel
migration.

## 7. Open questions for whoever continues this

1. **Does the skeleton card get promoted to a real 10th template, replace
   one or more of the existing 4, or stay a permanent demo-only
   experiment?** No decision has been made either way — it has been kept
   deliberately isolated to `layout-demo-activity.kt` throughout, exactly
   as instructed, specifically so this decision could be made later without
   any real screen depending on the answer yet.
2. **Is the dropdown-height-equals-slider-height sizing actually safe?**
   Flagged in §5 — needs a real visual check, not just a code read.
3. **`ui-entity.kt`'s orphaned sealed class** (§4) — finish it, or delete it?
4. **The remaining 5 templates + the `add-task-screen` audit** — untouched.
   The original prompt's own instruction stands: audit before assuming
   shape, the same way this session's §2 findings falsified the original
   audit's Part A claims by actually reading the code.
