plugins {
    id("com.eevdf.android-library-convention")
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.eevdf.capabilities.runhistory"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
