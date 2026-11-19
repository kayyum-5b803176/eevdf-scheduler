plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.capabilities.taskwake"
    defaultConfig { minSdk = 31 }
    sourceSets {
        getByName("test") { java.srcDir("src/test/kotlin") }
    }
}
dependencies {
    implementation(project(":kernel"))
    // TaskRepository (read a task, wake a sleeper, list what is visible) and the wake table's DAO.
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:navigation-routes"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)          // TaskWakeDao's annotations / supertypes
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
