plugins { id("com.eevdf.android-library-convention") }
android {
    namespace = "com.eevdf.capabilities.grouppicker"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(project(":capabilities:design-system"))
    implementation(project(":capabilities:task-storage"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
}
