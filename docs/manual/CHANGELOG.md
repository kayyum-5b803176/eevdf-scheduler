# Changelog

Covers the 6.0.0 line. Newest first. Each entry corresponds to one exported
zip; the zip filename is that change's diff, this file is the summary.

---

## 6.36.1 — Fixed blink never appearing: segments rebuilt every second

### Fixed
`buildPhaseStatusSegments` tore down and recreated all 7 segment views on
every call — and this bar refreshes every second, on every quota tick. A
blinking segment's animation restarted from full opacity every single time
its View was recreated, so it could never actually complete a visible fade.
Segments are now created once and updated in place on every later call;
the blink animation is only (re)started when the blink target actually
changes, not on every refresh. This also removes the "tear down all 7,
then rebuild" window entirely, which was a plausible source of the
inconsistent/partially-stale colors also reported.

## 6.36.0 — Quota-overage blink on the phase-status bar

### Added
When the phase-status bar has nothing else to show (exactly `[QUOTA]`
active — no delay/wait mixed in), one segment now slowly pulses to indicate
overage severity: the root-to-current ancestor chain (placement-aware, same
as the existing quota-exhaustion check) is walked, the WORST overage ratio
found anywhere in it is taken (not just the selected task's own — a distant
ancestor at 8x correctly outweighs the task's own 2x), and each doubling of
that ratio advances the blinking segment one further: 1x → segment 1, 2x →
segment 2, 4x → segment 3, capped at segment 7. Any other active state
(delay/wait) disables blinking entirely and reverts to the existing static
bar — the gate is checked before the severity calculation, not after.

## 6.35.2 — Centralized the door: TaskInstanceRef.of() ignored inherited membership

### Fixed
`TaskInstanceRef.of(item)` — the factory every call site uses to build a
ref from a row — only ever read a row's OWN direct `membershipId`, never
falling back to `entryMembershipId` (the door a leaf inherits from an
ancestor hardlink placement it's merely nested inside). `matchesInstance`
had the identical gap on the comparison side. Only one call site
(`onRunClick`) had ever manually combined the two fields correctly — every
other consumer of `.of()`, and the comparison function itself, disagreed
with it. A ref could be built correctly at the moment of selection and then
fail every subsequent "is this the one" check, because the type's own
factory and its own comparison function used different rules than the one
place that built it right.

Fixed centrally, once, in the type itself — `.of()` and `matchesInstance`
now both use the same combined-door logic — instead of requiring every
caller to remember to combine `membershipId ?: entryMembershipId`
correctly on its own, which is exactly how this was missed for as long as
it was. Simplified `onRunClick` to just call `TaskInstanceRef.of(item)`
directly now that the type itself gets this right.

## 6.35.1 — The actual dominant bug: field-refresh calls silently reset placement

### Fixed
Every place that updates a field on the ALREADY-selected task (tick, pause,
reset, expiry, notice reset, post-run vruntime credit) calls
`currentTaskOwner.set/setAsync` again — and the default parameter recomputes
"real" every time, since it has no way to know these are refreshes rather
than a real switch. Two of these fire constantly:

- **The per-second tick observer** — every single tick while any timer
  runs was silently resetting the placement back to real. This is almost
  certainly why a hardlink selection appeared to "become real" the moment
  Start was pressed — the tick fires within the same second.
- **The expiry observer** — reset to real at the exact moment accounting
  matters most, explaining "when it expires it credits the real task."

Also fixed the identical pattern in `pauseTimer`, `resetTimer`,
`NoticeStateMachine`'s pause-phase reset, and the post-run vruntime-credit
refresh — all now explicitly preserve `currentInstanceRef.value` instead of
letting the default silently overwrite it.

**A third, entirely separate mechanism** with the same bug: phone-call
interrupt handling (`CallSwitchDelegate`) has its own `savedTaskBeforeCall`,
never touched by the earlier interrupt-delegate fix — same fix applied,
`savedRefBeforeCall` added alongside it.

**`jumpToFirst`** was discarding the found row's placement by grabbing
`.task` directly instead of building a ref from the whole row.

**Cold-start / cross-device recovery** (`StartupRecoveryDelegate`,
`TaskViewModel`'s dead-Activity recovery): a bare DB query has no placement
info of its own — now falls back to whatever was persisted for that same
task, instead of defaulting to real. One of these had a compounding bug:
it defaulted to real AND then immediately re-saved that wrong "real" back
into the persisted preference on the very next line — permanently
destroying a correctly-saved placement on every cold start.

## 6.35.0 — No bare task id may mean "the selected/running one," anywhere

### Fixed
Found via a screenshot showing a real task and its hardlink both displaying
a live "00:02" countdown simultaneously: `TaskAdapter.runningTaskId` was a
plain `String?`, matched by bare task id (`task.id == runningTaskId`) —
completely separate from all the `TaskInstanceRef` work in 6.34.0, and never
touched by it. Every row sharing that task id — real, hardlink, or symlink —
lit up as "running" together, because the one signal deciding that was never
placement-aware to begin with. This was the actual visible bug the whole
`TaskInstanceRef` effort had been chasing.

### Changed
Applied the same rule everywhere this pattern was found in the adapter
layer, not just the one spot: `runningTaskId` → `runningInstance`,
`selectedTaskId` → `selectedInstance` (tap-highlight, same bug shape), both
now `TaskInstanceRef?`, matched via `TaskDisplayItem.matchesInstance` (exact
placement) instead of bare id. `positionOf` gained a placement-aware
overload alongside its existing bare-id one (kept separate — the bare-id
version is still legitimately used by the unrelated notice-phase mechanism).

**Persisted selection** (survives app restart) had the identical gap in
storage form: only the task id was ever saved, so a hardlink/symlink
selection reverted to the real placement after every reboot — including the
*read-back* path on cold start, which is worth calling out on its own,
since it would have silently undone the fix even after fixing the save
side. Added two more optional preference keys (membership id, symlink id)
alongside the existing one; old saved prefs with only a task id simply mean
"no membership/symlink" — safe, no migration needed.

### Explicitly not touched
Vruntime/quota per-hardlink accounting — confirmed working correctly and
intentionally left exactly as it is; this pass was scoped only to
selection/running-state representation.

## 6.34.3 — Fixed missing door propagation in the class-tab builder

### Fixed
`buildFilteredScheduleList` (the Deadline/Realtime/Fair tab builder) never
threaded the "door" (which hardlink placement, if any, a row was reached
through) down its recursive walk at all — a leaf merely nested inside a
hardlinked group's subtree had no way to record that, and silently fell
back to the real placement the moment it was selected. This was a separate,
concrete gap from the current-task/ref race fixed in 6.34.2 — likely the
actual remaining cause of "hardlink with a clean quota chain still shows
red." Fixed by threading the door through `render`/`renderOwnedSubtree` the
same way the older, pre-existing list builders already do it correctly.

### Known related gap (pre-existing, not introduced here)
There is no equivalent mechanism anywhere in the app for a leaf nested
inside a *symlinked* group — only hardlink placements have a "door" concept
today. A symlink pointing at a group and expanded to show its real children
has no way to record that inheritance either.

## 6.34.2 — Fixed a race between current-task and its placement ref

### Fixed
`CurrentTaskOwner.set`/`setAsync` updated `_current` before `_instanceRef`.
Since a `MutableLiveData` notifies its observers the instant its value is
set — synchronously for `.value =`, and in call-order for `postValue` on the
same thread — anything observing `currentTask` (like the quota-exhaustion
refresh) could fire and read `currentInstanceRef.value` one step too early,
seeing the PREVIOUS placement instead of the one just selected. This is what
caused both regressions reported after 6.34.0: a hardlink with a clean
quota chain still showing red (stale ref from before), and a real task's
exhausted-parent indicator only appearing after the task actually ran
(waiting for a later, coincidentally-correct refresh). Fixed by setting the
ref first, the task second, in both functions.

## 6.34.1 — Fixed TaskInstanceRef visibility compile error

### Fixed
`TaskInstanceRef` and its extension functions were `internal`, but
`TaskViewModel` (a public class) exposes them through public members
(`currentInstanceRef`, `setCurrentTask`) — Kotlin doesn't allow a public
declaration to expose an internal type. Made `TaskInstanceRef` and its
extensions public to match the visibility of what already exposes them.

## 6.34.0 — TaskInstanceRef: placement-aware selection (permanent link fix)

### Added
`TaskInstanceRef` (task id + symlinkId + membershipId) — reuses the same
identity triple `TaskDisplayItem`/the RecyclerView diff callback already use
for row identity, promoted to a first-class concept. `CurrentTaskOwner` now
tracks this alongside the bare `Task` (`vm.currentInstanceRef`), so "what's
selected" can finally answer not just *which task* but *which placement of
it* — a real task, or a specific hardlink/symlink.

This closes one root cause behind four independent-looking bugs that were
all the same underlying gap:

### Fixed
- **Quota-exhaustion indicator** walked the wrong ancestor chain for a
  linked selection (used the real task's own parent instead of the actual
  placement's host).
- **Tap-to-run** discarded placement context immediately for symlinks (never
  handled at all before this) and only partially for hardlinks.
- **Interrupt jump/return** (`InterruptDelegate`) saved a bare task id as
  "what to return to," so returning from an interrupt always landed on the
  real placement, never the hardlink/symlink actually running before the
  interrupt (in-memory only — a return-to saved right before a reboot still
  falls back to the real placement, since there's no DB column for the
  placement yet).
- **Alarm/expiry re-seat** (`stopAlarmSound`) had the same loss — now
  captures and restores the placement active at the moment of expiry.
- **"Next"/"Global Rotate"/"Auto"** (`rotateSiblings`, `rotateGlobal`,
  `selectAutoNextTask`) matched "current" by bare task id, which could
  anchor on the wrong occurrence when a real task and a link to it were both
  on screen, and could land on/return the wrong placement too. All three now
  match and land by exact placement.

### Known remaining gap
The DB has no column yet for persisting *which placement* an interrupt
return-to was saved through — only the in-memory case is fully fixed. A
schema change would be needed to close this the rest of the way.

## 6.33.4 — Fixed timer-card flicker on alarm stop

### Fixed
`stopAlarmSound()` cleared the "alarm ringing" flag before re-seating the
task that triggers the card's normal (Start/Pause) content. That left a
brief window where neither "alarm ringing" nor "task selected" was true —
read as Hidden, closing the card — until the very next line re-seated the
task and reopened it. That close-then-reopen was the flicker. Re-seating
the task first, then clearing the alarm flag, means a task is already
selected the moment the flag clears, so the card goes directly from
expired to its normal content in one step. No button or functional
behavior changed — this is a statement-order fix only.

## 6.33.3 — Phase-status bar always drawn, neutral when idle

### Changed
The strip no longer goes `INVISIBLE` when nothing is active — it now always
draws all 7 segments, same size either way, using the neutral `divider`
track color at rest (same tone the card's other progress bars already use
for "nothing here yet") instead of disappearing. Same principle as a plain
`ProgressBar` always showing its empty track at 0%.

## 6.33.2 — Matching reserved space on the expired/alarm card

### Fixed
Added an inert spacer of the same height/margin as `viewPhaseStatus` to the
expired/alarm block, so the timer card doesn't change overall height when
it swaps between the countdown and expired/alarm states. Purely a layout
matcher — this block doesn't run notice/quota state, so nothing is wired to
the spacer.

## 6.33.1 — Phase-status bar reserves its space when empty

### Fixed
Switched from `GONE` to `INVISIBLE` when no state is active, so the strip's
space stays reserved on the card instead of collapsing and shifting the
button row up/down as states come and go.

## 6.33.0 — Generalized phase-status bar; quota-exhaustion indicator

### Added
`viewPhaseStatus` (the timer card's status strip, previously hardcoded to
NOTIFICATION delay/wait only) is now a general-purpose indicator any feature
can report through. It's built as 7 fixed segments; when more than one
state is active at once, colors ping-pong across the 7 segments in a fixed
priority order (quota outermost, wait innermost) rather than picking just
one to show.

New: quota-exhaustion detection, checked from the root of the task tree down
to the currently-selected task — the first exhausted ancestor found (own
task included) lights the strip red; nothing below that ancestor needs
checking once one is found.

### Changed
Moved the strip from the bottom edge of the Next/Start/Int button row to
above it. Colors now come from real design-system tokens
(`quotaBarExceeded`, `timerYellow`, `timerGreen`) instead of hardcoded hex.

## 6.32.2 — Fair tab no longer "owns" a subtree

### Fixed
The "owned subtree — show everything inside unconditionally" rule (built for
Deadline/Realtime-owned groups, e.g. a group whose own class is Deadline
showing its entire contents regardless of each child's own class) was
accidentally also firing for plain Fair-class groups, since Fair is every
ordinary group's default class. On the Fair tab this dragged in Deadline/RT
descendants unfiltered. Only Deadline/Realtime can trigger unconditional
full-subtree inclusion now — a Fair-class group always recurses through
normal relevance filtering, same as any other plain container.

## 6.32.1 — Realtime same-level sort uses window proximity, not priority

### Changed
Sibling Realtime groups/tasks on a class-filtered tab now sort by which one
is closest to its next window transition — time left before it closes if
currently active, time left until it opens if not — instead of raw
`rtPriority`. Matches the Deadline tab's "nearest in time" logic.

## 6.32.0 — Same-level urgency sort on class-filtered tabs

### Added
At every depth, independently, a group's children are ordered by the most
urgent Deadline/Realtime/Fair match reachable inside each child —
recomputed live as task state changes. This is same-level ordering only: it
never changes which parent shows, or how a parent ranks among *its own*
siblings (that would be hoisting, deliberately excluded — see 6.30.0).

### Fixed (self-caught, pre-export)
Mid-edit, an accidental deletion of a function's own signature line left its
body orphaned under the wrong function name. Caught via a brace-balance
check before shipping.

## 6.31.3 — Auto-dive no longer blocked by an empty/collapsed sibling

### Fixed
A collapsed or empty group was still being counted as a real "candidate at
this level" when deciding whether to auto-dive, so closing an empty Group B
stopped "Next" from diving into Group A even though A had real children to
rotate through. A group now only counts toward that decision if it can
actually produce something to land on.

## 6.31.2 — Next/rotate navigate by screen position, not `task.parentId`

### Fixed
Rewrote the structural lookups behind "Next" to use on-screen depth and list
order instead of `task.parentId`. This is what makes hardlinks and symlinks
fully navigable: a linked row's underlying task still carries its original
real parent, so parentId-based lookups silently failed to treat it as a
child of wherever it's actually displayed. Depth/order-based lookups don't
need to know or care whether a row is a real task, a hardlink placement, or
a symlink — only where it's drawn on screen right now.

## 6.31.1 — Restored real depth-resolution mechanism, drill-down aware

### Fixed
Reverted an earlier from-scratch reinvention of "Global Rotate" (which
anchored depth resolution from the root and had a real bug) back to the
original correct mechanism — re-pointed at the screen-bound display lists,
with one necessary addition: depth resolution now starts from whatever
parent id is actually common to the visible list's own top level, instead
of a hardcoded `null` root. That's what makes it correctly stay within a
drill-down frame instead of trying to escalate past what that frame can
show.

## 6.31.0 — "Global Rotate" restored (screen-aware rebuild)

### Added
Brought back the "Global Rotate" menu toggle (removed in 6.30.2) and its
auto-depth-diving behavior — rebuilt to read only from the screen-bound
task lists, so it can no longer drift out of sync with whatever tab, class
filter, or collapse state is currently showing.

## 6.30.2 — "Global Rotate" removed; "Next" fully screen-aware

### Changed
Every button in `SchedulerDelegate` (Next, rotate, jump-to-first) now reads
directly from the same `LiveData` the screen's adapter is bound to, instead
of separately re-querying/re-deriving "roughly the same list" — the root
cause of Next silently doing nothing once class-filtered tabs existed.

### Removed
"Global Rotate" and its raw-tree-walking implementation, as part of the same
pass (later restored correctly in 6.31.0 / 6.31.1).

## 6.30.1 — Hardlink/symlink class-tab fix

### Fixed
Hardlink placements (`TaskMembership`) weren't visible on class-filtered
tabs at all — the tree walk never called `EEVDFScheduler.withMemberships(...)`,
so a hardlink placement (which only exists as a synthetic entry that
function creates) was invisible. Each hardlink placement is now evaluated
independently along its own path, matching how the app already tracks
per-placement vruntime.

## 6.30.0 — Descendant-hoisting removed from the Schedule tab; link propagation added

### Fixed
The default Schedule tab's own row-ordering still had the "promote a
fair-class group because a descendant is Deadline/RT" behavior
(`hasActiveDlDescendant` / `hasActiveRtDescendant`) baked into its bucket
sort — a leftover predating this line's scheduler-fundamentals work. A
group's bucket is decided entirely by its own class now, never a
descendant's.

### Added
Symlinks/hardlinks gained correct "upward propagation" (a linked row shows
its real target's live class/state) on the class-filtered tabs. Every
button in `SchedulerDelegate` was moved onto the real screen-bound lists as
part of the same pass.

## 6.29.3 — Schedule/Fair menu rows get the same blank dot as Deadline/Realtime

### Fixed
All four popup-menu rows now carry the same blank/white status dot by
default, instead of only Deadline/Realtime having one — keeps every row's
label aligned regardless of which tab is selected.

## 6.29.2 — Popup-menu dot colors moved to design-system tokens

### Changed
Replaced hardcoded hex colors for the class-filter popup's status dots with
real `colors.xml` tokens (`classFilterDotBlank` / `OneActive` / `TwoActive` /
`ThreeOrMoreActive`), matching the existing `syncDotOK` / `syncDotError`
naming convention already used elsewhere in the app.

## 6.29.1 — Popup menu status dots

### Added
Deadline/Realtime rows in the class-filter popup carry a plain colored dot
reporting how many of that class are active right now: blank at 0, blue at
1, green at 2, red at 3+.

## 6.29.0 — Schedule tab class filter (Deadline / Realtime / Fair / Schedule)

### Added
Re-tapping the Schedule tab opens a popup letting the person narrow it to
one scheduler class. The tab's own label mirrors whichever class is
selected. Badge shows the count of classes more urgent than the current
selection that aren't visible in it.

### Fixed
Popup now centers on the actual tapped tab — was anchoring flush-left on
the whole tab bar regardless of which tab was tapped.

### Changed
"RT" relabeled "Realtime" in the popup menu (the on-card pill stays "RT").
"All" renamed to "Schedule" as the default/unfiltered state.

## 6.28.0 – 6.28.3 — Class-filtered tab tree correctness

### Fixed
Early iterations of the class-tab feature and its bug fixes: parent
structure preserved as a full real ancestor chain to root rather than
truncated to one level; collapse/expand made to work correctly inside a
filtered tab (a collapsed group used to disappear entirely instead of
staying as a re-openable row); "Next" made aware of the active filter so it
stopped jumping to tasks not actually visible on the current tab.

## 6.27.1 – 6.27.3 — Single clock source; current-task dispatch via bus

### Changed
Every capability now reads "now" through the kernel's one injected `Clock`
instead of calling `System.currentTimeMillis()` independently (`object`
singletons that Hilt can't field-inject use a swappable `var clock` test
seam instead). "Which task is currently running" is now owned by one
`CurrentTaskOwner`, announced over the event bus (`Topics.CURRENT_TASK_CHANGED`)
instead of ~30 scattered direct writes to a shared LiveData field across the
app.
