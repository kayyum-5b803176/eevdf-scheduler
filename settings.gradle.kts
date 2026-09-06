pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "EEVDFScheduler"
// v6.2.0: ":testing" retired — its entire contents (4 characterization
// tests + Fakes.kt) were already just task-scheduling's own tests; they now
// live at capabilities/task-scheduling/src/test/, so the separate module
// has nothing left in it.
include(
    ":app", ":contract", ":core", ":data", ":feature", ":kernel", ":platform",
    ":capabilities:feedback-cues", ":capabilities:design-system",
    ":capabilities:task-storage", ":capabilities:task-scheduling", ":capabilities:run-history",
    ":capabilities:reminder-notifier", ":capabilities:settings-storage",
    ":capabilities:group-picker", ":capabilities:navigation-routes", ":capabilities:feature-toggles",
)
