# UI template catalog — full application audit (extends TEMPLATE_CATALOG.md)

This is the "open question" the existing catalog left pending: *"Every
existing settings screen needs to be checked against this table... that
audit is the next step."* This document does that audit, and extends it
past settings to every screen in the app, since the same governance rule
applies everywhere, not just settings rows.

Same rules as the original: a new fundamental template is only added when
(1) it's needed in more than one place, and (2) it cannot be produced by
any arrangement of an existing template's pieces. Visual difference alone
is never sufficient.

---

## Part A — existing 4 templates: where they're already used correctly

| Template | Confirmed real usage found in code |
|---|---|
| NavCard | `settings-activity.kt` → 8 sub-screen entries (Display, Sound & Vibration, Hardware Key, Notifications, Profile, Permissions, Layout Demo, Button Action) |
| ToggleCard | `notification-settings-activity.kt`, `sound-vibration-activity.kt`, `hardware-key-option-activity.kt` |
| ValueCard | `display-settings-activity.kt` (density/scale sliders), `hardware-key-action-activity.kt` |
| DropdownCard | `profile-settings-activity.kt`, `button-action-activity.kt` |

No violations found in the screens read — these 4 hold up.

---

## Part B — genuinely new templates found (pass both governance tests)

### Template 5 — TaskCard

The primary list row (`task-view-holder.kt`). Instanced once per task in
the `RecyclerView` — satisfies "needed in more than one place" trivially,
same as the catalog's own biology framing (many instances, one type). Its
27 view fields cannot be produced by any arrangement of NavCard/ToggleCard/
ValueCard/DropdownCard pieces — those are single-purpose text+indicator
rows; this is a multi-region card with independently-optional regions.

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Header: rank + name + category + priority badge | F | Always renders; badges individually optional inside the piece |
| 1 | Task progress bar | O | `progressTask` |
| 2 | Quota progress bar | O | `progressQuota` — only legal if the task has a quota budget |
| 3 | Notice segment bar | O | `progressNotice` — visual phase indicator, see `notice-segments.kt` |
| 4 | Time info row (slice / remaining) | O | `rowTimeInfo` |
| 5 | EEVDF metrics row (vruntime / vdeadline / cpu share) | O | `rowEevdfMetrics` — only legal when the fairness algorithm exposes these for the task's scheduling class |
| 6 | Deadline/RT status badges | O | `tvDlStatus`, `tvRtStatus` |
| 7 | Run count + running indicator | O | `tvRunCount`, `viewRunningIndicator` |
| 8 | Action button row | F | `btnDelete/Complete/Run/GroupToggle/ResetSlice/Revert` — always renders, buttons individually enabled/disabled rather than removed, since the row's presence signals "this is an actionable card" |

Legal: any subset that includes 0 and 8, preserving order of what's between.
Illegal: piece 5 present without piece 4 (metrics without context is the
same "orphaned caption" problem ValueCard's piece 3 rule already forbids).

### Template 6 — ChartCard

`stats-charts-fragment.kt`: `LineChart` (load average) and `RadarChart`
(load factor spider) both need this. A drawing canvas is a different
geometric role than any text/indicator row — cannot be expressed by
existing pieces.

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Period/period label | F | `tvPeriodLabel` |
| 1 | Chart surface | F | The chart itself — line or radar is a render-mode parameter, not a new template, same logic the catalog already applies to switch vs. checkbox on ToggleCard |
| 2 | Empty-state text | O | `tvLoadAvgEmpty` — shown in place of piece 1's content when no data exists; never both at once |

Legal: (0,1), (0,2) — never (0,1,2) simultaneously.

### Template 7 — CalendarCard

`stats-calendar-fragment.kt`: a month grid + per-day detail panel. A 2D
date grid with cell-selection state is not producible from any 1D
row-stack template.

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Month title + nav controls | F | `tvMonthTitle` |
| 1 | Date grid | F | The calendar cells themselves |
| 2 | Detail panel (date, mode, value) | O | `tvDetailDate`, `tvDetailMode` |
| 3 | Detail empty state | O | `tvDetailEmpty` — mutually exclusive with piece 2, same rule as ChartCard's piece 1/2 |

### Template 8 — SearchableListDialog

`picker-dialog.kt`: search field (`etSearch`) + filterable, sectioned list
(`Header`/`Entry` items) in a `RecyclerView`. This is the answer to "search
toolbar" — there is no search box on the main list toolbar today (checked;
`menu-sync-delegate.kt` has a standard app-bar menu, not a live-filtering
`SearchView`); the only real search input in the app lives inside this
dialog. Worth noting as a gap: if search-while-browsing-the-main-list is
wanted later, it should be built as an instance of this same template, not
a new one — it already has everything needed (search field + filtered,
sectioned list).

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Search field | F | `etSearch` — always present, even if the list is short enough not to need it |
| 1 | Sectioned result list | F | Header/Entry items — headers are non-selectable, entries are |

### Template 9 — FullScreenAlert

`alarm-activity.kt`: a full-screen, single-purpose interruption — task
name, elapsed time, one large stop action. Distinct geometric role from
every card template above (those live inside a scroll/list context; this
one owns the entire screen and is intentionally free of navigation chrome).

| # | Piece | F/O | Notes |
|---|-------|-----|-------|
| 0 | Primary label (task name) | F | `tvTaskName` |
| 1 | Live counter (elapsed time) | F | `tvElapsed` |
| 2 | Primary action button | F | `btnStop` — large, singular, no secondary actions permitted on this template by definition (a second action would dilute the interruption's urgency) |

### Template 10 — ColorSwatchGrid

`color-matrix-activity.kt`: a grid of color family/stop swatches with a
detail readout. Only one confirmed usage site found (`ColorMatrixActivity`
itself) — **this one fails the "needed in more than one place" test as of
today.** Flagged as a candidate, not confirmed. Do not promote to a
fundamental template unless a second real usage appears; until then, treat
it as `ColorMatrixActivity`'s own one-off layout, same as the catalog's
own rule that a single screen's convenience is never sufficient
justification.

---

## Part C — patterns that look new but are NOT new templates

**Stats overview's 4 stat values** (`tvTotalRuntime`, `tvTotalRuns`,
`tvActiveTasks`, `tvAvgRun` in `stats-overview-fragment.kt`) — this is 4
instances of **ValueCard, piece 0 only** (label + value, no slider),
arranged in a grid layout instead of a stacked list. Per the existing
catalog's own rule, visual arrangement (grid vs. stack) is a layout
concern, not a template concern. **No new template needed** — this should
be rebuilt as 4 `ValueCardView` instances in a grid container, not
hand-coded `TextView`s, which is exactly the kind of silent drift from the
catalog the original doc's audit was meant to catch.

**Add-task-screen's 10 section files** (`group-section.kt`,
`quota-section.kt`, `time-slice-section.kt`, etc.) — not yet individually
audited in this pass, but on the pattern seen so far, these are very
likely ValueCard/DropdownCard/ToggleCard instances per field, assembled
into a form. Flag for a follow-up read before assuming new templates are
needed there.

---

## Summary — the closed set, app-wide

| # | Template | Status |
|---|---|---|
| 1 | NavCard | existing, confirmed correct usage |
| 2 | ToggleCard | existing, confirmed correct usage |
| 3 | ValueCard | existing, confirmed correct usage — **and should absorb the stats-overview stat grid, which is currently hand-coded outside it** |
| 4 | DropdownCard | existing, confirmed correct usage |
| 5 | TaskCard | new — the primary list row |
| 6 | ChartCard | new — line/radar charts |
| 7 | CalendarCard | new — the stats calendar |
| 8 | SearchableListDialog | new — the group picker; also the answer to "where's search" |
| 9 | FullScreenAlert | new — the alarm ring screen |
| 10 | ColorSwatchGrid | **not promoted** — single usage site, fails governance rule as of today |

**9 confirmed fundamental templates** (not 4) cover the entire app's UI
surface read so far. `add-task-screen`'s 10 section files still need a
follow-up audit before this list can be called complete.
