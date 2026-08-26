# Changelog

## 5.6.0 — Color matrix screen under Display → render

`versionName` 5.5.0 → **5.6.0** (MINOR). `versionCode` unchanged at 1.

### Added — ColorMatrixActivity

New screen, opened from Display → render → "color" (sibling `NavCard` to
"layout"). Manifest-declared, `parentActivityName` pointing back to
`DisplaySettingsActivity`. Ships with the same blank/unwired `TabLayout`
every screen in this catalog carries by convention.

A 13-row × 7-column grid: rows are the app's real color families (grey,
red, orange, yellow, lime, green, mint, cyan, azure, blue, violet,
magenta, rose — all 13 defined in `colors.xml`), columns are the 7 real
lumen/tone stops (50, 100, 300, 500, 700, 800, 900). Every one of the 91
swatches' `android:background` references the actual
`@color/app_<family>_<stop>` resource — never a hardcoded hex copy — so
the grid always reflects `colors.xml`, never a frozen snapshot of it.
Scrolls vertically with the standard `App.PageScroll`/`App.PageContent`
chrome, same as every other screen.

Tapping a swatch reads that swatch's real color value at runtime
(`ContextCompat.getColor`) and shows family name, lumen code, real hex,
and token name in a one-line detail strip above the grid. The
family/lumen/token data comes from a `token → R.color` map cross-checked
1:1:1 against the layout's 91 swatch tags and backgrounds before this
shipped — no gaps, no typos, no orphaned entries either direction.

New dedicated dimension tokens (`app_color_matrix_cell_size` (40dp),
`app_color_matrix_row_header_width` (72dp),
`app_color_matrix_header_row_height` (32dp)) kept in their own namespace,
even though 40dp and 72dp both numerically coincide with unrelated
existing tokens (`app_size_calendar_row`, `app_row_boundary_rung_72`) —
same reasoning as prior rungs: two unrelated concerns should never
silently track the same value by coincidence of number.

New `App.ColorMatrix.*` styles: `DetailStrip`, `Row`, `RowHeader`,
`ColumnHeader`, `Swatch`. The swatch style deliberately puts its ripple
in `android:foreground`, not `background` — the real swatch color is
already set via `android:background` in the layout, and a ripple placed
in `background` instead would have been silently overwritten.

### Fixed — literal `--` inside XML comments, caught before this shipped

Found and fixed six instances (across this file, `activity_layout_demo.xml`,
and `themes.xml`) of a literal double-hyphen used as a dash separator
inside XML comment bodies — invalid per the XML spec, and the same class
of build-breaking mistake as the `App.SettingsCard.Body` implicit-parent
bug from 5.2.0. Swept every file touched this session with a proper
comment-body parser (not a naive text search, which false-positives on
em-dashes and box-drawing characters) to confirm no other instance
remained before packaging.

## 5.5.0 — Render tab becomes a card list; demo catalog moves to Layout screen

`versionName` 5.4.0 → **5.5.0** (MINOR). `versionCode` unchanged at 1.

### Changed — render tab restructured

Display's `render` tab previously held the full demo config catalog
directly. It's now a card list, same pattern as the main Settings page —
currently a single `NavCard`, "layout," which navigates to a new screen.
If this card list is ever empty, the tab shows a genuinely blank content
area — no placeholder message, no empty-state illustration, consistent
with the piece-sequence rule that optional content fully vanishes rather
than reserving dead space.

### Added — LayoutDemoActivity

New screen, opened from Display → render → "layout." Manifest-declared
with `parentActivityName` pointing back to `DisplaySettingsActivity`.
Hosts the demo config catalog that used to live directly in the render
tab (NavCard ×2, ToggleCard, ValueCard, DropdownCard, each with its real
metric caption) — moved verbatim, not recreated.

Ships with the same blank/unwired `TabLayout` pattern
`DisplaySettingsActivity` originally had before its own `ui`/`render`
tabs were wired up: present in the layout, not connected to any tab
content yet — an intentional extension point for this screen to grow
its own tabs later, the same way.

## 5.4.0 — Render tab: ToggleCard/ValueCard/DropdownCard demos + real metrics

`versionName` 5.3.0 → **5.4.0** (MINOR). `versionCode` unchanged at 1.

### Fixed — spacer inheritance bug, caught before release

`App.Row.Nav.Spacer` (added in 5.3.0, not yet in any released build) had
no explicit `parent=`, so it implicitly inherited `App.Row.Nav` →
`App.Row.Base` — which carries `minHeight="@dimen/app_row_min_height"`
(48dp), horizontal/vertical padding, and a ripple background. A spacer's
own explicit `layout_height` (6dp) does not override an inherited
`minHeight` that is larger, so the spacer was silently rendering at 48dp,
not 6dp — defeating the entire point of computing it. Fixed by giving
every spacer style an explicit `parent="App"` (the genuinely inert root
style already used by `App.List.Content` and `App.SettingsCard.Body`),
so a spacer never inherits row chrome it has no business carrying.

### Added — three more demo templates in the Render tab

`activity_display_settings.xml`'s render tab previously had only NavCard
demos. Added, each computed the same way NavCard's spacer was (real piece
heights, checked against the boundary-rung scale, spacer sized to close
the actual gap — never an invented number):

- **ToggleCard (0,1)** — `App.SwitchRow` + `App.SwitchRow.Description`.
  Piece 0 (~32dp, switch-dominated) + piece 1 (~20dp) + card padding
  (40dp) = 92dp → `App.Row.Toggle.Spacer` (+4dp) → new rung
  `app_row_boundary_rung_96`.
- **ValueCard (0,1,2,3)** — `App.ValueRow` + description + `App.Slider`
  + caption row. Pieces total ~104dp + card padding (40dp) = 144dp →
  `App.Row.Value.Spacer` (+16dp) → new rung `app_row_boundary_rung_160`.
- **DropdownCard (0,1)** — title + `App.TextInput.Dropdown`. Piece 0
  (~20dp) + piece 1 (~56dp, Material outlined dropdown intrinsic height)
  + card padding (40dp) = 116dp → `App.Row.Dropdown.Spacer` (+44dp) →
  reuses the same `app_row_boundary_rung_160` ValueCard just defined,
  since 116dp already falls under that rung.

New rung tokens (`app_row_boundary_rung_96`, `_160`) are named in their
own dedicated namespace deliberately, even where the numeric value
coincides with an unrelated existing token (96dp = `app_label_slot_sm`,
a label width; 160dp = `app_chart_height_xs`, a chart size) — reusing
those by number alone would make two unrelated concerns silently track
the same value by coincidence rather than by shared meaning.

### Added — real metric captions

Every demo card in the render tab, including the two pre-existing NavCard
ones, now shows a monospace caption underneath (`App.Render.Metric`)
stating the actual piece heights, the computed total, whether a spacer
was needed, and which rung it lands on — e.g. `piece 0 (48dp) + piece 1
(18dp) = 66dp -> App.Row.Nav.Spacer +6dp -> app_row_boundary_rung_72`.
This is the real number backing each demo, not a placeholder or a
rounded estimate.

## 5.3.0 — Display screen rename, ui/render tabs, template demo catalog

`versionName` 5.2.0 → **5.3.0** (MINOR). `versionCode` unchanged at 1.

### Renamed

`UiCustomizationActivity` → `DisplaySettingsActivity`. Follows through
internally, not just the on-screen title: class/file name, layout file
(`activity_ui_customization.xml` → `activity_display_settings.xml`),
`UiCustomizationPrefs` → `DisplayPrefs` (class/file, all call sites across
`MainActivity.kt`, `AlarmForegroundService.kt`, `SchedulerApplication.kt`,
`SettingsPage.kt`), manifest declaration, and the on-screen title
("UI Customization" → "Display").

**Breaking, by explicit request, no migration:** the SharedPreferences
on-disk filename also changed (`ui_customization_prefs` →
`display_settings_prefs`). Existing installs will have Dark Mode, Simple
Mode, SI Unit Format, Overlay Intent, Card Height, and Window Calibrate
settings reset to default the first time this update runs. `SettingsBackup.kt`
updated to match so future backups target the new filename; old backups
made before this version will no longer restore these settings.

### Added — ui / render tabs

The Display screen's `TabLayout` existed in the layout already but was
never wired to anything — added two tabs, `ui` and `render`, and connected
visibility switching in `DisplaySettingsActivity.kt` (same
`OnTabSelectedListener` pattern already used in `ProfileSettingsActivity`).

- **ui** — the screen's existing settings content, unchanged, now wrapped
  in `tabContentUi`.
- **render** — new. A demo-config catalog: instances of catalog templates
  (see `TEMPLATE_CATALOG.md`) built from the same production styles a real
  settings page uses (`App.Card`, `App.Row.Nav`, `App.Row.Title`,
  `App.Row.Subtitle`, `App.Text.Chevron`, `App.Row.Nav.Spacer`) — never a
  mock or a copy. Intended as the check surface a new template or piece
  variant is verified against before it is applied to a real settings page.
  Ships with two `NavCard` demos (piece subsets `(0)` and `(0,1)`,
  including the auto-inserted spacer); `ToggleCard`/`ValueCard`/
  `DropdownCard` demos are not yet included.

## 5.2.0 — Settings UI template system: reserved-slot rows, sliders, enforcement

`versionName` 5.1.1 → **5.2.0** (MINOR). `versionCode` unchanged at 1.

### Fixed after initial commit — same day

`App.SettingsCard.Body` was declared with no explicit `parent=`. Android's
dot-notation implicit inheritance strips the last segment of the name to
find its parent, so it silently tried to inherit from a style literally
named `App.SettingsCard` — which doesn't exist — and AAPT failed the build
with `resource style/App.SettingsCard not found`. This is the identical
failure mode `App.List.Content` hit in the previous session; it recurred
because nothing mechanically checked implicit-parent resolution, only that
referenced style *names* existed somewhere in `themes.xml`.

Fixed by adding `parent="App"` to the declaration. `scripts/check_ui_geometry.sh`
gained a 5th check ("Style implicit-parent resolution") that parses every
`<style>` tag in `values/themes.xml` and fails if a dotted name's implicit
parent — or an explicit `parent="..."` — doesn't resolve to either a locally
declared style or a known AndroidX/Material/platform root. Verified by
reintroducing the exact bug and confirming the check catches it before
re-fixing it.

### Why (original entry)

Every settings screen was independently hand-rolling the same three shapes —
a full-bleed toggle with a description, a value row with a slider beneath it,
and a nav row with a chevron — with the geometry baked into each call site as
raw `android:textSize`, `android:padding*`, and `android:margin*` attributes.
`activity_hardware_key_action.xml` had drifted furthest: raw `dp`/`sp`
literals throughout and the legacy `textPrimary`/`textSecondary` color tokens
instead of the `app_text_*` family the rest of the app had already moved to.
Two rows in the same card could end up at slightly different padding with
nothing to catch it, because nothing tied any of them back to one definition.

The specific bug this produces: a label placed next to a trailing value or
slider was sized to its own text (`wrap_content` / `0dp` + weight sharing
the row with the value), so the value's start position shifted per-row
depending on label length — "Sound Timeout" and "Gradual Volume Increase"
put their sliders at different effective starting points even though both
"looked anchored to the edge."

### Added — themes.xml / dimens.xml

- `app_label_slot_sm/md/lg` — reserved (not measured) label-width tiers, so
  a value-row's label claims a fixed box and the trailing value/slider
  always starts at the same relative X regardless of label text length.
- `App.SettingsCard.Body` — the vertical container every settings card's
  content sits in; replaces the padding block hand-copied into every card.
- `App.SwitchRow` / `App.SwitchRow.Description` — full-bleed toggle-with-title
  pattern (Simple Mode, SI Unit Format, Overlay Intent, Haptic Feedback).
- `App.ValueRow` / `App.ValueRow.Label` / `App.ValueRow.Value` — label
  (reserved slot) + trailing current-value text (Sound Timeout, Default
  Volume, Card Height, Action Volume, ...).
- `App.Slider` / `App.Slider.CaptionRow` / `App.Slider.CaptionStart` /
  `App.Slider.CaptionEnd` — slider plus its min/max caption row, with the
  same reserved-vs-measured discipline on the caption labels.
- `App.Row.Toggle.Flush` / `App.Row.Value.Flush` / `App.Row.Nav.FlushHorizontal`
  — named variants for a row nested inside a card that already supplies its
  own padding, replacing the ad hoc zero-padding overrides that were
  starting to appear at individual call sites.

### Changed — layouts migrated onto the template

`activity_hardware_key_action.xml`, `activity_ui_customization.xml`,
`activity_sound_vibration.xml`, `activity_profile_settings.xml`,
`activity_button_action.xml`, `activity_data_backup.xml`. No `@+id/` was
renamed or removed — every `findViewById` call in the corresponding
Activities resolves unchanged. Visual output is equivalent where the
original was already consistent, and corrected where it wasn't (hardware
key action screen now uses the same card/row/text tokens as every other
settings screen instead of raw dp/sp and legacy color tokens).

### Added — scripts/check_ui_geometry.sh

Ratchet-style guard, same convention as `check_architecture.sh`: fails CI on
new raw `dp`/`sp` literals, legacy `textPrimary`/`textSecondary`/
`textOnHeader` color tokens, `left`/`right` directional attributes, or a
style whose implicit or explicit parent doesn't resolve (see "Fixed after
initial commit" above). Dialog/list-item/toolbar-menu-action layouts
(`dialog_group_picker.xml`, `item_group_picker_*.xml`,
`fragment_stats_calendar.xml`, `menu_action_*.xml`) are grandfathered as a
separate component family, not settings screens — shrink that list as they
migrate; never add a new settings screen to it. The directional-anchoring
check (`left`/`right` vs `start`/`end`) is zero-tolerance with no grandfather
list: every layout already passes it today.

Run locally: `bash scripts/check_ui_geometry.sh`

### Not done in this pass

Dialog rows, list-item rows, and stats fragments still use their own
component conventions (single-line picker rows, not card+row settings
screens) — intentionally out of scope; migrating them is a separate pass
with its own template shape, not an extension of this one.

## 4.7.1 — Fix: two self-contradictory quota tests

`versionName` 4.7.0 → **4.7.1** (PATCH). `versionCode` unchanged at 1.
Test-only. No production code changed.

### First real test run: 60 of 62 passed

Both failures were mine, not the app's. Notably, all three `KNOWN BUG` /
`KNOWN GAP` assertions **passed**, which confirms the readings behind them:

- `TimerEffect.Expired.ranSeconds` really is hardcoded to `0L`
- `RtConfig.secondsUntilClose` really does ignore `isConfigured`
- `SchedTask.weight` really does divide by zero at `priority = 0`

Those are genuine bugs in `:core`, now locked in by passing tests. Fix them when
convenient; the tests will go red on purpose and should be updated deliberately.

### Cause of the two failures

`QuotaBudget.usedAt()`:

```kotlin
if (!isEnabled || periodStartEpochSeconds == 0L) return usedSeconds.coerceAtLeast(0L)
```

`periodStartEpochSeconds == 0L` is a **sentinel meaning "the period has not
started"**, not a real timestamp. I wrote one test asserting exactly that (it
passed), then wrote two more using `0L` as though it were a real epoch and
expected leak-back. The suite contradicted itself; the app was right both times.

### Fix

Leak-back tests now use a real start (`START = 1_700_000_000L`). Arithmetic
re-derived independently before shipping:

```
elapsed 43_200  -> replenished 1_800  -> used 1_800, remaining 1_800
elapsed 200_000 -> replenished 8_333  -> used 0,     remaining 3_600
```

`quota exceeded exactly at the limit` also moved off `0L` — it was passing via
the sentinel path rather than the behaviour it claimed to test.

### Added — the sentinel is now tested on purpose

`zero period start is a sentinel meaning not started` (QuotaBudget) and
`dl zero period start means the budget is untouched` (DlBudget, which has the
same short-circuit at `isBudgetActiveAt` line 83). Plus
`quota is not exceeded once enough has leaked back`, which exercises the real
replenishment path at the limit.

Recorded as a latent quirk rather than fixed: epoch 0 is also a legal instant
(1 Jan 1970), so a budget genuinely started then would never replenish. Not
reachable in practice, but it is why every other budget test uses a non-zero
start.

### Net

64 tests, expected all green. The safety net for the `TaskViewModel` work is now
verified rather than assumed.

---

## 4.7.0 — Phase 2d: break up the worst shared edit surface

`versionName` 4.6.0 → **4.7.0** (MINOR). `versionCode` unchanged at 1.
Behaviour-preserving refactor plus one new guard.

### Changed — `MainActivity.setupObservers()`: 213 lines → nine functions

This was the single worst merge-conflict surface in the app. Any feature that
observed anything edited it, so two people working in parallel collided here
constantly — and the conflicts were the nasty kind, both sides valid, resolution
requiring you to understand both features.

Now:

```
setupObservers()
  ├─ observeCallEvents()        auto-switch call detection
  ├─ observeTaskLists()         queue / schedule / completed tabs
  ├─ observeCurrentTask()       task identity + countdown text
  ├─ observeTimerCard()         the merged card, single source of truth
  ├─ observeNoticePhase()       phase bar + adapter segmented progress
  ├─ observeStatsAndToasts()    header stats, toasts, overrun counter
  ├─ observeDisplayToggles()    menu checkmarks, FAB visibility
  ├─ observeSync()              sync dot + restart-after-import
  └─ observeActionButtons()     Next/Auto and INT
```

Two features touching different concerns now touch different functions, and git
merges them cleanly.

**No logic changed.** Every block was already self-contained — no local crossed
a boundary — so this is a pure extraction. Verified mechanically: all 20
observers present, and a whitespace-insensitive diff of the whole file shows
exactly nine added declarations and nine added call lines, nothing else.

The longest function in the file dropped 213 → 60.

### Added — guard check `[7/7] Long-function ratchet`

**File length is the wrong metric.** This split made `MainActivity.kt` 65 lines
*longer* while making it much easier to work in. What hurts a team is one huge
function every feature must edit, not the file's total size. So the ratchet
measures functions:

```
longest: 199 lines (ceiling 199) — TaskAdapter.onBindViewHolder
over the 60-line target: 29 (debt baseline 29)
```

Two numbers, both one-way. No function may exceed today's worst, and the count
over target may shrink but never grow. Split something, lower `DEBT_COUNT`,
lock the win in.

Set to the measured state rather than an aspirational one — a limit of 60 fails
on 29 pre-existing functions on day one, and a guard that fails immediately is a
guard that gets disabled. Verified by bloating a function: caught, 29 → 30.

The worst offenders it surfaces, for when you want them:
`TaskAdapter.onBindViewHolder` (199), `SyncFieldGuard.detectConflicts` (64),
`BackupManager.taskToJson` (62) / `taskFromJson` (61).

### Still open

`TaskViewModel` (1151 lines). Its state is spread across ~40 LiveData fields
that its delegates already reach into, so extraction is a genuinely different
and riskier job than this one — not a pure line move. Best done with the
characterization tests confirmed green first.

---

## 4.6.0 — Phase 2c: freeze the `tasks` table

`versionName` 4.5.1 → **4.6.0** (MINOR). `versionCode` unchanged at 1.
No production code changed — this release is a guard, a guard-script check, and
a documentation correction.

### Added — `TaskSchemaFreezeTest`

`tasks` is pinned at its current 51 columns. Adding a 52nd fails the build with
a message pointing at `docs/SIDE_TABLE_TEMPLATE.md`.

This is the most important guard in the repo, because it is the only one that
prevents a cause rather than detecting a symptom. 51 columns and 21 migrations
exist because every feature ever added became columns on one shared row — and
that is precisely the mechanism by which a new feature breaks a working one
here. A new column forces edits to `Task`, `TaskDatabase`, `TaskDao`,
`TaskRepository`, `BackupManager`, `SyncFieldGuard` and every UI mapper: six
shared files, six chances to break something unrelated, on every feature.

`BackupRoundTripCoverageTest` and `TaskFieldClassificationTest` catch the
fallout. This one stops it happening.

The escape hatch is deliberate and narrow: a value the scheduler reads on every
tick (a `vruntime`-class field on the `tickQuotaOnVisibleItems` path, where a
join costs real frame time). Add it to `FROZEN_FIELDS` and justify it in the PR.
Editing that list should feel like a decision.

### Added — guard check `[4/6] The tasks table is frozen`

Counts `ALTER TABLE tasks ADD COLUMN` in `TaskDatabase.kt` against a measured
baseline of 39 (the legitimate history of migrations 1→21). Catches a widening
migration in two seconds, before the test suite runs, with a message that names
the alternative. Verified by planting a column: caught.

### Fixed — wrong backup guidance in `SIDE_TABLE_TEMPLATE.md`

Tracing `DataBackupActivity` showed export writes the **raw `.db` file** into a
zip and import replaces that file wholesale. **Side tables are therefore backed
up automatically** — the explicit export/import step the template previously
told you to write was unnecessary coupling.

Corrected, with the wrong advice left visible and struck through rather than
quietly deleted: "the doc said so" is exactly how unnecessary coupling gets
added, and the correction is more useful than a clean-looking document.

### Documented — sync is the real exposure, not backup

Sync is field-by-field JSON (`toSyncJson` / `fromSyncJson`) and knows only about
`Task`. **A side table is invisible to sync until explicitly handled.** It will
be correct locally and in backups, and silently absent on a peer device. Both
docs now require an explicit decision — local-only or synced — and a written
note either way. Silent divergence between two users' devices is among the
hardest bugs to diagnose in this app.

### Not in this release

The `MainActivity` (1281 lines) / `TaskViewModel` (1151) split. It is the last
big item and the riskiest, and it is the one place where the characterization
tests stop being insurance and become load-bearing. Worth running
`:testing:test` and `:data:testDebugUnitTest` before starting it.

---

## 4.5.1 — Fix: AlarmController lost the ringing alarm's data

`versionName` 4.5.0 → **4.5.1** (PATCH). `versionCode` unchanged at 1.

Fixes `:app:compileDebugKotlin` failure introduced in 4.5.0:

```
TaskViewModel.kt:296 Unresolved reference 'alarmState'
```

### Cause

4.5.0 collapsed `AlarmScheduler.currentState(ctx) is AlarmState.Ringing` into a
boolean `alarms.isRinging()`. That threw away the value itself — and the
app-kill recovery path further down the same block still needed
`alarmState.firedEpoch` and `alarmState.taskName` to work out how long the alarm
had been overrunning. Replacing a value with a predicate is only safe when the
value is genuinely unused; here it was not.

### Fix

`AlarmController` now returns a neutral snapshot instead of a boolean:

```kotlin
data class RingingAlarm(val taskName: String, val taskType: String, val firedEpoch: Long)

fun ringingAlarm(): RingingAlarm?
fun isRinging(): Boolean = ringingAlarm() != null   // default, for callers that only need the predicate
```

`AlarmControllerImpl` maps `AlarmState.Ringing` to it, so the sealed class still
never leaves the alarm feature. `TaskViewModel` uses
`val ringing = alarms.ringingAlarm(); if (ringing != null) { … }`, which also
gives a non-null smart cast for the rest of the block.

### Also cleaned

Two locals left dangling by the 4.5.0 rewiring — `val ctx = app` in
`TaskViewModel` and `val ctx = vm.app` in
`TaskNoticeStateMachine.triggerAlarmExpire`. Warnings rather than errors, but
they were dead. Every other file touched in 4.5.0 was scanned for the same
pattern and is clean.

---

## 4.5.0 — Phase 2b: service-control contracts

`versionName` 4.4.1 → **4.5.0** (MINOR). `versionCode` unchanged at 1.

**Cross-feature import edges: 5 → 1.** Combined with Phase 2a, the graph has
gone 15 → 1 and the feature packages are now, with one documented exception,
independently extractable into Gradle modules.

### Added — two contracts, implemented by the features that own the behaviour

`app.core.control.AlarmController` and `app.core.control.OverlayController`.

The implementations live **inside** the features they wrap —
`AlarmControllerImpl` in `feature/alarm`, `OverlayControllerImpl` in
`feature/autoswitch` — each with its own `@Binds` Hilt module. That placement is
the point: when `:feature:alarm` becomes a Gradle module it ships its binding
with it and `:app` needs no change. The dependency arrow now points inward to a
contract instead of sideways to a sibling.

Before / after:

```kotlin
AlarmForegroundService.timerPause(app)                 // task -> alarm
alarms.timerPause()                                    // task -> contract

AlarmScheduler.currentState(ctx) is AlarmState.Ringing // leaks a sealed class
alarms.isRinging()                                     // a boolean crosses cleanly

ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleOverlayService::class.java)
    .apply { action = BubbleOverlayService.ACTION_CALL_STARTED })
overlay.onCallStarted()                                // task -> contract
```

Two incidental improvements fell out of this:

- **No more `Context` at call sites.** The old static API took one everywhere.
  The implementations are `@Singleton`s holding the application context.
- **The bubble-enabled preference check moved into `OverlayControllerImpl`.**
  A caller in the task feature should not have to read autoswitch preferences
  to decide whether it is allowed to make a request.

### Added — `app.core.control.AlarmActions`

`MainActivity` depended on the alarm feature solely to read
`AlarmStopReceiver.ACTION_STOP_ALARM` — a string constant. Moved to a neutral
object; `AlarmStopReceiver` now re-exports it so there is still one source of
truth and nothing inside the alarm feature changed.

### Changed — `AutoSwitchActivity` reads the repository directly

It borrowed `TaskViewModel` for exactly one thing: `activeTasks.observe(...)`.
That is `TaskRepository`'s own LiveData, so the ViewModel was pure overhead plus
a cross-feature edge. Now `@Inject lateinit var repository: TaskRepository`.

### Removed — three dead imports

`BubbleOverlayService`, `CallStateReceiver` and `CallSwitchService` each
imported `TaskCallSwitchDelegate` while referencing it only in KDoc comments.
`MainActivity` imported `BubbleOverlayService` on the same basis. Four edges
that existed only on paper.

### Files rewired

`TaskViewModel` (constructor-injected `alarms` + `overlay`, `internal` so the
delegates in its package reuse them), `TaskNoticeStateMachine` (5 call sites via
`vm.alarms`), `TaskCallSwitchDelegate` (3 overlay call sites),
`CallSwitchService` and `BubbleOverlayService` (field-injected — both were
already `@AndroidEntryPoint`), `MainActivity`, `AutoSwitchActivity`,
`AlarmStopReceiver`.

### The one remaining edge

```
backup -> task    DataBackupActivity calls TaskViewModel.prepareForDbExport()
                  and .prepareForDbImport()
```

Deliberately left. Each is `pauseTimer()` + clear-current-task + a WAL
checkpoint/close. Extracting it needs a controller that can stop a running timer
from outside the ViewModel, and getting that wrong risks a half-written database
during a restore — the one operation where a bug destroys user data. It gets
done alongside the `MainActivity`/`TaskViewModel` split, where the timer
lifecycle is being reworked anyway.

### Verification performed

No compiler available here, so: guard green at the new 1-edge baseline; every
`package` declaration matches its directory; brace balance unchanged in every
touched file; no `AlarmForegroundService.` or `BubbleOverlayService.` reference
survives outside its own feature; `Context` arguments removed from all six
migrated `timerStart` call sites; imports orphaned by the rewiring
(`AutoSwitchPrefs`, `viewModels`) removed.

**Most likely compile issue:** Hilt now has to construct `TaskViewModel` with
two extra parameters. If `AlarmController` or `OverlayController` fails to
resolve, check that `AlarmControlModule` and `OverlayControlModule` were both
copied in — they are new files inside the feature packages, not in `app/di/`.

---

## 4.4.1 — Gradle wrapper committed

`versionName` 4.4.0 → **4.4.1** (PATCH). `versionCode` unchanged at 1.
Build tooling only; no application code changed.

### Added

- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`

Taken verbatim from the official Gradle repository at tag `v8.9.0`
(`raw.githubusercontent.com/gradle/gradle/v8.9.0/...`).

    gradle-wrapper.jar
      sha256  498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
      size    43504 bytes
      contains org/gradle/wrapper/GradleWrapperMain.class

Verify it yourself before trusting a binary from a zip:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
unzip -l gradle/wrapper/gradle-wrapper.jar | grep GradleWrapperMain
```

### Changed

- `gradle/wrapper/gradle-wrapper.properties` — upstream ships pointing at
  `gradle-8.9-rc-2-bin.zip`; repointed to **`gradle-8.9-bin.zip`** (final), which
  is what the project already specified. Also added `networkTimeout=10000` and
  `validateDistributionUrl=true` from the official template.
- `.gitignore` — explicit `!gradle/wrapper/gradle-wrapper.jar`, `!gradlew`,
  `!gradlew.bat`. Many Android `.gitignore` templates ignore `*.jar` and
  silently break the wrapper for everyone who clones.
- `README.md` — removed the `gradle wrapper --gradle-version 8.9` bootstrap
  step; it is no longer needed.

### Why this matters for a team

Without a committed wrapper, every developer and CI runner needs a matching
Gradle installed by hand. Different local Gradle versions are a direct source of
"works on my machine" build differences — the exact class of problem the rest of
this work is trying to eliminate.

### Note

`gradlew` needs its executable bit. Zip archives do not reliably preserve it:

```bash
chmod +x gradlew scripts/*.sh
```

---

## 4.4.0 — Phase 2a: collapse the cross-feature dependency graph

`versionName` 4.3.0 → **4.4.0** (MINOR). `versionCode` unchanged at 1.

Rationale: no user-visible behaviour changes, but this is structural work rather
than bug fixing — internal package locations and one public class name changed.
MINOR communicates "safe to take, but not a no-op patch."

**Cross-feature import edges: 15 → 5 (−67%).** That number is the whole point of
this release: those edges are what prevent the feature packages from becoming
real Gradle modules, and every one of them is a path by which a change in one
feature can break another.

### Moved — code that was filed under the wrong feature

None of these are logic changes. Each class was being reached across a feature
boundary because it lived in the wrong place, not because the coupling was real.

| Class | From | To |
|---|---|---|
| `TimerState` → **`TaskTimerState`** | `app.feature.task.timer` | `data.task.timer` |
| `TaskTimerExt` (`timerState`, `withTimerState`) | `app.feature.task.timer` | `data.task.timer` |
| `NotificationHelper` | `app.feature.notification` | `app.core.notification` |
| `SoundManager`, `VibrationManager` | `app.feature.settings` | `app.core.media` |
| `UiCustomizationPrefs`, `QuickActionPrefs`, `HardwareKeyPrefs` | `app.feature.settings` | `app.core.prefs` |
| `AutoSwitchPrefs` | `app.feature.autoswitch` | `app.core.prefs` |
| `RecentGroupPrefs` | `app.feature.task` | `app.core.prefs` |
| `BubbleEventBus`, `CallEvents` | `app.feature.autoswitch` | `app.core.signals` |
| `TaskSettingsDelegate` | `app.feature.settings` | `app.feature.task` |
| `TaskCallSwitchDelegate` | `app.feature.autoswitch` | `app.feature.task` |

`TimerState` operates on the `:data` `Task` entity and was used by both `task`
and `autoswitch`, so `:data` is its real home. The two delegates both exist to
serve `TaskViewModel` — `TaskCallSwitchDelegate` literally takes one in its
constructor — so they belong in the task feature.

The `app.feature.notification` package is now empty and removed.

### Renamed — `TimerState` → `TaskTimerState`

**Breaking for any code you have outside this zip.** There were two unrelated
classes named `TimerState`: the pure FSM state in
`core.scheduler.timer` and the persisted-task state formerly in
`app.feature.task.timer`. Once the latter moved into `:data`, both were
reachable from the same files.

This is not hypothetical — the collision produced four wrong import insertions
during this very refactor before being caught. Renaming removes the trap.

`timerState` and `withTimerState` (the extensions) keep their names.

### Added — `AppRoutes` navigation seam

`app.core.nav.AppRoutes` resolves screens by class **name** rather than by
`Activity::class.java`. A direct class reference is a compile-time dependency,
and it is exactly what would stop `:feature:task` and `:feature:stats` from
becoming separate modules. Eight navigation call sites now go through it:

```kotlin
startActivity(AppRoutes.stats(this))        // was Intent(this, StatsActivity::class.java)
AppRoutes.main(context).apply { flags = … }  // for the alarm PendingIntents
```

Intents are explicit (`setClassName` with the app's own package), so nothing
becomes interceptable by another app.

`AppRoutesTest` restores the safety the compiler used to provide: it resolves
every route with `Class.forName`, asserts each is an `Activity` subclass, and
reflects over the declared constants so a new route that is not added to
`ALL_ROUTES` fails the build.

### Changed

- `scripts/feature_import_allowlist.txt` rebaselined from 15 edges to 5, with
  each survivor documented and a named plan for removing it.

### Still coupled — the 5 remaining edges

All five are **service control**, which a file move cannot fix:

```
task -> alarm         TaskViewModel / TaskNoticeStateMachine drive
                      AlarmForegroundService, AlarmScheduler, AlarmState
task -> autoswitch    MainActivity starts/stops BubbleOverlayService
autoswitch -> alarm   CallSwitchService stops the alarm when a call starts
autoswitch -> task    autoswitch services observe TaskViewModel
backup -> task        DataBackupActivity reads TaskViewModel
```

Phase 2b: an `AlarmController` / `OverlayController` interface implemented in
`:app` and injected, so callers depend on a contract instead of a service class.

### Verification performed

No compiler was available here, so every change was verified mechanically:

- Zero stale references to any old package (`grep`, including the 14
  fully-qualified inline usages in `AlarmActivity`, `TaskViewModel` and
  `TaskCallSwitchDelegate` that a plain import rewrite would have missed).
- Every `package` declaration matches its directory.
- No file references both `TimerState` classes.
- Brace balance unchanged in every touched file.
- Architecture guard passes at the new 5-edge baseline.

---

## 4.3.0 — Phase 1: guard rails, characterization tests, build fixes

`versionName` 4.2.0 → **4.3.0** (MINOR). `versionCode` unchanged at 1.

Rationale: backwards-compatible functionality was added (feature flags, crash
isolation, sync field classification, test infrastructure) alongside bug fixes.
No existing public API changed, so this is not a MAJOR bump; it is more than
fixes, so it is not a PATCH bump.

32 files: 21 added, 11 modified, 0 deleted.

---

### Fixed — build blockers

- **`app/proguard-rules.pro` did not exist** but was referenced by
  `buildTypes.release`. `assembleRelease` could not complete. Added, with keep
  rules for Room entities, Hilt, Kotlin metadata, coroutines, MPAndroidChart and
  `org.json`. `Task` field names are kept explicitly — obfuscating them would
  break restore of any backup archive written by an older build.
- **Release builds were signed with the DEBUG key.** An app published that way
  can never be updated under a real key. Now reads `keystore.properties` from
  the project root (gitignored); falls back to debug signing with a loud build
  warning until you supply one.
- **`java.util.Properties` unresolved in `app/build.gradle.kts`.** In a `.kts`
  script a bare `java` resolves to Gradle's `JavaPluginExtension`, not the
  package. Fixed with a top-level `import java.util.Properties`.
- Replaced `org.gradle.internal.os.OperatingSystem` (an internal API that breaks
  between Gradle versions) with `System.getProperty("os.name")`.

### Added — regression guards

- **`scripts/check_architecture.sh`** — runs in ~2s, enforces five rules:
  1. No feature package may import another feature. 15 pre-existing edges are
     baselined in `scripts/feature_import_allowlist.txt`; the list may shrink,
     never grow. New edges fail with the exact file and line.
  2. `:core` stays free of Android / Room / Hilt imports.
  3. `@Database(version)`, the `MIGRATION_x_y` objects and the exported schema
     JSON must agree. Catches duplicate versions (two branches both writing
     `MIGRATION_21_22`), gaps, migrations declared but never registered in
     `addMigrations()`, and `fallbackToDestructiveMigration` (silent data loss).
  4. Global mutable state may not grow beyond the current count.
  5. No new file over 800 lines (`MainActivity` and `TaskViewModel`
     grandfathered until Phase 2).
- **`BackupRoundTripCoverageTest`** — reflects over `Task`'s constructor, fills
  all 51 fields with distinct sentinels, round-trips through the real
  export/import path, and names any field that did not survive. No hardcoded
  field list, so a new field cannot be silently dropped from backup.
- **`TaskFieldClassificationTest`** + **`TaskFieldClassification`** — every
  persisted `Task` field must be declared `CONTENT`, `OPERATIONAL` or
  `IDENTITY`. Previously `SyncFieldGuard` carried a private hand-maintained
  list, so a new content field could be silently blanked by a remote peer.
- **`TaskDatabaseMigrationTest`** — walks schema versions 1 → 21, both in one
  step and one version at a time, and asserts a row inserted at v1 still exists
  at v21. Schema validation alone does not catch a migration that recreates a
  table without copying rows.
- **CI** (`.github/workflows/ci.yml`) — architecture guard, unit tests, detekt,
  `assembleDebug`, and a check that Room did not export an uncommitted schema.
  PRs are merged against the target branch first, so a branch that is green
  against a stale `main` cannot pass.
- **detekt** (`config/detekt/detekt.yml`) — `ignoreFailures = true` for now so
  it does not fail on day one against 20k existing lines. Flip to false once the
  baseline is clean.
- **`CODEOWNERS`** — review gate on the shared surfaces: build config,
  `TaskDatabase.kt`, `Task.kt`, backup/sync, `:core`, and the guards themselves.

### Added — characterization tests for `:core`

Lock in current behaviour so the Phase 2 refactors cannot change it silently.

- `TimerEngineCharacterizationTest` — start/tick/pause/clear, sub-second
  remainder carry, expiry firing exactly once, reducer purity.
- `RtWindowCharacterizationTest` — activation windows including the
  midnight-crossing and Saturday→Sunday wrap branches.
- `CpuSharesCharacterizationTest` — proportional and pinned shares, over-pinned
  clamping, hierarchical scoping, pinned-weight convergence, Jain fairness.
- `BudgetCharacterizationTest` — quota leak-back, DL replenishment, and the
  EEVDF edge cases the existing test did not cover.

**Three real bugs found and locked in** (tests assert current behaviour and are
marked `KNOWN BUG` / `KNOWN GAP`; fix the bug, then update the test):

- `TimerEffect.Expired.ranSeconds` is hardcoded `0L`, so anything crediting run
  time from that effect records zero.
- `RtConfig.secondsUntilClose` never checks `isConfigured`, despite its KDoc
  promising 0 when inactive.
- `SchedTask.weight` has no floor: `priority = 0` divides by zero, producing an
  infinite `virtualDeadline`, and `advanceVruntime` then never advances that
  task — it can starve the queue.

### Added — runtime safety

- **`FeatureFlag` / `FeatureFlags` / `SharedPrefsFeatureFlags`** — local kill
  switches so a broken feature can be turned off instead of hotfixed.
- **`safeFeature { }` / `CrashIsolation`** — contains a crash at a feature's
  outer edge and reports it as a non-fatal, so a failure in stats or the bubble
  overlay cannot take down the alarm foreground service. Installed in
  `SchedulerApplication.onCreate`.

### Changed

- **`BubbleEventBus`** is now `StateFlow`-backed. The `var` API is unchanged, so
  all 27 call sites compile untouched; consumers can now `collect` instead of
  polling. Added `clearBubbleTap()` and `reset()`. This is the seam that makes
  the Phase 2 conversion to an injected `@Singleton` mechanical.
- **`TaskDatabase`**: `exportSchema = false` → `true`. Room now writes
  `data/schemas/*.json` at compile time.
- **`gradle.properties`**: heap 2 GB → 4 GB (KSP + Hilt + Room codegen across
  five modules was tight), `nonTransitiveRClass`, incremental KSP. Configuration
  cache left OFF deliberately — AGP 8.5 + KSP + Hilt is not reliably CC-clean.
- **`.gitignore`**: ignores `keystore.properties`; documents that
  `data/schemas/` must NOT be ignored.

### One-time setup after your first successful build

```bash
./gradlew :data:assembleDebug   # Room writes data/schemas/*.json
git add data/schemas            # commit them — migration tests need them
```

Until then the guard reports `PENDING` on that check.

### Not in this release (Phase 2)

Gated on a green build: splitting `MainActivity` (1281 lines) and
`TaskViewModel` (1151); promoting feature packages to Gradle modules; freezing
`tasks` and moving new feature data to side tables; multibound backup/sync
contributors; `BubbleEventBus` → injected singleton; `build-logic` convention
plugins; `explicitApi()`.
