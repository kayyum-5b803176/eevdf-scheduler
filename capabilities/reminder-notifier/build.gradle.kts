plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.remindernotifier"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)
}
