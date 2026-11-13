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
    implementation(project(":capabilities:notification"))
    // v6.38.0: the multi-device-sync dependency that used to be declared here
    // (flagged debt since v6.10.8) is gone. The sync icon, its status dot and
    // the "data changed elsewhere" reaction are all bus topics now.
    // v6.10.8 (flagged, narrow): reads AlarmActivity.EXTRA_RESTART_AFTER_EXPIRE
    // / EXTRA_TASK_NAME -- Intent-extra-key string constants only, for the
    // hardware-key restart-after-expire path. No reverse dependency exists.
    implementation(project(":capabilities:alarm-ringer"))
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
