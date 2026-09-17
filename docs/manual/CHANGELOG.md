# Changelog

Covers the 6.0.0 line. Newest first. Each entry corresponds to one exported
zip; the zip filename is that change's diff, this file is the summary.

---

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
