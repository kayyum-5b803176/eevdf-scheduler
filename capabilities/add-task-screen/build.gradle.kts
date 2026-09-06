plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.capabilities.addtaskscreen"
    defaultConfig { minSdk = 31 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:navigation-routes"))
    implementation(project(":capabilities:task-scheduling"))
    implementation(project(":capabilities:group-picker"))
    // v6.10.6 (flagged, not a cycle): this whole screen shares TaskViewModel
    // with task-list-screen (AddTaskActivity uses `by viewModels<TaskViewModel>()`
    // directly, and 7 other files here read/write its repository/activeTasks/
    // activeGroups/interrupt-slot members). task-list-screen has NO dependency
    // back on add-task-screen, so this is one-way and compiles -- but it is
    // broad, pre-existing coupling on one file, not the narrow documented
    // exceptions elsewhere (AlarmRingingQuery, task-storage->task-scheduling
    // bridge). Properly resolving it means add-task-screen talking to
    // task-storage's TaskRepository directly, the same change already made
    // for links-screen in v6.6.0 -- out of scope for a build-fix release.
    implementation(project(":capabilities:task-list-screen"))
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
