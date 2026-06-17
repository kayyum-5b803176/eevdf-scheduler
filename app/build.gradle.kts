plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.eevdf.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.eevdf.scheduler"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { getByName("debug") { applicationIdSuffix = ".debug" } }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":platform"))
    implementation(project(":shared"))
    implementation(project(":feature:task"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.room:room-runtime:2.6.1")
}
