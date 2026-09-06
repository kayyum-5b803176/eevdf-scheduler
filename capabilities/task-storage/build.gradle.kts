plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.eevdf.capabilities.taskstorage"
    defaultConfig { minSdk = 26 }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    sourceSets {
        getByName("test") { java.srcDir("src/test/kotlin") }
        getByName("androidTest") {
            java.srcDir("src/androidTest/kotlin")
            assets.srcDir("$projectDir/schemas")
        }
    }
}
dependencies {
    implementation(project(":kernel"))
    // Documented exception (rule 3): the Task <-> SchedTask bridge lives in
    // task-scheduling; task-storage depends on it directly, not via the bus.
    implementation(project(":capabilities:task-scheduling"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    // FLAGGED (see manifest.kt): test-only cross-capability dependency for
    // vruntime-staleness-regression-test.kt.
    androidTestImplementation(project(":capabilities:run-history"))
}
