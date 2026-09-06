plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.settingsstorage"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)
    // DisplayPrefs applies AppCompatDelegate night mode directly.
    implementation(libs.androidx.appcompat)
    // AppPreferences is a @Qualifier annotation (javax.inject).
    implementation(libs.hilt.android)
}
