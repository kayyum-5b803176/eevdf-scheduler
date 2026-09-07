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
    implementation(project(":capabilities:reminder-notifier"))
    // v6.10.8 (flagged, not a cycle): TaskViewModel calls MultiUserSyncManager
    // and reads SyncState directly (triggerSyncExport, sync icon state).
    // multi-device-sync has no dependency back on task-list-screen, so this
    // is one-way and safe -- but it is the same class of broad, un-narrow
    // coupling flagged for add-task-screen in v6.10.6. Properly resolving it
    // means TaskViewModel publishing a sync-requested bus event instead of
    // holding a direct reference -- out of scope for a build-fix release.
    implementation(project(":capabilities:multi-device-sync"))
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
