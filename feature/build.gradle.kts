plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.feature"
    defaultConfig { minSdk = 31 }

    // v6.8.0: "task", "links", "backup" dropped from this list entirely.
    // Every file that lived under them moved to its own capability module
    // (task-list-screen, add-task-screen, countdown-timer, notice-phase,
    // links-screen, backup-restore) in Phase 8. What's left here —
    // alarm, autoswitch, settings, stats, sync — moves in Phase 9.
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            val subfeatures = listOf(
                "alarm", "autoswitch", "settings", "stats", "sync",
            )
            kotlin.srcDirs(subfeatures.map { "src/main/$it/kotlin" })
            res.srcDirs(subfeatures.map { "src/main/$it/res" })
        }
    }
}
dependencies {
    implementation(project(":contract"))
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":platform"))
    implementation(project(":capabilities:feedback-cues"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:run-history"))
    implementation(project(":capabilities:reminder-notifier"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:navigation-routes"))
    // task-scheduling and group-picker removed v6.8.0 — only used by the
    // task/links subfeatures that moved out to their own modules this phase.

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mpandroidchart)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
