plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.platform"
    defaultConfig { minSdk = 26 }
}
dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
}
