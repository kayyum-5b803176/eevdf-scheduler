plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    // One-way as of v6.10.2: task-storage -> task-scheduling. The reverse edge
    // is gone; task-scheduling no longer knows Task at all.
    implementation(project(":capabilities:task-scheduling"))
    // TaskRepository writes run-log entries, and scheduling/load-ewma-reconstructor.kt
    // (moved here in v6.10.2) reads RunLogEntry/RunDailySummary.
    implementation(project(":capabilities:run-history"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
