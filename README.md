# EEVDF Task Scheduler — Android App

A full-featured Android task manager powered by the **EEVDF (Earliest Eligible Virtual Deadline First)** scheduling algorithm — the same algorithm used in the **Linux kernel** (since v6.6) for CPU process scheduling. Adapted here for real-life productivity and time management.

---

## 📁 Project Structure

```
EEVDFScheduler/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/eevdf/scheduler/
│           ├── EEVDFApp.kt                   ← Application class
│           ├── model/
│           │   └── Task.kt                   ← Task data model with EEVDF fields
│           ├── scheduler/
│           │   └── EEVDFScheduler.kt         ← Core EEVDF algorithm
│           ├── db/
│           │   ├── TaskDao.kt                ← Room DAO
│           │   ├── TaskDatabase.kt           ← Room database
│           │   └── TaskRepository.kt         ← Data repository
│           ├── viewmodel/
│           │   └── TaskViewModel.kt          ← ViewModel + timer logic
│           ├── adapter/
│           │   └── TaskAdapter.kt            ← RecyclerView adapter
│           └── ui/
│               ├── MainActivity.kt           ← Main screen (Queue/Schedule/Done)
│               ├── AddTaskActivity.kt        ← Add/Edit task screen
│               └── NotificationHelper.kt    ← Timer notifications
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 🚀 Setup Instructions

### Requirements
- **Android Studio Hedgehog** (2023.1.1) or later
- **JDK 17**
- **Android SDK 34** (compileSdk), min SDK 26 (Android 8.0+)

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select the EEVDFScheduler/ folder
   ```

2. **Sync Gradle**
   - Android Studio will prompt to sync — click "Sync Now"
   - Wait for all dependencies to download (first time ~2–3 min)

3. **Add missing launcher icons** (required to build)
   - Right-click `res/` → New → Image Asset
   - Create `ic_launcher` and `ic_launcher_round` icons
   - Or copy any existing mipmap icons into:
     `app/src/main/res/mipmap-hdpi/` etc.

4. **Run the app**
   - Connect an Android device (USB debug on) or start an emulator
   - Click ▶ Run

---

## 🧠 EEVDF Algorithm — How It Works

### Core Concepts

| Term | Meaning |
|------|---------|
| **Weight** | = Task priority (1–10). Higher weight → more CPU time |
| **Virtual Runtime (vruntime)** | Weighted time consumed. `vruntime += seconds_ran / weight` |
| **Average VRT** | Weighted mean vruntime across all active tasks |
| **Lag** | `lag = (avgVRT - vruntime) × weight`. Positive = task is owed time |
| **Eligible Time** | When a task becomes schedulable (lag ≥ 0) |
| **Virtual Deadline** | `eligible_time + time_slice / weight` |

### Scheduling Decision

```
1. Compute avgVruntime (weighted average across all tasks)
2. For each task: lag = (avgVRT - task.vruntime) * task.weight
3. Eligible tasks = those with lag ≥ 0 (owed CPU time)
4. Among eligible tasks → pick task with SMALLEST virtualDeadline
5. If no eligible tasks → pick task with SMALLEST vruntime
```

### Real-Life Analogy

Imagine 3 tasks:
- **Task A** — Priority 8, 30-min slice
- **Task B** — Priority 5, 20-min slice  
- **Task C** — Priority 2, 60-min slice

Task A's vruntime grows slowest (÷8), so after running it has the least "virtual" time consumed. Task C's vruntime grows fastest (÷2), so it becomes eligible less often. Result: high-priority tasks get scheduled more frequently, while low-priority tasks still run fairly — never starved.

### Fairness Score
Uses **Jain's Fairness Index** on vruntime distribution:
```
J = (Σ vrt_i)² / (n × Σ vrt_i²)   →   0.0 (unfair) to 1.0 (perfectly fair)
```

---

## 📱 App Features

### Task Management
- ✅ Add tasks with **name, description, priority (1–10), time slice (h/m/s), category**
- ✅ Edit / delete / mark complete
- ✅ Persistent storage with **Room database**
- ✅ Categories: Work, Study, Health, Personal, Project, Meeting, General

### EEVDF Scheduler
- ✅ **"Schedule Next"** — auto-selects the highest-priority eligible task
- ✅ **Schedule tab** — shows full EEVDF ordering with rank badges (#1, #2…)
- ✅ Live **vruntime** and **virtual deadline** shown per task
- ✅ **Fairness score** and stats bar (active tasks, total weight, avg VRT)
- ✅ VRT updates after each timer session

### Built-in Timer
- ✅ **Countdown timer** for the current task
- ✅ ▶ Start / ⏸ Pause / ⏭ Skip controls
- ✅ VRT automatically updated on pause (partial credit for elapsed time)
- ✅ Full reset option
- ✅ **Timer notification** shown in notification bar while running
- ✅ **Completion notification** when time slice finishes
- ✅ Progress bar shows % of time slice consumed

### UI / UX
- ✅ Material Design 3 with Blue + Orange accent
- ✅ Priority color coding (Red → Orange → Blue → Green → Grey)
- ✅ Running task highlighted in blue with accent side bar
- ✅ Tabs: Queue · Schedule · Completed
- ✅ Empty state illustration

---

## 🔧 Key Classes

### `EEVDFScheduler.kt`
Pure Kotlin singleton — zero Android dependencies. Fully unit-testable.
```kotlin
EEVDFScheduler.recalculate(tasks)      // Updates all vruntime/deadline fields
EEVDFScheduler.selectNext(tasks)       // Returns the next task to run
EEVDFScheduler.getScheduleOrder(tasks) // Full ranked list
EEVDFScheduler.updateVruntime(task, secondsRan)
EEVDFScheduler.getStats(tasks)         // Fairness, counts, avg VRT
```

### `TaskViewModel.kt`
Manages timer lifecycle (CountDownTimer), EEVDF calls, and LiveData.
```kotlin
viewModel.addTask(task)
viewModel.scheduleNext()   // EEVDF select + set as current
viewModel.startTimer()
viewModel.pauseTimer()     // Saves elapsed, updates VRT
viewModel.skipTask()
viewModel.markCompleted(task)
```

---

## 📦 Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Material Components | 1.11.0 | UI |
| Room | 2.6.1 | SQLite ORM |
| Lifecycle ViewModel | 2.7.0 | MVVM |
| Coroutines | 1.7.3 | Async DB ops |
| RecyclerView | 1.3.2 | Task list |
| CardView | 1.0.0 | Task cards |

---

## 🔮 Possible Extensions

- **Recurring tasks** — reset vruntime and re-queue on completion
- **Deadline-aware scheduling** — add absolute deadlines for hard constraints
- **Pomodoro integration** — enforce 25/5 work-break cycles
- **Statistics dashboard** — charts of time spent per category (using MPAndroidChart)
- **Widget** — home screen timer widget
- **Google Calendar sync** — import events as tasks
- **Backup/restore** — export task DB to JSON

---

## 📖 References

- [Linux Kernel EEVDF patch (Peter Zijlstra, 2023)](https://lwn.net/Articles/925371/)
- [Original EEVDF paper — Stoica, Abdel-Wahab, Jeffay (1995)](https://citeseerx.ist.psu.edu/doc/10.1.1.43.5347)
- [Jain's Fairness Index](https://en.wikipedia.org/wiki/Fairness_measure)
