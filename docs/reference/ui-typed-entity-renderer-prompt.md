# UI Redesign — Typed Entity + Centralized Renderer — Implementation Prompt

Paste this as the opening message in a new Claude Code / Claude session to
begin implementation. It captures every design decision made so far —
read it fully before writing any code.

**Companion files already produced:**
- `docs/manual/TEMPLATE_CATALOG.md` — the original 4-template settings-row
  catalog (already in the project).
- `TEMPLATE_CATALOG-full-app-audit.md` — the full 9-template audit of the
  whole app (produced this session).
- `microkernel-redesign-prompt.md` — the capability/kernel/bus migration
  this UI work sits on top of (already executed, this project is v6.0.0+).

---

## 0. Context to give Claude

> This app (`eevdf-scheduler`, v6.0.0+, already migrated to a microkernel
> architecture — `kernel/`, `capabilities/`, `composition/`) currently
> builds its UI the way most Android apps do: each screen's own code
> constructs its own Views, ViewHolders, and layout bindings directly.
> A template catalog already exists (`docs/manual/TEMPLATE_CATALOG.md`,
> 4 templates) and was extended this session to 9 confirmed templates
> covering the whole app (`TEMPLATE_CATALOG-full-app-audit.md`).
>
> I want to redesign the UI layer the way Home Assistant's frontend works:
> every capability exposes typed data ("entities") describing what to show,
> and ships ZERO view-construction code. `design-system` is the ONLY
> capability allowed to construct a View for one of the 9 template shapes.
> Consistency becomes structurally impossible to violate, not just
> convention — the same principle already applied to `:core`'s Android-free
> purity rule and the kernel's capability boundaries.

---

## 1. Non-negotiable rules

1. **A typed entity, not a View, is what every non-`design-system`
   capability produces.** E.g. `TaskCardEntity`, `ValueCardEntity` — plain
   data classes, no Android View types in their signature.
2. **`design-system/renderers/` is the only place a View is ever
   constructed for one of the 9 template shapes.** One renderer file per
   template (`render-task-card.kt`, `render-value-card.kt`, etc.).
3. **Enforcement is structural, not conventional.** Move the actual View/
   ViewHolder/layout-XML code (e.g. `TaskViewHolder`, the chart binding
   code, `PickerDialog`'s list rendering) physically into `design-system`.
   The owning capability's `build.gradle.kts` should no longer need
   `CardView`/`RecyclerView.ViewHolder`/chart-library imports it doesn't
   use anymore — if it can't construct the View, it can't drift from the
   catalog, by construction, not by review.
4. **New template governance rule carries over unchanged** from
   `TEMPLATE_CATALOG.md`: a new template is only added when (a) needed in
   2+ places and (b) not producible from any arrangement of an existing
   template's pieces. `ColorMatrixActivity`'s swatch grid stays a one-off
   (single usage site) unless a second real usage appears.
5. **The escape hatch (hand-built screens) must still read every color/
   spacing value from `design-tokens.kt`.** No hardcoded `#RRGGBB` or `dp`
   literal anywhere outside `design-tokens.kt` — this is the "custom
   Lovelace card still reads `--primary-color`" rule.
6. **One scale value, one place, reaches everything.** `DisplayScaleDelegate`'s
   manual per-view `applyCardScaleToView(...)` calls are deleted once their
   target Views no longer live in `task-list-screen` — scale is read once,
   inside each `render-*.kt`, from `design-tokens.kt`, with no per-screen
   wiring required anywhere else.

---

## 2. The 9 confirmed templates (from the full-app audit)

| # | Template | Entity type | Source screen(s) |
|---|---|---|---|
| 1 | NavCard | `NavCardEntity` | settings sub-screen list |
| 2 | ToggleCard | `ToggleCardEntity` | notification/sound-vibration/hardware-key settings |
| 3 | ValueCard | `ValueCardEntity` | display settings sliders, **and the stats-overview stat grid (currently a bug — hand-coded `TextView`s, must be migrated to this)** |
| 4 | DropdownCard | `DropdownCardEntity` | profile/button-action settings |
| 5 | TaskCard | `TaskCardEntity` | the main task list row (`task-view-holder.kt`) |
| 6 | ChartCard | `ChartCardEntity` | stats line/radar charts |
| 7 | CalendarCard | `CalendarCardEntity` | stats calendar grid |
| 8 | SearchableListDialog | `SearchableListEntity` | group picker dialog |
| 9 | FullScreenAlert | `FullScreenAlertEntity` | alarm ring screen |

`ColorMatrixActivity` is NOT promoted to a template (single usage site) —
leave it hand-built, subject to rule 5 above.

`add-task-screen`'s 10 section files were **not yet audited** — do that
audit (same method as `TEMPLATE_CATALOG-full-app-audit.md`) before
migrating them; they likely decompose into existing templates 2/3/4 per
field, but confirm before assuming.

---

## 3. Target structure

```
capabilities/design-system/
├── entities/                    [NEW] the typed data every other
│   ├── nav-card-entity.kt        capability constructs instead of a View
│   ├── toggle-card-entity.kt
│   ├── value-card-entity.kt
│   ├── dropdown-card-entity.kt
│   ├── task-card-entity.kt
│   ├── chart-card-entity.kt
│   ├── calendar-card-entity.kt
│   ├── searchable-list-entity.kt
│   └── full-screen-alert-entity.kt
│
├── renderers/                   [NEW] the ONLY code that constructs a
│   ├── render-nav-card.kt        View for these 9 shapes, anywhere in
│   ├── render-toggle-card.kt     the app
│   ├── render-value-card.kt
│   ├── render-dropdown-card.kt
│   ├── render-task-card.kt       ← absorbs task-view-holder.kt's
│   │                              27 fields + its layout XML
│   ├── render-chart-card.kt      ← absorbs the LineChart/RadarChart
│   │                              binding code
│   ├── render-calendar-card.kt   ← absorbs the calendar grid code
│   ├── render-searchable-list.kt ← absorbs PickerDialog's list-rendering
│   │                              (search field stays in group-picker,
│   │                              only the View construction moves)
│   └── render-full-screen-alert.kt ← absorbs alarm-activity's layout code
│
└── output/                      (existing 4 View classes — NavCardView,
                                  ToggleCardView, ValueCardView,
                                  DropdownCardView — become the
                                  implementation detail INSIDE the
                                  corresponding render-*.kt file, no
                                  longer imported directly by any other
                                  capability)
```

---

## 4. Migration plan — do this in order, verifying visually + with tests
after each phase

### Phase 1 — entities + renderers for the 4 already-correct templates
Lowest risk: these already read `DesignTokens` correctly via
`card-density.kt`. Just wrap them:
1. Create `NavCardEntity`/`ToggleCardEntity`/`ValueCardEntity`/
   `DropdownCardEntity`.
2. Create `render-nav-card.kt` etc. — each wraps the existing
   `NavCardView`/etc. construction, now taking an entity instead of
   individual parameters.
3. Update `settings-screens`' 8 activities to build entities and call the
   renderer, instead of constructing `NavCardView` directly.
4. Confirm: settings screens render identically (visual regression check),
   scale slider still works.

### Phase 2 — TaskCard (highest value, since it's the busiest screen)
1. Create `TaskCardEntity` matching the 9-piece table in the audit doc.
2. Move `task-view-holder.kt` and its layout XML into
   `design-system/renderers/render-task-card.kt`.
3. Rewrite `task-adapter.kt` to build a `TaskCardEntity` per task and call
   `designSystem.render(container, entity)` — remove all direct
   `findViewById`/View field access from `task-list-screen`.
4. Delete `applyCardScaleToView`'s calls for task-card-related views from
   `display-scale-delegate.kt` — scale now reaches this template
   automatically via `render-task-card.kt` reading `design-tokens.kt`
   directly.
5. Confirm: task list renders identically, scale slider now visibly
   affects it without any code in `task-list-screen` doing so.

### Phase 3 — ChartCard + CalendarCard
1. Create both entities.
2. Move the `LineChart`/`RadarChart` binding code and the calendar grid
   code out of `stats-charts-fragment.kt`/`stats-calendar-fragment.kt`
   into their renderers.
3. **Fix the stats-overview bug found in the audit**: rebuild the 4
   hand-coded `TextView`s (`tvTotalRuntime`, `tvTotalRuns`, `tvActiveTasks`,
   `tvAvgRun`) as 4 `ValueCardEntity` instances in a grid container.
4. Confirm: scale slider now reaches stats screens for the first time.

### Phase 4 — SearchableListDialog + FullScreenAlert
1. Create both entities.
2. Move `PickerDialog`'s list-rendering (not its search-input logic, which
   stays app-specific to group-picker) into `render-searchable-list.kt`.
3. Move `alarm-activity.kt`'s layout/View code into
   `render-full-screen-alert.kt`.
4. Confirm: both render identically, scale slider now reaches them too.

### Phase 5 — audit + migrate add-task-screen's 10 sections
1. Run the same audit method as `TEMPLATE_CATALOG-full-app-audit.md`
   against each of the 10 section files.
2. For each: confirm it decomposes into existing templates (most likely),
   or justify a new template against the governance rule if it genuinely
   doesn't.
3. Migrate accordingly.

### Phase 6 — cleanup + enforcement check
1. Confirm no capability other than `design-system` imports `CardView`,
   `RecyclerView.ViewHolder`, chart-library classes, or constructs a View
   for any of the 9 template shapes — grep-checkable, add this check to
   `check_architecture.sh`.
2. Confirm no hardcoded color/dp literal exists outside `design-tokens.kt`
   in any hand-built escape-hatch screen (`ColorMatrixActivity`).
3. Update `TEMPLATE_CATALOG.md` to merge in the 5 new templates from the
   audit doc, so there's one canonical catalog going forward.

---

## 5. Definition of done, per template migration

- [ ] A typed entity exists with no Android View types in its fields
- [ ] Exactly one `render-*.kt` in `design-system` constructs the View for it
- [ ] The originating capability's `build.gradle.kts` no longer depends on
      the View-construction libraries it used to need for this shape
- [ ] Visual output is unchanged (screenshot/manual comparison)
- [ ] The scale slider visibly affects this shape with zero code in the
      originating capability
- [ ] `check_architecture.sh` (or its extension) fails the build if a
      second capability tries to construct this shape's View directly

---

## 6. Instruction to Claude for this implementation session

> Work one phase at a time from §4. Before writing any code, show me the
> exact entity shape and the renderer's signature for that phase and wait
> for my confirmation. After I approve, make the changes, then confirm the
> screen still renders identically and run the existing test suite before
> moving to the next phase. Do not skip Phase 5's audit — assume nothing
> about the 10 section files' shape until you've actually read them, the
> same way the original 9-template audit was done by reading real code,
> not guessing from file names.
