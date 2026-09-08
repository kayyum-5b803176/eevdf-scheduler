plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.appforeground"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
