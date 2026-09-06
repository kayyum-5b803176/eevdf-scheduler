pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "EEVDFScheduler"

// v6.9.0 — the ownership-based modules are gone.
//
// Retired this phase: ":feature", ":core", ":platform", ":data". Every file
// they held now lives in the capability that owns it. (":testing" went in
// v6.2.0, ":shared" in v6.5.0.)
//
// ":contract" survives holding exactly ONE file: AlarmRingingQuery — the
// single sanctioned synchronous-query exception to rule 3, documented in its
// own KDoc. Everything else that lived there (AppRoutes, AlarmController,
// OverlayController, AlarmActions) is either a capability or a bus topic now.
//
// Root arity is fixed per rule 7: kernel, capabilities, composition. Only
// ":capabilities:*" grows.
include(
    ":app",
    ":contract",
    ":kernel",

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
    ":capabilities:notice-phase",
    ":capabilities:links-screen",
    ":capabilities:backup-restore",
    ":capabilities:alarm-ringer",
    ":capabilities:call-autoswitch",
    ":capabilities:settings-screens",
    ":capabilities:stats-screens",
    ":capabilities:multi-device-sync",
)
