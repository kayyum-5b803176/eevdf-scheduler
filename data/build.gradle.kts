plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.data"
    defaultConfig { minSdk = 26 }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Unit tests here run against the real org.json jar (added below),
            // NOT the empty android.jar stubs, so BackupManager round trips for
            // real instead of silently returning defaults.
            isReturnDefaultValues = false
        }
    }
}
dependencies {
    api(project(":core"))
    // v6.2.0: task/runlog/scheduler moved to :capabilities:task-storage,
    // :capabilities:run-history, :capabilities:task-scheduling — Room/ksp
    // and the schema/assets wiring moved with them. BackupManager and the
    // sync/* classes remaining here still touch task data, so :data now
    // depends on task-storage instead of owning it.
    implementation(project(":capabilities:task-storage"))

    api(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt — repositories use @Inject constructors; @InstallIn modules live in :app
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ── Unit tests (JVM) ─────────────────────────────────────────────────────
    // kotlin-reflect powers the field-coverage guards in BackupRoundTripCoverageTest
    // and TaskFieldClassificationTest: they enumerate Task's constructor so a new
    // field cannot be forgotten.
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("reflect"))
}
