plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.feature"
    defaultConfig { minSdk = 31 }

    // Deliberately NOT one flat src/main/{kotlin,res}/. Each subfeature is
    // physically co-located — its own kotlin/ and res/ side by side — instead of
    // being scattered across type-based top-level folders. It is still exactly
    // one Android module: one namespace, one generated R class, one manifest.
    // Per-feature COMPILE isolation (task cannot import settings) is still the
    // job of scripts/check_architecture.sh, not the module system, until a
    // future phase splits these into real per-feature Gradle modules.
    //
    // "ui" is dropped from this list as of v6.1.0 — it moved to the standalone
    // :capabilities:design-system module (Phase 1). "links" stays here for now:
    // it has real cross-feature imports (task, group) that haven't migrated
    // yet, so it can't move to an isolated capability module until Phase 4.
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            val subfeatures = listOf(
                "task", "alarm", "autoswitch", "backup",
                "settings", "stats", "sync", "shared", "links",
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
    implementation(project(":shared"))
    implementation(project(":capabilities:feedback-cues"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:task-scheduling"))
    implementation(project(":capabilities:run-history"))
    implementation(project(":capabilities:reminder-notifier"))

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
