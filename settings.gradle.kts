pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "EEVDFScheduler"

// v6.10.3 — ":capabilities:notice-phase" retired, merged into
// task-list-screen. NoticeStateMachine takes TaskViewModel directly and
// reaches into ~15 of its internals (bus, repository, _currentTask,
// pauseTimer, etc.) — the two were never actually separable, the same
// mistake class as the v6.10.2 task-scheduling/task-storage cycle.
//
// v6.10.0 — the microkernel layout is complete.
//
// Every original ownership module is now retired: ":testing" (v6.2.0),
// ":shared" (v6.5.0), ":feature"/":core"/":platform"/":data" (v6.9.0), and
// ":contract" (this release).
//
// ":contract" is retired too as of v6.10.0. Its last file, AlarmRingingQuery,
// moved to kernel/contracts/ — both sides of that interface live in different
// capabilities, so the kernel is the only place both may depend on.
//
// Root arity is fixed per rule 7: kernel, capabilities, composition. Only
// ":capabilities:*" grows.
include(
    ":app",
    ":kernel",
    ":composition",

    ":capabilities:feedback-cues",
    ":capabilities:design-system",
    ":capabilities:task-storage",
    ":capabilities:task-scheduling",
    ":capabilities:run-history",
    ":capabilities:reminder-notifier",
    ":capabilities:settings-storage",
    ":capabilities:group-picker",
    ":capabilities:navigation-routes",
    ":capabilities:feature-toggles",
    ":capabilities:task-list-screen",
    ":capabilities:add-task-screen",
    ":capabilities:countdown-timer",
    ":capabilities:links-screen",
    ":capabilities:backup-restore",
    ":capabilities:alarm-ringer",
    ":capabilities:call-autoswitch",
    ":capabilities:settings-screens",
    ":capabilities:stats-screens",
    ":capabilities:multi-device-sync",
)
