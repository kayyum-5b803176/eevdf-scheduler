plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.taskscheduling"
    defaultConfig { minSdk = 26 }
    sourceSets { getByName("test") { java.srcDir("src/test/kotlin") } }
}
// v6.10.2: this capability is PURE scheduling domain — SchedTask, SchedConfig,
// EevdfScheduler, CpuShares, RtPolicy. It knows nothing about Task or RunLog,
// so it depends on no other capability. See its manifest.kt for why the
// Task-aware adapters moved out.
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
