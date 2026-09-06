plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.capabilities.tasklistscreen"
    defaultConfig { minSdk = 31 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:navigation-routes"))
    implementation(project(":capabilities:task-scheduling"))
    implementation(project(":capabilities:run-history"))
    implementation(project(":capabilities:countdown-timer"))
    implementation(project(":capabilities:notice-phase"))
    implementation(project(":capabilities:reminder-notifier"))
    implementation(project(":capabilities:feedback-cues"))
    implementation(project(":contract"))
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
