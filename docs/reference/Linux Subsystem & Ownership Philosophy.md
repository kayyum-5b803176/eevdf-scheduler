# Linux Subsystem & Ownership Philosophy

## A General Architecture Principle for Building Software That Can Scale

---

# 1. Purpose

Large software systems eventually face the same problem:

> **Where does new code belong?**

When a project is small, almost any structure works.

You can have:

```text
ui/
model/
utils/
manager/
service/
repository/
```

With 20 files, this is manageable.

With:

- 1,000 files
- 10,000 files
- hundreds of developers
- dozens of independent capabilities

the same structure can become difficult to navigate and maintain.

The Linux kernel demonstrates a different philosophy:

> **Divide the system according to responsibility. Give each responsibility a clear owner. Allow complexity to grow inside that owner's boundary.**

This document describes that philosophy as a reusable architecture principle.

---

# 2. The Central Principle

The fundamental idea is:

# **Every responsibility should have an owner.**

When a new piece of code is created, the primary question should be:

> **Who owns this responsibility?**

Not:

> What programming language is it?

Not:

> What type of class is it?

Not:

> Is it a manager, helper, controller, or service?

Instead:

> **Which subsystem is responsible for this behavior?**

The answer determines where the code belongs.

---

# 3. What Is a Subsystem?

A subsystem is a bounded part of a larger system that owns a particular responsibility.

Conceptually:

```text
SYSTEM
│
├── Subsystem A → owns responsibility A
├── Subsystem B → owns responsibility B
├── Subsystem C → owns responsibility C
└── Subsystem N → owns responsibility N
```

For example, an operating system may have responsibilities such as:

```text
Memory
Networking
Filesystems
Devices
Security
Scheduling
```

Each responsibility becomes a conceptual subsystem.

The exact folder names are not important.

The ownership boundaries are.

---

# 4. Ownership Answers the Placement Problem

Suppose you create a new file:

```text
TaskTimer.kt
```

There are two possible ways to organize it.

## File-Type Thinking

Ask:

> What kind of file is this?

Possible result:

```text
timer/
└── TaskTimer.kt
```

Later:

```text
timer/
├── TaskTimer.kt
├── MusicTimer.kt
├── NetworkTimer.kt
├── AnimationTimer.kt
└── ...
```

These files may have completely unrelated owners.

They are together only because they share a technical label.

---

## Ownership Thinking

Ask:

> Who owns the responsibility of this timer?

If the answer is:

> The Task subsystem

then:

```text
feature/
└── task/
    └── TaskTimer.kt
```

The code lives with the subsystem that owns its behavior.

This is the fundamental difference.

---

# 5. Responsibility Before File Type

A large system should primarily be organized around:

```text
RESPONSIBILITY
        ↓
OWNERSHIP
        ↓
SUBSYSTEM
        ↓
INTERNAL STRUCTURE
```

Not:

```text
FILE TYPE
        ↓
GLOBAL CATEGORY
```

For example:

## Weak organization

```text
project/
├── activities/
├── viewmodels/
├── adapters/
├── repositories/
└── managers/
```

A single feature is scattered throughout the project.

---

## Strong ownership organization

```text
project/
├── task/
│   ├── TaskActivity.kt
│   ├── TaskViewModel.kt
│   ├── TaskAdapter.kt
│   └── TaskRepository.kt
│
├── settings/
│   ├── SettingsActivity.kt
│   └── SettingsViewModel.kt
│
└── statistics/
    ├── StatsActivity.kt
    └── StatsRepository.kt
```

The file type is secondary.

Ownership is primary.

---

# 6. The Territory Model

Think of the software system as a map.

```text
┌──────────────────────────────────────────┐
│                  SYSTEM                  │
│                                          │
│   ┌──────────┐       ┌──────────┐        │
│   │ MEMORY   │       │ NETWORK  │        │
│   │  OWNS    │       │   OWNS   │        │
│   │ MEMORY   │       │ NETWORK  │        │
│   └──────────┘       └──────────┘        │
│                                          │
│   ┌──────────┐       ┌──────────┐        │
│   │ STORAGE  │       │ SECURITY │        │
│   │  OWNS    │       │   OWNS   │        │
│   │ STORAGE  │       │ SECURITY │        │
│   └──────────┘       └──────────┘        │
│                                          │
└──────────────────────────────────────────┘
```

Each subsystem has a territory.

The territory answers:

- What does this subsystem own?
- What changes belong here?
- What code belongs here?
- What behavior is this subsystem responsible for?

A good subsystem boundary makes these answers obvious.

---

# 7. High Cohesion

A subsystem should contain things that strongly belong together.

This is called:

# **High Cohesion**

Example:

```text
feature/
└── task/
    ├── TaskScreen.kt
    ├── TaskViewModel.kt
    ├── TaskState.kt
    ├── TaskTimer.kt
    └── TaskActions.kt
```

These components belong together because they participate in the same responsibility.

When the Task subsystem changes, many of these files may change together.

That is a good sign.

The subsystem has high cohesion.

---

# 8. Low Coupling

Subsystems should not depend excessively on each other's internal implementation.

Bad:

```text
Task
 │
 ├────────► Settings internals
 │
 ├────────► Statistics internals
 │
 └────────► Backup internals
```

Eventually:

```text
Task ───── Settings
 │ ╲       ╱
 │  ╲     ╱
Stats ─── Backup
```

Every subsystem becomes connected to every other subsystem.

This is high coupling.

High coupling makes changes dangerous.

---

A better model is:

```text
Task
 │
 ▼
PUBLIC INTERFACE
 │
 ▼
Other subsystem
```

Or:

```text
Subsystem A
      │
      ▼
   Contract
      ▲
      │
Subsystem B
```

Subsystems communicate through controlled boundaries.

Their internal implementation remains private.

This creates:

# **Low Coupling**

---

# 9. Stable Boundaries, Flexible Internals

One of the most important principles of scalable architecture is:

> **Keep the boundary stable. Allow the inside to change.**

A subsystem should look conceptually like this:

```text
OUTSIDE WORLD
      │
      │
      ▼
┌────────────────────────────┐
│       PUBLIC BOUNDARY      │
├────────────────────────────┤
│                            │
│     INTERNAL SYSTEM        │
│                            │
│   implementation can grow  │
│   implementation can change│
│   implementation can be    │
│   completely replaced      │
│                            │
└────────────────────────────┘
```

The outside world should depend on:

```text
What the subsystem promises
```

Not:

```text
How the subsystem currently works internally
```

---

# 10. Public API vs Internal Implementation

A subsystem can expose:

```text
subsystem/
├── api/
│   └── PublicInterface.kt
│
└── internal/
    ├── ImplementationA.kt
    ├── ImplementationB.kt
    └── InternalState.kt
```

Other subsystems should ideally know only:

```text
api/
```

They should not directly depend on:

```text
internal/
```

This allows:

```text
Implementation Version 1
```

to become:

```text
Implementation Version 2
```

without forcing the entire system to change.

The contract remains stable.

The implementation evolves.

---

# 11. Complexity Should Grow Downward

A scalable system should not continuously expand its top-level structure.

Bad:

```text
project/
├── feature-a/
├── feature-b/
├── feature-c/
├── new-system-1/
├── new-system-2/
├── new-system-3/
├── experimental/
├── helpers/
├── managers/
└── misc/
```

The root becomes increasingly difficult to understand.

Instead:

```text
project/
├── stable-system/
├── platform/
├── infrastructure/
└── features/
    ├── feature-a/
    ├── feature-b/
    ├── feature-c/
    └── feature-n/
```

When Feature C becomes complex:

```text
features/
└── feature-c/
    ├── subsystem-a/
    ├── subsystem-b/
    ├── subsystem-c/
    └── internal/
```

The complexity grows downward.

Not outward.

# **Grow inside boundaries before creating new global boundaries.**

---

# 12. Stable Roots and Growth Surfaces

A large architecture should distinguish between:

## Stable areas

Areas that define the fundamental structure of the system.

```text
core/
platform/
contracts/
shared/
```

These should change relatively slowly.

---

## Growth surfaces

Areas designed to accept continuous new capabilities.

```text
features/
```

This creates:

```text
STABLE ROOT
     │
     ├── Core
     ├── Platform
     ├── Contracts
     └── Features ← unlimited growth
             │
             ├── A
             ├── B
             ├── C
             └── N...
```

This is one of the strongest ways to prevent architectural chaos.

---

# 13. The Subsystem Boundary Rule

A subsystem should answer three questions.

## 1. What do I own?

Example:

```text
Task subsystem

Owns:
- task behavior
- task UI
- task state
- task-specific actions
```

---

## 2. What do I expose?

Example:

```text
Task subsystem exposes:

- TaskEntry
- TaskService interface
- Task events
```

---

## 3. What is private?

Example:

```text
Private:

- internal state machines
- internal adapters
- implementation details
- temporary helpers
```

If these three things are clear, the subsystem has a strong boundary.

---

# 14. The Ownership Decision Algorithm

When adding new code, use this process.

```text
NEW CODE
   │
   ▼
Does one subsystem clearly own it?
   │
 ┌─┴───────────────┐
 │ YES             │ NO
 ▼                 ▼
Put it inside      Is it a new independent
that subsystem    responsibility?
                   │
              ┌────┴────┐
              │ YES     │ NO
              ▼         ▼
          Create a     Find the
          subsystem    existing owner
```

Before creating a global folder, always ask:

> **Does an existing subsystem already own this responsibility?**

Usually, the answer should be yes.

---

# 15. Shared Code Is an Exception

A common mistake is creating:

```text
common/
shared/
utils/
helpers/
misc/
```

as dumping grounds.

The ownership philosophy says:

> **Shared code should exist only when no meaningful subsystem owns it.**

Ask:

```text
Who owns this code?
```

If:

```text
Task owns it
```

then:

```text
feature/task/
```

If:

```text
Settings owns it
```

then:

```text
feature/settings/
```

Only when:

```text
No subsystem owns it
AND
multiple independent subsystems genuinely need it
```

should it become:

```text
shared/
```

---

# 16. Do Not Share Too Early

This is an important rule.

Suppose two features contain similar code.

Do not automatically do this:

```text
shared/
└── UniversalManager.kt
```

Ask first:

> Is this actually the same responsibility?

Two pieces of code may look similar but belong to different subsystems.

Premature sharing creates unnecessary coupling.

A safer evolution is:

```text
Feature A implementation
Feature B implementation
```

Then, after the common concept becomes genuinely clear:

```text
Shared abstraction
```

The abstraction should emerge from proven commonality.

Not speculation.

---

# 17. The Linux-Style Layering Principle

A large system often has different kinds of responsibilities.

For example:

```text
SYSTEM
│
├── Fundamental logic
├── Platform implementation
├── Infrastructure
└── Product capabilities
```

These are different ownership domains.

A reusable architecture might therefore become:

```text
project/
│
├── core/          Fundamental rules
├── platform/      OS-specific implementation
├── infrastructure/ Shared technical systems
├── contracts/     Stable boundaries
├── shared/        Generic ownerless utilities
│
└── features/      Product capabilities
```

The names can change.

The ownership principle should remain.

---

# 18. Do Not Copy Linux Folder Names

Linux contains directories such as:

```text
arch/
drivers/
fs/
mm/
net/
```

These names exist because Linux has responsibilities such as:

- hardware architecture support
- device drivers
- filesystems
- memory management
- networking

Your application may have completely different responsibilities.

For example:

```text
music/
library/
player/
search/
recommendation/
```

Therefore, copying:

```text
arch/
drivers/
fs/
mm/
net/
```

into an application would be meaningless.

Instead, copy the reasoning:

```text
Linux folder
        │
        ▼
Represents a responsibility
        │
        ▼
That responsibility has an owner
        │
        ▼
The owner contains related complexity
```

This reasoning is transferable.

The names are not.

---

# 19. Linux Philosophy vs Literal Linux Tree

## Literal copying

```text
My App/
├── arch/
├── drivers/
├── fs/
├── mm/
└── net/
```

This copies Linux's vocabulary.

It does not necessarily copy Linux's architecture.

---

## Philosophical copying

```text
My App/
├── core/
├── platform/
├── infrastructure/
└── features/
```

Each exists because it owns a different responsibility.

This copies the architectural principle.

---

# 20. Subsystems Should Be Independently Understandable

A developer should ideally be able to enter:

```text
feature/task/
```

and answer:

> What does this subsystem do?

without reading the entire application.

The same should apply to:

```text
core/scheduler/
platform/alarm/
feature/settings/
```

Each subsystem should have a coherent purpose.

A good test is:

> **Can I explain this subsystem in one sentence?**

For example:

```text
core/scheduler/

"Owns the application's scheduling rules and algorithms."
```

```text
platform/alarm/

"Owns the Android-specific implementation of alarms."
```

```text
feature/task/

"Owns the user-facing task capability."
```

If you cannot explain ownership clearly, the boundary may be poorly defined.

---

# 21. Dependencies Should Follow Boundaries

A subsystem should not freely access everything.

Think of dependencies as roads.

Bad architecture:

```text
A ────── B
│ ╲     ╱ │
│  ╲   ╱  │
│   ╲ ╱   │
C ────── D
```

Every subsystem directly connects to every other subsystem.

Good architecture:

```text
Feature
   │
   ▼
Contract / Core
   │
   ▼
Platform
```

Dependencies should have deliberate direction.

The exact graph depends on the system.

The general principle is:

> **A dependency should cross a subsystem boundary only when there is a defined reason to do so.**

---

# 22. Internal Complexity Is Allowed

A subsystem does not need to remain small.

For example:

```text
feature/
└── music/
    ├── player/
    ├── library/
    ├── queue/
    ├── metadata/
    ├── search/
    └── recommendation/
```

This can grow enormously.

That is acceptable.

The architecture is not trying to eliminate complexity.

It is trying to:

# **Contain complexity.**

The goal is:

```text
Complexity exists
       ↓
Complexity has an owner
       ↓
Complexity stays mostly inside a boundary
       ↓
The rest of the system remains understandable
```

---

# 23. Architecture Is a Containment System

This is the deepest interpretation of subsystem architecture.

You cannot prevent a successful system from becoming complex.

A large system naturally develops:

- more features
- more logic
- more interactions
- more code
- more developers

The goal is not:

> Make complexity disappear.

The goal is:

> **Prevent complexity from spreading everywhere.**

Subsystem boundaries act like containers.

```text
SYSTEM
│
├── ┌───────────────────┐
│   │ SUBSYSTEM A       │
│   │ Internal complexity│
│   └───────────────────┘
│
├── ┌───────────────────┐
│   │ SUBSYSTEM B       │
│   │ Internal complexity│
│   └───────────────────┘
│
└── ┌───────────────────┐
    │ SUBSYSTEM C       │
    │ Internal complexity│
    └───────────────────┘
```

This is why boundaries are so important.

---

# 24. The Reusable Architecture Formula

The complete philosophy can be summarized as:

```text
CLEAR RESPONSIBILITY
        ↓
CLEAR OWNERSHIP
        ↓
SUBSYSTEM BOUNDARY
        ↓
HIGH INTERNAL COHESION
        ↓
LOW EXTERNAL COUPLING
        ↓
CONTROLLED INTERFACES
        ↓
INTERNAL GROWTH
        ↓
SYSTEM SCALABILITY
```

---

# 25. The Universal File Placement Rule

For every new file, ask:

## Question 1

> Which responsibility does this belong to?

## Question 2

> Who owns that responsibility?

## Question 3

> Does that owner already have a subsystem?

### If yes:

```text
Place the code inside that subsystem.
```

### If no:

```text
Determine whether a genuinely new subsystem is required.
```

## Question 4

> Is this truly ownerless and generic?

Only then consider:

```text
shared/
```

---

# 26. The Final Principle

The Linux subsystem/ownership philosophy is not:

> Use folders called `arch`, `drivers`, `fs`, `mm`, and `net`.

It is:

# **Divide a large system according to responsibility.**

# **Give every responsibility a clear owner.**

# **Keep related complexity inside the owner's boundary.**

# **Expose controlled interfaces to other subsystems.**

# **Allow internal implementation to evolve without spreading changes across the entire system.**

# **Grow complexity downward inside stable boundaries instead of continuously expanding the global architecture.**

---

# Final Architecture Law

> **Large systems do not remain understandable because they stay small.**

> **They remain understandable because complexity has somewhere to belong.**

That is the core of the Linux subsystem and ownership philosophy.

---

## One-Sentence Version

> **Organize software around who owns a responsibility, not around what type of file something happens to be.**