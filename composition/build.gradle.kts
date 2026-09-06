plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.composition"
    defaultConfig { minSdk = 31 }
}
dependencies {
    implementation(project(":kernel"))

    // The composition layer is the one place allowed to know the full list of
    // capabilities that exist (rule 6). This list IS that knowledge — adding
    // capability N+1 means one line here and one in settings.gradle.kts.
    implementation(project(":capabilities:feedback-cues"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:task-scheduling"))
    implementation(project(":capabilities:run-history"))
    implementation(project(":capabilities:reminder-notifier"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:group-picker"))
    implementation(project(":capabilities:navigation-routes"))
    implementation(project(":capabilities:feature-toggles"))
    implementation(project(":capabilities:task-list-screen"))
    implementation(project(":capabilities:add-task-screen"))
    implementation(project(":capabilities:countdown-timer"))
    implementation(project(":capabilities:notice-phase"))
    implementation(project(":capabilities:links-screen"))
    implementation(project(":capabilities:backup-restore"))
    implementation(project(":capabilities:alarm-ringer"))
    implementation(project(":capabilities:call-autoswitch"))
    implementation(project(":capabilities:settings-screens"))
    implementation(project(":capabilities:stats-screens"))
    implementation(project(":capabilities:multi-device-sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
